package rs.raf.trading.otc.saga.realcrash;

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
import rs.raf.trading.otc.saga.model.SagaLogEntry;
import rs.raf.trading.otc.saga.model.SagaPhase;
import rs.raf.trading.otc.saga.model.SagaStatus;
import rs.raf.trading.otc.saga.model.SagaStepKind;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;
import rs.raf.trading.otc.saga.scheduler.SagaRecoveryService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * <b>P0-T2 — REAL-CRASH SAGA exercise correctness (N1-N4).</b>
 *
 * <p>Razlika prema postojecim suite-ovima: {@code OtcSagaInvariantIntegrationTest} /
 * {@code SagaRecoveryServiceTest} koriste IN-PROCESS {@link RuntimeException} fault u
 * ZIVOM outer tx-u — kompenzacija tece u istom tx-u koji se NA KRAJU commit-uje
 * zajedno sa kompenzacijama, pa nema divergencije write-ahead-flag ↔ outer-commit.
 * To NIJE real-crash. Ovde se simulira PRAVI pad koordinatora: outer tx se
 * ROLLBACK-uje (kroz raw {@link Error} koji eskalira iznad {@code exercise()} koji
 * hvata samo {@code RuntimeException}), dok write-ahead {@code saga_logs} zapisi
 * PREZIVE — pa {@link SagaRecoveryService} mora da dovede sagu do terminala BEZ
 * krsenja invarijanti, oslanjajuci se na STVARNO stanje baze (a ne na zaostale
 * write-ahead flag-ove ciji je lokalni efekat rollback-ovan).
 *
 * <ul>
 *   <li><b>N1</b> — write-ahead phantom shares (I2): F4 seller dekrement (LOKALNI JPA)
 *       rollback-ovan real-crash-om, ali (pre fix-a) {@code f4SellerApplied} write-ahead
 *       preziveo → recovery C4 {@code += qty} → phantom akcije. Posle fix-a flag deli
 *       sudbinu sa dekrementom (outer tx) → nema phantom-a.</li>
 *   <li><b>N2</b> — lazni COMPLETED → re-exercise: F5 status flip (LOKALNI) rollback-ovan,
 *       contract ostaje ACTIVE; recovery NE sme proglasiti COMPLETED dok DB kaze ACTIVE.</li>
 *   <li><b>N3</b> — C3 refund na TACAN kupcev racun (multi-account): efektivni
 *       {@code buyerAccountId} write-ahead u F1 → recovery C3 refundira taj, ne default.</li>
 *   <li><b>N4</b> — F3 credit↔persist prozor (I1): {@code creditIntent} write-ahead pre
 *       creditFunds → recovery zna da je credit MOZDA izvrsen → pun reverzni transfer,
 *       ne commit-only refund (koji bi stvorio novac). Recovery NE pogadja po prozoru
 *       (intent=true/done=false) nego AUTORITATIVNO pita banka-core da li je F3-credit
 *       idempotency kljuc KONZUMIRAN: consumed=true -> pun reverzni transfer (I1);
 *       consumed=false (credit nikad izvrsen) -> commit-only refund kupcu, prodavac NETAKNUT.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({FakeBankaCoreConfig.class, OtcSagaRealCrashRecoveryIntegrationTest.RealCrashFaultConfig.class})
class OtcSagaRealCrashRecoveryIntegrationTest {

    private static final Long BUYER_ID = 7301L;
    private static final Long SELLER_ID = 7302L;
    private static final Long BUYER_ACCOUNT_ID = 7401L;
    private static final Long BUYER_ACCOUNT_ID_ALT = 7403L;   // N3 multi-account: NON-default racun
    private static final Long SELLER_ACCOUNT_ID = 7402L;
    private static final String CCY = "USD";
    private static final int QTY = 10;
    private static final BigDecimal STRIKE = new BigDecimal("160.0000");
    private static final BigDecimal COST = new BigDecimal("1600.0000");
    private static final BigDecimal START_BALANCE = new BigDecimal("100000.0000");

    /**
     * Real-crash injektor: baca RAW {@link Error} (NE RuntimeException) na zadatoj tacki.
     * {@code exercise()} hvata samo {@code RuntimeException} → Error eskalira → outer tx
     * rollback (tacno kao SIGKILL koordinatora: ceo lokalni tx se odbacuje, a vec
     * write-ahead-commit-ovani saga_logs zapisi prezive).
     */
    @TestConfiguration
    static class RealCrashFaultConfig {
        enum Point { NONE, MID_F4, AFTER_F5 }
        static volatile Point armed = Point.NONE;

