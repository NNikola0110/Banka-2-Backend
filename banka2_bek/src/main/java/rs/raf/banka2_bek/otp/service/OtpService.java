package rs.raf.banka2_bek.otp.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.otp.repository.OtpConsumedCodeRepository;
import rs.raf.banka2_bek.otp.repository.TotpSecretRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OtpService {

    private static final long TOTP_WINDOW_SECONDS = 30L;
    /**
     * BE-AUTH-01 fix (Celina 2 §18, TODO_testovi.pdf Sc 14): nakon N neuspesnih
     * pokusaja TOTP provere se transakcija otkazuje. Caffeine cache prati fail
     * counter po email-u sa TTL 5 minuta (slidi sa TOTP code prozorom +
     * realisticnim grace periodom).
     *
     * <p>R5-365: vrednost je sada konfigurabilna preko {@code otp.max-attempts}
     * (default 3). Ranije je {@code application.properties otp.max-attempts=3}
     * postojao ali se NIGDE nije citao — hardkodirana konstanta 3 ga je ignorisala.
     */
    private final int maxFailedAttempts;
    private static final Duration FAILED_ATTEMPTS_TTL = Duration.ofMinutes(5);

    /**
     * P1-auth-2 (R2 1364): {@code users} i {@code employees} su odvojene tabele
     * sa nezavisnim IDENTITY sekvencama, pa se njihovi numericki ID-evi preklapaju
     * (User id=2 i Employee id=2 mogu da koegzistiraju). {@link TotpSecret#userId}
     * i {@code OtpConsumedCode} su keyovani jednim {@code Long}, pa bi resolvovanje
     * employee-a po SIROVOM ID-u delilo TOTP secret i consumed-code zapise sa
     * istonumeričkim user-om (cross-contamination). Employee ID-evi se zato mapiraju
     * u DISJUNKTAN numericki prostor ovim offset-om — bez DDL izmene sheme.
     */
    private static final long EMPLOYEE_SUBJECT_ID_OFFSET = 1_000_000_000_000L;

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final TotpService totpService;
    private final TotpSecretRepository totpSecretRepository;
    private final NotificationPublisher notificationPublisher;
    private final OtpConsumedCodeRepository otpConsumedCodeRepository;
    private final OtpConsumedCodeWriter otpConsumedCodeWriter;
    private final int emailExpiryMinutes;
    /**
     * BE-AUTH-01 per-email fail counter (rate-limit).
     *
     * <p><b>R1-616 — HA / Multi-instance limitacija:</b> ovo je IN-MEMORY Caffeine
     * cache po BE instanci. U multi-instance HA deploy-u counter se NE deli, pa
     * napadac moze rasporediti pokusaje preko replika i zaobici {@link #maxFailedAttempts}
     * limit (svaka instanca broji nezavisno). Za single-instance (KT3 demo) je dovoljno.
     * Single-use replay zastita (vec-potrosen kod) je NASUPROT TOME DB-backed
     * ({@code OtpConsumedCode} + UNIQUE (user_id, code_hash)) pa radi kroz instance.
     * TODO (production HA): preseliti fail-counter na shared store (Redis/Bucket4j JCache)
     * — ista migracija kao {@link rs.raf.banka2_bek.auth.config.AuthRateLimitFilter}
     * i {@link rs.raf.banka2_bek.auth.service.JwtBlacklistService} (vidi njihov Redis skeleton).
     */
    private final Cache<String, Integer> failedAttempts;

    public OtpService(UserRepository userRepository,
                      EmployeeRepository employeeRepository,
                      TotpService totpService,
                      TotpSecretRepository totpSecretRepository,
                      NotificationPublisher notificationPublisher,
                      OtpConsumedCodeRepository otpConsumedCodeRepository,
                      OtpConsumedCodeWriter otpConsumedCodeWriter,
                      @Value("${otp.expiry-minutes:5}") int emailExpiryMinutes,
                      @Value("${otp.max-attempts:3}") int maxFailedAttempts) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.totpService = totpService;
        this.totpSecretRepository = totpSecretRepository;
        this.notificationPublisher = notificationPublisher;
        this.otpConsumedCodeRepository = otpConsumedCodeRepository;
        this.otpConsumedCodeWriter = otpConsumedCodeWriter;
        this.emailExpiryMinutes = emailExpiryMinutes;
        this.maxFailedAttempts = maxFailedAttempts;
        this.failedAttempts = Caffeine.newBuilder()
                .expireAfterWrite(FAILED_ATTEMPTS_TTL)
                .maximumSize(10_000)
                .build();
    }

    /**
     * P1-auth-2 (R2 1364): razresava stabilni OTP subject-id za email. User ima
     * prednost (samoregistrovani klijenti), pa Employee (Arbitro employee-OTP
     * akcija). Vraca {@code null} ako ni User ni Employee ne postoje. Employee
     * ID je offset-ovan u disjunktan prostor (vidi {@link #EMPLOYEE_SUBJECT_ID_OFFSET}).
     */
    private Long resolveSubjectId(String email) {
        Long userId = userRepository.findByEmail(email).map(User::getId).orElse(null);
        if (userId != null) {
            return userId;
        }
        return employeeRepository.findByEmail(email)
                .map(Employee::getId)
                .map(id -> EMPLOYEE_SUBJECT_ID_OFFSET + id)
                .orElse(null);
    }

    @Transactional
    public void generateAndSend(String email) {
        ensureSecret(email);
    }

    @Transactional
    public void generateAndSendViaEmail(String email) {
        String secret = ensureSecret(email);
        String code = currentCode(secret);
        notificationPublisher.sendOtpMail(email, code, emailExpiryMinutes);
    }

    @Transactional
    public Map<String, Object> getActiveOtp(String email) {
        String secret = ensureSecret(email);
        String code = currentCode(secret);
        long secondsLeft = TOTP_WINDOW_SECONDS - (Instant.now().getEpochSecond() % TOTP_WINDOW_SECONDS);

        return Map.of(
                "active", true,
                "code", code,
                "expiresInSeconds", secondsLeft,
                "attempts", 0,
                "maxAttempts", 0);
    }

    /**
     * Verifikuje TOTP kod za korisnika.
     *
     * <p>BE-AUTH-01: posle {@link #maxFailedAttempts} uzastopnih neuspesnih
     * pokusaja u prozoru od {@link #FAILED_ATTEMPTS_TTL}, naredne provere
     * vracaju {@code blocked=true} (caller mora otkazati transakciju).
     * Counter se resetuje pri svakoj uspesnoj verifikaciji.</p>
     */
    @Transactional
    public Map<String, Object> verify(String email, String code) {
        return verify(email, code, null);
    }

    /**
     * N2 — verifikacija sa bind-to-intent. Pored TOTP provere i BE-AUTH-01
     * rate-limit-a, kod se SINGLE-USE potrosi (consume): isti kod za istog
     * korisnika ne prolazi drugi put (replay zastita za sve OTP flow-ove).
     *
     * @param intentHash opcioni hash amount+recipient namere — vezuje OTP za
     *                   konkretnu transakciju (NULL kad caller ne vezuje intent).
     */
    @Transactional
    public Map<String, Object> verify(String email, String code, String intentHash) {
        // P1-auth-2 (R2 1364): resolvuj i User i Employee (offset namespace) — ranije
        // je verify radio samo nad userRepository, pa je Arbitro employee-OTP akcija
        // uvek padala sa "kod nije pronadjen".
        Long subjectId = resolveSubjectId(email);
        if (subjectId == null) {
            return Map.of(
                    "verified", false,
                    "blocked", false,
                    "attempts", 0,
                    "maxAttempts", maxFailedAttempts,
                    "message", "Verifikacioni kod nije pronadjen. Zatrazite novi kod.");
        }

        // BE-AUTH-01: ako je transakcija vec blokirana, ne diraj counter (TTL
        // window jos uvek vazi). Klijent treba da otkaze i pokrene nov OTP flow.
        Integer currentCount = failedAttempts.getIfPresent(email);
        if (currentCount != null && currentCount >= maxFailedAttempts) {
            return Map.of(
                    "verified", false,
                    "blocked", true,
                    "attempts", currentCount,
                    "maxAttempts", maxFailedAttempts,
                    "message", "Prekoracen je broj pokusaja. Transakcija je otkazana.");
        }

        // N2: single-use guard PRE TOTP provere — ako je ovaj kod vec potrosen za
        // ovog korisnika, odbij kao replay (ne brojimo kao failed-attempt jer kod
        // je tehnicki ispravan, samo iskoriscen).
        String codeHash = hashCode(subjectId, code);
        if (otpConsumedCodeRepository.existsByUserIdAndCodeHash(subjectId, codeHash)) {
            return replayedResult();
        }

        boolean ok;
        try {
            ok = totpService.verify(subjectId, code);
        } catch (IllegalStateException ex) {
            int attempts = recordFailure(email);
            boolean blocked = attempts >= maxFailedAttempts;
            return Map.of(
                    "verified", false,
                    "blocked", blocked,
                    "attempts", attempts,
                    "maxAttempts", maxFailedAttempts,
                    "message", blocked
                            ? "Prekoracen je broj pokusaja. Transakcija je otkazana."
                            : "Verifikacioni kod nije pronadjen. Zatrazite novi kod.");
        }

        if (ok) {
            // N2: potrosi kod ATOMICNO. existsBy provera gore hvata sekvencijalni
            // replay; UNIQUE constraint na (user_id, code_hash) hvata konkurentni
            // race — drugi thread dobija DataIntegrityViolationException i odbija se.
            //
            // N2-a (V-1): consume ide kroz OtpConsumedCodeWriter u REQUIRES_NEW
            // tranzakciji — commit-uje NEZAVISNO od caller-ove biznis tranzakcije.
            // Tako uspesan TOTP ostaje potrosen i kad biznis-logika kasnije padne
            // (npr. insufficient funds), pa isti OTP ne moze da se replay-uje.
            try {
                otpConsumedCodeWriter.consume(subjectId, codeHash, intentHash);
            } catch (DataIntegrityViolationException dup) {
                // Kod je vec potrosen u paralelnoj transakciji — tretiraj kao replay.
                return replayedResult();
            }
            failedAttempts.invalidate(email);
            return Map.of(
                    "verified", true,
                    "blocked", false,
                    "attempts", 0,
                    "maxAttempts", maxFailedAttempts,
                    "message", "Transakcija uspesno verifikovana");
        }

        int attempts = recordFailure(email);
        boolean blocked = attempts >= maxFailedAttempts;
        return Map.of(
                "verified", false,
                "blocked", blocked,
                "attempts", attempts,
                "maxAttempts", maxFailedAttempts,
                "message", blocked
                        ? "Prekoracen je broj pokusaja. Transakcija je otkazana."
                        : "Pogresan verifikacioni kod.");
    }

    /** N2: jedinstven odgovor za vec-potrosen (replay-ovan) OTP kod. */
    private Map<String, Object> replayedResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verified", false);
        result.put("blocked", false);
        result.put("replayed", true);
        result.put("attempts", 0);
        result.put("maxAttempts", maxFailedAttempts);
        result.put("message", "Verifikacioni kod je vec iskoriscen. Zatrazite novi kod.");
        return result;
    }

    /**
     * N2: SHA-256 hex od {@code userId + ":" + code}. Ne cuvamo sirov OTP u bazi;
     * userId u prefiksu sprecava cross-user kolizije istog 6-cifrenog koda.
     */
    private String hashCode(Long userId, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(
                    (userId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * BE-AUTH-01: increment-and-return atomic helper. Caffeine
     * {@code asMap().merge} garantuje at-most-once povecanje po pozivu.
     */
    private int recordFailure(String email) {
        return failedAttempts.asMap().merge(email, 1, Integer::sum);
    }

    private String ensureSecret(String email) {
        // P1-auth-2 (R2 1364): resolvuj User pa Employee (offset namespace) — ranije
        // je ensureSecret padao za zaposlene jer je trazio samo u users tabeli.
        Long subjectId = resolveSubjectId(email);
        if (subjectId == null) {
            throw new IllegalArgumentException("Korisnik nije pronadjen: " + email);
        }

        return totpSecretRepository.findByUserId(subjectId)
                .map(rs.raf.banka2_bek.otp.model.TotpSecret::getSecret)
                .orElseGet(() -> totpService.generateSecret(subjectId));
    }

    private String currentCode(String secret) {
        int raw = new GoogleAuthenticator().getTotpPassword(secret);
        return String.format("%06d", raw);
    }
}
