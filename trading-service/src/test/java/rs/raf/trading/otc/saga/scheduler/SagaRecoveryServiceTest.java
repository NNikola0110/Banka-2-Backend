package rs.raf.trading.otc.saga.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsResponse;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.otc.saga.fault.NoOpSagaFaultInjector;
import rs.raf.trading.otc.saga.model.SagaLog;
import rs.raf.trading.otc.saga.model.SagaLogEntry;
import rs.raf.trading.otc.saga.model.SagaStatus;
import rs.raf.trading.otc.saga.model.SagaStepKind;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;
import rs.raf.trading.otc.saga.service.OtcExerciseSagaOrchestrator;
import rs.raf.trading.otc.saga.service.SagaLogWriter;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1.9 — crash-recovery + scheduler-driven retry SAGA put.
 *
 * <p>Jedinicni testovi (Mockito, bez Springa): gradi REALAN {@link
 * OtcExerciseSagaOrchestrator} sa {@code @Mock} kolaboratorima + {@link
 * NoOpSagaFaultInjector}, pa {@link SagaRecoveryService} koji ga obavija.
 * {@code recoverOnce()} se poziva eksplicitno (scheduler je gejtovan
 * {@code SchedulingConfig}-om koji je OFF u test profilu).
 *
 * <ul>
 *   <li><b>Recover COMPENSATING -> COMPENSATED:</b> log [F1 ok, F2 ok, F3 err]
 *       status COMPENSATING -> kompenzuje C2 pa C1 -> COMPENSATED.</li>
 *   <li><b>Primer 9 / SG-16 (scheduler retry):</b> C2 padne na PRVOM prolazu
 *       (status ostaje COMPENSATING, C2 err), na DRUGOM prolazu uspe -> COMPENSATED.
 *       Nema parcijalne kompenzacije (I8).</li>
 *   <li><b>Recover fully-applied RUNNING -> COMPLETED:</b> log [F1..F5 ok]
 *       status RUNNING -> COMPLETED, bez kompenzacije.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SagaRecoveryServiceTest {

    @Mock private OtcContractRepository contractRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private SagaLogRepository sagaLogRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private TradingUserResolver userResolver;

    private OtcExerciseSagaOrchestrator orchestrator;
    private SagaRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        orchestrator = new OtcExerciseSagaOrchestrator(contractRepository, portfolioRepository,
                sagaLogRepository, new SagaLogWriter(sagaLogRepository),
                bankaCoreClient, currencyConversionService, userResolver,
                new NoOpSagaFaultInjector());
        recoveryService = new SagaRecoveryService(sagaLogRepository, orchestrator);
        when(sagaLogRepository.saveAndFlush(any(SagaLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private Listing stockListing(long id, String ticker, String ccy) {
        Listing l = new Listing();
        l.setId(id);
        l.setTicker(ticker);
        l.setName(ticker + " Inc.");
        l.setExchangeAcronym("NYSE");
        l.setListingType(ListingType.STOCK);
        l.setPrice(new BigDecimal("150.00"));
        l.setQuoteCurrency(ccy);
        return l;
    }

    private InternalAccountDto account(long id, String number, String owner, String ccy,
                                       Long ownerClientId) {
        return new InternalAccountDto(id, number, owner,
                new BigDecimal("100000.00"), new BigDecimal("100000.00"), BigDecimal.ZERO,
                ccy, "ACTIVE", ownerClientId, null, "CHECKING");
    }

    private Portfolio sellerPortfolio(long id, long userId, String userRole, long listingId,
                                      int quantity, int publicQty, int reservedQty) {
        Portfolio p = new Portfolio();
        p.setId(id);
        p.setUserId(userId);
        p.setUserRole(userRole);
        p.setListingId(listingId);
        p.setListingTicker("AAPL");
        p.setListingName("AAPL Inc.");
        p.setListingType("STOCK");
        p.setQuantity(quantity);
        p.setAverageBuyPrice(new BigDecimal("100.00"));
        p.setPublicQuantity(publicQty);
        p.setReservedQuantity(reservedQty);
        return p;
    }

    private OtcContract reservedActiveContract(long id, long buyerId, long sellerId,
                                               Listing listing, int qty, String strike,
                                               String reservationId) {
        OtcContract c = new OtcContract();
        c.setId(id);
        c.setSourceOfferId(1L);
        c.setBuyerId(buyerId);
        c.setBuyerRole(UserRole.CLIENT);
        c.setSellerId(sellerId);
        c.setSellerRole(UserRole.CLIENT);
        c.setListing(listing);
        c.setQuantity(qty);
        c.setStrikePrice(new BigDecimal(strike));
        c.setPremium(new BigDecimal("50.00"));
        c.setSettlementDate(LocalDate.now().plusDays(30));
        c.setStatus(OtcContractStatus.ACTIVE);
        c.setBuyerReservedAccountId(10L);
        c.setBuyerReservedAmount(new BigDecimal(strike).multiply(BigDecimal.valueOf(qty)));
        c.setBankaCoreReservationId(reservationId);
        return c;
    }

    /**
     * SagaLog u COMPENSATING stanju: [F1 fwd ok, F2 fwd ok, F3 fwd err], bez compensate zapisa.
     *
     * <p><b>BUG-W2-01:</b> {@code createdHere} flag-ovi perzistovani u SagaLog-u
     * govore recovery kompenzatorima da li su F1/F2 OVE saga-e kreirali rezervaciju
     * (reserve-at-exercise → oslobodi) ili su REUSE-ovali accept-time hold (cuva se).
     */
    private SagaLog compensatingSaga(String sagaId, long contractId, boolean createdHere) {
        SagaLog saga = new SagaLog();
        saga.setSagaId(sagaId);
        saga.setContractId(contractId);
        saga.setStatus(SagaStatus.COMPENSATING);
        saga.setCurrentStep(3);
        saga.setBankaCoreReservationId("RES-77");
        saga.setBuyerReservationCreatedHere(createdHere);
        saga.setSellerSharesReservedHere(createdHere);
        saga.setSellerSharesReservedAmount(createdHere ? 10 : 0);
        saga.append(SagaLogEntry.ok(1, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.ok(2, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.err(3, SagaStepKind.FORWARD, "forward failed (crash)"));
        return saga;
    }

    @Test
    @DisplayName("Recover COMPENSATING accept-time ugovor -> COMPENSATED; accept-time rezervacije "
            + "OCUVANE (C1 release NIJE pozvan, seller reservedQuantity OSTAJE 10 — BUG-W2-01)")
    void recoverCompensating_acceptTime_compensatesC2C1_reservationsPreserved() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");
        // createdHere=false: accept-time put — F1/F2 su REUSE-ovali, C1/C2 ne oslobadjaju.
        SagaLog saga = compensatingSaga("saga-comp-1", 7L, false);

        when(sagaLogRepository.findByStatusIn(
                List.of(SagaStatus.RUNNING, SagaStatus.COMPENSATING))).thenReturn(List.of(saga));
        when(sagaLogRepository.findBySagaIdForUpdate("saga-comp-1")).thenReturn(Optional.of(saga));
        when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));

        recoveryService.recoverOnce();

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // log = [F1 fwd ok, F2 fwd ok, F3 fwd err, C2 ok, C1 ok]
        List<SagaLogEntry> entries = saga.getEntries();
        assertThat(entries).hasSize(5);
        assertThat(entries.get(3).phase()).isEqualTo(2);
        assertThat(entries.get(3).kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(entries.get(3).outcome()).isEqualTo("ok");
        assertThat(entries.get(4).phase()).isEqualTo(1);
        assertThat(entries.get(4).kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(entries.get(4).outcome()).isEqualTo("ok");

        // BUG-W2-01 FIX: accept-time hold → C2 no-op (seller reservedQuantity OSTAJE 10);
        // C1 NE poziva releaseFunds (rezervacija ostaje na ACTIVE ugovoru).
        assertThat(sellerPf.getReservedQuantity()).isEqualTo(10);
        verify(bankaCoreClient, never())
                .releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class));
    }

    @Test
    @DisplayName("Recover COMPENSATING reserve-at-exercise ugovor -> COMPENSATED; rezervacije "
            + "OSLOBODJENE (C1 release pozvan, seller reservedQuantity 10 -> 0)")
    void recoverCompensating_reserveAtExercise_compensatesC2C1_reservationsReleased() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");
        // createdHere=true: F1/F2 su OVE saga-e kreirali rezervaciju → C1/C2 ih oslobadjaju.
        SagaLog saga = compensatingSaga("saga-comp-rae", 7L, true);

        when(sagaLogRepository.findByStatusIn(
                List.of(SagaStatus.RUNNING, SagaStatus.COMPENSATING))).thenReturn(List.of(saga));
        when(sagaLogRepository.findBySagaIdForUpdate("saga-comp-rae")).thenReturn(Optional.of(saga));
        when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        when(bankaCoreClient.releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class)))
                .thenReturn(new ReleaseFundsResponse("RES-77", new BigDecimal("1600.00"), BigDecimal.ZERO));

        recoveryService.recoverOnce();

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // BUG-W2-01: reserve-at-exercise → C2 oslobadja 10 (10 -> 0), C1 poziva releaseFunds.
        assertThat(sellerPf.getReservedQuantity()).isEqualTo(0);
        verify(bankaCoreClient)
                .releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class));
    }

    /**
     * <b>P0-1 (Bug A, recovery partial-safety)</b> — crash POSLE F3 commit-a, PRE credit-a.
     *
     * <p>Zaglavljen COMPENSATING SagaLog: [F1 ok, F2 ok, F3 NEMA forward ok] + perzistovan
     * {@code f3CommitDone=true, f3CreditDone=false} (kupac debitovan, prodavac nikad kreditiran).
     * Pre fix-a recovery bi preskocio C3 (F3 nema forward "ok") pa bi novac nestao. Posle fix-a
     * {@code partiallyAppliedFailedStep} ukljucuje F3 u remaining; {@code rebuildContext} cita
     * granularne flag-ove pa C3 ide u commit-only granu → {@code creditFunds(KUPAC, 1600)}
     * (NE reverzni transfer — prodavac nema priliv). Dokazuje da je recovery partial-aware kao live.
     */
    @Test
    @DisplayName("Recovery F3 commit-only crash -> C3 kreditira KUPCA (partial-aware recovery, Bug A)")
    void recoverF3CommitOnlyCrash_c3CreditsBuyer_partialSafe() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");

        SagaLog saga = new SagaLog();
        saga.setSagaId("saga-f3-commit-crash");
        saga.setContractId(7L);
        saga.setStatus(SagaStatus.COMPENSATING);
        saga.setCurrentStep(3);
        saga.setBankaCoreReservationId("RES-77");
        saga.setBuyerReservationCreatedHere(false);   // accept-time
        saga.setSellerSharesReservedHere(false);
        saga.setSellerSharesReservedAmount(0);
        saga.setF3CommitDone(true);                    // P0-1: commit obavljen, credit NIJE
        saga.setF3CreditDone(false);
        saga.append(SagaLogEntry.ok(1, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.ok(2, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.err(3, SagaStepKind.FORWARD, "crash posle commit-a, pre credit-a"));

        when(sagaLogRepository.findByStatusIn(
                List.of(SagaStatus.RUNNING, SagaStatus.COMPENSATING))).thenReturn(List.of(saga));
        when(sagaLogRepository.findBySagaIdForUpdate("saga-f3-commit-crash"))
                .thenReturn(Optional.of(saga));
        when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                .thenReturn(new CreditFundsResponse(10L, new BigDecimal("1600.00"),
                        new BigDecimal("100000.00")));

        recoveryService.recoverOnce();

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // C3 commit-only grana: kreditira KUPCA (10) commit-ovani iznos, NE radi reverzni transfer.
        ArgumentCaptor<CreditFundsRequest> cap = ArgumentCaptor.forClass(CreditFundsRequest.class);
        verify(bankaCoreClient).creditFunds(anyString(), cap.capture());
        assertThat(cap.getValue().accountId()).isEqualTo(10L);
        assertThat(cap.getValue().amount()).isEqualByComparingTo(new BigDecimal("1600.00"));
        verify(bankaCoreClient, never())
                .transferFunds(anyString(), any(TransferFundsRequest.class));
        // accept-time: C1 ne oslobadja, C2 no-op → seller reservedQuantity ocuvan.
        verify(bankaCoreClient, never())
                .releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class));
        assertThat(sellerPf.getReservedQuantity()).isEqualTo(10);
    }

    /**
     * <b>P0-1 (Bug B, recovery partial-safety)</b> — crash POSLE F4 seller dekrementa, PRE buyer credit-a.
     *
     * <p>Zaglavljen COMPENSATING SagaLog: [F1 ok, F2 ok, F3 ok, F4 NEMA forward ok] + perzistovan
     * {@code f4SellerApplied=true} (prodavac umanjen) i {@code preF4BuyerExisted=null} (buyer-blok
     * nije ni izvrsen). Pre fix-a recovery bi preskocio C4 → akcije unistene. Posle fix-a F4 ulazi u
     * remaining, {@code rebuildContext} cita {@code f4SellerApplied} pa C4 vraca SAMO seller akcije
     * (buyer netaknut). F3 je ok → C3 reverzni transfer (f3CreditDone=true).
     *
     * <p><b>N1 (P0-T2):</b> recovery C4 sad restore-uje seller-leg SAMO ako je F4 OUTER-TX-COMMITTED
     * ({@code f4Committed=true}). Ovaj test modeluje svet gde je F4 seller dekrement ZAISTA durable u
     * bazi (seller=10) — sto pod all-or-nothing outer tx-om znaci da je marker {@code f4Committed=true};
     * tada C4 tacno vraca seller akcije (10→20). Bez markera (real-crash rollback) C4 je no-op
     * (vidi {@code OtcSagaRealCrashRecoveryIntegrationTest.n1_*} koji dokazuje phantom-safety).
     */
    @Test
    @DisplayName("Recovery F4 seller-only crash (f4Committed) -> C4 vraca SAMO seller akcije (partial-aware, Bug B)")
    void recoverF4SellerOnlyCrash_c4RestoresSellerOnly_partialSafe() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");

        SagaLog saga = new SagaLog();
        saga.setSagaId("saga-f4-seller-crash");
        saga.setContractId(7L);
        saga.setStatus(SagaStatus.COMPENSATING);
        saga.setCurrentStep(4);
        saga.setBankaCoreReservationId("RES-77");
        saga.setBuyerReservationCreatedHere(false);   // accept-time
        saga.setSellerSharesReservedHere(false);
        saga.setSellerSharesReservedAmount(0);
        saga.setF3CommitDone(true);
        saga.setF3CreditDone(true);                    // F3 pun → C3 reverzni transfer
        saga.setF4SellerApplied(true);                 // P0-1: seller umanjen, buyer-blok NIJE
        saga.setF4Committed(true);                     // N1: F4 lokalni efekti durable (seller=10 u bazi)
        // preF4BuyerExisted ostaje null → C4 ne dira buyer poziciju
        saga.append(SagaLogEntry.ok(1, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.ok(2, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.ok(3, SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.err(4, SagaStepKind.FORWARD, "crash posle seller dekrementa, pre buyer credit-a"));

        when(sagaLogRepository.findByStatusIn(
                List.of(SagaStatus.RUNNING, SagaStatus.COMPENSATING))).thenReturn(List.of(saga));
        when(sagaLogRepository.findBySagaIdForUpdate("saga-f4-seller-crash"))
                .thenReturn(Optional.of(saga));
        when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        // seller pozicija (vec dekrementovana u F4: 20 -> 10); C4 vraca 10 -> 20.
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 10, 0, 0);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                .thenReturn(new TransferFundsResponse(88L, 10L, new BigDecimal("1600.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));

        recoveryService.recoverOnce();

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // C4 partial-safe: vraca seller akcije (10 -> 20), buyer NIKAD diran (snapshot null → preskace).
        assertThat(sellerPf.getQuantity()).isEqualTo(20);
        verify(portfolioRepository, never())
                .findByUserIdAndUserRoleAndListingIdForUpdate(1L, UserRole.CLIENT, 100L);
        // F3 pun → C3 reverzni transfer (obe noge) vraca novac.
        verify(bankaCoreClient).transferFunds(anyString(), any(TransferFundsRequest.class));
    }

    @Test
    @DisplayName("Primer 9 / SG-16: C2 padne na 1. prolazu (ostaje COMPENSATING), uspe na 2. prolazu -> COMPENSATED")
    void schedulerDrivenRetry_c2FailsThenSucceeds_eventuallyCompensated() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");
        // createdHere=true: reserve-at-exercise put — C2 zaista dekrementuje (poziva save → fault inject).
        SagaLog saga = compensatingSaga("saga-retry-1", 7L, true);

        when(sagaLogRepository.findByStatusIn(
                List.of(SagaStatus.RUNNING, SagaStatus.COMPENSATING))).thenReturn(List.of(saga));
        when(sagaLogRepository.findBySagaIdForUpdate("saga-retry-1")).thenReturn(Optional.of(saga));
        when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        when(bankaCoreClient.releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class)))
                .thenReturn(new ReleaseFundsResponse("RES-77", new BigDecimal("1600.00"), BigDecimal.ZERO));
        // C2 efekat (portfolioRepository.save za seller) baca na PRVOM pozivu, uspeva na DRUGOM
        AtomicInteger c2Calls = new AtomicInteger(0);
        when(portfolioRepository.save(sellerPf)).thenAnswer(inv -> {
            if (c2Calls.getAndIncrement() == 0) {
                throw new RuntimeException("C2 banka-core privremeno nedostupan (crash)");
            }
            return inv.getArgument(0);
        });

        // 1. prolaz: C2 padne -> status ostaje COMPENSATING, C2 err zabelezen
        recoveryService.recoverOnce();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATING);
        List<SagaLogEntry> afterFirst = saga.getEntries();
        // [F1 ok, F2 ok, F3 err, C2 err]
        assertThat(afterFirst).hasSize(4);
        assertThat(afterFirst.get(3).phase()).isEqualTo(2);
        assertThat(afterFirst.get(3).kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(afterFirst.get(3).outcome()).isEqualTo("err");

        // 2. prolaz (scheduler ponovni sweep): C2 uspe -> C1 -> COMPENSATED
        recoveryService.recoverOnce();
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        List<SagaLogEntry> afterSecond = saga.getEntries();
        // [F1 ok, F2 ok, F3 err, C2 err, C2 ok, C1 ok]
        assertThat(afterSecond).hasSize(6);
        assertThat(afterSecond.get(4).phase()).isEqualTo(2);
        assertThat(afterSecond.get(4).kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(afterSecond.get(4).outcome()).isEqualTo("ok");
        assertThat(afterSecond.get(5).phase()).isEqualTo(1);
        assertThat(afterSecond.get(5).kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(afterSecond.get(5).outcome()).isEqualTo("ok");
        assertThat(sellerPf.getReservedQuantity()).isEqualTo(0);
    }

    /**
     * <b>N2 (P0-T2):</b> crash posle F5 efekta (contract STVARNO EXERCISED u bazi) ali pre status
     * flip-a → recovery RE-VERIFIKUJE {@code contract.status==EXERCISED} pa proglasi COMPLETED.
     * Kontrast je {@code recoverF5LogButContractActive_*} koji dokazuje da log "all-applied" sam
     * NIJE dovoljan kad je contract jos ACTIVE (lazni COMPLETED → re-exercise).
     */
    @Test
    @DisplayName("Recover fully-applied RUNNING + contract EXERCISED -> COMPLETED (N2 re-verifikacija)")
    void recoverFullyAppliedRunning_contractExercised_becomesCompleted_noCompensation() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");
        contract.setStatus(OtcContractStatus.EXERCISED);    // F5 efekat ZAISTA commit-ovan u bazi
        SagaLog saga = new SagaLog();
        saga.setSagaId("saga-done-1");
        saga.setContractId(7L);
        saga.setStatus(SagaStatus.RUNNING);
        saga.setCurrentStep(5);
        for (int i = 1; i <= 5; i++) {
            saga.append(SagaLogEntry.ok(i, SagaStepKind.FORWARD));
        }

        when(sagaLogRepository.findByStatusIn(
                List.of(SagaStatus.RUNNING, SagaStatus.COMPENSATING))).thenReturn(List.of(saga));
        when(sagaLogRepository.findBySagaIdForUpdate("saga-done-1")).thenReturn(Optional.of(saga));
        when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));

        recoveryService.recoverOnce();

        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPLETED);
        // bez kompenzacije: nikakvi banka-core/portfolio bocni efekti
        verify(bankaCoreClient, never())
                .releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class));
        verify(bankaCoreClient, never())
                .transferFunds(anyString(), any(TransferFundsRequest.class));
        // bez novih log zapisa (samo 5 forward ok)
        assertThat(saga.getEntries()).hasSize(5);
    }

    /**
     * <b>N2 (P0-T2) — lazni COMPLETED → re-exercise (real-crash posle F5 write-ahead "ok").</b>
     *
     * <p>Log ima svih 5 forward "ok" ALI je contract jos ACTIVE u bazi (F5 lokalni status-flip je
     * rollback-ovan real-crash-om, dok je zaostali write-ahead "ok" preziveo). Pre fix-a recovery
     * bi {@code appliedSteps.containsAll(ALL_STEPS)} → COMPLETED → korisnik moze re-exercise (drugi
     * F3 ponovo naplati). Posle fix-a COMPLETED grana zahteva {@code contract.status==EXERCISED};
     * posto je ACTIVE, recovery NE proglasi COMPLETED nego gura ka COMPENSATED (sigurna grana).
     */
    @Test
    @DisplayName("N2: log all-applied ali contract ACTIVE -> NE lazni COMPLETED (gura ka COMPENSATED)")
    void recoverF5LogButContractActive_doesNotFalselyComplete() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");
        contract.setStatus(OtcContractStatus.ACTIVE);       // F5 status-flip rollback-ovan (real-crash)
        SagaLog saga = new SagaLog();
        saga.setSagaId("saga-false-complete");
        saga.setContractId(7L);
        saga.setStatus(SagaStatus.RUNNING);
        saga.setCurrentStep(5);
        saga.setBuyerReservationCreatedHere(false);
        saga.setSellerSharesReservedHere(false);
        saga.setSellerSharesReservedAmount(0);
        // Posle real-crash-a F4 lokalni efekti su rollback-ovani → f4SellerApplied NIJE durable.
        for (int i = 1; i <= 5; i++) {
            saga.append(SagaLogEntry.ok(i, SagaStepKind.FORWARD));
        }

        when(sagaLogRepository.findByStatusIn(
                List.of(SagaStatus.RUNNING, SagaStatus.COMPENSATING))).thenReturn(List.of(saga));
        when(sagaLogRepository.findBySagaIdForUpdate("saga-false-complete")).thenReturn(Optional.of(saga));
        when(contractRepository.findById(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(any())).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 1L, "USD"))
                .thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));

        recoveryService.recoverOnce();

        // KLJUC (N2): NE COMPLETED dok je contract ACTIVE — gura ka terminalnom COMPENSATED.
        assertThat(saga.getStatus())
                .as("N2 FIX: log all-applied ali contract ACTIVE → ne lazni COMPLETED")
                .isNotEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
    }
}
