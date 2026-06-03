package rs.raf.trading.otc.saga.service;

import rs.raf.trading.otc.saga.model.SagaStatus;

/**
 * Handle koji orchestrator vraca pozivaocu (controller-u) posle pokretanja
 * SAGA-e: jedinstveni {@code sagaId} za polling preko {@code GET /otc/saga/{id}},
 * finalni {@link SagaStatus} (COMPLETED / COMPENSATED / FAILED) i ordinal
 * poslednje pokusane forward faze.
 */
public record SagaResult(String sagaId, SagaStatus status, int currentStep) {
}
