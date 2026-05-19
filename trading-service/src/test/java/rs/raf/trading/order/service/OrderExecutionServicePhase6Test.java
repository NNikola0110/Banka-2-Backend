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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6 testovi {@link OrderExecutionService} — adaptacija monolitnog testa
 * (faza 2c): verifikuju initial delay guard, BUY rewire na
 * {@code FundReservationService.consumeForBuyFill}, SELL rewire na
 * {@code BankaCoreClient.creditFunds}, i scheduler izolaciju (jedan failing
 * order ne sprecava ostale).
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
        ReflectionTestUtils.setField(service, "initialDelaySeconds", 60L);
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
        return o;
    }

    private Order sellOrder(LocalDateTime approvedAt) {
        Order o = buyOrder(approvedAt);
        o.setId(101L);
        o.setDirection(OrderDirection.SELL);
        return o;
    }

    // ── 1. Initial delay guard ────────────────────────────────────────────────

    @Test
    @DisplayName("executeApprovedOrders: order mladji od initialDelay se preskace")
    void executeApprovedOrders_skipsOrdersWithinInitialDelay() {
        Order fresh = buyOrder(LocalDateTime.now());
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(fresh));

        service.executeOrders();

        verify(listingRepository, never()).findById(any());
        verify(fundReservationService, never()).consumeForBuyFill(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("executeApprovedOrders: order stariji od initialDelay se izvrsava")
    void executeApprovedOrders_executesAfterInitialDelay() {
        Order stale = buyOrder(LocalDateTime.now().minusSeconds(65));
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(stale));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        service.executeOrders();

        verify(listingRepository, times(1)).findById(10L);
        verify(fundReservationService, times(1))
                .consumeForBuyFill(eq(stale), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
    }

    // ── 2. BUY rewire ────────────────────────────────────────────────────────

    @Test
    @DisplayName("executeSingleOrder BUY: poziva consumeForBuyFill sa fill quantity i cenom")
    void executeSingleOrder_clientBuy_callsConsumeForBuyFill_withFillQuantity() {
        Order order = buyOrder(LocalDateTime.now().minusSeconds(120));
        order.setAllOrNone(true); // forsiraj deterministican fill = 10

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(order));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        service.executeOrders();

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

    // ── 3. SELL rewire ───────────────────────────────────────────────────────

    @Test
    @DisplayName("executeSingleOrder SELL: poziva creditFunds sa neto prihodom")
    void executeSingleOrder_clientSell_callsCreditFunds_andConsumeForSellFill() {
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

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(order));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT"))
                .thenReturn(new ArrayList<>(List.of(portfolio)));
        when(bankaCoreClient.getAccount(1L)).thenReturn(usdAccount());

        service.executeOrders();

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

    // ── 4. Scheduler isolation ───────────────────────────────────────────────

    @Test
    @DisplayName("executeApprovedOrders: jedan failing order ne sprecava ostale")
    void executeApprovedOrders_continuesOtherOrders_whenOneFails() {
        Order bad = buyOrder(LocalDateTime.now().minusSeconds(120));
        bad.setId(200L);
        Order good = buyOrder(LocalDateTime.now().minusSeconds(120));
        good.setId(201L);
        good.setAllOrNone(true);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(bad, good));
        when(listingRepository.findById(10L))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        service.executeOrders();

        verify(fundReservationService, times(1))
                .consumeForBuyFill(eq(good), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
    }

    // ── 5. Zaposleni ne placa proviziju ──────────────────────────────────────

    @Test
    @DisplayName("executeSingleOrder BUY (EMPLOYEE): commission = 0")
    void executeSingleOrder_employeeBuy_zeroCommission() {
        Order order = buyOrder(LocalDateTime.now().minusSeconds(120));
        order.setUserRole("EMPLOYEE");
        order.setAllOrNone(true);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(order));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        service.executeOrders();

        ArgumentCaptor<BigDecimal> priceCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> commCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(fundReservationService).consumeForBuyFill(eq(order), eq(10),
                priceCap.capture(), commCap.capture());
        assertThat(priceCap.getValue()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(commCap.getValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("executeSingleOrder: kompletiranje BUY-a emituje OrderCompletedEvent")
    void executeSingleOrder_buyCompleted_publishesEvent() {
        Order order = buyOrder(LocalDateTime.now().minusSeconds(120));
        order.setAllOrNone(true);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(order));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        service.executeOrders();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DONE);
        verify(eventPublisher).publishEvent(any(Object.class));
    }
}
