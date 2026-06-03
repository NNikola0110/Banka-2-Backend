package rs.raf.banka2_bek.employee.service.implementation;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.employee.dto.ActivationTokenStatusDto;
import rs.raf.banka2_bek.employee.model.ActivationToken;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.ActivationTokenRepository;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.employee.service.EmployeeAuthService;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of {@link EmployeeAuthService}.
 * Authors: Aleksa Vucinic (avucinic6020rn@raf.rs), Petar Poznanovic (ppoznanovic4917rn@raf.rs)
 */
@Service
@RequiredArgsConstructor
public class EmployeeAuthServiceImpl implements EmployeeAuthService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeAuthServiceImpl.class);

    /** Spec Sc 9: aktivacioni token traje 24h (isti TTL kao u createEmployee). */
    private static final int ACTIVATION_TOKEN_TTL_HOURS = 24;

    private final ActivationTokenRepository activationTokenRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;

    private final NotificationPublisher notificationPublisher;

    @Override
    @Transactional
    public void activateAccount(String tokenValue, String newPassword) {
        ActivationToken token = activationTokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid activation token."));

        if (token.isUsed() || token.isInvalidated()) {
            throw new IllegalArgumentException("Activation token already used or invalidated.");
        }

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Activation token has expired.");
        }

        Employee employee = token.getEmployee();

        if (Boolean.TRUE.equals(employee.getActive())) {
            throw new IllegalStateException("Account is already active.");
        }

        // Set the new password (hashed with existing salt)
        String salt = employee.getSaltPassword();
        employee.setPassword(passwordEncoder.encode(newPassword + salt));
        employee.setActive(true);

        token.setUsed(true);
        token.setUsedAt(LocalDateTime.now());
        token.setInvalidated(true);

        employeeRepository.save(employee);
        activationTokenRepository.save(token);

        notificationPublisher.sendActivationConfirmationMail(employee.getEmail(), employee.getFirstName());
    }

    /**
     * Spec Sc 9 + ad-hoc bag 12.05.2026: vraca stanje tokena bez bacanja
     * izuzetka, tako da FE moze da pre-check-uje pre renderovanja forme.
     *
     * Status redosled provere odgovara redosledu u {@link #activateAccount}:
     *   1) Token ne postoji  → INVALID
     *   2) used || invalidated → USED
     *   3) expiresAt < now → EXPIRED
     *   4) employee.active == true → ALREADY_ACTIVE (token jos validan ali nalog je vec aktivan)
     *   5) Sve OK → VALID
     *
     * BE-AUTH-05 fix: {@code @Transactional(readOnly=true)} je obavezno jer u
     * {@code if (token.isUsed() || token.isInvalidated())} putanji ucitavamo
     * lazy asocijaciju {@code token.getEmployee()}. Bez aktivne Hibernate Session-a
     * (van Tx scope-a), {@code LazyInitializationException} se baca. {@code readOnly=true}
     * dodatno hint-uje JPA provider-u da preskoci flush + omogucava {@code LazyConnectionDataSourceProxy}
     * da rute zahtev na read replica ako je konfigurisano.
     */
    @Override
    @Transactional(readOnly = true)
    public ActivationTokenStatusDto getTokenStatus(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            return ActivationTokenStatusDto.builder().status("INVALID").build();
        }
        Optional<ActivationToken> opt = activationTokenRepository.findByToken(tokenValue);
        if (opt.isEmpty()) {
            return ActivationTokenStatusDto.builder().status("INVALID").build();
        }
        ActivationToken token = opt.get();

        if (token.isUsed() || token.isInvalidated()) {
            // Vracamo email (radi UX-a — "ovaj nalog je vec aktiviran") ali se
            // status razlikuje od ALREADY_ACTIVE jer je u ovom slucaju token
            // potrosen, FE moze prikazati "Link za aktivaciju je vec iskoriscen".
            Employee emp = token.getEmployee();
            return ActivationTokenStatusDto.builder()
                    .status("USED")
                    .expiresAt(token.getExpiresAt())
                    .email(emp != null ? emp.getEmail() : null)
                    .build();
        }

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ActivationTokenStatusDto.builder()
                    .status("EXPIRED")
                    .expiresAt(token.getExpiresAt())
                    .build();
        }

        Employee employee = token.getEmployee();
        if (employee != null && Boolean.TRUE.equals(employee.getActive())) {
            return ActivationTokenStatusDto.builder()
                    .status("ALREADY_ACTIVE")
                    .expiresAt(token.getExpiresAt())
                    .email(employee.getEmail())
                    .build();
        }

        return ActivationTokenStatusDto.builder()
                .status("VALID")
                .expiresAt(token.getExpiresAt())
                .email(employee != null ? employee.getEmail() : null)
                .build();
    }

    /**
     * Spec Celina 1 Sc 9: slanje novog aktivacionog linka za zaposlenog ciji je
     * (najcesce istekli) aktivacioni token prosledjen.
     *
     * <p>Anti-enumeration (mirror {@code AuthService.requestPasswordReset}):
     * uvek se vraca tiho, bez izuzetka, bez razlikovanja "token ne postoji" /
     * "nalog vec aktivan" — caller vraca istu generic poruku da se ne otkrije
     * stanje naloga napadacu.
     *
     * <p>Tok kad zaposleni POSTOJI i NIJE aktivan:
     *   1) invalidiraj sve njegove aktivne tokene (stari link prestaje da vredi),
     *   2) generisi svez token sa istim 24h TTL kao u {@code createEmployee},
     *   3) posalji nov aktivacioni email istim mehanizmom (sendActivationMail).
     */
    @Override
    @Transactional
    public void resendActivation(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            // Bez interakcije sa bazom — generic no-op (anti-enumeration).
            log.info("Sc9 resend-activation: prazan token, ignorisem (generic no-op).");
            return;
        }

        Optional<ActivationToken> opt = activationTokenRepository.findByToken(tokenValue);
        if (opt.isEmpty()) {
            // Nepostojeci token — tiho izlazimo, bez 404 leak-a.
            log.info("Sc9 resend-activation: token ne postoji, generic no-op (anti-enumeration).");
            return;
        }

        Employee employee = opt.get().getEmployee();
        if (employee == null || Boolean.TRUE.equals(employee.getActive())) {
            // Nalog je vec aktivan (ili token bez vlasnika) — ne radimo nista, ista generic poruka.
            log.info("Sc9 resend-activation: nalog vec aktivan ili token bez vlasnika, generic no-op.");
            return;
        }

        // 1) Invalidiraj sve aktivne (neiskoriscene, ne-invalidovane) tokene zaposlenog.
        activationTokenRepository.invalidateAllActiveTokensForEmployee(employee);

        // 2) Generisi svez token sa istim 24h TTL kao u EmployeeServiceImpl.createEmployee.
        String newTokenValue = UUID.randomUUID().toString();
        ActivationToken freshToken = ActivationToken.builder()
                .token(newTokenValue)
                .employee(employee)
                .expiresAt(LocalDateTime.now().plusHours(ACTIVATION_TOKEN_TTL_HOURS))
                .used(false)
                .invalidated(false)
                .build();
        activationTokenRepository.save(freshToken);

        // 3) Posalji nov aktivacioni email (isti mehanizam kao createEmployee).
        notificationPublisher.sendActivationMail(
                employee.getEmail(), employee.getFirstName(), newTokenValue);
    }
}