        @Bean
        @Primary
        SagaFaultInjector realCrashInjector() {
            return new SagaFaultInjector() {
                @Override public void maybeFailForward(SagaPhase phase, String kind) {
                    if (armed == Point.AFTER_F5 && phase == SagaPhase.F5 && "after".equalsIgnoreCase(kind)) {
                        throw new Error("REAL-CRASH posle F5 efekta, pre outer commit-a (N2)");
                    }
                }
                @Override public void maybeFailForwardMid(SagaPhase phase) {
                    if (armed == Point.MID_F4 && phase == SagaPhase.F4) {
                        throw new Error("REAL-CRASH u sredini F4: posle seller dekrementa, pre buyer credit-a (N1)");
                    }
                }
                @Override public void maybeFailCompensator(String sagaId, SagaPhase phase) { }
                @Override public void maybeDelay(SagaPhase phase) { }
            };
        }
    }

    @Autowired private OtcExerciseSagaOrchestrator orchestrator;
    @Autowired private OtcContractRepository contractRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private PortfolioRepository portfolioRepository;
    @Autowired private SagaLogRepository sagaLogRepository;
    @Autowired private SagaRecoveryService sagaRecoveryService;
    @Autowired private BankaCoreClient bankaCoreClient;
    private FakeBankaCoreClient fake;

    @MockitoBean private TradingUserResolver tradingUserResolver;

