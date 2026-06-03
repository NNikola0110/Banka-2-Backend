package rs.raf.trading.margin.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.model.UserMarginAccount;
import rs.raf.trading.margin.repository.MarginAccountRepository;
import rs.raf.trading.margin.service.MarginAccountService.BlockedAccountSnapshot;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OT-1111 (bulkUpdateStatus stale-cache): {@link MarginAccountService#blockEligibleAccounts()}
 * flipa VISE eligible racuna ACTIVE→BLOCKED u JEDNOJ transakciji preko
 * {@code @Modifying} bulk UPDATE-a ({@link MarginAccountRepository#bulkUpdateStatus}),
 * pa potom re-cita iste redove ({@code findAllById}) da izgradi notifikacione
 * snapshot-e.
 *
 * <p><b>Zasto je ovo test-rupa:</b> {@code bulkUpdateStatus} je JPQL bulk UPDATE
 * koji NE prolazi kroz first-level (persistence-context) kes. Da su entiteti vec
 * bili ucitani u kontekst pre UPDATE-a, naredni {@code findAllById} bi vratio
 * STALE (ACTIVE) snapshot-e (poznata Hibernate zamka — bez {@code clearAutomatically=true}).
 * Postojeci {@code MarginAccountServiceTest} testovi mock-uju repozitorijum pa
 * NIKAD ne izvrse pravi bulk UPDATE → ova realna JPA (H2) provera pinuje da posle
 * blok-ciklusa DB STVARNO reflektuje BLOCKED za sve eligible racune i da nijedan
 * stale ACTIVE red ne ostane.
 *
 * <p>Spring Boot 4 nema {@code @DataJpaTest} — {@code @SpringBootTest} + H2
 * (application-test.properties), isti obrazac kao
 * {@code MarginAccountRepositoryEligibleForBlockTest}. {@code blockEligibleAccounts}
 * je cisto-DB (bez mreznog I/O), pa {@code BankaCoreClient} nije ni potreban —
 * pozivamo direktno bulk-blok metodu, ne {@code checkMaintenanceMargin} (koji bi
 * radio email lookup za notifikacije).
 */
@SpringBootTest
@ActiveProfiles("test")
class MarginBlockEligibleIntegrationTest {

    @Autowired
    private MarginAccountService marginAccountService;

    @Autowired
    private MarginAccountRepository marginAccountRepository;

    @AfterEach
    void tearDown() {
        marginAccountRepository.deleteAll();
    }

    @Test
    @DisplayName("blockEligibleAccounts — vise eligible racuna se blokira u DB-u (nema stale ACTIVE)")
    void blocksAllEligibleAccounts_dbReflectsBlocked_noStaleActive() {
        // 3 eligible (raspolozivi IM < MM) + 1 zdrav (IM iznad MM) → samo 3 se blokiraju.
        UserMarginAccount a1 = save(10L, 4000, 5000, 0, MarginAccountStatus.ACTIVE);   // 4000 < 5000
        UserMarginAccount a2 = save(11L, 8000, 5000, 4000, MarginAccountStatus.ACTIVE); // 4000 < 5000
        UserMarginAccount a3 = save(12L, 2000, 5000, 0, MarginAccountStatus.ACTIVE);   // 2000 < 5000
        UserMarginAccount healthy = save(13L, 9000, 5000, 0, MarginAccountStatus.ACTIVE); // 9000 >= 5000

        List<BlockedAccountSnapshot> snapshots = marginAccountService.blockEligibleAccounts();

        // Tacno 3 snapshot-a (eligible), nikad zdrav racun.
        assertThat(snapshots).hasSize(3);
        assertThat(snapshots).extracting(BlockedAccountSnapshot::accountId)
                .containsExactlyInAnyOrder(a1.getId(), a2.getId(), a3.getId());

        // DB STVARNO reflektuje BLOCKED za sva tri — ponovni upit (sveza Tx) ne sme
        // da vrati nijedan stale ACTIVE eligible red.
        List<MarginAccount> blocked = marginAccountRepository.findByStatus(MarginAccountStatus.BLOCKED);
        assertThat(blocked).extracting(MarginAccount::getId)
                .containsExactlyInAnyOrder(a1.getId(), a2.getId(), a3.getId());

        // Zdrav racun ostaje ACTIVE.
        MarginAccount healthyReloaded = marginAccountRepository.findById(healthy.getId()).orElseThrow();
        assertThat(healthyReloaded.getStatus()).isEqualTo(MarginAccountStatus.ACTIVE);

        // Nakon blokade, NEMA vise eligible-for-block redova (svi flipovani).
        assertThat(marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE)).isEmpty();
    }

    @Test
    @DisplayName("blockEligibleAccounts — kad nema eligible racuna, vraca praznu listu i nista ne menja")
    void noEligible_returnsEmpty_andLeavesActive() {
        UserMarginAccount healthy = save(20L, 9000, 5000, 0, MarginAccountStatus.ACTIVE);

        List<BlockedAccountSnapshot> snapshots = marginAccountService.blockEligibleAccounts();

        assertThat(snapshots).isEmpty();
        assertThat(marginAccountRepository.findById(healthy.getId()).orElseThrow().getStatus())
                .isEqualTo(MarginAccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("blockEligibleAccounts — snapshot deficit = MM − raspolozivi IM (IM − reservedMargin)")
    void snapshotDeficit_usesAvailableMarginNotRawIm() {
        // IM=8000, reserved=4000 → raspolozivi = 4000; MM=5000 → deficit = 5000 − 4000 = 1000.
        UserMarginAccount a = save(30L, 8000, 5000, 4000, MarginAccountStatus.ACTIVE);

        List<BlockedAccountSnapshot> snapshots = marginAccountService.blockEligibleAccounts();

        assertThat(snapshots).hasSize(1);
        BlockedAccountSnapshot snap = snapshots.get(0);
        assertThat(snap.accountId()).isEqualTo(a.getId());
        assertThat(snap.userId()).isEqualTo(30L);
        assertThat(snap.deficit()).isEqualByComparingTo("1000");
    }

    private UserMarginAccount save(long userId, int im, int mm, int reserved, MarginAccountStatus status) {
        UserMarginAccount account = UserMarginAccount.builder()
                .accountId(userId)
                .accountNumber("222000112345678" + userId)
                .userId(userId)
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
