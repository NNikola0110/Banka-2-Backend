package rs.raf.trading.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test {@link StopOrderActivationService} — porten verbatim iz monolita
 * (faza 2c, samo package rename). {@code Order}/{@code Listing} su lokalni
 * trading-service entiteti, repozitorijumi se mockuju.
 */
@ExtendWith(MockitoExtension.class)
class StopOrderActivationServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ListingRepository listingRepository;

    @InjectMocks
    private StopOrderActivationService stopOrderActivationService;

    @Test
    void testCheckAndActivate_StopSell_Success() {
        Listing stock = new Listing();
        stock.setId(2L);
        stock.setPrice(new BigDecimal("95.00"));

        Order order = new Order();
        order.setId(20L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.SELL);
        order.setStopValue(new BigDecimal("100.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(2L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.MARKET, order.getOrderType());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void testCheckAndActivate_StopLimitBuy_ToLimit() {
        Listing stock = new Listing();
        stock.setId(3L);
        stock.setPrice(new BigDecimal("210.00"));

        Order order = new Order();
        order.setId(30L);
        order.setOrderType(OrderType.STOP_LIMIT);
        order.setDirection(OrderDirection.BUY);
        order.setStopValue(new BigDecimal("200.00"));
        order.setLimitValue(new BigDecimal("205.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(3L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.LIMIT, order.getOrderType());
        assertEquals(new BigDecimal("205.00"), order.getPricePerUnit());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void testCheckAndActivate_ConditionNotMet_NoAction() {
        Listing stock = new Listing();
        stock.setId(4L);
        stock.setPrice(new BigDecimal("140.00"));

        Order order = new Order();
        order.setId(40L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.BUY);
        order.setStopValue(new BigDecimal("150.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(4L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.STOP, order.getOrderType());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void testCheckAndActivate_ListingNotFound_Skip() {
        Order order = new Order();
        order.setId(50L);
        order.setListing(new Listing());
        order.getListing().setId(999L);
        order.setOrderType(OrderType.STOP);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(999L)).thenReturn(Optional.empty());

        stopOrderActivationService.checkAndActivateStopOrders();

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void testCheckAndActivate_StopBuy_PriceExceedsStop_ActivatesToMarket() {
        Listing stock = new Listing();
        stock.setId(5L);
        stock.setPrice(new BigDecimal("160.00"));

        Order order = new Order();
        order.setId(60L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.BUY);
        order.setStopValue(new BigDecimal("150.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(5L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.MARKET, order.getOrderType());
        assertEquals(new BigDecimal("160.00"), order.getPricePerUnit());
        assertNotNull(order.getLastModification());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void testCheckAndActivate_StopBuy_PriceEqualsStop_Activates() {
        Listing stock = new Listing();
        stock.setId(6L);
        stock.setPrice(new BigDecimal("150.00"));

        Order order = new Order();
        order.setId(70L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.BUY);
        order.setStopValue(new BigDecimal("150.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(6L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.MARKET, order.getOrderType());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void testCheckAndActivate_StopLimitSell_ActivatesToLimit() {
        Listing stock = new Listing();
        stock.setId(7L);
        stock.setPrice(new BigDecimal("80.00"));

        Order order = new Order();
        order.setId(80L);
        order.setOrderType(OrderType.STOP_LIMIT);
        order.setDirection(OrderDirection.SELL);
        order.setStopValue(new BigDecimal("90.00"));
        order.setLimitValue(new BigDecimal("85.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(7L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.LIMIT, order.getOrderType());
        assertEquals(new BigDecimal("85.00"), order.getPricePerUnit());
        assertNotNull(order.getLastModification());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void testCheckAndActivate_ListingPriceNull_Skip() {
        Listing stock = new Listing();
        stock.setId(8L);
        stock.setPrice(null);

        Order order = new Order();
        order.setId(90L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.BUY);
        order.setStopValue(new BigDecimal("100.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(8L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.STOP, order.getOrderType());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void testCheckAndActivate_ListingPriceZero_Skip() {
        Listing stock = new Listing();
        stock.setId(9L);
        stock.setPrice(BigDecimal.ZERO);

        Order order = new Order();
        order.setId(100L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.SELL);
        order.setStopValue(new BigDecimal("50.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(9L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.STOP, order.getOrderType());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void testCheckAndActivate_ListingPriceNegative_Skip() {
        Listing stock = new Listing();
        stock.setId(10L);
        stock.setPrice(new BigDecimal("-5.00"));

        Order order = new Order();
        order.setId(110L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.BUY);
        order.setStopValue(new BigDecimal("100.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.STOP, order.getOrderType());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void testCheckAndActivate_NullStopValue_Skip() {
        Listing stock = new Listing();
        stock.setId(11L);
        stock.setPrice(new BigDecimal("200.00"));

        Order order = new Order();
        order.setId(120L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.BUY);
        order.setStopValue(null);
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(11L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.STOP, order.getOrderType());
        verify(orderRepository, never()).save(any(Order.class));
    }

    // ── P1-dividends-order-1 (1321 / §329-331,358-360): trigger po ask/bid, ne po last price ──

    @Test
    void stopBuy_triggersOnAskNotLastPrice() {
        // Spec §329: "Order se izvrsava kada ASK postane veci od stop vrednosti".
        // Last price je ispod stopValue (ne bi okinuo), ali ASK je iznad -> mora okinuti.
        Listing stock = new Listing();
        stock.setId(20L);
        stock.setPrice(new BigDecimal("145.00")); // last price ispod stopa
        stock.setAsk(new BigDecimal("152.00"));   // ask iznad stopa -> trigger
        stock.setBid(new BigDecimal("144.00"));

        Order order = new Order();
        order.setId(200L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.BUY);
        order.setStopValue(new BigDecimal("150.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(20L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.MARKET, order.getOrderType());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void stopBuy_askBelowStop_doesNotTrigger_evenIfLastPriceAbove() {
        // Last price iznad stopa, ali ASK (po kome zapravo kupujemo) ispod -> NE okida.
        Listing stock = new Listing();
        stock.setId(21L);
        stock.setPrice(new BigDecimal("155.00")); // last price iznad stopa
        stock.setAsk(new BigDecimal("148.00"));   // ask ispod stopa -> NEMA trigger
        stock.setBid(new BigDecimal("147.00"));

        Order order = new Order();
        order.setId(201L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.BUY);
        order.setStopValue(new BigDecimal("150.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(21L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.STOP, order.getOrderType());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void stopSell_triggersOnBidNotLastPrice() {
        // Spec §331: "Order se izvrsava kada BID postane manje od stop vrednosti".
        // Last price iznad stopa, ali BID ispod -> mora okinuti.
        Listing stock = new Listing();
        stock.setId(22L);
        stock.setPrice(new BigDecimal("95.00")); // last price iznad stopa
        stock.setAsk(new BigDecimal("96.00"));
        stock.setBid(new BigDecimal("88.00"));   // bid ispod stopa -> trigger

        Order order = new Order();
        order.setId(202L);
        order.setOrderType(OrderType.STOP);
        order.setDirection(OrderDirection.SELL);
        order.setStopValue(new BigDecimal("90.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(22L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.MARKET, order.getOrderType());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void stopLimitBuy_triggersOnAskNotLastPrice() {
        // Spec §358: "Kada trzisna ASK cena dostigne ili predje Stop vrednost -> Buy Limit".
        Listing stock = new Listing();
        stock.setId(23L);
        stock.setPrice(new BigDecimal("195.00")); // last price ispod stopa
        stock.setAsk(new BigDecimal("205.00"));   // ask iznad stopa -> trigger
        stock.setBid(new BigDecimal("194.00"));

        Order order = new Order();
        order.setId(203L);
        order.setOrderType(OrderType.STOP_LIMIT);
        order.setDirection(OrderDirection.BUY);
        order.setStopValue(new BigDecimal("200.00"));
        order.setLimitValue(new BigDecimal("206.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(23L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.LIMIT, order.getOrderType());
        assertEquals(new BigDecimal("206.00"), order.getPricePerUnit());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void stopLimitSell_triggersOnBidNotLastPrice() {
        // Spec §360: "Kada trzisna BID cena padne ispod Stop vrednosti -> Sell Limit".
        Listing stock = new Listing();
        stock.setId(24L);
        stock.setPrice(new BigDecimal("95.00")); // last price iznad stopa
        stock.setAsk(new BigDecimal("96.00"));
        stock.setBid(new BigDecimal("85.00"));   // bid ispod stopa -> trigger

        Order order = new Order();
        order.setId(204L);
        order.setOrderType(OrderType.STOP_LIMIT);
        order.setDirection(OrderDirection.SELL);
        order.setStopValue(new BigDecimal("90.00"));
        order.setLimitValue(new BigDecimal("84.00"));
        order.setListing(stock);
        order.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED)).thenReturn(List.of(order));
        when(listingRepository.findById(24L)).thenReturn(Optional.of(stock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.LIMIT, order.getOrderType());
        assertEquals(new BigDecimal("84.00"), order.getPricePerUnit());
        verify(orderRepository, times(1)).save(order);
    }

    @Test
    void testCheckAndActivate_NoStopOrders_NoAction() {
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(Collections.emptyList());

        stopOrderActivationService.checkAndActivateStopOrders();

        verify(listingRepository, never()).findById(anyLong());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void testCheckAndActivate_NonStopOrdersFiltered() {
        Order marketOrder = new Order();
        marketOrder.setId(130L);
        marketOrder.setOrderType(OrderType.MARKET);
        marketOrder.setDirection(OrderDirection.BUY);
        marketOrder.setStatus(OrderStatus.APPROVED);

        Order limitOrder = new Order();
        limitOrder.setId(131L);
        limitOrder.setOrderType(OrderType.LIMIT);
        limitOrder.setDirection(OrderDirection.SELL);
        limitOrder.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(marketOrder, limitOrder));

        stopOrderActivationService.checkAndActivateStopOrders();

        verify(listingRepository, never()).findById(anyLong());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void testCheckAndActivate_MultipleOrders_OnlyMatchingActivated() {
        Listing stock1 = new Listing();
        stock1.setId(14L);
        stock1.setPrice(new BigDecimal("200.00"));

        Listing stock2 = new Listing();
        stock2.setId(15L);
        stock2.setPrice(new BigDecimal("50.00"));

        Order shouldActivate = new Order();
        shouldActivate.setId(140L);
        shouldActivate.setOrderType(OrderType.STOP);
        shouldActivate.setDirection(OrderDirection.BUY);
        shouldActivate.setStopValue(new BigDecimal("150.00"));
        shouldActivate.setListing(stock1);
        shouldActivate.setStatus(OrderStatus.APPROVED);

        Order shouldNotActivate = new Order();
        shouldNotActivate.setId(141L);
        shouldNotActivate.setOrderType(OrderType.STOP);
        shouldNotActivate.setDirection(OrderDirection.BUY);
        shouldNotActivate.setStopValue(new BigDecimal("100.00"));
        shouldNotActivate.setListing(stock2);
        shouldNotActivate.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(shouldActivate, shouldNotActivate));
        when(listingRepository.findById(14L)).thenReturn(Optional.of(stock1));
        when(listingRepository.findById(15L)).thenReturn(Optional.of(stock2));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.MARKET, shouldActivate.getOrderType());
        assertEquals(OrderType.STOP, shouldNotActivate.getOrderType());
        verify(orderRepository, times(1)).save(shouldActivate);
        verify(orderRepository, never()).save(shouldNotActivate);
    }

    @Test
    void testCheckAndActivate_ExceptionOnOneOrder_ContinuesProcessing() {
        Listing goodStock = new Listing();
        goodStock.setId(16L);
        goodStock.setPrice(new BigDecimal("200.00"));

        Order badOrder = new Order();
        badOrder.setId(150L);
        badOrder.setOrderType(OrderType.STOP);
        badOrder.setDirection(OrderDirection.BUY);
        badOrder.setStopValue(new BigDecimal("100.00"));
        Listing badListing = new Listing();
        badListing.setId(999L);
        badOrder.setListing(badListing);
        badOrder.setStatus(OrderStatus.APPROVED);

        Order goodOrder = new Order();
        goodOrder.setId(151L);
        goodOrder.setOrderType(OrderType.STOP);
        goodOrder.setDirection(OrderDirection.BUY);
        goodOrder.setStopValue(new BigDecimal("150.00"));
        goodOrder.setListing(goodStock);
        goodOrder.setStatus(OrderStatus.APPROVED);

        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(badOrder, goodOrder));
        when(listingRepository.findById(999L)).thenThrow(new RuntimeException("DB error"));
        when(listingRepository.findById(16L)).thenReturn(Optional.of(goodStock));

        stopOrderActivationService.checkAndActivateStopOrders();

        assertEquals(OrderType.MARKET, goodOrder.getOrderType());
        verify(orderRepository, times(1)).save(goodOrder);
    }
}
