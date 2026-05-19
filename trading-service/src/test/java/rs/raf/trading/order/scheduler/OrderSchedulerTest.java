package rs.raf.trading.order.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.order.service.OrderExecutionService;
import rs.raf.trading.order.service.StopOrderActivationService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/**
 * Test {@link OrderScheduler} — porten verbatim iz monolita (faza 2c, samo
 * package rename). Scheduler je inertan (TradingServiceApplication nema
 * @EnableScheduling), ali metode su i dalje pozive-vredne i testabilne.
 */
@ExtendWith(MockitoExtension.class)
public class OrderSchedulerTest {

    @Mock
    private StopOrderActivationService stopOrderActivationService;

    @Mock
    private OrderExecutionService orderExecutionService;

    @InjectMocks
    private OrderScheduler orderScheduler;

    @Test
    void processStopOrders_shouldCallService() {
        orderScheduler.processStopOrders();
        verify(stopOrderActivationService, times(1)).checkAndActivateStopOrders();
    }

    @Test
    void processStopOrders_shouldLogError_whenExceptionThrown() {
        doThrow(new RuntimeException("test error"))
                .when(stopOrderActivationService).checkAndActivateStopOrders();

        assertDoesNotThrow(() -> orderScheduler.processStopOrders());
        verify(stopOrderActivationService, times(1)).checkAndActivateStopOrders();
    }

    @Test
    void executeApprovedOrders_shouldCallService() {
        orderScheduler.executeApprovedOrders();
        verify(orderExecutionService, times(1)).executeOrders();
    }

    @Test
    void executeApprovedOrders_shouldLogError_whenExceptionThrown() {
        doThrow(new RuntimeException("test error"))
                .when(orderExecutionService).executeOrders();

        assertDoesNotThrow(() -> orderScheduler.executeApprovedOrders());
        verify(orderExecutionService, times(1)).executeOrders();
    }
}
