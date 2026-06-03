package rs.raf.trading.otc.saga.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * <b>W1.9</b> jednokratni SAGA crash-recovery sweep pri pokretanju aplikacije
 * (SG-11). Ako je servis pao usred SAGA-e, restart odmah gura sve zaglavljene
 * instance ka terminalnom stanju (umesto da ceka prvi {@link
 * SagaRecoveryScheduler} interval).
 *
 * <p>Recovery greska NE sme da blokira startup — sweep je obavijen
 * {@code try/catch} (scheduler ce ionako retry-ovati u sledecem intervalu).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaStartupRecovery implements ApplicationRunner {

    private final SagaRecoveryService recoveryService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("SAGA startup recovery: pokrecem inicijalni sweep zaglavljenih SAGA instanci");
            recoveryService.recoverOnce();
        } catch (RuntimeException e) {
            // Recovery greska ne sme blokirati boot; scheduler retry-uje kasnije.
            log.warn("SAGA startup recovery sweep pao (scheduler ce retry-ovati): {}", e.toString());
        }
    }
}
