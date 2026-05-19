package rs.raf.trading.order.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.stock.model.Listing;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Test {@link OrderCleanupScheduler} — porten verbatim iz monolita (faza 2c,
 * samo package rename).
 */
@ExtendWith(MockitoExtension.class)
public class OrderCleanupSchedulerTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderCleanupScheduler orderCleanupScheduler;

    @Test
    void cleanupExpiredOrders_shouldDeclineOrderWithPassedSettlementDate() {
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(LocalDate.now().minusDays(1));
        when(listing.getTicker()).thenReturn("CLM24");
        when(order.getListing()).thenReturn(listing);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(order).setStatus(OrderStatus.DECLINED);
        verify(order).setApprovedBy("SYSTEM - Settlement date expired");
        verify(orderRepository).save(order);
    }

    @Test
    void cleanupExpiredOrders_shouldNotDeclineOrderWithFutureSettlement() {
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(LocalDate.now().plusDays(10));
        when(order.getListing()).thenReturn(listing);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(order, never()).setStatus(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cleanupExpiredOrders_shouldSkipOrderWithNullSettlementDate() {
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(null);
        when(order.getListing()).thenReturn(listing);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(order, never()).setStatus(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cleanupExpiredOrders_shouldDoNothing_whenNoCandidates() {
        when(orderRepository.findActiveNonDone()).thenReturn(List.of());

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(orderRepository, never()).save(any());
    }
}
