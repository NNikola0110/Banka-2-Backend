package rs.raf.banka2_bek.savings.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-error-contract-2 R1 356 — savings {@code IllegalStateException} je state-conflict
 * → 409 Conflict, NE 500.
 */
class SavingsExceptionHandlerTest {

    private final SavingsExceptionHandler handler = new SavingsExceptionHandler();

    @Test
    void notFound_returns404() {
        ResponseEntity<Map<String, String>> r = handler.handleNotFound(
                new SavingsDepositNotFoundException("Depozit ne postoji"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("message", "Depozit ne postoji");
    }

    @Test
    void illegalArgument_returns400() {
        ResponseEntity<Map<String, String>> r = handler.handleBadRequest(
                new IllegalArgumentException("Nevalidan iznos"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // R1 356 — raskid pre dospeca / vec raskinut = 409, ne 500.
    @Test
    void illegalState_returns409_notInternalServerError() {
        ResponseEntity<Map<String, String>> r = handler.handleConflict(
                new IllegalStateException("Depozit je vec raskinut"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("message", "Depozit je vec raskinut");
    }

    // R1-665 / R1-667 — @Version concurrent update (npr. toggleAutoRenew vs maturity cron)
    // je 409 Conflict, NE 500.
    @Test
    void springOptimisticLock_returns409() {
        ResponseEntity<Map<String, String>> r = handler.handleOptimisticLock(
                new org.springframework.dao.OptimisticLockingFailureException("stale version"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsKey("message");
    }

    @Test
    void jpaOptimisticLock_returns409() {
        ResponseEntity<Map<String, String>> r = handler.handleOptimisticLock(
                new jakarta.persistence.OptimisticLockException("stale version"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
