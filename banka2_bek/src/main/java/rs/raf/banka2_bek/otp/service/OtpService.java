package rs.raf.banka2_bek.otp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.otp.model.OtpVerification;
import rs.raf.banka2_bek.otp.repository.OtpVerificationRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;

/*
 * TODO [B3 - TOTP verifikacioni kod | Nosilac: Nikola Stamenkovic]
 *
 * Trenutna implementacija koristi jednokratni 6-cifreni OTP kod sa rokom vaznosti
 * od 5 minuta koji se cuva u tabeli otp_verifications i salje korisniku emailom
 * ili prikazuje u mobilnoj aplikaciji.
 *
 * Zadatak: zameniti ovu logiku delegacijom na novi TotpService koji implementira
 * vremenski zasnovane jednokratne kodove po standardu RFC 6238 (TOTP):
 *   - Vremenski prozor: 30 sekundi po kodu (TOTP standard).
 *   - Algoritam: HMAC-SHA1 ili HMAC-SHA256, tajni kljuc per-korisnik.
 *   - Tolerancija: dozvoliti +/-1 vremenski prozor radi sinhronizacije casovnika.
 *
 * KRITICNO - potpis metode verify(...) mora ostati nepromenjen:
 *   public Map<String, Object> verify(String email, String code)
 * Svi postojeci pozivaoci (OtcService, PaymentServiceImpl, SavingsDepositService,
 * itd.) ne smeju biti modifikovani - samo interna implementacija verify() se
 * menja tako da delegira proveru na TotpService umesto na OtpVerificationRepository.
 *
 * Takodje zadrzati isti potpis generateAndSend(String email) i
 * generateAndSendViaEmail(String email) - ovi pozivaoci takodje ostaju nepromenjeni.
 * Interna implementacija moze preskociti generisanje i cuvanje koda u bazu
 * (TOTP kod se generise on-the-fly iz tajnog kljuca i trenutnog vremena).
 */
@Service
public class OtpService {

    private final OtpVerificationRepository otpRepository;
    private final NotificationPublisher notificationPublisher;
    private final int expiryMinutes;
    private final int maxAttempts;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpService(OtpVerificationRepository otpRepository,
                      NotificationPublisher notificationPublisher,
                      @Value("${otp.expiry-minutes:5}") int expiryMinutes,
                      @Value("${otp.max-attempts:3}") int maxAttempts) {
        this.otpRepository = otpRepository;
        this.notificationPublisher = notificationPublisher;
        this.expiryMinutes = expiryMinutes;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public void generateAndSend(String email) {
        // Invalidate any existing unused OTP
        otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(email)
                .ifPresent(existing -> {
                    existing.setUsed(true);
                    otpRepository.save(existing);
                });

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));

        OtpVerification otp = OtpVerification.builder()
                .email(email)
                .code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(expiryMinutes))
                .build();

        otpRepository.save(otp);

        // OTP se prikazuje na mobilnoj aplikaciji - email se ne salje
    }

    @Transactional
    public void generateAndSendViaEmail(String email) {
        otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(email)
                .ifPresent(existing -> {
                    existing.setUsed(true);
                    otpRepository.save(existing);
                });

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));

        OtpVerification otp = OtpVerification.builder()
                .email(email)
                .code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(expiryMinutes))
                .build();

        otpRepository.save(otp);
        notificationPublisher.sendOtpMail(email, code, expiryMinutes);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getActiveOtp(String email) {
        OtpVerification otp = otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(email)
                .orElse(null);

        if (otp == null || otp.isExpired()) {
            return Map.of("active", false, "message", "Nema aktivnog verifikacionog koda.");
        }

        long secondsLeft = java.time.Duration.between(LocalDateTime.now(), otp.getExpiresAt()).getSeconds();

        return Map.of(
                "active", true,
                "code", otp.getCode(),
                "expiresInSeconds", Math.max(secondsLeft, 0),
                "attempts", otp.getAttempts(),
                "maxAttempts", maxAttempts);
    }

    @Transactional
    public Map<String, Object> verify(String email, String code) {
        OtpVerification otp = otpRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(email)
                .orElse(null);

        if (otp == null) {
            return Map.of(
                    "verified", false,
                    "blocked", false,
                    "message", "Verifikacioni kod nije pronadjen. Zatrazite novi kod.");
        }

        if (otp.isExpired()) {
            otp.setUsed(true);
            otpRepository.save(otp);
            return Map.of(
                    "verified", false,
                    "blocked", false,
                    "message", "Verifikacioni kod je istekao. Zatrazite novi kod.");
        }

        if (otp.getAttempts() >= maxAttempts) {
            otp.setUsed(true);
            otpRepository.save(otp);
            return Map.of(
                    "verified", false,
                    "blocked", true,
                    "message", "Transakcija otkazana - previse neuspesnih pokusaja");
        }

        if (otp.getCode().equals(code)) {
            otp.setUsed(true);
            otpRepository.save(otp);
            return Map.of(
                    "verified", true,
                    "message", "Transakcija uspesno verifikovana");
        }

        otp.setAttempts(otp.getAttempts() + 1);
        otpRepository.save(otp);

        int remaining = maxAttempts - otp.getAttempts();
        if (remaining <= 0) {
            otp.setUsed(true);
            otpRepository.save(otp);
            return Map.of(
                    "verified", false,
                    "blocked", true,
                    "message", "Transakcija otkazana - previse neuspesnih pokusaja");
        }

        return Map.of(
                "verified", false,
                "blocked", false,
                "message", "Pogresan verifikacioni kod. Preostalo pokusaja: " + remaining);
    }
}
