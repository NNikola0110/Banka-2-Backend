package rs.raf.trading.otc.saga.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CommitFundsResponse;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsResponse;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.otc.saga.fault.NoOpSagaFaultInjector;
import rs.raf.trading.otc.saga.fault.SagaFaultException;
import rs.raf.trading.otc.saga.fault.SagaFaultInjector;
import rs.raf.trading.otc.saga.model.SagaLog;
import rs.raf.trading.otc.saga.model.SagaLogEntry;
import rs.raf.trading.otc.saga.model.SagaPhase;
import rs.raf.trading.otc.saga.model.SagaStatus;
import rs.raf.trading.otc.saga.model.SagaStepKind;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pokriva pre-saga validaciju orchestratora (W1.3) + happy-path / kompenzacioni
 * orkestracioni put kroz sve faze F1–F5 i kompenzatore C1–C5 (W1.4–W1.8).
 *
 * <p>Pre-saga validacija mora baciti 4xx-mapirane izuzetke BEZ kreiranja {@code
 * saga_logs} reda i BEZ bilo kakvih bocnih efekata:
 * <ul>
 *   <li><b>SG-02</b> iskoriscavanje od lica koje nije kupac -> 403 + nema loga</li>
 *   <li><b>SG-05</b> nepostojeci ugovor -> 404</li>
 *   <li>status != ACTIVE -> 409</li>
 *   <li>settlement u proslosti -> 409</li>
 * </ul>
 *
 * <p>Orkestracioni testovi (jedinicni, Mockito):
 * <ul>
 *   <li><b>SGT-01</b> happy path: svih 5 forward faza prolazi -> COMPLETED,
 *       currentStep=5, 5 FORWARD "ok" zapisa, ugovor EXERCISED</li>
 *   <li><b>SGT-05</b> forsiran fail u F3 (kind="before") -> kompenzuje C2,C1 ->
 *       COMPENSATED, currentStep=3, log [F1 fwd, F2 fwd, F3 fwd err, C2, C1],
 *       state vracen (commit/credit nikad pozvani, ugovor NIJE EXERCISED,
 *       C1 release pozvan, C2 dekrementuje seller reservedQuantity)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OtcExerciseSagaOrchestratorTest {

    @Mock private OtcContractRepository contractRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private SagaLogRepository sagaLogRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private TradingUserResolver userResolver;

    private OtcExerciseSagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new OtcExerciseSagaOrchestrator(contractRepository, portfolioRepository,
                sagaLogRepository, new SagaLogWriter(sagaLogRepository), bankaCoreClient,
                currencyConversionService, userResolver, new NoOpSagaFaultInjector());
        // P2-7: exercise() sad forsira OTC access gate (ensureOtcAccess) — klijent mora
        // imati TRADE_STOCKS authority u SecurityContext-u. Default identitet u testovima je
        // CLIENT koji SME da trguje; testovi za 403 access gate eksplicitno menjaju kontekst.
        authClientWithTradeStocks();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Klijent sa TRADE_STOCKS — sme OTC exercise (mirror OtcServiceSagaTest.authClient). */
    private void authClientWithTradeStocks() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@x.rs", "n",
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"),
                                new SimpleGrantedAuthority("TRADE_STOCKS"))));
    }

    private OtcContract activeContract(Long buyerId, String buyerRole) {
        OtcContract k = new OtcContract();
        k.setId(1L);
        k.setBuyerId(buyerId);
        k.setBuyerRole(buyerRole);
        k.setSellerId(99L);
        k.setSellerRole(UserRole.CLIENT);
        k.setQuantity(10);
        k.setStatus(OtcContractStatus.ACTIVE);
        k.setSettlementDate(LocalDate.now().plusDays(7));
        return k;
    }

    @Test
    @DisplayName("Iskoriscavanje od lica koje nije kupac -> AccessDeniedException + nema saga loga (SG-02)")
    void nonBuyer_throwsAccessDenied_andCreatesNoLog() {
        when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, UserRole.CLIENT));
        when(contractRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(activeContract(42L, UserRole.CLIENT))); // kupac je 42, ne 7

        assertThatThrownBy(() -> orchestrator.exercise(1L, 100L))
                .isInstanceOf(AccessDeniedException.class);

        verify(sagaLogRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Iskoriscavanje nepostojeceg ugovora -> EntityNotFoundException (SG-05)")
    void missingContract_throwsEntityNotFound() {
        when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, UserRole.CLIENT));
        when(contractRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.exercise(404L, 100L))
                .isInstanceOf(EntityNotFoundException.class);

        verify(sagaLogRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Iskoriscavanje vec iskoriscenog ugovora (status != ACTIVE) -> IllegalStateException (409)")
    void exercisedContract_throwsIllegalState() {
        when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, UserRole.CLIENT));
        OtcContract k = activeContract(7L, UserRole.CLIENT);
        k.setStatus(OtcContractStatus.EXERCISED);
        when(contractRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(k));

        assertThatThrownBy(() -> orchestrator.exercise(1L, 100L))
                .isInstanceOf(IllegalStateException.class);

        verify(sagaLogRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Settlement datum u proslosti -> IllegalStateException (409)")
    void pastSettlement_throwsIllegalState() {
        when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, UserRole.CLIENT));
        OtcContract k = activeContract(7L, UserRole.CLIENT);
        k.setSettlementDate(LocalDate.now().minusDays(1));
        when(contractRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(k));

        assertThatThrownBy(() -> orchestrator.exercise(1L, 100L))
                .isInstanceOf(IllegalStateException.class);

        verify(sagaLogRepository, never()).saveAndFlush(any());
    }

    // ─────────────── P2-7: OTC access gate (mora pre saga loga / citanja) ──────────────

    @Test
    @DisplayName("P2-7: CLIENT kupac BEZ TRADE_STOCKS -> AccessDeniedException PRE saga loga; "
            + "ugovor se ne cita (gate je pre findByIdForUpdate)")
    void clientWithoutTradeStocks_accessDenied_noLog_noContractRead() {
        // SecurityContext bez TRADE_STOCKS (samo ROLE_CLIENT) — gate mora odbiti.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@x.rs", "n",
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))));
        when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, UserRole.CLIENT));

        assertThatThrownBy(() -> orchestrator.exercise(1L, 100L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("TRADE_STOCKS");

        // Gate je PRE citanja ugovora i PRE loga: ni jedno ni drugo se ne sme desiti.
        verify(contractRepository, never()).findByIdForUpdate(any());
        verify(sagaLogRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("P2-7: CLIENT kupac SA TRADE_STOCKS -> prolazi gate, nastavlja na pre-saga "
            + "validaciju (cita ugovor preko findByIdForUpdate)")
    void clientWithTradeStocks_passesGate_readsContractForUpdate() {
        // default @BeforeEach auth ima TRADE_STOCKS. Ugovor ne postoji -> 404 (dokaz da je gate
        // prosao i da je dosao do pre-saga citanja).
        when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, UserRole.CLIENT));
        when(contractRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.exercise(1L, 100L))
                .isInstanceOf(EntityNotFoundException.class);

        // Dokaz FIX 1: pre-saga citanje ide kroz pesimisticki lock query.
        verify(contractRepository).findByIdForUpdate(1L);
        verify(sagaLogRepository, never()).saveAndFlush(any());
    }

    // ─────────────────── Orkestracioni testovi (W1.4–W1.8) ───────────────────

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

    /**
     * Ugovor sa vec postavljenom rezervacijom (accept-time) tako da F1 reuse-uje
     * postojeci {@code bankaCoreReservationId} umesto da poziva {@code reserveFunds}.
     */
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
     * Ugovor BEZ accept-time rezervacije (reserve-at-exercise put): F1 mora da
     * pozove {@code reserveFunds} i kreira novu rezervaciju, F2 da rezervise akcije.
     */
    private OtcContract unreservedActiveContract(long id, long buyerId, long sellerId,
                                                 Listing listing, int qty, String strike) {
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
        c.setBuyerReservedAccountId(null);
        c.setBuyerReservedAmount(BigDecimal.ZERO);
        c.setBankaCoreReservationId(null);
        return c;
    }

    /** Test fault injector koji forsira fail na zadatoj forward fazi/kind. */
    private static final class ForceForwardFailInjector implements SagaFaultInjector {
        private final SagaPhase failPhase;
        private final String failKind;

        ForceForwardFailInjector(SagaPhase failPhase, String failKind) {
            this.failPhase = failPhase;
            this.failKind = failKind;
        }

        @Override public void maybeFailForward(SagaPhase phase, String kind) {
            if (phase == failPhase && failKind.equalsIgnoreCase(kind)) {
                throw new SagaFaultException("forsiran fail " + phase + " (" + kind + ")");
            }
        }

        @Override public void maybeFailCompensator(String sagaId, SagaPhase phase) { }
        @Override public void maybeDelay(SagaPhase phase) { }
    }

    @Test
    @DisplayName("SGT-01 happy path: svih 5 faza prolazi -> COMPLETED, currentStep=5, "
            + "5 FORWARD ok zapisa, ugovor EXERCISED")
    void happyPath_allFivePhases_completed() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");

        when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
        when(contractRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(contract));
        // F1: racuni (reuse postojeceg reservationId — reserveFunds NE poziva)
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        // F2 + F4: seller portfolio (qty 20 >= 10), buyer portfolio odsutan -> kreira se u F4
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                1L, UserRole.CLIENT, 100L)).thenReturn(Optional.empty());
        // F3: commit + credit ok
        when(bankaCoreClient.commitFunds(anyString(), anyString(), any(CommitFundsRequest.class)))
                .thenReturn(new CommitFundsResponse("RES-77", new BigDecimal("1600.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));
        when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                .thenReturn(new CreditFundsResponse(88L, new BigDecimal("1600.00"),
                        new BigDecimal("101600.00")));
        // SagaLog perzistencija — vrati isti (mutabilni) argument da mozemo inspect-ovati
        when(sagaLogRepository.saveAndFlush(any(SagaLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SagaResult result = orchestrator.exercise(7L, 10L);

        assertThat(result.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(result.currentStep()).isEqualTo(5);

        // Uhvati poslednji perzistovani SagaLog
        ArgumentCaptor<SagaLog> captor = ArgumentCaptor.forClass(SagaLog.class);
        verify(sagaLogRepository, atLeastOnce()).saveAndFlush(captor.capture());
        SagaLog persisted = captor.getValue();
        assertThat(persisted.getStatus()).isEqualTo(SagaStatus.COMPLETED);

        List<SagaLogEntry> entries = persisted.getEntries();
        assertThat(entries).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(entries.get(i).kind()).isEqualTo(SagaStepKind.FORWARD);
            assertThat(entries.get(i).outcome()).isEqualTo("ok");
            assertThat(entries.get(i).phase()).isEqualTo(i + 1);
        }

        // F5 finalizacija: ugovor je EXERCISED i save-ovan
        ArgumentCaptor<OtcContract> ccaptor = ArgumentCaptor.forClass(OtcContract.class);
        verify(contractRepository, atLeastOnce()).save(ccaptor.capture());
        assertThat(ccaptor.getValue().getStatus()).isEqualTo(OtcContractStatus.EXERCISED);
        assertThat(contract.getStatus()).isEqualTo(OtcContractStatus.EXERCISED);
        assertThat(contract.getExercisedAt()).isNotNull();
        // F1 reuse: reserveFunds nikad nije pozvan (rezervacija postoji od accept-a)
        verify(bankaCoreClient, never())
                .reserveFunds(anyString(), any(rs.raf.banka2.contracts.internal.ReserveFundsRequest.class));
    }

    @Test
    @DisplayName("SGT-05 accept-time ugovor, forsiran fail u F3 (before) -> COMPENSATED, currentStep=3, "
            + "log [F1 fwd, F2 fwd, F3 fwd err, C3 no-op, C2, C1]; accept-time rezervacije OCUVANE (BUG-W2-01)")
    void f3ForcedFail_acceptTimeContract_compensatesC2C1_reservationsPreserved() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");

        OtcExerciseSagaOrchestrator orch = new OtcExerciseSagaOrchestrator(contractRepository,
                portfolioRepository, sagaLogRepository, new SagaLogWriter(sagaLogRepository),
                bankaCoreClient, currencyConversionService,
                userResolver, new ForceForwardFailInjector(SagaPhase.F3, "before"));

        when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
        when(contractRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        // Seller portfolio sa rezervacijom 10 (accept-time) -> F2 no-op, C2 NE dira rezervaciju.
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        when(sagaLogRepository.saveAndFlush(any(SagaLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SagaResult result = orch.exercise(7L, 10L);

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(result.currentStep()).isEqualTo(3);

        ArgumentCaptor<SagaLog> captor = ArgumentCaptor.forClass(SagaLog.class);
        verify(sagaLogRepository, atLeastOnce()).saveAndFlush(captor.capture());
        SagaLog persisted = captor.getValue();
        assertThat(persisted.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        List<SagaLogEntry> entries = persisted.getEntries();
        // P0-1 (loop-inclusive): petlja sad ukljucuje failed step (3), pa se i C3 izvrsi — ali kao
        // NO-OP (F3 je pao u "before" → f3CommitDone=false → C3 grana je no-op, BEZ banka poziva).
        // [F1 fwd ok, F2 fwd ok, F3 fwd err, C3 ok (no-op), C2 ok, C1 ok]
        assertThat(entries).hasSize(6);
        assertThat(entries.get(0).phase()).isEqualTo(1);
        assertThat(entries.get(0).kind()).isEqualTo(SagaStepKind.FORWARD);
        assertThat(entries.get(0).outcome()).isEqualTo("ok");
        assertThat(entries.get(1).phase()).isEqualTo(2);
        assertThat(entries.get(1).kind()).isEqualTo(SagaStepKind.FORWARD);
        assertThat(entries.get(1).outcome()).isEqualTo("ok");
        assertThat(entries.get(2).phase()).isEqualTo(3);
        assertThat(entries.get(2).kind()).isEqualTo(SagaStepKind.FORWARD);
        assertThat(entries.get(2).outcome()).isEqualTo("err");
        assertThat(entries.get(3).phase()).isEqualTo(3);
        assertThat(entries.get(3).kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(entries.get(3).outcome()).isEqualTo("ok");
        assertThat(entries.get(4).phase()).isEqualTo(2);
        assertThat(entries.get(4).kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(entries.get(4).outcome()).isEqualTo("ok");
        assertThat(entries.get(5).phase()).isEqualTo(1);
        assertThat(entries.get(5).kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(entries.get(5).outcome()).isEqualTo("ok");

        // F3/F4/F5 forward side effects NIJESU se desili (C3 no-op grana ne poziva commit/credit)
        verify(bankaCoreClient, never())
                .commitFunds(anyString(), anyString(), any(CommitFundsRequest.class));
        verify(bankaCoreClient, never())
                .creditFunds(anyString(), any(CreditFundsRequest.class));
        assertThat(contract.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);
        assertThat(contract.getExercisedAt()).isNull();

        // BUG-W2-01 FIX: ugovor ostaje ACTIVE → accept-time rezervacije se NE oslobadjaju.
        // C1: releaseFunds NIJE pozvan (F1 je REUSE-ovao accept-time hold, ne kreirao novi).
        verify(bankaCoreClient, never())
                .releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class));
        // C2: seller reservedQuantity OSTAJE 10 (accept-time lock — F2 bio no-op).
        assertThat(sellerPf.getReservedQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("SGT-05b reserve-at-exercise ugovor (bez accept-time rezervacije), fail u F3 (before) -> "
            + "COMPENSATED; C1 releaseFunds POZVAN i seller reservedQuantity vracen na 0")
    void f3ForcedFail_reserveAtExerciseContract_compensatesC2C1_reservationsReleased() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = unreservedActiveContract(7L, 1L, 2L, listing, 10, "160.00");

        OtcExerciseSagaOrchestrator orch = new OtcExerciseSagaOrchestrator(contractRepository,
                portfolioRepository, sagaLogRepository, new SagaLogWriter(sagaLogRepository),
                bankaCoreClient, currencyConversionService,
                userResolver, new ForceForwardFailInjector(SagaPhase.F3, "before"));

        when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
        when(contractRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        // F1: kreira novu rezervaciju (reserve-at-exercise).
        when(bankaCoreClient.reserveFunds(anyString(),
                any(rs.raf.banka2.contracts.internal.ReserveFundsRequest.class)))
                .thenReturn(new ReserveFundsResponse("RES-NEW", 10L, new BigDecimal("1600.00"),
                        new BigDecimal("98400.00")));
        // Seller portfolio BEZ rezervacije (reservedQuantity=0) -> F2 rezervise 10, C2 oslobadja 10.
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 0);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        // C1 release ok
        when(bankaCoreClient.releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class)))
                .thenReturn(new ReleaseFundsResponse("RES-NEW", new BigDecimal("1600.00"), BigDecimal.ZERO));
        when(sagaLogRepository.saveAndFlush(any(SagaLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SagaResult result = orch.exercise(7L, 10L);

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(result.currentStep()).isEqualTo(3);
        assertThat(contract.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);

        // BUG-W2-01 FIX: F1/F2 su kreirali rezervacije OVE saga-e → C1/C2 ih oslobadjaju.
        // C1: releaseFunds POZVAN na novokreiranoj rezervaciji.
        verify(bankaCoreClient, atLeastOnce())
                .releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class));
        // C2: F2 rezervisao 10 (0 -> 10), C2 oslobodio (10 -> 0).
        assertThat(sellerPf.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    @DisplayName("SGT-06 forsiran fail u F4 (before) -> COMPENSATED, currentStep=4, C4 no-op, "
            + "C3 reverzni transfer prodavac->kupac (obe noge) cuva sredstva (I1)")
    void f4ForcedFail_c3ReverseTransfer_conservesFunds() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");

        OtcExerciseSagaOrchestrator orch = new OtcExerciseSagaOrchestrator(contractRepository,
                portfolioRepository, sagaLogRepository, new SagaLogWriter(sagaLogRepository),
                bankaCoreClient, currencyConversionService,
                userResolver, new ForceForwardFailInjector(SagaPhase.F4, "before"));

        when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
        when(contractRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        // F3 uspeva (commit + credit prodavcu) pre nego sto F4 padne
        when(bankaCoreClient.commitFunds(anyString(), anyString(), any(CommitFundsRequest.class)))
                .thenReturn(new CommitFundsResponse("RES-77", new BigDecimal("1600.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));
        when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                .thenReturn(new CreditFundsResponse(88L, new BigDecimal("1600.00"),
                        new BigDecimal("101600.00")));
        // C3 reverzni transfer prodavac->kupac
        when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                .thenReturn(new TransferFundsResponse(88L, 10L, new BigDecimal("1600.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));
        // (BUG-W2-01 FIX: C1 ne poziva releaseFunds za accept-time ugovor → bez stub-a, STRICT_STUBS.)
        when(sagaLogRepository.saveAndFlush(any(SagaLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SagaResult result = orch.exercise(7L, 10L);

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(result.currentStep()).isEqualTo(4);

        ArgumentCaptor<SagaLog> captor = ArgumentCaptor.forClass(SagaLog.class);
        verify(sagaLogRepository, atLeastOnce()).saveAndFlush(captor.capture());
        List<SagaLogEntry> entries = captor.getValue().getEntries();
        // P0-1 (loop-inclusive): petlja ukljucuje failed step (4), pa se i C4 izvrsi — ali kao NO-OP
        // (F4 pao u "before" → f4SellerApplied=false → C4 guard vraca odmah, bez diranja portfolija).
        // [F1 fwd ok, F2 fwd ok, F3 fwd ok, F4 fwd err, C4 ok (no-op), C3 ok, C2 ok, C1 ok]
        assertThat(entries).hasSize(8);
        assertThat(entries.get(3).phase()).isEqualTo(4);
        assertThat(entries.get(3).kind()).isEqualTo(SagaStepKind.FORWARD);
        assertThat(entries.get(3).outcome()).isEqualTo("err");
        assertThat(entries.get(4).phase()).isEqualTo(4);
        assertThat(entries.get(4).kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(entries.get(4).outcome()).isEqualTo("ok");
        assertThat(entries.get(5).phase()).isEqualTo(3);
        assertThat(entries.get(5).kind()).isEqualTo(SagaStepKind.COMPENSATE);
        assertThat(entries.get(5).outcome()).isEqualTo("ok");

        // F3 je izvrsen (commit prodavcu) pa je C3 reverzni transfer obavezan
        verify(bankaCoreClient, atLeastOnce())
                .commitFunds(anyString(), anyString(), any(CommitFundsRequest.class));
        // C3: reverzni transfer prodavac(88)->kupac(10), obe noge 1600 — kljucna provera ocuvanja (I1)
        ArgumentCaptor<TransferFundsRequest> tcap = ArgumentCaptor.forClass(TransferFundsRequest.class);
        verify(bankaCoreClient).transferFunds(anyString(), tcap.capture());
        TransferFundsRequest tr = tcap.getValue();
        assertThat(tr.fromAccountId()).isEqualTo(88L);
        assertThat(tr.toAccountId()).isEqualTo(10L);
        assertThat(tr.debitAmount()).isEqualByComparingTo(new BigDecimal("1600.00"));
        assertThat(tr.creditAmount()).isEqualByComparingTo(new BigDecimal("1600.00"));
        // F5 nije dostignut -> ugovor ostaje ACTIVE
        assertThat(contract.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);
        // BUG-W2-01 FIX: accept-time ugovor; F2 je bio no-op (pokrice), F4 pao PRE bocnih
        // efekata (C4 se ne izvrsava jer F4 nikad nije primenjen), pa C2 no-op → seller
        // reservedQuantity OSTAJE na accept vrednosti 10 (rezervacija ocuvana na ACTIVE ugovoru).
        assertThat(sellerPf.getReservedQuantity()).isEqualTo(10);
        // C1: releaseFunds NIJE pozvan (F1 REUSE-ovao accept-time hold).
        verify(bankaCoreClient, never())
                .releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class));
    }

    /**
     * <b>SG-07 (SAGA_test.pdf) — F5 forsiran fail → kompenzacija C4, C3, C2, C1.</b>
     *
     * <p>F1–F4 prolaze (rezervacija, akcije, prenos sredstava, prenos vlasnistva), pa
     * F5 (status-flip ACTIVE→EXERCISED) padne. Orkestrator kompenzuje obrnutim redom
     * od koraka 5: <b>C5</b> (restore statusa na ACTIVE — no-op jer F5 pao "before" pa
     * flip nije ni primenjen), <b>C4</b> (hartije vracene prodavcu), <b>C3</b> (reverzni
     * transfer prodavac→kupac — sredstva vracena kupcu), <b>C2/C1</b> (accept-time
     * rezervacije ocuvane). Terminalni status COMPENSATED, current_step=5, ugovor ostaje
     * ACTIVE — "stanje identicno prethodnom".
     *
     * <p>PDF SG-07 navodi log F1–F4 ok / F5 err / C4 / C3 / C2 / C1 (5 ok kompenzatora bi
     * bilo C4..C1 = 4). Nasa impl je <b>loop-inclusive</b> (kao SGT-05/06): petlja ukljucuje
     * i failed step 5, pa se dodatno izvrsi <b>C5 no-op</b> (restore na ACTIVE) PRE C4 — log je
     * SUPERSET PDF-a (svi PDF kompenzatori C4/C3/C2/C1 prisutni + C5 no-op), pa je ovo
     * dokumentovana benigna deviacija (C5 ne menja stanje jer F5 nije primenio flip).
     */
    @Test
    @DisplayName("SG-07 forsiran fail u F5 (before) -> COMPENSATED, currentStep=5, C4 vraca hartije "
            + "prodavcu / C3 reverzni transfer vraca novac kupcu (I1/I2), ugovor ACTIVE")
    void f5ForcedFail_compensatesC4ReturnsSharesC3RefundsBuyer_step5() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");

        OtcExerciseSagaOrchestrator orch = new OtcExerciseSagaOrchestrator(contractRepository,
                portfolioRepository, sagaLogRepository, new SagaLogWriter(sagaLogRepository),
                bankaCoreClient, currencyConversionService,
                userResolver, new ForceForwardFailInjector(SagaPhase.F5, "before"));

        when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
        when(contractRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        // Seller portfolio sa accept-time rezervacijom (qty 20, reserved 10): F2 no-op,
        // F4 prenos vlasnistva (qty 20->10, reserved 10->0), C4 vraca (qty 10->20, reserved 0->10).
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        // Buyer portfolio odsutan -> F4 ga kreira, C4 (preF4BuyerExisted=false) ga brise (mock vraca
        // empty pa je delete no-op nad praznim Optional-om — bez NPE).
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                1L, UserRole.CLIENT, 100L)).thenReturn(Optional.empty());
        // F3 uspeva u potpunosti (commit + credit prodavcu) PRE nego sto F5 padne.
        when(bankaCoreClient.commitFunds(anyString(), anyString(), any(CommitFundsRequest.class)))
                .thenReturn(new CommitFundsResponse("RES-77", new BigDecimal("1600.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));
        when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                .thenReturn(new CreditFundsResponse(88L, new BigDecimal("1600.00"),
                        new BigDecimal("101600.00")));
        // C3 reverzni transfer prodavac(88)->kupac(10) (f3CreditDone=true grana).
        when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                .thenReturn(new TransferFundsResponse(88L, 10L, new BigDecimal("1600.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));
        when(sagaLogRepository.saveAndFlush(any(SagaLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SagaResult result = orch.exercise(7L, 10L);

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(result.currentStep()).isEqualTo(5);

        ArgumentCaptor<SagaLog> captor = ArgumentCaptor.forClass(SagaLog.class);
        verify(sagaLogRepository, atLeastOnce()).saveAndFlush(captor.capture());
        List<SagaLogEntry> entries = captor.getValue().getEntries();
        // Loop-inclusive log: [F1 ok, F2 ok, F3 ok, F4 ok, F5 err, C5 ok (no-op), C4 ok, C3 ok, C2 ok, C1 ok]
        assertThat(entries).hasSize(10);
        // F1..F4 forward ok
        for (int i = 0; i < 4; i++) {
            assertThat(entries.get(i).phase()).isEqualTo(i + 1);
            assertThat(entries.get(i).kind()).isEqualTo(SagaStepKind.FORWARD);
            assertThat(entries.get(i).outcome()).isEqualTo("ok");
        }
        // F5 forward err
        assertThat(entries.get(4).phase()).isEqualTo(5);
        assertThat(entries.get(4).kind()).isEqualTo(SagaStepKind.FORWARD);
        assertThat(entries.get(4).outcome()).isEqualTo("err");
        // Kompenzatori obrnutim redom C5,C4,C3,C2,C1 — svi ok
        int[] compPhases = {5, 4, 3, 2, 1};
        for (int i = 0; i < 5; i++) {
            assertThat(entries.get(5 + i).phase()).isEqualTo(compPhases[i]);
            assertThat(entries.get(5 + i).kind()).isEqualTo(SagaStepKind.COMPENSATE);
            assertThat(entries.get(5 + i).outcome()).isEqualTo("ok");
        }

        // C4 — hartije vracene prodavcu: F4 je preneo (qty 20->10, reserved 10->0),
        // C4 vratio (qty 10->20, reserved 0->10) => I2 ocuvan.
        assertThat(sellerPf.getQuantity()).as("SG-07 C4: prodavcu vracena kolicina").isEqualTo(20);
        assertThat(sellerPf.getReservedQuantity()).as("SG-07 C4: vracena rezervacija").isEqualTo(10);

        // C3 — sredstva vracena kupcu: reverzni transfer prodavac(88)->kupac(10), obe noge 1600 (I1).
        verify(bankaCoreClient, atLeastOnce())
                .commitFunds(anyString(), anyString(), any(CommitFundsRequest.class));
        ArgumentCaptor<TransferFundsRequest> tcap = ArgumentCaptor.forClass(TransferFundsRequest.class);
        verify(bankaCoreClient).transferFunds(anyString(), tcap.capture());
        TransferFundsRequest tr = tcap.getValue();
        assertThat(tr.fromAccountId()).isEqualTo(88L);
        assertThat(tr.toAccountId()).isEqualTo(10L);
        assertThat(tr.debitAmount()).isEqualByComparingTo(new BigDecimal("1600.00"));
        assertThat(tr.creditAmount()).isEqualByComparingTo(new BigDecimal("1600.00"));

        // I6 — ugovor ostaje ACTIVE (F5 flip nije primenjen / C5 restore), exercisedAt null.
        assertThat(contract.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);
        assertThat(contract.getExercisedAt()).isNull();
        // C1: accept-time hold -> releaseFunds NIJE pozvan (rezervacija ocuvana na ACTIVE ugovoru).
        verify(bankaCoreClient, never())
                .releaseFunds(anyString(), anyString(), any(ReleaseFundsRequest.class));
    }

    /**
     * <b>SGT-07 (P0-1, Bug A) — F3 commit OK, credit FAIL → C3 commit-only grana.</b>
     * F3 {@code commitFunds} uspe (kupac debitovan, rezervacija zatvorena), pa
     * {@code creditFunds} ka prodavcu baci → saga pada U KORAKU 3 sa
     * {@code f3CommitDone=true, f3CreditDone=false}. C3 mora da kreditira KUPCA
     * nazad commit-ovani iznos (prodavac NIKAD nije kreditiran → ne sme se debitovati),
     * a NE da radi reverzni {@code transferFunds} (koji bi debitovao prodavca koji
     * nema priliv → STVORIO bi novac / negativan saldo). Dokazuje granu iz tacke 2.
     */
    @Test
    @DisplayName("SGT-07 F3 commit-OK/credit-FAIL -> C3 kreditira KUPCA (ne transferFunds) — partial-aware C3")
    void f3CommitOk_creditFails_c3CreditsBuyerNotTransfer() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = reservedActiveContract(7L, 1L, 2L, listing, 10, "160.00", "RES-77");

        when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
        when(contractRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        // accept-time seller pokrice → F2 no-op, C2 no-op.
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        // F3: commit uspe...
        when(bankaCoreClient.commitFunds(anyString(), anyString(), any(CommitFundsRequest.class)))
                .thenReturn(new CommitFundsResponse("RES-77", new BigDecimal("1600.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));
        // ...a credit ka prodavcu (prvi creditFunds) baci → saga pada u F3 POSLE commit-a.
        // C3 commit-only grana ce pozvati creditFunds DRUGI put (ka kupcu) — taj poziv mora uspeti.
        AtomicInteger creditCalls = new AtomicInteger(0);
        when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                .thenAnswer(inv -> {
                    if (creditCalls.getAndIncrement() == 0) {
                        throw new BankaCoreClientException(503, "credit privremeno nedostupan");
                    }
                    CreditFundsRequest req = inv.getArgument(1);
                    return new CreditFundsResponse(req.accountId(), req.amount(), new BigDecimal("100000.00"));
                });
        when(sagaLogRepository.saveAndFlush(any(SagaLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SagaResult result = orchestrator.exercise(7L, 10L);

        assertThat(result.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(result.currentStep()).isEqualTo(3);

        // C3 commit-only grana: drugi creditFunds poziv ide KUPCU (racun 10) sa commit-ovanim iznosom.
        ArgumentCaptor<CreditFundsRequest> cap = ArgumentCaptor.forClass(CreditFundsRequest.class);
        verify(bankaCoreClient, atLeastOnce()).creditFunds(anyString(), cap.capture());
        CreditFundsRequest refund = cap.getValue();   // poslednji = C3 povrat
        assertThat(refund.accountId()).as("C3 kreditira KUPCA nazad").isEqualTo(10L);
        assertThat(refund.amount()).isEqualByComparingTo(new BigDecimal("1600.00"));
        // C3 NE sme da radi reverzni transferFunds (prodavac nikad nije kreditiran).
        verify(bankaCoreClient, never())
                .transferFunds(anyString(), any(TransferFundsRequest.class));
        assertThat(contract.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);
    }

    /** Forsira F3/before fail + UVEK obara kompenzator zadate faze (trajan kompenzator pad). */
    private static final class F3FailPlusCompensatorAlwaysFailsInjector implements SagaFaultInjector {
        private final SagaPhase compensatorFailPhase;

        F3FailPlusCompensatorAlwaysFailsInjector(SagaPhase compensatorFailPhase) {
            this.compensatorFailPhase = compensatorFailPhase;
        }

        @Override public void maybeFailForward(SagaPhase phase, String kind) {
            if (phase == SagaPhase.F3 && "before".equalsIgnoreCase(kind)) {
                throw new SagaFaultException("forsiran fail F3 (before)");
            }
        }

        @Override public void maybeFailCompensator(String sagaId, SagaPhase phase) {
            if (phase == compensatorFailPhase) {
                throw new SagaFaultException("forsiran TRAJAN pad kompenzatora C" + phase.step());
            }
        }

        @Override public void maybeDelay(SagaPhase phase) { }
    }

    /**
     * <b>P1-1 (defect 2) — trajan pad kompenzatora ne sme da baci iz {@code exercise()}.</b>
     *
     * <p>F3 pada (before) → kompenzacija C3(no-op)→C2→C1. C1 kompenzator UVEK baca (banka-core
     * trajno nedostupan). Pre fix-a {@code compensate()} bi posle 5 pokusaja {@code throw}-ovao
     * {@link IllegalStateException} koja propagira iz {@code @Transactional exercise()} → outer
     * tx rollback → SVI {@code saga_logs} zapisi izgubljeni (saga nestaje, recovery je ne nadje).
     *
     * <p>Posle fix-a {@code compensate()} ostavlja status <b>COMPENSATING</b> i VRACA se (ne baca);
     * {@code exercise()} vraca {@link SagaResult} sa COMPENSATING (NE propagira izuzetak), a
     * poslednji durabilni {@code saveAndFlush} nosi COMPENSATING — pa ga sledeci
     * {@code SagaRecoveryService} sweep nalazi i retry-uje C1 (identicna semantika kao {@code recover()}).
     */
    @Test
    @DisplayName("P1-1 defect 2: trajan pad kompenzatora -> exercise() NE baca, vraca COMPENSATING (durabilno)")
    void compensatorPermanentlyFails_doesNotThrow_returnsCompensating() {
        Listing listing = stockListing(100L, "AAPL", "USD");
        OtcContract contract = unreservedActiveContract(7L, 1L, 2L, listing, 10, "160.00");

        OtcExerciseSagaOrchestrator orch = new OtcExerciseSagaOrchestrator(contractRepository,
                portfolioRepository, sagaLogRepository, new SagaLogWriter(sagaLogRepository),
                bankaCoreClient, currencyConversionService,
                userResolver, new F3FailPlusCompensatorAlwaysFailsInjector(SagaPhase.F1));

        when(userResolver.resolveCurrent()).thenReturn(new UserContext(1L, UserRole.CLIENT));
        when(contractRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(contract));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account(10L, "111", "Buyer", "USD", 1L));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 2L, "USD"))
                .thenReturn(account(88L, "222", "Seller", "USD", 2L));
        // reserve-at-exercise: F1 kreira rezervaciju (da bi C1 imao sta da oslobodi — i tu pada).
        when(bankaCoreClient.reserveFunds(anyString(),
                any(rs.raf.banka2.contracts.internal.ReserveFundsRequest.class)))
                .thenReturn(new ReserveFundsResponse("RES-NEW", 10L, new BigDecimal("1600.00"),
                        new BigDecimal("98400.00")));
        Portfolio sellerPf = sellerPortfolio(5L, 2L, UserRole.CLIENT, 100L, 20, 10, 0);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(
                2L, UserRole.CLIENT, 100L)).thenReturn(Optional.of(sellerPf));
        when(sagaLogRepository.saveAndFlush(any(SagaLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // KLJUC: exercise() NE sme da baci uprkos trajnom padu C1 — direktan poziv (propagiran
        // izuzetak bi srusio test). Pre fix-a ovde je leteo IllegalStateException ("C1 trajno pala").
        SagaResult result = orch.exercise(7L, 10L);

        assertThat(result.status())
                .as("trajan pad kompenzatora -> COMPENSATING (recovery retry), NE propagiran izuzetak")
                .isEqualTo(SagaStatus.COMPENSATING);

        // Durabilno perzistovan COMPENSATING (poslednji saveAndFlush) — recovery ga nalazi.
        ArgumentCaptor<SagaLog> captor = ArgumentCaptor.forClass(SagaLog.class);
        verify(sagaLogRepository, atLeastOnce()).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SagaStatus.COMPENSATING);
    }
}
