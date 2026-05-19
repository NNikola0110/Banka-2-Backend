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
import java.time.LocalDateTime;
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
 * Coverage testovi {@link OrderExecutionService} — adaptacija monolitnog testa
 * (faza 2c). Pokrivaju fill engine grane: legacy guard, LIMIT cena guards,
 * settlement auto-decline, after-hours delay, partial fill, AON false,
 * releaseReservationSafe, updatePortfolio blend, remaining=0 early DONE.
 * Monolitne asercije o {@code transactionRepository.save} i
 * "creditBankCommission bank account not found" su izostavljene — banka-core
 * sada pise audit {@code Transaction} i resolve-uje bankin racun (pokriveno
 * banka-core {@code internalapi} testovima).
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

    @InjectMocks
    private OrderExecutionService service;

    private Listing listing;

    private InternalAccountDto usdAccount() {
        return new InternalAccountDto(1L, "111", "Owner",
                new BigDecimal("10000.00"), new BigDecimal("8000.00"), new BigDecimal("2000.00"),
                "USD", "ACTIVE", 42L, null, "CLIENT");
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "initialDelaySeconds", 0L);
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
        return o;
    }

    // ── 1. Legacy guard: null accountId i null reservedAccountId ──────────────
    @Test
    @DisplayName("Legacy guard: order bez ijednog account-a → DECLINED")
    void legacyGuard_nullAccounts_markedDeclined() {
        Order o = baseOrder();
        o.setAccountId(null);
        o.setReservedAccountId(null);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));

        service.executeOrders();

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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        service.executeOrders();

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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT")).thenReturn(new ArrayList<>());

        service.executeOrders();

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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT"))
                .thenReturn(new ArrayList<>(List.of(p)));
        when(bankaCoreClient.getAccount(1L)).thenReturn(usdAccount());

        service.executeOrders();

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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        service.executeOrders();

        verify(fundReservationService, never()).consumeForSellFill(any(), any(), anyInt());
    }

    // ── 6. Auto-decline kad je settlement date u proslosti ────────────────────
    @Test
    @DisplayName("Auto-decline: settlement date protekao → DECLINED + release")
    void executeOrders_settlementExpired_autoDeclined() {
        listing.setSettlementDate(LocalDate.now().minusDays(1));
        Order o = baseOrder();

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));

        service.executeOrders();

        assertThat(o.getStatus()).isEqualTo(OrderStatus.DECLINED);
        assertThat(o.isDone()).isTrue();
        verify(orderRepository).save(o);
        verify(fundReservationService, times(1)).releaseForBuy(o);
        verify(listingRepository, never()).findById(any());
    }

    // ── 7. Auto-decline: release baca exception — swallow ─────────────────────
    @Test
    @DisplayName("Auto-decline: releaseReservationSafe exception je progutan")
    void executeOrders_settlementExpired_releaseThrows_swallowed() {
        listing.setSettlementDate(LocalDate.now().minusDays(5));
        Order o = baseOrder();

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        doThrow(new RuntimeException("release boom"))
                .when(fundReservationService).releaseForBuy(any());

        service.executeOrders();

        assertThat(o.getStatus()).isEqualTo(OrderStatus.DECLINED);
        verify(orderRepository).save(o);
    }

    // ── 8. After-hours order: unutar prosirenog delay-a → preskocen ───────────
    @Test
    @DisplayName("After-hours: order u prosirenom delay-u se preskace")
    void afterHours_withinDelay_skipped() {
        ReflectionTestUtils.setField(service, "initialDelaySeconds", 60L);
        ReflectionTestUtils.setField(service, "afterHoursDelaySeconds", 60L);

        Order o = baseOrder();
        o.setAfterHours(true);
        o.setApprovedAt(LocalDateTime.now().minusSeconds(80)); // < 120

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));

        service.executeOrders();

        verify(listingRepository, never()).findById(any());
    }

    // ── 9. After-hours: proteklo vise od prosirenog delay-a → izvrsava ────────
    @Test
    @DisplayName("After-hours: order izvrsen nakon prosirenog delay-a")
    void afterHours_afterDelay_executes() {
        ReflectionTestUtils.setField(service, "initialDelaySeconds", 60L);
        ReflectionTestUtils.setField(service, "afterHoursDelaySeconds", 60L);

        Order o = baseOrder();
        o.setAfterHours(true);
        o.setApprovedAt(LocalDateTime.now().minusSeconds(130));

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        service.executeOrders();

        verify(fundReservationService, times(1))
                .consumeForBuyFill(eq(o), eq(10), any(BigDecimal.class), any(BigDecimal.class));
    }

    // ── 10. approvedAt == null → fallback na createdAt ───────────────────────
    @Test
    @DisplayName("Delay guard: approvedAt null → koristi createdAt")
    void delayGuard_nullApprovedAt_usesCreatedAt() {
        ReflectionTestUtils.setField(service, "initialDelaySeconds", 60L);

        Order o = baseOrder();
        o.setApprovedAt(null);
        o.setCreatedAt(LocalDateTime.now().minusSeconds(10)); // < 60

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));

        service.executeOrders();

        verify(listingRepository, never()).findById(any());
    }

    // ── 11. Oba null → nema delay skip (prolazi dalje) ───────────────────────
    @Test
    @DisplayName("Delay guard: oba referenceTime null → ne skip")
    void delayGuard_bothTimesNull_proceeds() {
        Order o = baseOrder();
        o.setApprovedAt(null);
        o.setCreatedAt(null);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        service.executeOrders();

        verify(fundReservationService)
                .consumeForBuyFill(eq(o), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
    }

    // ── 12. STOP orderi se filtriraju ────────────────────────────────────────
    @Test
    @DisplayName("Filter: STOP/STOP_LIMIT orderi se ignorisu")
    void filter_stopOrdersIgnored() {
        Order stop = baseOrder();
        stop.setOrderType(OrderType.STOP);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(stop));

        service.executeOrders();

        verify(listingRepository, never()).findById(any());
    }

    // ── 13. AON provera vraca false → ne izvrsava ────────────────────────────
    @Test
    @DisplayName("AON: checkCanExecuteAon vraca false → ne izvrsava fill")
    void aon_checkReturnsFalse_noFill() {
        Order o = baseOrder();

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(false);

        service.executeOrders();

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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT"))
                .thenReturn(new ArrayList<>(List.of(existing)));

        service.executeOrders();

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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT"))
                .thenReturn(new ArrayList<>(List.of(p)));
        when(bankaCoreClient.getAccount(1L)).thenReturn(usdAccount());
        doThrow(new RuntimeException("release sell boom"))
                .when(fundReservationService).releaseForSell(any(), any());

        service.executeOrders();

        assertThat(o.getStatus()).isEqualTo(OrderStatus.DONE);
        assertThat(o.isDone()).isTrue();
    }

    // ── 16. releaseReservationSafe: vec oslobodjeno → no-op ──────────────────
    @Test
    @DisplayName("releaseReservationSafe: reservationReleased=true → no-op")
    void releaseReservationSafe_alreadyReleased_noop() {
        listing.setSettlementDate(LocalDate.now().minusDays(1));
        Order o = baseOrder();
        o.setReservationReleased(true);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));

        service.executeOrders();

        verify(fundReservationService, never()).releaseForBuy(any());
        assertThat(o.getStatus()).isEqualTo(OrderStatus.DECLINED);
    }

    // ── 17. Listing not found → exception → log error i continue ─────────────
    @Test
    @DisplayName("Listing nije pronadjen → exception uhvacen u executeOrders")
    void listingNotFound_exceptionCaughtAndLogged() {
        Order o = baseOrder();

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.empty());

        service.executeOrders();

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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT")).thenReturn(new ArrayList<>());

        service.executeOrders();

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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT"))
                .thenReturn(new ArrayList<>(List.of(p)));
        when(bankaCoreClient.getAccount(1L)).thenReturn(usdAccount());

        service.executeOrders();

        verify(bankaCoreClient).getAccount(1L);
        verify(fundReservationService).consumeForSellFill(eq(o), eq(p), eq(10));
    }

    // ── 20. SELL: portfolio nije pronadjen → IllegalStateException → caught ──
    @Test
    @DisplayName("SELL: portfolio not found → exception caught")
    void sell_portfolioNotFound_caught() {
        Order o = baseOrder();
        o.setDirection(OrderDirection.SELL);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT")).thenReturn(new ArrayList<>());

        service.executeOrders();

        verify(fundReservationService, never()).consumeForSellFill(any(), any(), anyInt());
    }

    // ── 21. remainingPortions = 0 → early DONE return ─────────────────────────
    @Test
    @DisplayName("executeSingleOrder: remainingPortions=0 → DONE + release + save")
    void remainingZero_earlyDone() {
        Order o = baseOrder();
        o.setAllOrNone(false);
        o.setRemainingPortions(0);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        service.executeOrders();

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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT")).thenReturn(new ArrayList<>());

        service.executeOrders();

        assertThat(o.getStatus()).isNotEqualTo(OrderStatus.DECLINED);
        verify(fundReservationService)
                .consumeForBuyFill(eq(o), eq(10), any(BigDecimal.class), any(BigDecimal.class));
    }

    // ── 23. consumeForBuyFill baca exception → uhvacen u executeOrders ───────
    @Test
    @DisplayName("BUY: consumeForBuyFill baca exception → uhvacen u try/catch")
    void buyFill_consumeThrows_caught() {
        Order o = baseOrder();

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        doThrow(new RuntimeException("commit boom"))
                .when(fundReservationService).consumeForBuyFill(any(), anyInt(), any(), any());

        service.executeOrders();

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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(o));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(7L, "FUND")).thenReturn(new ArrayList<>());

        service.executeOrders();

        verify(fundLiquidationService).onFillCompleted(o.getId());
    }
}
