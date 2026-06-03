package rs.raf.banka2_bek.otp;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import rs.raf.banka2_bek.IntegrationTestCleanup;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.otp.model.TotpSecret;
import rs.raf.banka2_bek.otp.repository.OtpConsumedCodeRepository;
import rs.raf.banka2_bek.otp.repository.TotpSecretRepository;
import rs.raf.banka2_bek.otp.service.OtpService;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OtpServiceIntegrationTest {

    @Autowired private OtpService otpService;
    @Autowired private UserRepository userRepository;
    @Autowired private TotpSecretRepository totpSecretRepository;
    @Autowired private OtpConsumedCodeRepository otpConsumedCodeRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    private static final String EMAIL = "totp-it@test.com";

    private String currentCode() {
        Long userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        String secret = totpSecretRepository.findByUserId(userId).orElseThrow().getSecret();
        return String.format("%06d", new GoogleAuthenticator().getTotpPassword(secret));
    }

    @BeforeEach
    void cleanAndSeed() {
        IntegrationTestCleanup.truncateAllTables(dataSource);
        userRepository.save(new User("Test", "User", EMAIL, "x", true, "CLIENT"));
    }

    @Test
    @DisplayName("generateAndSend creates a persisted TOTP secret for the user")
    void generateAndSendPersistsSecret() {
        otpService.generateAndSend(EMAIL);

        Long userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        Optional<TotpSecret> stored = totpSecretRepository.findByUserId(userId);

        assertThat(stored).isPresent();
        assertThat(stored.get().getSecret()).isNotBlank();
    }

    @Test
    @DisplayName("verify returns verified=true for the current TOTP code")
    void verifyAcceptsCurrentCode() {
        otpService.generateAndSend(EMAIL);

        Long userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        String secret = totpSecretRepository.findByUserId(userId).orElseThrow().getSecret();
        String code = String.format("%06d", new GoogleAuthenticator().getTotpPassword(secret));

        Map<String, Object> result = otpService.verify(EMAIL, code);

        assertThat(result.get("verified")).isEqualTo(true);
    }

    @Test
    @DisplayName("verify returns verified=false for an obviously wrong code")
    void verifyRejectsWrongCode() {
        otpService.generateAndSend(EMAIL);

        Map<String, Object> result = otpService.verify(EMAIL, "000000");

        assertThat(result.get("verified")).isEqualTo(false);
        assertThat(result.get("blocked")).isEqualTo(false);
    }

    @Test
    @DisplayName("getActiveOtp returns active=true with a 6-digit code and seconds left")
    void getActiveOtpReturnsCurrentCode() {
        otpService.generateAndSend(EMAIL);

        Map<String, Object> result = otpService.getActiveOtp(EMAIL);

        assertThat(result.get("active")).isEqualTo(true);
        assertThat((String) result.get("code")).matches("\\d{6}");
        assertThat((long) result.get("expiresInSeconds")).isBetween(1L, 30L);
    }

    /**
     * V-3 (N2-c): pravi DB-level replay — uspesan verify potrosi kod, drugi
     * verify istog koda mora biti odbijen end-to-end (UNIQUE constraint na
     * {@code (user_id, code_hash)}, ne mock).
     */
    @Test
    @DisplayName("V-3: isti kod verifikovan 2x — drugi put odbijen (DB UNIQUE replay guard)")
    void replayOfConsumedCodeRejectedAtDbLevel() {
        otpService.generateAndSend(EMAIL);
        String code = currentCode();

        Map<String, Object> first = otpService.verify(EMAIL, code);
        assertThat(first.get("verified")).isEqualTo(true);
        Long userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        assertThat(otpConsumedCodeRepository.findAll()).hasSize(1);

        Map<String, Object> second = otpService.verify(EMAIL, code);
        assertThat(second.get("verified")).isEqualTo(false);
        assertThat(second.get("replayed")).isEqualTo(true);
        // i dalje samo jedan zapis — replay nije napravio drugi consume.
        assertThat(otpConsumedCodeRepository.findAll()).hasSize(1);
    }

    /**
     * V-1 (N2-a): consume mora prezivati rollback caller-ove biznis tranzakcije.
     *
     * <p>Simuliramo inline caller (npr. {@code openDeposit}) tako sto pozovemo
     * {@code verify} UNUTAR spoljne tranzakcije koja se zatim ROLLBACK-uje (kao
     * da je biznis-validacija pala posle OTP-a). Posto {@code OtpConsumedCodeWriter}
     * commit-uje u {@code REQUIRES_NEW}, potroseni kod mora ostati u bazi i nakon
     * rollback-a — pa replay (2. put isti kod) mora biti odbijen.</p>
     */
    @Test
    @DisplayName("V-1: consume prezivi rollback biznis-tx (REQUIRES_NEW) — replay posle fail-a odbijen")
    void consumeSurvivesBusinessRollback() {
        otpService.generateAndSend(EMAIL);
        String code = currentCode();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // Spoljna tx: verify uspe (consume u REQUIRES_NEW), pa simuliramo pad
        // biznis-logike postavljanjem rollback-only -> spoljna tx se ponistava.
        Boolean verified = tx.execute(status -> {
            Map<String, Object> result = otpService.verify(EMAIL, code);
            status.setRollbackOnly(); // biznis "pada" posle OTP gate-a
            return Boolean.TRUE.equals(result.get("verified"));
        });
        assertThat(verified).isTrue();

        // Uprkos rollback-u spoljne tx, consume zapis je commit-ovan nezavisno.
        assertThat(otpConsumedCodeRepository.findAll()).hasSize(1);

        // Replay istog koda (kao da korisnik pokusa ponovo) — mora biti odbijen.
        Map<String, Object> replay = otpService.verify(EMAIL, code);
        assertThat(replay.get("verified")).isEqualTo(false);
        assertThat(replay.get("replayed")).isEqualTo(true);
    }
}
