package rs.raf.trading.option.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.option.service.OptionMaintenanceService;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

/**
 * P2-money-tx-1 (R3 1587): {@link OptionScheduler} je sad TANAK delegator —
 * stvarna {@code @Transactional} logika je u {@link OptionMaintenanceService}
 * (testirana u {@code OptionMaintenanceServiceTest}). Ovde verifikujemo:
 * (1) scheduler zove sve tri maintenance metode KROZ injektovani bean (proxy →
 * {@code @Transactional} zaista vazi, vise NIJE protected self-invocation NO-OP);
 * (2) fault-isolation — pad jedne faze ne sprecava sledece.
 */
@ExtendWith(MockitoExtension.class)
class OptionSchedulerTest {

    @Mock
    private OptionMaintenanceService maintenanceService;

    @InjectMocks
    private OptionScheduler optionScheduler;

    @Test
    @DisplayName("calls all three maintenance methods in sequence via proxy bean")
    void callsAllThreeMethods() {
        optionScheduler.dailyOptionMaintenance();

        verify(maintenanceService).cleanupExpiredOptions();
        verify(maintenanceService).generateNewOptions();
        verify(maintenanceService).recalculatePrices();
    }

    @Test
    @DisplayName("continues to generate and recalculate even if cleanup throws exception")
    void continuesAfterCleanupException() {
        doThrow(new RuntimeException("Cleanup error")).when(maintenanceService).cleanupExpiredOptions();

        assertThatNoException().isThrownBy(() -> optionScheduler.dailyOptionMaintenance());

        verify(maintenanceService).generateNewOptions();
        verify(maintenanceService).recalculatePrices();
    }

    @Test
    @DisplayName("continues to recalculate even if generation throws exception")
    void continuesAfterGenerationException() {
        doThrow(new RuntimeException("Generation error")).when(maintenanceService).generateNewOptions();

        assertThatNoException().isThrownBy(() -> optionScheduler.dailyOptionMaintenance());

        verify(maintenanceService).recalculatePrices();
    }

    @Test
    @DisplayName("does not propagate exception from recalculation")
    void doesNotPropagateRecalculationException() {
        doThrow(new RuntimeException("Recalc error")).when(maintenanceService).recalculatePrices();

        assertThatNoException().isThrownBy(() -> optionScheduler.dailyOptionMaintenance());
    }
}
