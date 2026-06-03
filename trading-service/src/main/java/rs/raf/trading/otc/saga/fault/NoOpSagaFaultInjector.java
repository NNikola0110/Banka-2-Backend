package rs.raf.trading.otc.saga.fault;

import org.springframework.stereotype.Component;
import rs.raf.trading.otc.saga.model.SagaPhase;

/** Default bean; HeaderSagaFaultInjector @Primary overrides it under chaos profile. */
@Component
public class NoOpSagaFaultInjector implements SagaFaultInjector {
    @Override public void maybeFailForward(SagaPhase phase, String kind) { }
    @Override public void maybeFailCompensator(String sagaId, SagaPhase phase) { }
    @Override public void maybeDelay(SagaPhase phase) { }
}
