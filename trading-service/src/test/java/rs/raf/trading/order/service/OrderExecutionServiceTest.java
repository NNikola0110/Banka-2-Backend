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
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P2-3: Unit testovi za {@link OrderExecutionService} kao ORKESTRATORA tick-a.
 *
 * <p>Posle izdvajanja per-order fill logike u {@link SingleOrderExecutor}
 * (REQUIRES_NEW tx po orderu), ovaj test proverava SAMO orkestraciju:
 * <ul>
 *   <li>delegaciju na {@code singleOrderExecutor.execute(order)} za svaki
 *       izvrsivi order;</li>
 *   <li>per-order izolaciju — kada jedan order baci tokom obrade, petlja
 *       nastavlja i obradjuje sledeci order, i {@code executeOrders()} NE
 *       propagira {@code UnexpectedRollbackException} ni bilo koju gresku;</li>
 *   <li>eligibility filtere (samo MARKET/LIMIT, fill interval guard).</li>
 * </ul>
 * Sam fill flow je testiran u {@link SingleOrderExecutorTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderExecutionServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private SingleOrderExecutor singleOrderExecutor;
    @Mock private io.micrometer.core.instrument.Timer orderExecutionTimer;

    @InjectMocks
    private OrderExecutionService orderExecutionService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(orderExecutionService, "initialDelaySeconds", 0L);
        ReflectionTestUtils.setField(orderExecutionService, "afterHoursDelaySeconds", 0L);
    }

    private Listing listing() {
        Listing l = new Listing();
        l.setId(1L);
        l.setTicker("AAPL");
        l.setName("Apple Inc");
        l.setAsk(new BigDecimal("100.00"));
        l.setBid(new BigDecimal("95.00"));
        l.setVolume(10000L);
        l.setListingType(ListingType.STOCK);
        return l;
    }

    private Order order(long id) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(1L);
        o.setListing(listing());
        o.setAccountId(1L);
        o.setReservedAccountId(1L);
        o.setQuantity(10);
        o.setRemainingPortions(10);
        o.setContractSize(1);
        o.setUserRole("CLIENT");
        o.setStatus(OrderStatus.APPROVED);
        o.setOrderType(OrderType.MARKET);
        o.setDirection(OrderDirection.BUY);
        return o;
    }

    @Test
    @DisplayName("delegira svaki izvrsivi order na SingleOrderExecutor (REQUIRES_NEW)")
    void delegatesEachOrderToSingleExecutor() {
        Order o1 = order(100L);
        Order o2 = order(101L);
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(o1, o2));

        orderExecutionService.executeOrders();

        verify(singleOrderExecutor).execute(o1);
        verify(singleOrderExecutor).execute(o2);
    }

    @Test
    @DisplayName("P2-3: jedan order baci -> izoluje se, drugi order se i dalje obradi, bez propagacije")
    void oneOrderFailing_doesNotPoisonTick() {
        Order failing = order(100L);
        Order succeeding = order(101L);
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(failing, succeeding));

        // Prvi order baca tokom obrade (simulira settlement fail / rollback-only),
        // drugi order prolazi. Posto je svaki u svojoj REQUIRES_NEW tx, greska
        // prvog se NE sme propagirati niti spreciti obradu drugog.
        doThrow(new org.springframework.dao.OptimisticLockingFailureException("boom"))
                .when(singleOrderExecutor).execute(failing);
        // succeeding execute je no-op (mock).

        assertDoesNotThrow(() -> orderExecutionService.executeOrders());

        // Oba ordera su pokusana — neuspeli nije sprecio uspesni.
        verify(singleOrderExecutor).execute(failing);
        verify(singleOrderExecutor).execute(succeeding);
    }

    @Test
    @DisplayName("filtrira ne-MARKET/LIMIT ordere (STOP se ne izvrsava ovde)")
    void filtersOutNonMarketLimitOrders() {
        Order stop = order(100L);
        stop.setOrderType(OrderType.STOP);
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(stop));

        orderExecutionService.executeOrders();

        verify(singleOrderExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("fill interval guard: order sa nextFillAt u buducnosti se preskace")
    void skipsOrderNotYetEligibleByNextFillAt() {
        Order o = order(100L);
        o.setNextFillAt(java.time.LocalDateTime.now().plusMinutes(10));
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(o));

        orderExecutionService.executeOrders();

        verify(singleOrderExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("initial delay guard: order mladji od initialDelay se preskace")
    void skipsOrderWithinInitialDelay() {
        ReflectionTestUtils.setField(orderExecutionService, "initialDelaySeconds", 60L);
        Order o = order(100L);
        o.setApprovedAt(java.time.LocalDateTime.now()); // tek odobren
        o.setCreatedAt(java.time.LocalDateTime.now());
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(o));

        orderExecutionService.executeOrders();

        verify(singleOrderExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("initial delay guard: order stariji od initialDelay se delegira")
    void delegatesOrderAfterInitialDelay() {
        ReflectionTestUtils.setField(orderExecutionService, "initialDelaySeconds", 60L);
        Order o = order(100L);
        o.setApprovedAt(java.time.LocalDateTime.now().minusSeconds(120));
        o.setCreatedAt(java.time.LocalDateTime.now().minusSeconds(120));
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(o));

        orderExecutionService.executeOrders();

        verify(singleOrderExecutor).execute(o);
    }

    @Test
    @DisplayName("after-hours order: u prosirenom delay-u se preskace")
    void skipsAfterHoursOrderWithinExtendedDelay() {
        ReflectionTestUtils.setField(orderExecutionService, "initialDelaySeconds", 60L);
        ReflectionTestUtils.setField(orderExecutionService, "afterHoursDelaySeconds", 60L);
        Order o = order(100L);
        o.setAfterHours(true);
        o.setApprovedAt(java.time.LocalDateTime.now().minusSeconds(80)); // < 120
        o.setCreatedAt(java.time.LocalDateTime.now().minusSeconds(80));
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(o));

        orderExecutionService.executeOrders();

        verify(singleOrderExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("delay guard: oba referenceTime null → delegira (nema skip)")
    void delegatesWhenBothReferenceTimesNull() {
        ReflectionTestUtils.setField(orderExecutionService, "initialDelaySeconds", 60L);
        Order o = order(100L);
        o.setApprovedAt(null);
        o.setCreatedAt(null);
        when(orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED))
                .thenReturn(List.of(o));

        orderExecutionService.executeOrders();

        verify(singleOrderExecutor).execute(o);
    }
}
