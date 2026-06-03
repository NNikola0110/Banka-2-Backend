package rs.raf.trading.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.investmentfund.service.FundLiquidationService;
import rs.raf.trading.margin.service.MarginOrderSettlementService;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage testovi {@link SingleOrderExecutor} — per-order fill engine grane:
 * legacy guard, LIMIT cena guards, settlement auto-decline, partial fill,
 * AON false, releaseReservationSafe, updatePortfolio blend, remaining=0 early
 * DONE, FUND liquidation hook.
 *
 * <p>P2-3: posle izdvajanja per-order logike iz {@code OrderExecutionService} u
 * {@link SingleOrderExecutor} (REQUIRES_NEW tx), ovi testovi pozivaju
 * {@code executor.execute(order)} direktno (eligibility/delay guard je sada
 * orkestratorov posao, pokriven u {@code OrderExecutionServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderExecutionServiceCoverageTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private AonValidationService aonValidationService;
    @Mock private FundReservationService fundReservationService;
    @Mock private FundLiquidationService fundLiquidationService;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private rs.raf.trading.notification.service.NotificationService notificationService;
    @Mock private MarginOrderSettlementService marginOrderSettlementService;
    @Mock private io.micrometer.core.instrument.Counter ordersExecutedCounter;

    @InjectMocks
    private SingleOrderExecutor service;

    private Listing listing;

    private InternalAccountDto usdAccount() {
        return new InternalAccountDto(1L, "111", "Owner",
                new BigDecimal("10000.00"), new BigDecimal("8000.00"), new BigDecimal("2000.00"),
                "USD", "ACTIVE", 42L, null, "CLIENT");
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "afterHoursDelaySeconds", 0L);
        ReflectionTestUtils.setField(service, "maxFillIntervalSeconds", 600L);

        listing = new Listing();
        listing.setId(10L);
        listing.setTicker("AAPL");
        listing.setName("Apple Inc");
        listing.setAsk(new BigDecimal("100.00"));
        listing.setBid(new BigDecimal("95.00"));
        listing.setListingType(ListingType.STOCK);
    }

    private Order baseOrder() {
        Order o = new Order();
        o.setId(100L);
        o.setUserId(42L);
        o.setUserRole("CLIENT");
        o.setListing(listing);
        o.setOrderType(OrderType.MARKET);
        o.setDirection(OrderDirection.BUY);
        o.setQuantity(10);
        o.setRemainingPortions(10);
        o.setContractSize(1);
        o.setStatus(OrderStatus.APPROVED);
        o.setAccountId(1L);
        o.setReservedAccountId(1L);
        o.setBankaCoreReservationId("res-100");
        o.setAllOrNone(true); // forsiraj deterministican fill
        // P2-concurrency-locks-1 (R6-1998): execute() re-fetch-uje order pod lockom
        // (findByIdForUpdate) i radi nad svezim managed entitetom. Vrati BAS ovaj order
        // tako da postojece asercije nad njim i dalje vaze.
        when(orderRepository.findByIdForUpdate(o.getId())).thenReturn(Optional.of(o));
        return o;
    }

    // ── 1. Legacy guard: null accountId i null reservedAccountId ──────────────
    @Test
    @DisplayName("Legacy guard: order bez ijednog account-a → DECLINED")
    void legacyGuard_nullAccounts_markedDeclined() {
        Order o = baseOrder();
        o.setAccountId(null);
        o.setReservedAccountId(null);

        service.execute(o);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.DECLINED);
        verify(orderRepository).save(o);
        verify(listingRepository, never()).findById(any());
        verify(fundReservationService, never()).consumeForBuyFill(any(), anyInt(), any(), any());
    }

    // ── 2. LIMIT BUY: cena previsoka → ranije return ──────────────────────────
    @Test
    @DisplayName("LIMIT BUY: ask iznad limit → skip fill")
    void limitBuy_askTooHigh_noFill() {
        Order o = baseOrder();
        o.setOrderType(OrderType.LIMIT);
        o.setLimitValue(new BigDecimal("50.00"));

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        service.execute(o);

        verify(fundReservationService, never()).consumeForBuyFill(any(), anyInt(), any(), any());
        assertThat(o.getRemainingPortions()).isEqualTo(10);
        assertThat(o.isDone()).isFalse();
    }

    // ── 3. LIMIT BUY: cena u okviru limit-a → uspesan fill ──────────────────
    @Test
    @DisplayName("LIMIT BUY: ask ispod limit → uspesan fill")
    void limitBuy_askWithinLimit_fills() {
        Order o = baseOrder();
        o.setOrderType(OrderType.LIMIT);
        o.setLimitValue(new BigDecimal("150.00"));

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT")).thenReturn(new ArrayList<>());

        service.execute(o);

        verify(fundReservationService, times(1))
                .consumeForBuyFill(eq(o), eq(10), any(BigDecimal.class), any(BigDecimal.class));
        verify(portfolioRepository).save(any(Portfolio.class));
    }

    // ── 4. LIMIT SELL: bid iznad limit-a → uspesan fill ───────────────────────
    @Test
    @DisplayName("LIMIT SELL: bid iznad limit → uspesan fill")
    void limitSell_bidAboveLimit_fills() {
        Order o = baseOrder();
        o.setOrderType(OrderType.LIMIT);
        o.setDirection(OrderDirection.SELL);
        o.setLimitValue(new BigDecimal("50.00")); // bid = 95 >= 50

        Portfolio p = new Portfolio();
        p.setId(5L);
        p.setUserId(42L);
        p.setListingId(10L);
        p.setQuantity(50);
        p.setReservedQuantity(10);
        p.setAverageBuyPrice(new BigDecimal("80.00"));

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 10L))
                .thenReturn(Optional.of(p));
        when(bankaCoreClient.getAccount(1L)).thenReturn(usdAccount());

        service.execute(o);

        verify(fundReservationService).consumeForSellFill(eq(o), eq(p), eq(10));
    }

    // ── 5. LIMIT SELL: bid ispod limit-a ──────────────────────────────────────
    @Test
    @DisplayName("LIMIT SELL: bid ispod limit → skip fill")
    void limitSell_bidBelowLimit_noFill() {
        Order o = baseOrder();
        o.setOrderType(OrderType.LIMIT);
        o.setDirection(OrderDirection.SELL);
        o.setLimitValue(new BigDecimal("200.00"));

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        service.execute(o);

        verify(fundReservationService, never()).consumeForSellFill(any(), any(), anyInt());
    }

    // ── 6. Auto-decline kad je settlement date u proslosti ────────────────────
    @Test
    @DisplayName("Auto-decline: settlement date protekao → DECLINED + release")
    void executeOrders_settlementExpired_autoDeclined() {
        listing.setSettlementDate(LocalDate.now().minusDays(1));
        Order o = baseOrder();

        service.execute(o);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.DECLINED);
        assertThat(o.isDone()).isTrue();
        verify(orderRepository).save(o);
        verify(fundReservationService, times(1)).releaseForBuy(o);
    }

    // ── 7. Auto-decline: release baca exception — swallow ─────────────────────
    @Test
    @DisplayName("Auto-decline: releaseReservationSafe exception je progutan")
    void executeOrders_settlementExpired_releaseThrows_swallowed() {
        listing.setSettlementDate(LocalDate.now().minusDays(5));
        Order o = baseOrder();

        doThrow(new RuntimeException("release boom"))
                .when(fundReservationService).releaseForBuy(any());

        service.execute(o);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.DECLINED);
        verify(orderRepository).save(o);
    }

    // ── 13. AON provera vraca false → ne izvrsava ────────────────────────────
    @Test
    @DisplayName("AON: checkCanExecuteAon vraca false → ne izvrsava fill")
    void aon_checkReturnsFalse_noFill() {
        Order o = baseOrder();

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(false);

        service.execute(o);

        verify(fundReservationService, never()).consumeForBuyFill(any(), anyInt(), any(), any());
        assertThat(o.getRemainingPortions()).isEqualTo(10);
    }

    // ── 14. updatePortfolio: postojeci portfolio BUY → blend avg price ───────
    @Test
    @DisplayName("updatePortfolio BUY: postojeci portfolio — blend avg price")
    void updatePortfolio_existing_blendsAveragePrice() {
        Order o = baseOrder();

        Portfolio existing = new Portfolio();
        existing.setId(5L);
        existing.setUserId(42L);
        existing.setListingId(10L);
        existing.setQuantity(20);
        existing.setAverageBuyPrice(new BigDecimal("80.00"));
        existing.setListingTicker("AAPL");

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT"))
                .thenReturn(new ArrayList<>(List.of(existing)));

        service.execute(o);

        // oldTotal = 80 * 20 = 1600; newFill = 100*10 = 1000; newQty=30; newAvg = 2600/30 = 86.6667
        assertThat(existing.getQuantity()).isEqualTo(30);
        assertThat(existing.getAverageBuyPrice()).isEqualByComparingTo(new BigDecimal("86.6667"));
    }

    // ── 15. releaseReservationSafe SELL: releaseForSell baca exception → swallowed ──
    @Test
    @DisplayName("releaseReservationSafe SELL: releaseForSell exception je progutan")
    void releaseReservationSafe_sellReleaseThrows_swallowed() {
        Order o = baseOrder();
        o.setDirection(OrderDirection.SELL);

        Portfolio p = new Portfolio();
        p.setId(5L);
        p.setUserId(42L);
        p.setListingId(10L);
        p.setQuantity(50);
        p.setReservedQuantity(10);
        p.setAverageBuyPrice(new BigDecimal("80.00"));

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 10L))
                .thenReturn(Optional.of(p));
        when(bankaCoreClient.getAccount(1L)).thenReturn(usdAccount());
        // releaseReservationSafe SELL putanja koristi findByUserIdAndUserRole (ne forUpdate).
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT"))
                .thenReturn(new ArrayList<>(List.of(p)));
        doThrow(new RuntimeException("release sell boom"))
                .when(fundReservationService).releaseForSell(any(), any());

        service.execute(o);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.DONE);
        assertThat(o.isDone()).isTrue();
    }

    // ── 16. releaseReservationSafe: vec oslobodjeno → no-op (settlement decline) ──
    @Test
    @DisplayName("releaseReservationSafe: reservationReleased=true → no-op")
    void releaseReservationSafe_alreadyReleased_noop() {
        listing.setSettlementDate(LocalDate.now().minusDays(1));
        Order o = baseOrder();
        o.setReservationReleased(true);

        service.execute(o);

        verify(fundReservationService, never()).releaseForBuy(any());
        assertThat(o.getStatus()).isEqualTo(OrderStatus.DECLINED);
    }

    // ── 17. Listing not found → exception propagira (REQUIRES_NEW rollback) ──
    @Test
    @DisplayName("Listing nije pronadjen → RuntimeException")
    void listingNotFound_throws() {
        Order o = baseOrder();

        when(listingRepository.findById(10L)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.execute(o));
        verify(fundReservationService, never()).consumeForBuyFill(any(), anyInt(), any(), any());
    }

    // ── 18. Partial fill (remaining > 0 posle filla) ─────────────────────────
    @Test
    @DisplayName("Partial fill: consumeForBuyFill pozvan, status ostaje APPROVED")
    void partialFill_keepsApproved() {
        Order o = baseOrder();
        o.setAllOrNone(false);
        o.setQuantity(100);
        o.setRemainingPortions(100);

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT")).thenReturn(new ArrayList<>());

        service.execute(o);

        verify(fundReservationService, times(1))
                .consumeForBuyFill(eq(o), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
    }

    // ── 19. SELL fallback accountId za receivingAccount kad reservedAccountId null ─
    @Test
    @DisplayName("SELL: reservedAccountId null → koristi accountId za receivingAccount")
    void sell_nullReserved_usesAccountId() {
        Order o = baseOrder();
        o.setDirection(OrderDirection.SELL);
        o.setReservedAccountId(null);

        Portfolio p = new Portfolio();
        p.setId(5L);
        p.setUserId(42L);
        p.setListingId(10L);
        p.setQuantity(50);
        p.setReservedQuantity(10);
        p.setAverageBuyPrice(new BigDecimal("80.00"));

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 10L))
                .thenReturn(Optional.of(p));
        when(bankaCoreClient.getAccount(1L)).thenReturn(usdAccount());

        service.execute(o);

        verify(bankaCoreClient).getAccount(1L);
        verify(fundReservationService).consumeForSellFill(eq(o), eq(p), eq(10));
    }

    // ── 20. SELL: portfolio nije pronadjen → IllegalStateException ──
    @Test
    @DisplayName("SELL: portfolio not found → IllegalStateException")
    void sell_portfolioNotFound_throws() {
        Order o = baseOrder();
        o.setDirection(OrderDirection.SELL);

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 10L))
                .thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.execute(o));
        verify(fundReservationService, never()).consumeForSellFill(any(), any(), anyInt());
    }

    // ── 21. remainingPortions = 0 → early DONE return ─────────────────────────
    @Test
    @DisplayName("execute: remainingPortions=0 → DONE + release + save")
    void remainingZero_earlyDone() {
        Order o = baseOrder();
        o.setAllOrNone(false);
        o.setRemainingPortions(0);

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        service.execute(o);

        assertThat(o.isDone()).isTrue();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.DONE);
        verify(fundReservationService, never()).consumeForBuyFill(any(), anyInt(), any(), any());
        verify(orderRepository).save(o);
        verify(fundReservationService).releaseForBuy(o);
    }

    // ── 22. settlementDate u buducnosti → executes normally ───────────────────
    @Test
    @DisplayName("settlementDate u buducnosti → ne auto-decline, izvrsava normalno")
    void settlementDate_inFuture_executesNormally() {
        listing.setSettlementDate(LocalDate.now().plusDays(30));
        Order o = baseOrder();

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT")).thenReturn(new ArrayList<>());

        service.execute(o);

        assertThat(o.getStatus()).isNotEqualTo(OrderStatus.DECLINED);
        verify(fundReservationService)
                .consumeForBuyFill(eq(o), eq(10), any(BigDecimal.class), any(BigDecimal.class));
    }

    // ── 23. consumeForBuyFill baca exception → propagira (REQUIRES_NEW rollback) ──
    @Test
    @DisplayName("BUY: consumeForBuyFill baca exception → propagira (tx rollback)")
    void buyFill_consumeThrows_propagates() {
        Order o = baseOrder();

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        doThrow(new RuntimeException("commit boom"))
                .when(fundReservationService).consumeForBuyFill(any(), anyInt(), any(), any());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> service.execute(o));
        assertThat(o.isDone()).isFalse();
    }

    // ── 24. FUND order: posle fill-a poziva fundLiquidationService.onFillCompleted ──
    @Test
    @DisplayName("FUND order: posle uspesnog fill-a poziva fundLiquidationService.onFillCompleted")
    void fundOrder_callsFundLiquidationOnFill() {
        Order o = baseOrder();
        o.setUserRole("FUND");
        o.setUserId(7L);
        o.setFundId(7L);

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(7L, "FUND")).thenReturn(new ArrayList<>());

        service.execute(o);

        verify(fundLiquidationService).onFillCompleted(o.getId());
    }
}