    @BeforeEach
    void setUp() {
        RealCrashFaultConfig.armed = RealCrashFaultConfig.Point.NONE;
        fake = (FakeBankaCoreClient) bankaCoreClient;
        sagaLogRepository.deleteAll();
        contractRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();

        lenient().when(tradingUserResolver.resolveCurrent())
                .thenReturn(new UserContext(BUYER_ID, UserRole.CLIENT));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("buyer@test.com", "n",
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"),
                                new SimpleGrantedAuthority("TRADE_STOCKS"))));

        fake.mapPreferredAccount(UserRole.CLIENT, BUYER_ID, BUYER_ACCOUNT_ID);
        fake.mapPreferredAccount(UserRole.CLIENT, SELLER_ID, SELLER_ACCOUNT_ID);
    }

    @AfterEach
    void tearDown() {
        RealCrashFaultConfig.armed = RealCrashFaultConfig.Point.NONE;
        SecurityContextHolder.clearContext();
        sagaLogRepository.deleteAll();
        contractRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();
    }

    // ─────────────────────────── seed helpers ───────────────────────────────

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

    private OtcContract savedReservedContract(Listing listing, Long buyerReservedAccountId,
                                              String reservationId) {
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
        c.setBuyerReservedAccountId(buyerReservedAccountId);
        c.setBuyerReservedAmount(COST);
        c.setBankaCoreReservationId(reservationId);
        c.setCreatedAt(LocalDateTime.now());
        return contractRepository.save(c);
    }

    private Portfolio savedSellerPortfolio(Listing listing, int quantity, int reservedQty) {
        Portfolio p = new Portfolio();
        p.setUserId(SELLER_ID);
        p.setUserRole(UserRole.CLIENT);
        p.setListingId(listing.getId());
        p.setListingTicker("AAPL");
        p.setListingName("Apple Inc.");
        p.setListingType("STOCK");
        p.setQuantity(quantity);
        p.setAverageBuyPrice(new BigDecimal("100.0000"));
        p.setPublicQuantity(0);
        p.setReservedQuantity(reservedQty);
        return portfolioRepository.save(p);
    }

    private int sellerShares(Listing listing) {
        return portfolioRepository.findByUserIdAndUserRoleAndListingId(SELLER_ID, UserRole.CLIENT, listing.getId())
                .map(p -> p.getQuantity() == null ? 0 : p.getQuantity()).orElse(0);
    }

    private int buyerShares(Listing listing) {
        return portfolioRepository.findByUserIdAndUserRoleAndListingId(BUYER_ID, UserRole.CLIENT, listing.getId())
                .map(p -> p.getQuantity() == null ? 0 : p.getQuantity()).orElse(0);
    }

    // ──────────────────────────────── N1 ────────────────────────────────────

    /**
     * <b>N1 (CRITICAL) — write-ahead phantom shares (I2) na real-crash u F4.</b>
     *
     * <p>Live {@code exercise()} dekrementuje seller poziciju (LOKALNI JPA, outer tx),
     * pa pukne raw {@link Error} U SREDINI F4 (pre buyer credit-a) → outer tx ROLLBACK
     * (seller dekrement ponisten, contract ostaje ACTIVE). Write-ahead saga_logs zapisi
     * (RUNNING + F1/F2/F3 ok) prezive. {@code SagaRecoveryService} preuzima zaglavljenu sagu.
     *
     * <p><b>Bug (pre fix-a):</b> {@code f4SellerApplied} je bio write-ahead → preziveo
     * rollback → recovery C4 radi {@code seller.quantity += qty} → seller dobije ORIGINAL+qty
     * akcija koje niko nije oduzeo → PHANTOM akcije (I2 prekrsen, ukupne akcije narastu).
     *
     * <p><b>Fix:</b> {@code f4SellerApplied} sad deli sudbinu sa dekrementom (outer tx) → na
     * rollback-u i flag nestane → recovery C4 no-op za seller-leg → ukupne akcije ocuvane.
     */
    @Test
    @DisplayName("N1: real-crash u sredini F4 → recovery NE sme dodati phantom akcije (I2 ocuvan)")
    void n1_realCrashMidF4_recoveryDoesNotCreatePhantomShares() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing, BUYER_ACCOUNT_ID, "RES-N1");
        savedSellerPortfolio(listing, 20, 10);
        fake.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE, COST);
        fake.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);
        fake.seedReservation("RES-N1", BUYER_ACCOUNT_ID, COST);

        int sellerBefore = sellerShares(listing);     // 20
        int totalSharesBefore = sellerBefore + buyerShares(listing);
        BigDecimal moneyBefore = fake.totalMoney();

        // REAL-CRASH: raw Error u sredini F4 → outer tx rollback (seller dekrement ponisten).
        RealCrashFaultConfig.armed = RealCrashFaultConfig.Point.MID_F4;
        assertThatThrownBy(() -> orchestrator.exercise(contract.getId(), BUYER_ACCOUNT_ID))
                .isInstanceOf(Error.class);
        RealCrashFaultConfig.armed = RealCrashFaultConfig.Point.NONE;

        // Outer tx rollback: seller dekrement ponisten (fresh read iz baze).
        assertThat(sellerShares(listing))
                .as("outer tx rollback je ponistio F4 seller dekrement")
                .isEqualTo(sellerBefore);

        // Recovery preuzima zaglavljenu sagu (RUNNING/COMPENSATING).
        sagaRecoveryService.recoverOnce();

        // KLJUC (I2): recovery NIJE dodao phantom akcije. seller == original, ukupne akcije ocuvane.
        assertThat(sellerShares(listing))
                .as("N1 FIX: f4SellerApplied deli sudbinu sa dekrementom → recovery C4 ne pravi phantom")
                .isEqualTo(sellerBefore);
        assertThat(sellerShares(listing) + buyerShares(listing))
                .as("ukupne akcije ocuvane (I2) — nema phantom-a")
                .isEqualTo(totalSharesBefore);

        // I1: F3 je premestio novac out-of-process; recovery C3 ga vraca → masa ocuvana.
        assertThat(fake.totalMoney())
                .as("I1: recovery C3 vraca F3 novac → masa ocuvana")
                .isEqualByComparingTo(moneyBefore);

        // saga u terminalu COMPENSATED, contract ACTIVE.
        SagaLog saga = sagaLogRepository.findByContractId(contract.getId()).get(0);
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(contractRepository.findById(contract.getId()).orElseThrow().getStatus())
                .isEqualTo(OtcContractStatus.ACTIVE);
    }

    // ──────────────────────────────── N2 ────────────────────────────────────

    /**
     * <b>N2 (CRITICAL) — lazni COMPLETED → re-exercise na real-crash posle F5.</b>
     *
     * <p>Live {@code exercise()} prodje F1-F4, F5 postavi contract EXERCISED (LOKALNI JPA),
     * pa pukne raw {@link Error} POSLE F5 efekta (pre outer commit-a) → outer tx ROLLBACK
     * (contract ostaje ACTIVE, F4 akcije vracene, ali F3 novac je out-of-process premesten).
     *
     * <p><b>Bug (pre fix-a):</b> F5 "ok" write-ahead zapis bi preziveo → recovery
     * {@code appliedSteps.containsAll(ALL_STEPS)} → COMPLETED dok je contract jos ACTIVE →
     * korisnik moze ponovo exercise → drugi F3 PONOVO naplati (dvostruka naplata).
     *
     * <p><b>Fix:</b> COMPLETED grana RE-VERIFIKUJE {@code contract.status==EXERCISED} u bazi;
     * F4/F5 "ok" su sad outer-tx (nestanu na rollback-u). recovery → NE COMPLETED (gura ka
     * COMPENSATED), pa contract ostaje ACTIVE i moze se legitimno ponovo exercise-ovati
     * tek posle ciste kompenzacije — bez duple naplate u recovery-ju.
     */
    @Test
    @DisplayName("N2: real-crash posle F5 (contract jos ACTIVE) → recovery NE sme proglasiti COMPLETED")
    void n2_realCrashAfterF5_recoveryDoesNotFalselyComplete() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing, BUYER_ACCOUNT_ID, "RES-N2");
        savedSellerPortfolio(listing, 20, 10);
        fake.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE, COST);
        fake.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);
        fake.seedReservation("RES-N2", BUYER_ACCOUNT_ID, COST);

        BigDecimal moneyBefore = fake.totalMoney();

        // REAL-CRASH: raw Error posle F5 efekta → outer tx rollback (contract ostaje ACTIVE).
        RealCrashFaultConfig.armed = RealCrashFaultConfig.Point.AFTER_F5;
        assertThatThrownBy(() -> orchestrator.exercise(contract.getId(), BUYER_ACCOUNT_ID))
                .isInstanceOf(Error.class);
        RealCrashFaultConfig.armed = RealCrashFaultConfig.Point.NONE;

        // Outer tx rollback: contract NIJE EXERCISED (status flip ponisten).
        assertThat(contractRepository.findById(contract.getId()).orElseThrow().getStatus())
                .as("outer tx rollback je ponistio F5 status flip → contract ostaje ACTIVE")
                .isEqualTo(OtcContractStatus.ACTIVE);

        sagaRecoveryService.recoverOnce();

        SagaLog saga = sagaLogRepository.findByContractId(contract.getId()).get(0);
        // KLJUC (N2): NE COMPLETED dok je contract ACTIVE — inace re-exercise/dupla naplata.
        assertThat(saga.getStatus())
                .as("N2 FIX: log all-applied ali contract ACTIVE → NE lazni COMPLETED")
                .isNotEqualTo(SagaStatus.COMPLETED);
        // Terminalno COMPENSATED (sigurna grana), contract i dalje ACTIVE.
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(contractRepository.findById(contract.getId()).orElseThrow().getStatus())
                .isEqualTo(OtcContractStatus.ACTIVE);
        // I1: recovery vratio F3 novac → masa ocuvana (nema duple naplate).
        assertThat(fake.totalMoney())
                .as("I1: recovery C3 vraca F3 novac → masa ocuvana, bez duple naplate")
                .isEqualByComparingTo(moneyBefore);
    }

    // ──────────────────────────────── N3 ────────────────────────────────────

    /**
     * <b>N3 — C3 refund na TACAN kupcev racun (multi-account).</b>
     *
     * <p>Real-crash u reserve-at-exercise putu: F1 rezervise sa NON-default kupcevog racuna
     * ({@code BUYER_ACCOUNT_ID_ALT}, prosledjen kao buyerAccountId), pa F3 commit-uje, pa pad.
     * {@code contract.buyerReservedAccountId} se postavlja tek u OUTER tx-u → real-crash ga
     * izgubi (ostaje null). Bez N3 fix-a, recovery bi C3 refundirao na DEFAULT racun
     * ({@code BUYER_ACCOUNT_ID}). Sa fix-om, {@code saga.buyerAccountId} (write-ahead u F1)
     * usmerava C3 na TACAN (ALT) racun.
     *
     * <p>Seed direktno: SagaLog [F1 ok, F2 ok] + f3CommitDone=true, f3CreditDone=false (kupac
     * debitovan, prodavac ne), {@code buyerAccountId=ALT}, contract.buyerReservedAccountId=null
     * (divergencija — racun je prosledjen kao {@code buyerAccountId} param pri exercise-u, a ne
     * sacuvan na ugovoru; legacy ugovor). {@code buyerReservedAmount=COST} je accept-time persistovan
     * (prezivi). recoverOnce() → C3 commit-only refund COST na ALT racun, ne default.
     */
    @Test
    @DisplayName("N3: recovery C3 refund na TACAN (F1) kupcev racun, ne default (multi-account)")
    void n3_recoveryC3RefundsCorrectBuyerAccount_notDefault() {
        Listing listing = savedStockListing();
        // contract.buyerReservedAccountId = null (racun NIJE na ugovoru — divergencija);
        // ali buyerReservedAmount=COST je accept-time persistovan (savedReservedContract postavlja COST).
        OtcContract contract = savedReservedContract(listing, null, "RES-N3");
        savedSellerPortfolio(listing, 20, 0);

        // ALT racun = NON-default (default je BUYER_ACCOUNT_ID iz mapPreferredAccount).
        fake.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE);      // default — NE sme biti diran
        // ALT: kupac je vec debitovan COST u F3 commit-u (commit zatvorio accept-time hold).
        fake.seedAccount(BUYER_ACCOUNT_ID_ALT, CCY, START_BALANCE.subtract(COST));  // ALT — kupcu se ovde vraca
        fake.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);
        // Accept-time rezervacija na ALT racunu, vec COMMITTED u F3 (C3 commit-only vraca direktnim credit-om).
        fake.seedReservation("RES-N3", BUYER_ACCOUNT_ID_ALT, COST);

        BigDecimal defaultBefore = fake.balanceOf(BUYER_ACCOUNT_ID);
        BigDecimal altBefore = fake.balanceOf(BUYER_ACCOUNT_ID_ALT);

        SagaLog saga = new SagaLog();
        saga.setSagaId("saga-n3");
        saga.setContractId(contract.getId());
        saga.setStatus(SagaStatus.COMPENSATING);
        saga.setCurrentStep(3);
        saga.setBankaCoreReservationId("RES-N3");
        saga.setBuyerAccountId(BUYER_ACCOUNT_ID_ALT);               // N3: efektivni F1 racun (write-ahead)
        saga.setBuyerReservationCreatedHere(false);                 // accept-time hold (C1 ne oslobadja)
        saga.setSellerSharesReservedHere(false);
        saga.setSellerSharesReservedAmount(0);
        saga.setF3CommitDone(true);                                 // kupac debitovan
        saga.setF3CreditDone(false);                               // prodavac NIJE kreditiran
        saga.append(SagaLogEntry.ok(1, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.ok(2, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.err(3, SagaStepKind.FORWARD, "crash"));
        sagaLogRepository.saveAndFlush(saga);

        sagaRecoveryService.recoverOnce();

        SagaLog recovered = sagaLogRepository.findBySagaId("saga-n3").orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // KLJUC (N3): C3 commit-only refund vratio COST na ALT racun, default NETAKNUT.
        assertThat(fake.balanceOf(BUYER_ACCOUNT_ID_ALT))
                .as("N3 FIX: C3 refundira F1 (ALT) racun")
                .isEqualByComparingTo(altBefore.add(COST));
        assertThat(fake.balanceOf(BUYER_ACCOUNT_ID))
                .as("default kupcev racun NETAKNUT (nije F1 racun)")
                .isEqualByComparingTo(defaultBefore);
    }

    // ──────────────────────────────── N4 ────────────────────────────────────

    /**
     * <b>N4a — credit JESTE izvrsen → recovery pun reverzni transfer (I1).</b>
     *
     * <p>Real-crash izmedju {@code creditFunds} (prodavac VEC kreditiran — out-of-process,
     * durable, idempotency kljuc KONZUMIRAN) i {@code persist(f3CreditDone)}. Seed: f3CommitDone=true,
     * f3CreditIntent=true, f3CreditDone=false; svet "posle credit-a" — kupac debitovan COST, prodavac
     * kreditiran COST, F3-credit kljuc oznacen consumed.
     *
     * <p><b>Bug (pre fix-a):</b> recovery vidi commit=true/credit=false → C3 commit-only refund
     * SAMO kupcu, a prodavceva isplata OSTAJE → masa novca naraste za COST (I1 prekrsen, novac stvoren).
     *
     * <p><b>Fix:</b> recovery AUTORITATIVNO pita banka-core (consumed=true) → pun reverzni transfer
     * (debit prodavca + credit kupca) → masa ocuvana (I1).
     */
    @Test
    @DisplayName("N4a: recovery, credit IZVRSEN (kljuc consumed) → pun reverzni transfer, I1 ocuvan")
    void n4_recoveryCreditIntentWindow_fullReverseTransfer_conservesMoney() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing, BUYER_ACCOUNT_ID, "RES-N4");
        savedSellerPortfolio(listing, 20, 10);

        // Svet "posle creditFunds": kupac vec debitovan COST (commit), prodavac vec kreditiran COST.
        fake.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE.subtract(COST));  // commit debitovao kupca
        fake.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE.add(COST));       // credit kreditirao prodavca
        // N4: credit JESTE izvrsen → F3-credit idempotency kljuc je KONZUMIRAN u banka-core dedup store-u.
        // Recovery C3 ce autoritativno pitati banka-core i dobiti consumed=true → pun reverzni transfer.
        fake.markIdempotencyConsumed(
                rs.raf.trading.otc.saga.service.OtcExerciseSagaOrchestrator.f3CreditIdempotencyKey("saga-n4"));
        BigDecimal moneyBefore = fake.totalMoney();   // = 2*START (COST kruzni, masa ista kao pocetna)

        SagaLog saga = new SagaLog();
        saga.setSagaId("saga-n4");
        saga.setContractId(contract.getId());
        saga.setStatus(SagaStatus.COMPENSATING);
        saga.setCurrentStep(3);
        saga.setBankaCoreReservationId("RES-N4");
        saga.setBuyerAccountId(BUYER_ACCOUNT_ID);
        saga.setBuyerReservationCreatedHere(false);   // accept-time
        saga.setSellerSharesReservedHere(false);
        saga.setSellerSharesReservedAmount(0);
        saga.setF3CommitDone(true);
        saga.setF3CreditIntent(true);                 // N4: intent zabelezen PRE creditFunds
        saga.setF3CreditDone(false);                  // crash pre persist(done) — ali credit JESTE izvrsen
        saga.append(SagaLogEntry.ok(1, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.ok(2, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.err(3, SagaStepKind.FORWARD, "crash posle credit-a, pre persist(done)"));
        sagaLogRepository.saveAndFlush(saga);

        sagaRecoveryService.recoverOnce();

        SagaLog recovered = sagaLogRepository.findBySagaId("saga-n4").orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // KLJUC (I1): pun reverzni transfer ponistio i credit i commit → masa ocuvana (NE naraste za COST).
        assertThat(fake.totalMoney())
                .as("N4 FIX: credit-intent → pun reverzni transfer → masa novca ocuvana (nema stvaranja)")
                .isEqualByComparingTo(moneyBefore);
        // Kupac vracen na pocetni, prodavac vracen na pocetni (COST reverzno premesten).
        assertThat(fake.balanceOf(BUYER_ACCOUNT_ID))
                .as("kupcu vracen COST (reverzni transfer)")
                .isEqualByComparingTo(START_BALANCE);
        assertThat(fake.balanceOf(SELLER_ACCOUNT_ID))
                .as("prodavcu oduzet COST (reverzni transfer) — isplata ponistena")
                .isEqualByComparingTo(START_BALANCE);
    }

    /**
     * <b>N4b (BLOKIRAJUCE — reviewer N4) — credit NIKAD nije izvrsen → recovery SAMO refundira kupca.</b>
     *
     * <p>Real-crash izmedju {@code persist(f3CreditIntent=true)} i samog {@code creditFunds} poziva
     * (SIGKILL u prozoru pre poziva). Svet "PRE credit-a": kupac debitovan COST (F3 commit), prodavac
     * NETAKNUT (credit nikad pozvan → F3-credit idempotency kljuc NIJE konzumiran u banka-core).
     * Seed: f3CommitDone=true, f3CreditIntent=true, f3CreditDone=false.
     *
     * <p><b>Bug (pre N4 fix-a):</b> {@code f3CreditMaybeApplied = intent && !done} pogadja "credit MOZDA
     * izvrsen" i radi PUN reverzni transfer → debituje prodavca za COST koji NIKAD nije primio:
     * (a) prodavcev saldo padne ispod pocetnog (per-account I1 puca / stvaranje novca kod kupca), ILI
     * (b) prodavac bez salda → transferFunds baca → saga STUCK COMPENSATING zauvek + kupcev novac trapped.
     *
     * <p><b>Fix:</b> recovery AUTORITATIVNO pita banka-core ({@code consumed=false}) → C3 commit-only
     * refund SAMO kupcu (COST), prodavac NETAKNUT; saga zavrsava COMPENSATED (ne stuck); masa ocuvana (I1).
     */
    @Test
    @DisplayName("N4b: recovery, credit NIKAD izvrsen (kljuc ne-consumed) → SAMO refund kupcu, prodavac NETAKNUT, COMPENSATED")
    void n4_recoveryCreditIntentWindow_creditNeverExecuted_refundsBuyerOnly_sellerUntouched() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing, BUYER_ACCOUNT_ID, "RES-N4B");
        savedSellerPortfolio(listing, 20, 10);

        // Svet "PRE creditFunds": kupac vec debitovan COST (F3 commit), prodavac NETAKNUT (credit nikad pozvan).
        // Konzervacioni baseline je ORIGINALNO (pre-saga) stanje: oba racuna na START (commit-ov debit kupca
        // se commit-only refund-om vraca, OTC se ponistava u celosti). Σ original = 2*START = 200000.
        BigDecimal originalTotal = START_BALANCE.add(START_BALANCE);            // pre-saga masa (OTC nikad)
        fake.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE.subtract(COST));  // commit debitovao kupca
        fake.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);                 // prodavac NETAKNUT
        // KLJUC N4b: F3-credit idempotency kljuc NIJE konzumiran (creditFunds nikad pozvan) →
        // recovery C3 query banka-core -> consumed=false -> commit-only refund (NE pun reverzni transfer).
        // (NE pozivamo markIdempotencyConsumed — fake po defaultu vraca false.)
        BigDecimal sellerBefore = fake.balanceOf(SELLER_ACCOUNT_ID);

        SagaLog saga = new SagaLog();
        saga.setSagaId("saga-n4b");
        saga.setContractId(contract.getId());
        saga.setStatus(SagaStatus.COMPENSATING);
        saga.setCurrentStep(3);
        saga.setBankaCoreReservationId("RES-N4B");
        saga.setBuyerAccountId(BUYER_ACCOUNT_ID);
        saga.setBuyerReservationCreatedHere(false);   // accept-time
        saga.setSellerSharesReservedHere(false);
        saga.setSellerSharesReservedAmount(0);
        saga.setF3CommitDone(true);
        saga.setF3CreditIntent(true);                 // intent zabelezen PRE creditFunds...
        saga.setF3CreditDone(false);                  // ...ali credit NIKAD nije izvrsen (crash pre poziva)
        saga.append(SagaLogEntry.ok(1, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.ok(2, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.err(3, SagaStepKind.FORWARD, "crash izmedju persist(intent) i creditFunds"));
        sagaLogRepository.saveAndFlush(saga);

        sagaRecoveryService.recoverOnce();

        SagaLog recovered = sagaLogRepository.findBySagaId("saga-n4b").orElseThrow();
        // KLJUC: saga NIJE stuck — dosegla terminalni COMPENSATED (pre fix-a: stuck ili I1 puca).
        assertThat(recovered.getStatus())
                .as("N4b FIX: consumed=false → commit-only refund → saga dosegla COMPENSATED (ne stuck)")
                .isEqualTo(SagaStatus.COMPENSATED);

        // KLJUC (I1): OTC ponisten u celosti — kupcu vracen COST (commit-only refund), prodavac NETAKNUT,
        // masa vracena na ORIGINALNU (pre-saga) vrednost (NE naraste — sto bi se desilo da je C3 debitovao
        // prodavca punim reverznim transferom za novac koji nikad nije primio).
        assertThat(fake.totalMoney())
                .as("N4b: commit-only refund → masa vracena na original (nema debitovanja prodavca koji nije primio)")
                .isEqualByComparingTo(originalTotal);
        assertThat(fake.balanceOf(BUYER_ACCOUNT_ID))
                .as("kupcu vracen COST (commit-only refund)")
                .isEqualByComparingTo(START_BALANCE);
        assertThat(fake.balanceOf(SELLER_ACCOUNT_ID))
                .as("KLJUC N4b: prodavac NETAKNUT — credit nikad izvrsen, ne sme se debitovati")
                .isEqualByComparingTo(sellerBefore);
    }
}
