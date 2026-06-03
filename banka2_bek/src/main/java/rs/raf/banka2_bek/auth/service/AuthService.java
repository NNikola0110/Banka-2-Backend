package rs.raf.banka2_bek.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountCategory;
import rs.raf.banka2_bek.account.model.AccountDefaults;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.model.AccountSubtype;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.account.util.AccountNumberUtils;
import rs.raf.banka2_bek.auth.dto.*;
import rs.raf.banka2_bek.auth.exception.AuthenticationFailedException;
import rs.raf.banka2_bek.auth.exception.EmailAlreadyExistsException;
import rs.raf.banka2_bek.auth.exception.InvalidRefreshTokenException;
import rs.raf.banka2_bek.auth.model.PasswordResetToken;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.PasswordResetTokenRepository;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.auth.util.EmailMasker;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.currency.repository.CurrencyRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.monitoring.BusinessMetrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final ClientRepository clientRepository;
    private final AccountRepository accountRepository;
    private final CurrencyRepository currencyRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final NotificationPublisher notificationPublisher;
    private final AccountLockoutService accountLockoutService;
    private final JwtBlacklistService jwtBlacklistService;
    private final BusinessMetrics businessMetrics;

    public AuthService(UserRepository userRepository,
                       EmployeeRepository employeeRepository,
                       ClientRepository clientRepository,
                       AccountRepository accountRepository,
                       CurrencyRepository currencyRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       NotificationPublisher notificationPublisher,
                       AccountLockoutService accountLockoutService,
                       JwtBlacklistService jwtBlacklistService,
                       BusinessMetrics businessMetrics) {
        this.userRepository = userRepository;
        this.employeeRepository = employeeRepository;
        this.clientRepository = clientRepository;
        this.accountRepository = accountRepository;
        this.currencyRepository = currencyRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.notificationPublisher = notificationPublisher;
        this.accountLockoutService = accountLockoutService;
        this.jwtBlacklistService = jwtBlacklistService;
        this.businessMetrics = businessMetrics;
    }

    /**
     * Po Celini 1 spec-u, self-service register kreira korisnicki nalog za login.
     * Da bi novi klijent odmah mogao da koristi feature-e koji preko
     * {@code UserResolver.resolveCurrent} traze {@code clients} red (BUY orderi,
     * OTC accept, fond invest), ovde se atomski kreira i Client zapis i jedan
     * podrazumevani RSD CHECKING/PERSONAL racun. Bez toga, UserResolver baca
     * {@code IllegalStateException} unutar {@code @Transactional} servisa,
     * pa Spring posle commit-a vraca {@code TransactionSystemException
     * ("Could not commit JPA transaction")} — bag prijavljen 10.05.2026.
     */
    @Transactional
    public String register(RegisterRequestDto request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            // R1 296: duplikat email = 409 Conflict, ne bare RuntimeException→400.
            throw new EmailAlreadyExistsException("User with this email already exists");
        }

        String hashedPassword = passwordEncoder.encode(request.getPassword());

        User user = new User();
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        user.setPassword(hashedPassword);
        user.setUsername(request.getUsername());
        user.setPhone(request.getPhone());
        user.setAddress(request.getAddress());
        user.setDateOfBirth(request.getDateOfBirth());
        user.setGender(request.getGender());
        user.setActive(true);
        user.setRole("CLIENT");

        userRepository.save(user);

        bootstrapClientArtifacts(user, hashedPassword);

        return "User registered successfully";
    }

    /**
     * Kreira odgovarajuci {@link Client} red i podrazumevani RSD CHECKING/PERSONAL
     * racun za novoregistrovanog korisnika. Idempotent — ako Client/racun vec
     * postoje, preskace.
     */
    private void bootstrapClientArtifacts(User user, String hashedPassword) {
        Optional<Client> existingByEmail = clientRepository.findByEmail(user.getEmail());
        Client client = existingByEmail.orElseGet(() -> {
            Client c = Client.builder()
                    .firstName(user.getFirstName())
                    .lastName(user.getLastName())
                    .email(user.getEmail())
                    .phone(user.getPhone() != null ? user.getPhone() : "N/A")
                    .address(user.getAddress())
                    .gender(user.getGender())
                    .dateOfBirth(user.getDateOfBirth() != null
                            ? Instant.ofEpochMilli(user.getDateOfBirth()).atZone(ZoneId.systemDefault()).toLocalDate()
                            : LocalDate.of(2000, 1, 1))
                    .password(hashedPassword)
                    .saltPassword("auto")
                    .active(true)
                    .build();
            return clientRepository.save(c);
        });

        if (client.getAccounts() != null && !client.getAccounts().isEmpty()) {
            return;
        }
        if (client.getId() != null
                && !accountRepository.findByClientId(client.getId()).isEmpty()) {
            return;
        }

        Currency rsd = currencyRepository.findByCode("RSD")
                .orElseThrow(() -> new IllegalStateException(
                        "RSD valuta nije seedovana — ne moze se otvoriti default racun za novog klijenta."));

        Employee creator = pickSystemEmployee();

        String accountNumber;
        do {
            accountNumber = AccountNumberUtils.generate(AccountType.CHECKING, AccountSubtype.PERSONAL, false);
        } while (accountRepository.existsByAccountNumber(accountNumber));

        Account account = Account.builder()
                .accountNumber(accountNumber)
                .accountType(AccountType.CHECKING)
                .accountSubtype(AccountSubtype.PERSONAL)
                .currency(rsd)
                .client(client)
                .employee(creator)
                .balance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .reservedAmount(BigDecimal.ZERO)
                .accountCategory(AccountCategory.CLIENT)
                // R1-626: podrazumevani limiti/naknada iz jedinstvenog izvora.
                .dailyLimit(AccountDefaults.DEFAULT_DAILY_LIMIT)
                .monthlyLimit(AccountDefaults.DEFAULT_MONTHLY_LIMIT)
                .maintenanceFee(AccountDefaults.MAINTENANCE_FEE)
                .status(AccountStatus.ACTIVE)
                .expirationDate(LocalDate.now().plusYears(5))
                .createdAt(LocalDateTime.now())
                .build();
        accountRepository.save(account);
    }

    private Employee pickSystemEmployee() {
        return employeeRepository.findAll().stream()
                .filter(e -> Boolean.TRUE.equals(e.getActive()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Nema aktivnih zaposlenih u sistemu — ne moze se kreirati racun za novog klijenta."));
    }

    public AuthResponseDto login(LoginRequestDto request) {
        // Opciono.2 — proveri lockout pre nego sto se proveri lozinka.
        // Bacaja AccountLockedException ako je email lock-ovan; mapira se u 401.
        accountLockoutService.assertNotLocked(request.getEmail());

        // First, try to find an employee with this email.
        // P1-auth-2 (R2 1365): case-insensitive lookup — lockout brojac (AccountLockoutService)
        // normalizuje email na lowercase, pa login lookup mora biti isto case-insensitive
        // da se isti nalog ne deli na dva razlicita lockout/login kljuca po case-u.
        Optional<Employee> employeeOpt = employeeRepository.findByEmailIgnoreCase(request.getEmail());
        if (employeeOpt.isPresent()) {
            Employee employee = employeeOpt.get();

            // Employee passwords are stored with salt concatenated
            String salt = employee.getSaltPassword();
            if (passwordEncoder.matches(request.getPassword() + salt, employee.getPassword())) {
                if (!Boolean.TRUE.equals(employee.getActive())) {
                    // BE-AUTH-04 fix: inactive accounts moraju takodje da increment
                    // failedLoginAttempts pre throw-a, inace deaktivirani nalozi dobijaju
                    // unlimited probe pokusaje. Ako napadac zna email aktivnog/deaktiviranog
                    // naloga + tacnu sifru, bez recordFailure-a moze beskonacno da poll-uje
                    // (npr. provera da li nalog jos uvek postoji u sistemu, ili priprema
                    // za reactivation napad ako admin reaktivira nalog kasnije).
                    rejectInactive(request.getEmail());
                }

                onSuccessfulLogin(request.getEmail());
                String accessToken = jwtService.generateAccessToken(employee);
                String refreshToken = jwtService.generateRefreshToken(employee);
                return new AuthResponseDto(accessToken, refreshToken);
            }
        }

        // If not found as employee or password didn't match, try regular user
        // (case-insensitive — P1-auth-2 R2 1365, vidi gore).
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(request.getEmail());
        if (userOpt.isPresent()) {
            User user = userOpt.get();

            if (passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                if (!user.isActive()) {
                    // BE-AUTH-04 fix: vidi gore — increment counter i za deaktivirane
                    // user naloge da napadac ne moze beskonacno da poll-uje.
                    rejectInactive(request.getEmail());
                }

                onSuccessfulLogin(request.getEmail());
                String accessToken = jwtService.generateAccessToken(user);
                String refreshToken = jwtService.generateRefreshToken(user);
                return new AuthResponseDto(accessToken, refreshToken);
            }
        }

        // Neither employee nor user found, or password didn't match — broji failure.
        // Spec Sc 5: posle 4 neuspeha, 5. pokusaj zakljucava nalog. AccountLockoutService
        // sad baca AccountLockedException u istom pozivu kad pretreknemo prag — tako
        // korisnik dobija lockout poruku na 5. pokusaju (Bug T1-015), ne tek na 6.
        accountLockoutService.recordFailure(request.getEmail());
        businessMetrics.recordLoginFailure();
        // Spec Sc 2/3: 401 sa generic porukom "Neispravni unos" (poruka na SR jer
        // ostatak UI je na SR — Bug T1-017 mesan SR/EN).
        throw new AuthenticationFailedException("Neispravan email ili lozinka.");
    }

    /**
     * R1-619: deljena grana za deaktiviran nalog (Employee i User su radili isto).
     * Inkrementira lockout brojac (BE-AUTH-04 — da deaktiviran nalog ne dobija
     * beskonacne probe) i baca 401 sa generic porukom (anti-enumeration). Uvek baca.
     */
    private void rejectInactive(String email) {
        accountLockoutService.recordFailure(email);
        businessMetrics.recordLoginFailure();
        // Spec Sc 14 — login deaktiviranog naloga vraca 401, ne 400 (Bug T1-012).
        // Generic poruka ne otkriva da li email postoji ili je deaktiviran.
        throw new AuthenticationFailedException("Nalog je deaktiviran.");
    }

    /** R1-619: deljeni uspesan-login book-keeping (reset lockout + metrika). */
    private void onSuccessfulLogin(String email) {
        accountLockoutService.recordSuccess(email);
        businessMetrics.recordLoginSuccess();
    }

    public String requestPasswordReset(PasswordResetRequestDto request) {
        // SEC-07: Email enumeration mitigation — endpoint UVEK vraca isti generic
        // odgovor (200 OK + generic message), bez obzira da li email postoji ili ne.
        // Napadac vise ne moze da iz HTTP status-a / response body-ja zakljuci da li
        // je email registrovan. Ako email NE postoji, samo logujemo interno i
        // tihovljeno vracamo success — UI prikazuje istu poruku "Ako nalog postoji,
        // poslat ti je email sa linkom za reset lozinke."
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);
        Employee employee = null;

        if (user == null) {
            employee = employeeRepository.findByEmail(request.getEmail()).orElse(null);
        }

        if (user == null && employee == null) {
            // Interno logujemo, ali UI vraca generic success — bez 400/404 leakage.
            // [P2-input-validation-1 / R4 1781] maskiraj email — anti-enumeration
            // grana NE sme da loguje pun PII svakog ko enumerise.
            log.info("SEC-07: Password reset requested for unknown email (no account exists): {}",
                    EmailMasker.mask(request.getEmail()));
            return "If an account with that email exists, a password reset link has been sent.";
        }

        String tokenValue = UUID.randomUUID().toString();

        PasswordResetToken passwordResetToken = new PasswordResetToken();
        passwordResetToken.setToken(tokenValue);
        if (user != null) {
            passwordResetToken.setUser(user);
        } else {
            passwordResetToken.setEmployee(employee);
        }
        // C1 Sc4 (§spec): reset link vazi 15 minuta (ranije 30; "pod-konac" literal).
        passwordResetToken.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        passwordResetToken.setUsed(false);

        passwordResetTokenRepository.save(passwordResetToken);

        String targetEmail = user != null ? user.getEmail() : employee.getEmail();
        notificationPublisher.sendPasswordResetMail(targetEmail, tokenValue);

        // Generic poruka — ista kao za nepostojeci email — da BE odgovor ne otkriva
        // da li nalog postoji.
        return "If an account with that email exists, a password reset link has been sent.";
    }

    @Transactional
    public String resetPassword(PasswordResetDto reset){

        PasswordResetToken passwordResetToken = passwordResetTokenRepository.findByToken(reset.getToken())
                .orElseThrow(() -> new RuntimeException("Reset token does not exist"));


        if(passwordResetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Reset token has expired");
        }

        if(passwordResetToken.getUsed()) {
            throw new RuntimeException("Reset token has been already used");
        }

        User user = passwordResetToken.getUser();
        Employee employee = passwordResetToken.getEmployee();
        if (user == null && employee == null) {
            throw new RuntimeException("Reset token is not linked to a user");
        }

        if (user != null) {
            String hashedNewPassword = passwordEncoder.encode(reset.getNewPassword());
            user.setPassword(hashedNewPassword);
            user.setFailedLoginAttempts(0);
            user.setAccountLockedUntil(null);
            userRepository.save(user);
        } else {
            String salt = employee.getSaltPassword();
            employee.setPassword(passwordEncoder.encode(reset.getNewPassword() + salt));
            employee.setFailedLoginAttempts(0);
            employee.setAccountLockedUntil(null);
            employeeRepository.save(employee);
        }
        passwordResetToken.setUsed(true);
        passwordResetTokenRepository.save(passwordResetToken);

        String targetEmail = user != null ? user.getEmail() : employee.getEmail();
        accountLockoutService.recordSuccess(targetEmail);

        return "Password reset successfully!";

    }

    public RefreshTokenResponseDto  refreshToken(RefreshTokenRequestDto  request){
        String refreshToken = request.getRefreshToken();

        if (!jwtService.isRefreshToken(refreshToken))
            throw new InvalidRefreshTokenException("Invalid refresh token");

        // N3: logout-om revoke-ovan refresh token ne sme da izda nov access token.
        // Bez ove provere logout je no-op za refresh (blacklist nikad nije konsultovan
        // na /auth/refresh putu), pa ukraden refresh token radi punih 7 dana.
        if (jwtBlacklistService.isBlacklisted(refreshToken))
            throw new InvalidRefreshTokenException("Invalid refresh token");

        String email = jwtService.extractEmail(refreshToken);

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            Employee employee = employeeRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
            // N1: deaktiviran nalog ne sme da dobije nov access token preko refresh-a.
            // Bez ove provere, admin-disablovan nalog zadrzava pristup do 7 dana
            // (refresh TTL) jer login-vremenski active flag nije ponovo proveravan.
            if (!Boolean.TRUE.equals(employee.getActive())) {
                throw new AuthenticationFailedException("Nalog je deaktiviran.");
            }
            String newAccessToken = jwtService.generateAccessToken(employee);
            String rotatedRefresh = rotateRefreshToken(refreshToken, jwtService.generateRefreshToken(employee));
            return new RefreshTokenResponseDto(newAccessToken, rotatedRefresh);
        }

        // N1: isto za korisnicke (CLIENT) naloge.
        if (!user.isActive()) {
            throw new AuthenticationFailedException("Nalog je deaktiviran.");
        }

        String newAccessToken = jwtService.generateAccessToken(user);
        String rotatedRefresh = rotateRefreshToken(refreshToken, jwtService.generateRefreshToken(user));

        return new RefreshTokenResponseDto(newAccessToken, rotatedRefresh);
    }

    /**
     * P1-auth-2 (R4 1740): refresh-token rotacija. Ranije je {@code /auth/refresh}
     * vracao ISTI refresh token bez rotacije → ukraden refresh je bio upotrebljiv
     * punih 7 dana (TTL), bez ikakve detekcije. Sada se pri svakom refresh-u izda
     * NOV refresh token, a STARI se blacklist-uje do njegovog exp-a. Posledica:
     * - ukraden stari refresh prestaje da radi cim ga legitiman korisnik iskoristi
     *   (rotacija ga blacklist-uje);
     * - reuse stare kopije (napadac ILI zrtva, ko god je drugi) odmah pada na
     *   {@code isBlacklisted} provero na pocetku {@code refreshToken()} → "Invalid".
     * Single-instance blacklist ogranicenje (BE-AUTH-07) i dalje vazi (memory store).
     */
    private String rotateRefreshToken(String oldRefreshToken, String newRefreshToken) {
        jwtBlacklistService.blacklist(oldRefreshToken);
        return newRefreshToken;
    }
}