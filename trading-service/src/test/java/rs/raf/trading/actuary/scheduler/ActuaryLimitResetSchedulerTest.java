package rs.raf.trading.actuary.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.service.AuditLogService;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActuaryLimitResetSchedulerTest {

    @Mock
    private ActuaryInfoRepository actuaryInfoRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ActuaryLimitResetScheduler scheduler;

    @Nested
    @DisplayName("resetDailyLimits")
    class ResetDailyLimits {

        @Test
        @DisplayName("calls resetAllUsedLimits on the repository exactly once")
        void callsResetAllUsedLimitsOnce() {
            when(actuaryInfoRepository.resetAllUsedLimits()).thenReturn(10);

            scheduler.resetDailyLimits();

            verify(actuaryInfoRepository, times(1)).resetAllUsedLimits();
        }

        @Test
        @DisplayName("works when zero actuaries are reset")
        void worksWhenZeroActuariesReset() {
            when(actuaryInfoRepository.resetAllUsedLimits()).thenReturn(0);

            scheduler.resetDailyLimits();

            verify(actuaryInfoRepository, times(1)).resetAllUsedLimits();
        }

        @Test
        @DisplayName("works when many actuaries are reset")
        void worksWhenManyActuariesReset() {
            when(actuaryInfoRepository.resetAllUsedLimits()).thenReturn(200);

            scheduler.resetDailyLimits();

            verify(actuaryInfoRepository, times(1)).resetAllUsedLimits();
        }

        @Test
        @DisplayName("R1 439: dnevni reset emituje USED_LIMIT_RESET_ALL audit (SCHEDULER aktor)")
        void emitsAudit_R1_439() {
            when(actuaryInfoRepository.resetAllUsedLimits()).thenReturn(7);

            scheduler.resetDailyLimits();

            verify(auditLogService).recordAfterCommit(
                    eq(0L), eq("SCHEDULER"),
                    eq(AuditActionType.USED_LIMIT_RESET_ALL),
                    anyString(), eq("ACTUARY"), isNull());
        }

        @Test
        @DisplayName("does not propagate exception from repository")
        void doesNotPropagateException() {
            when(actuaryInfoRepository.resetAllUsedLimits())
                    .thenThrow(new RuntimeException("DB error"));

            assertThatNoException().isThrownBy(() -> scheduler.resetDailyLimits());

            verify(actuaryInfoRepository, times(1)).resetAllUsedLimits();
        }

        @Test
        @DisplayName("catches all exception subtypes")
        void catchesAllExceptionSubtypes() {
            when(actuaryInfoRepository.resetAllUsedLimits())
                    .thenThrow(new IllegalStateException("illegal state"));

            assertThatNoException().isThrownBy(() -> scheduler.resetDailyLimits());
        }
    }
}
