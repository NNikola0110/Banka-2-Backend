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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test {@link OrderExecutionService} — money-seam adaptacija monolitnog testa
 * (faza 2c). Monolit je proveravao da se {@code Account} balansi menjaju i da
 * se pisu {@code Transaction} redovi; trading-service verzija delegira
 * novcanu nogu banka-core seam-u — BUY fill ide kroz
 * {@code FundReservationService.consumeForBuyFill}, SELL prihod kroz
 * {@code BankaCoreClient.creditFunds}. Asercija se pomera na verifikaciju tih
 * poziva. Fill engine logika (partial fills, AON, settlement decline) je
 * trading-service posao i ostaje verno testirana.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderExecutionServiceTest {
    @Mock private OrderRepository orderRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private AonValidationService aonValidationService;
    @Mock private FundReservationService fundReservationService;
    @Mock private FundLiquidationService fundLiquidationService;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderExecutionService orderExecutionService;

    private Order testOrder;
    private Listing testListing;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(orderExecutionService, "initialDelaySeconds", 0L);
        ReflectionTestUtils.setField(orderExecutionService, "afterHoursDelaySeconds", 0L);
        ReflectionTestUtils.setField(orderExecutionService, "maxFillIntervalSeconds", 600L);

        testListing = new Listing();
        testListing.setId(1L);
        testListing.setTicker("AAPL");
        testListing.setName("Apple Inc");
        testListing.setAsk(new BigDecimal("100.00"));
        testListing.setBid(new BigDecimal("95.00"));
        testListing.setVolume(10000L);
        testListing.setListingType(ListingType.STOCK);

        testOrder = new Order();
        testOrder.setId(100L);
        testOrder.setUserId(1L);
        testOrder.setListing(testListing);
        testOrder.setAccountId(1L);
        testOrder.setReservedAccountId(1L);
        testOrder.setQuantity(10);
        testOrder.setRemainingPortions(10);
        testOrder.setContractSize(1);
        testOrder.setUserRole("CLIENT");
        testOrder.setStatus(OrderStatus.APPROVED);
    }

    @Test
    @DisplayName("1. Market Buy - uspesno izvrsavanje preko banka-core commit seam-a")
    void testExecuteMarketBuy() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(testOrder));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        orderExecutionService.executeOrders();

        // FundReservationService je pozvan za BUY fill (banka-core commit seam)
        verify(fundReservationService, atLeastOnce())
                .consumeForBuyFill(eq(testOrder), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
        verify(orderRepository, atLeastOnce()).save(testOrder);
    }

    @Test
    @DisplayName("2. Limit Sell - ne izvrsava se ako je cena na berzi preniska")
    void testLimitSell_PriceTooLow() {
        testOrder.setOrderType(OrderType.LIMIT);
        testOrder.setDirection(OrderDirection.SELL);
        testOrder.setLimitValue(new BigDecimal("110.00")); // Zelim 110, a Bid je 95

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(testOrder));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));

        orderExecutionService.executeOrders();

        assertFalse(testOrder.isDone());
        verify(bankaCoreClient, never()).creditFunds(anyString(), any());
        verify(fundReservationService, never()).consumeForSellFill(any(), any(), anyInt());
    }

    @Test
    @DisplayName("3. All-Or-None (AON) - ne izvrsava se ako nije pun fill")
    void testAonOrder_NoPartialFill() {
        testOrder.setAllOrNone(true);
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(testOrder));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(eq(testOrder), anyInt())).thenReturn(false);

        orderExecutionService.executeOrders();

        assertEquals(10, testOrder.getRemainingPortions());
        assertFalse(testOrder.isDone());
    }

    @Test
    @DisplayName("4. Portfolio Update - provera kreiranja novog portfolija na prvom BUY")
    void testPortfolioCreationOnFirstBuy() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(testOrder));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(portfolioRepository.findByUserIdAndUserRole(any(), any())).thenReturn(List.of()); // Prazan portfolio
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        orderExecutionService.executeOrders();

        verify(portfolioRepository, atLeastOnce()).save(any(Portfolio.class));
    }

    @Test
    @DisplayName("5. Employee Role - BUY fill provizija je nula")
    void testCommissionForEmployeeIsZero() {
        testOrder.setUserRole("EMPLOYEE");
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testOrder.setAllOrNone(true); // deterministican fill = 10

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(testOrder));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        orderExecutionService.executeOrders();

        // commission za zaposlene = 0 → consumeForBuyFill sa nultom proviziom
        var commCap = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        verify(fundReservationService).consumeForBuyFill(eq(testOrder), eq(10),
                any(BigDecimal.class), commCap.capture());
        assertEquals(0, commCap.getValue().compareTo(BigDecimal.ZERO));
    }
}
