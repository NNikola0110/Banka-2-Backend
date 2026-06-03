package rs.raf.trading.margin.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.model.UserMarginAccount;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-margin-1 (R2 1326 / R3 1548): {@code findEligibleForBlock} mora koristiti
 * RASPOLOZIVI initialMargin {@code (IM − reservedMargin)} ispod MM, ne sirovi IM.
 *
 * <p>Bug: racun sa velikim {@code reservedMargin} (hold za in-flight margin BUY)
 * ima raspolozivu marzu ispod MM, ali ga je stari uslov {@code MM > IM} (sirov IM)
 * propustao → dnevni margin call je kasnio (banka izlozena). Sada je uslov
 * {@code (IM − reservedMargin) < MM}, konzistentno sa withdraw guard-om (P1-8) i
 * post-fill {@code checkMarginCallAndBlock}.
 *
 * <p>Spring Boot 4 nema {@code @DataJpaTest} — {@code @SpringBootTest} + H2
 * (application-test.properties), isti obrazac kao OrderOptimisticLockTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class MarginAccountRepositoryEligibleForBlockTest {

    @Autowired
    private MarginAccountRepository marginAccountRepository;

    @AfterEach
    void tearDown() {
        marginAccountRepository.deleteAll();
    }

    @Test
    @DisplayName("Reservacija gura raspolozivi IM ispod MM iako je sirov IM iznad MM -> eligible")
    void rawImAboveMmButAvailableBelowMm_isEligibleForBlock() {
        // Sirov IM=8000 >= MM=5000 (stari kod NE bi blokirao), ali reservedMargin=4000
        // → raspolozivi IM = 8000 - 4000 = 4000 < MM=5000 → MORA biti eligible.
        UserMarginAccount account = save(8000, 5000, 4000, MarginAccountStatus.ACTIVE);

        List<Long> eligible = marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE);

        assertThat(eligible).containsExactly(account.getId());
    }

    @Test
    @DisplayName("Bez rezervacije, IM iznad MM -> NIJE eligible")
    void noReservation_imAboveMm_notEligible() {
        save(8000, 5000, 0, MarginAccountStatus.ACTIVE);

        List<Long> eligible = marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE);

        assertThat(eligible).isEmpty();
    }

    @Test
    @DisplayName("Raspolozivi IM ispod MM cak i bez rezervacije -> eligible (regression)")
    void availableBelowMm_eligible() {
        UserMarginAccount account = save(4000, 5000, 0, MarginAccountStatus.ACTIVE);

        List<Long> eligible = marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE);

        assertThat(eligible).containsExactly(account.getId());
    }

    @Test
    @DisplayName("Vec BLOCKED racun nije ponovo eligible")
    void alreadyBlocked_notEligible() {
        save(2000, 5000, 0, MarginAccountStatus.BLOCKED);

        List<Long> eligible = marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE);

        assertThat(eligible).isEmpty();
    }

    @Test
    @DisplayName("Granica: raspolozivi IM == MM -> NIJE eligible (strogo manje)")
    void availableEqualsMm_notEligible() {
        save(7000, 5000, 2000, MarginAccountStatus.ACTIVE); // available = 5000 == MM

        List<Long> eligible = marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE);

        assertThat(eligible).isEmpty();
    }

    private UserMarginAccount save(int im, int mm, int reserved, MarginAccountStatus status) {
        UserMarginAccount account = UserMarginAccount.builder()
                .accountId(1L)
                .accountNumber("222000112345678911")
                .userId(10L)
                .currency("RSD")
                .initialMargin(new BigDecimal(im))
                .loanValue(BigDecimal.ZERO)
                .maintenanceMargin(new BigDecimal(mm))
                .bankParticipation(new BigDecimal("0.50"))
                .reservedMargin(new BigDecimal(reserved))
                .status(status)
                .build();
        return (UserMarginAccount) marginAccountRepository.save(account);
    }
}
