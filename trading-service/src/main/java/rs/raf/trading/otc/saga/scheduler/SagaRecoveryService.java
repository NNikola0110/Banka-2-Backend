package rs.raf.trading.otc.saga.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import rs.raf.trading.otc.saga.model.SagaLog;
import rs.raf.trading.otc.saga.model.SagaStatus;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;
import rs.raf.trading.otc.saga.service.OtcExerciseSagaOrchestrator;

import java.util.List;

/**
 * <b>W1.9</b> sweep zaglavljenih SAGA instanci (RUNNING/COMPENSATING posle pada)
 * ka terminalnom stanju. Za svaku takvu SAGA poziva {@link
 * OtcExerciseSagaOrchestrator#recover(String)} (koji rekonstruise kontekst i gura
 * je ka COMPENSATED/COMPLETED). Per-saga {@code try/catch} osigurava da jedna
 * problematicna SAGA ne prekine ceo sweep.
 *
 * <p>Poziva ga {@link SagaRecoveryScheduler} periodicno i {@link
 * SagaStartupRecovery} jednom pri boot-u. U testovima se poziva eksplicitno
 * (scheduling je OFF u test profilu preko {@code SchedulingConfig}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaRecoveryService {

    private static final List<SagaStatus> NON_TERMINAL =
            List.of(SagaStatus.RUNNING, SagaStatus.COMPENSATING);

    private final SagaLogRepository sagaLogRepository;
    private final OtcExerciseSagaOrchestrator orchestrator;

    /** Jedan prolaz: nadji sve zaglavljene SAGA i pokusaj recovery svake. */
    public void recoverOnce() {
        List<SagaLog> stuck = sagaLogRepository.findByStatusIn(NON_TERMINAL);
        if (stuck.isEmpty()) {
            return;
        }
        log.info("SAGA recovery sweep: {} zaglavljen(ih) SAGA instanci", stuck.size());
        for (SagaLog saga : stuck) {
            try {
                orchestrator.recover(saga.getSagaId());
            } catch (RuntimeException e) {
                // Jedna problematicna SAGA ne sme da prekine sweep ostalih.
                log.warn("SAGA recovery {} pala u ovom sweep-u (retry sledeci put): {}",
                        saga.getSagaId(), e.toString());
            }
        }
    }
}
