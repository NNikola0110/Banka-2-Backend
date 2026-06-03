package rs.raf.trading.otc.saga.durability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.otc.saga.fault.SagaFaultInjector;
import rs.raf.trading.otc.saga.model.SagaLog;
import rs.raf.trading.otc.saga.model.SagaPhase;
import rs.raf.trading.otc.saga.model.SagaStatus;
import rs.raf.trading.otc.saga.model.SagaStepKind;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;
import rs.raf.trading.otc.saga.service.OtcExerciseSagaOrchestrator;
import rs.raf.trading.otc.saga.support.FakeBankaCoreClient;
import rs.raf.trading.otc.saga.support.FakeBankaCoreConfig;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * <b>P1-1</b> — write-ahead durability of the SAGA log across an OUTER-transaction
 * rollback (coordinator-crash simulation). Proves SAGA_test.pdf I4 / SG-11: the
 * {@code saga_logs} row + per-phase progress survive even when the in-process
 * {@code exercise()} transaction is rolled back AFTER money already moved
 * out-of-process in banka-core.
 *
 * <p><b>Mechanism.</b> A {@code @Primary} {@link SagaFaultInjector} throws a raw
 * {@link Error} (NOT a {@link RuntimeException}) at {@code F2/before}. The
 * orchestrator's {@code exercise()} catches only {@code RuntimeException} for the
 * compensation path, so the {@code Error} escapes the method → Spring rolls back
 * the outer {@code @Transactional(REQUIRED)} transaction (exactly what a pod-kill /
 * SIGKILL after F1 would look like to PostgreSQL: the whole local tx is discarded).
 *
 * <p>The RUNNING row + the F1 forward entry are written through the
 * {@code SagaLogWriter} in its OWN {@code REQUIRES_NEW} transaction, so they commit
 * independently of the outer tx. After the {@code Error} the test re-reads
 * {@code saga_logs} in a fresh transaction (test method is non-transactional, each
 * repository call opens its own read tx) and asserts the row IS present with status
 * RUNNING and the F1 forward log — so {@code SagaRecoveryService.recoverOnce()}
 * (which queries {@code findByStatusIn([RUNNING,COMPENSATING])}) would find the
 * stuck saga and drive it to terminal.
 *
 * <p>Without the fix (RUNNING row + per-phase appends inside the outer tx) the
 * rollback wipes every {@code saga_logs} row → this test fails (the assertion that
 * the row survives is RED), which is precisely the durability defect.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeBankaCoreConfig.class, OtcSagaLogDurabilityIntegrationTest.ErrorAtF2Config.class})
class OtcSagaLogDurabilityIntegrationTest {

    private static final Long BUYER_ID = 7101L;
    private static final Long SELLER_ID = 7102L;
    private static final Long BUYER_ACCOUNT_ID = 7201L;
    private static final Long SELLER_ACCOUNT_ID = 7202L;
    private static final String CCY = "USD";
    private static final int QTY = 10;
    private static final BigDecimal STRIKE = new BigDecimal("160.0000");
    private static final BigDecimal COST = new BigDecimal("1600.0000");
    private static final BigDecimal START_BALANCE = new BigDecimal("100000.0000");

    /**
     * Forsira RAW {@link Error} u F2/before. {@code exercise()} hvata samo
     * {@code RuntimeException} → Error eskalira van metode → outer tx rollback.
     */
    @TestConfiguration
    static class ErrorAtF2Config {
        static final AtomicBoolean ARMED = new AtomicBoolean(true);

        @Bean
        @Primary
        SagaFaultInjector errorAtF2() {
            return new SagaFaultInjector() {
                @Override
                public void maybeFailForward(SagaPhase phase, String kind) {
                    if (phase == SagaPhase.F2 && "before".equalsIgnoreCase(kind) && ARMED.get()) {
                        throw new Error("simulirani coordinator crash u F2 (pre bocnih efekata)");
                    }
                }

                @Override
                public void maybeFailCompensator(String sagaId, SagaPhase phase) { }

                @Override
                public void maybeDelay(SagaPhase phase) { }
            };
        }
    }

    @Autowired private OtcExerciseSagaOrchestrator orchestrator;
    @Autowired private OtcContractRepository contractRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private PortfolioRepository portfolioRepository;
    @Autowired private SagaLogRepository sagaLogRepository;
    @Autowired private BankaCoreClient bankaCoreClient;
    private FakeBankaCoreClient fake;

    @MockitoBean private TradingUserResolver tradingUserResolver;

