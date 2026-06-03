package rs.raf.trading.order.controller.exception_handler;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test {@link OrderExceptionHandler} — porten verbatim iz monolita (faza 2c,
 * samo package rename).
 */
class OrderExceptionHandlerTest {

    private OrderExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OrderExceptionHandler();
    }

    @Test
    void handleNotFound_returnsNotFound() {
        EntityNotFoundException ex = new EntityNotFoundException("Order not found");

        ResponseEntity<Map<String, String>> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "Order not found");
    }

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid order type");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Invalid order type");
    }

    @Test
    void handleIllegalState_returnsForbidden() {
        IllegalStateException ex = new IllegalStateException("Order already processed");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Order already processed");
    }

    // ── P2-error-contract-2 ──────────────────────────────────────────────

    // R1 410 — state-conflict (npr. approve nad ne-PENDING) je 409, ne 403.
    @Test
    void handleStateConflict_returns409_withMessageAndError() {
        rs.raf.trading.order.exception.OrderStateConflictException ex =
                new rs.raf.trading.order.exception.OrderStateConflictException(
                        "Only PENDING orders can be approved");

        ResponseEntity<Map<String, String>> response = handler.handleStateConflict(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // FE/Mobile citaju message-first; legacy error zadrzan.
        assertThat(response.getBody()).containsEntry("message", "Only PENDING orders can be approved");
        assertThat(response.getBody()).containsEntry("error", "Only PENDING orders can be approved");
    }

    // R1 409 — nedovoljno sredstava/hartija je 409, ne 400.
    @Test
    void handleInsufficientFunds_returns409() {
        ResponseEntity<Map<String, String>> response = handler.handleInsufficient(
                new rs.raf.trading.order.exception.InsufficientFundsException("Nedovoljno sredstava"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "Nedovoljno sredstava");
    }

    @Test
    void handleInsufficientHoldings_returns409() {
        ResponseEntity<Map<String, String>> response = handler.handleInsufficient(
                new rs.raf.trading.order.exception.InsufficientHoldingsException("Nedovoljno hartija"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "Nedovoljno hartija");
    }
}
