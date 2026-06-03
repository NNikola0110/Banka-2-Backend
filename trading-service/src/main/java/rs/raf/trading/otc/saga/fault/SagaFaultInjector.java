package rs.raf.trading.otc.saga.fault;

import rs.raf.trading.otc.saga.model.SagaPhase;

public interface SagaFaultInjector {
    /** Throws SagaFaultException if X-Saga-Force-Fail names this phase with the given kind (before|after). */
    void maybeFailForward(SagaPhase phase, String kind); // kind = "before" | "after"
    /**
     * Throws SagaFaultException if X-Saga-Force-Fail-Mid names this phase — a MID-phase fault hook
     * invoked between distinct side-effect legs of a forward phase (e.g. F4: after the seller
     * decrement is committed, BEFORE the buyer credit). Used to drive partial-forward-failure
     * compensation tests (P0-1). Default no-op so non-chaos / older injectors stay inert.
     */
    default void maybeFailForwardMid(SagaPhase phase) { }
    /** Throws if X-Saga-Compensate-Fail names C{phase} and the per-saga countdown is not yet exhausted. */
    void maybeFailCompensator(String sagaId, SagaPhase phase);
    /** Sleeps if X-Saga-Inject-Delay names this phase. */
    void maybeDelay(SagaPhase phase);
}
