package rs.raf.trading.otc.saga.web;

import rs.raf.trading.otc.saga.model.SagaLogEntry;

import java.util.List;

/**
 * Odgovor na {@code GET /otc/saga/{sagaId}} — stanje SAGA instance za polling
 * (SAGA_test.pdf Model-B poll model).
 *
 * <p>{@code log} su {@link SagaLogEntry} zapisi (jedan po pokusanom forward/
 * kompenzacionom koraku) iz perzistovanog {@code SagaLog}-a.
 */
public record SagaStatusDto(String sagaId, String status, int currentStep,
                            List<SagaLogEntry> log) {
}
