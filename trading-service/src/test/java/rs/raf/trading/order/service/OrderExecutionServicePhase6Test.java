package rs.raf.trading.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
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
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6 testovi {@link SingleOrderExecutor} — adaptacija monolitnog testa
 * (faza 2c, retargetovano u P2-3): BUY rewire na
 * {@code FundReservationService.consumeForBuyFill}, SELL rewire na
 * {@code BankaCoreClient.creditFunds}, zaposleni bez provizije, OrderCompletedEvent.
 *
 * <p>Initial-delay guard i scheduler izolacija su sada orkestratorov posao
 * (pokriveni u {@code OrderExecutionServiceTest}), pa su tu testirani.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderExecutionServicePhase6Test {

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
        ReflectionTestUtils.setField(service, "afterHoursDelaySeconds", 60L);
        ReflectionTestUtils.setField(service, "maxFillIntervalSeconds", 600L);

        listing = new Listing();
        listing.setId(10L);
        listing.setTicker("AAPL");
        listing.setName("Apple Inc");
        listing.setAsk(new BigDecimal("100.00"));
        listing.setBid(new BigDecimal("95.00"));
        listing.setListingType(ListingType.STOCK);
    }

    private Order buyOrder(LocalDateTime approvedAt) {
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
        o.setReservedAmount(new BigDecimal("1200.00"));
        o.setBankaCoreReservationId("res-100");
        o.setApprovedAt(approvedAt);
        o.setCreatedAt(approvedAt);
        // P2-concurrency-locks-1 (R6-1998): execute() re-fetch-uje order pod lockom.
        when(orderRepository.findByIdForUpdate(o.getId())).thenReturn(Optional.of(o));
        return o;
    }

    private Order sellOrder(LocalDateTime approvedAt) {
        Order o = buyOrder(approvedAt);
        o.setId(101L);
        o.setDirection(OrderDirection.SELL);
        // SELL ima drugi id (101) — re-stub-uj re-fetch za njega.
        when(orderRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(o));
        return o;
    }

    // ── BUY rewire ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("execute BUY: poziva consumeForBuyFill sa fill quantity i cenom")
    void execute_clientBuy_callsConsumeForBuyFill_withFillQuantity() {
        Order order = buyOrder(LocalDateTime.now().minusSeconds(120));
        order.setAllOrNone(true); // forsiraj deterministican fill = 10

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        service.execute(order);

        ArgumentCaptor<Integer> qtyCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<BigDecimal> priceCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fundReservationService).consumeForBuyFill(eq(order), qtyCap.capture(),
                priceCap.capture(), any(BigDecimal.class));

        assertThat(qtyCap.getValue()).isEqualTo(10);
        // total = 100 * 10 * 1 = 1000 (exchangeRate null -> ONE)
        assertThat(priceCap.getValue()).isEqualByComparingTo(new BigDecimal("1000.00"));
        // Order je u potpunosti popunjen → rezervacija treba da bude oslobodjena
        verify(fundReservationService, times(1)).releaseForBuy(order);
    }

    // ── SELL rewire ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("execute SELL: poziva creditFunds sa neto prihodom")
    void execute_clientSell_callsCreditFunds_andConsumeForSellFill() {
        Order order = sellOrder(LocalDateTime.now().minusSeconds(120));
        order.setAllOrNone(true);

        Portfolio portfolio = new Portfolio();
        portfolio.setId(5L);
        portfolio.setUserId(42L);
        portfolio.setListingId(10L);
        portfolio.setListingTicker("AAPL");
        portfolio.setListingName("Apple Inc");
        portfolio.setListingType("STOCK");
        portfolio.setQuantity(50);
        portfolio.setReservedQuantity(10);
        portfolio.setAverageBuyPrice(new BigDecimal("80.00"));

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 10L))
                .thenReturn(Optional.of(portfolio));
        when(bankaCoreClient.getAccount(1L)).thenReturn(usdAccount());

        service.execute(order);

        verify(fundReservationService, times(1))
                .consumeForSellFill(eq(order), eq(portfolio), eq(10));

        // Bid = 95, qty = 10 → totalPrice = 950
        // Commission (MARKET client) = min(14% * 950, 7) = 7 → netRevenue = 943
        ArgumentCaptor<CreditFundsRequest> creditCap = ArgumentCaptor.forClass(CreditFundsRequest.class);
        verify(bankaCoreClient).creditFunds(eq("order-101-sell-fill-0"), creditCap.capture());
        assertThat(creditCap.getValue().amount()).isEqualByComparingTo("943.00");
        assertThat(creditCap.getValue().commission()).isEqualByComparingTo("7.00");
        assertThat(creditCap.getValue().accountId()).isEqualTo(1L);
    }

    // ── Zaposleni ne placa proviziju ──────────────────────────────────────────

    @Test
    @DisplayName("execute BUY (EMPLOYEE): commission = 0")
    void execute_employeeBuy_zeroCommission() {
        Order order = buyOrder(LocalDateTime.now().minusSeconds(120));
        order.setUserRole("EMPLOYEE");
        order.setAllOrNone(true);

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        service.execute(order);

        ArgumentCaptor<BigDecimal> priceCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> commCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fundReservationService).consumeForBuyFill(eq(order), eq(10),
                priceCap.capture(), commCap.capture());
        assertThat(priceCap.getValue()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(commCap.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("execute: kompletiranje BUY-a emituje OrderCompletedEvent")
    void execute_buyCompleted_publishesEvent() {
        Order order = buyOrder(LocalDateTime.now().minusSeconds(120));
        order.setAllOrNone(true);

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        service.execute(order);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DONE);
        verify(eventPublisher).publishEvent(any(Object.class));
    }
}