    @BeforeEach
    void setUp() {
        ErrorAtF2Config.ARMED.set(true);
        fake = (FakeBankaCoreClient) bankaCoreClient;
        sagaLogRepository.deleteAll();
        contractRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();

        lenient().when(tradingUserResolver.resolveCurrent())
                .thenReturn(new UserContext(BUYER_ID, UserRole.CLIENT));

        // P2-7: exercise() se ovde poziva DIREKTNO (bez HTTP filter chain-a), pa OTC access
        // gate cita SecurityContext koji moramo rucno postaviti — klijent sa TRADE_STOCKS.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("buyer@test.com", "n",
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"),
                                new SimpleGrantedAuthority("TRADE_STOCKS"))));

        fake.mapPreferredAccount(UserRole.CLIENT, BUYER_ID, BUYER_ACCOUNT_ID);
        fake.mapPreferredAccount(UserRole.CLIENT, SELLER_ID, SELLER_ACCOUNT_ID);
        fake.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE, COST);
        fake.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);
        fake.seedReservation("RES-DUR", BUYER_ACCOUNT_ID, COST);
    }

    @AfterEach
    void tearDown() {
        ErrorAtF2Config.ARMED.set(true);
        SecurityContextHolder.clearContext();
        sagaLogRepository.deleteAll();
        contractRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();
    }

    private Listing savedStockListing() {
        Listing l = new Listing();
        l.setTicker("AAPL");
        l.setName("Apple Inc.");
        l.setListingType(ListingType.STOCK);
        l.setQuoteCurrency(CCY);
        l.setExchangeAcronym("NASDAQ");
        l.setPrice(BigDecimal.valueOf(150));
        l.setAsk(BigDecimal.valueOf(151));
        l.setBid(BigDecimal.valueOf(149));
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    private OtcContract savedReservedContract(Listing listing) {
        OtcContract c = new OtcContract();
        c.setSourceOfferId(1L);
        c.setBuyerId(BUYER_ID);
        c.setBuyerRole(UserRole.CLIENT);
        c.setSellerId(SELLER_ID);
        c.setSellerRole(UserRole.CLIENT);
        c.setListing(listing);
        c.setQuantity(QTY);
        c.setStrikePrice(STRIKE);
        c.setPremium(new BigDecimal("50.0000"));
        c.setSettlementDate(LocalDate.now().plusDays(30));
        c.setStatus(OtcContractStatus.ACTIVE);
        c.setBuyerReservedAccountId(BUYER_ACCOUNT_ID);
        c.setBuyerReservedAmount(COST);
        c.setBankaCoreReservationId("RES-DUR");
        c.setCreatedAt(LocalDateTime.now());
        return contractRepository.save(c);
    }

    private Portfolio savedSellerPortfolio(Listing listing) {
        Portfolio p = new Portfolio();
        p.setUserId(SELLER_ID);
        p.setUserRole(UserRole.CLIENT);
        p.setListingId(listing.getId());
        p.setListingTicker("AAPL");
        p.setListingName("Apple Inc.");
        p.setListingType("STOCK");
        p.setQuantity(20);
        p.setAverageBuyPrice(new BigDecimal("100.0000"));
        p.setPublicQuantity(10);
        p.setReservedQuantity(10);
        return portfolioRepository.save(p);
    }

    @Test
    @DisplayName("Write-ahead durability (I4/SG-11): outer-tx rollback posle RUNNING write-a NE brise "
            + "saga_logs red — recovery ga moze naci")
    void sagaLogSurvivesOuterTransactionRollback() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);

        // Error u F2/before eskalira van exercise() (hvata se samo RuntimeException) → outer tx rollback.
        assertThatThrownBy(() -> orchestrator.exercise(contract.getId(), BUYER_ACCOUNT_ID))
                .isInstanceOf(Error.class);

        // Fresh read (test metoda nije @Transactional → svaki repo poziv je svoja read-tx):
        // ako je RUNNING red upisan kroz REQUIRES_NEW writer, PREZIVEO je outer rollback.
        List<SagaLog> logs = sagaLogRepository.findByContractId(contract.getId());
        assertThat(logs)
                .as("Write-ahead: RUNNING saga_logs red mora preziveti outer-tx rollback (inace ga recovery ne nadje)")
                .hasSize(1);

        SagaLog persisted = logs.get(0);
        assertThat(persisted.getStatus())
                .as("zaglavljena saga ostaje RUNNING — recoverOnce() je hvata preko findByStatusIn")
                .isEqualTo(SagaStatus.RUNNING);
        assertThat(persisted.getContractId()).isEqualTo(contract.getId());
        // F1 je prosao pre F2/before Error-a → njegov forward log je takodje durabilan (write-ahead progres).
        assertThat(persisted.getEntries())
                .as("F1 forward progres je durabilan (commit-ovan kroz REQUIRES_NEW pre Error-a)")
                .anySatisfy(e -> {
                    assertThat(e.phase()).isEqualTo(SagaPhase.F1.step());
                    assertThat(e.kind()).isEqualTo(SagaStepKind.FORWARD);
                    assertThat(e.outcome()).isEqualTo("ok");
                });

        // Recovery vidljivost: findByStatusIn([RUNNING,COMPENSATING]) nalazi zaglavljenu sagu.
        assertThat(sagaLogRepository.findByStatusIn(List.of(SagaStatus.RUNNING, SagaStatus.COMPENSATING)))
                .as("recovery sweep mora moci da pronadje zaglavljenu sagu")
                .extracting(SagaLog::getId)
                .contains(persisted.getId());
    }
}
