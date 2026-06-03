package rs.raf.trading.otc.saga.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>W1.9</b> periodicni okidac SAGA crash-recovery sweep-a. Interval je
 * konfigurabilan ({@code saga.recovery.interval-ms}, default 30s).
 *
 * <p>Globalno gejtovano postojecim {@link rs.raf.trading.config.SchedulingConfig}
 * ({@code @ConditionalOnProperty trading.scheduling.enabled}, OFF u test profilu)
 * — pa se {@code tick()} NE okida automatski u testovima; testovi pozivaju
 * {@link SagaRecoveryService#recoverOnce()} eksplicitno. Bean se i dalje
 * registruje (drzi metodu), samo {@code @Scheduled} ostaje inertan bez
 * {@code @EnableScheduling}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaRecoveryScheduler {

    private final SagaRecoveryService recoveryService;

    @Scheduled(fixedDelayString = "${saga.recovery.interval-ms:30000}")
    public void tick() {
        recoveryService.recoverOnce();
    }
}
