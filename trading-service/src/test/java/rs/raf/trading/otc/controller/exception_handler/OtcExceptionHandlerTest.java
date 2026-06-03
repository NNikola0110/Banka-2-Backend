package rs.raf.trading.otc.controller.exception_handler;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.exception.InsufficientHoldingsException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-error-contract-2 R1 1007 — OTC nedovoljno sredstava/hartija je 409 (uskladjeno
 * sa banka-core reserve), ne 400. R1 440 — telo nosi i {@code message} i {@code error}.
 */
class OtcExceptionHandlerTest {

    private final OtcExceptionHandler handler = new OtcExceptionHandler();

    @Test
    void notFound_returns404() {
        ResponseEntity<Map<String, String>> r = handler.handleNotFound(
                new EntityNotFoundException("Ugovor ne postoji"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("message", "Ugovor ne postoji");
        assertThat(r.getBody()).containsEntry("error", "Ugovor ne postoji");
    }

    @Test
    void illegalState_returns409() {
        ResponseEntity<Map<String, String>> r = handler.handleIllegalState(
                new IllegalStateException("Ugovor nije ACTIVE"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // R1 1007 — InsufficientFunds 400→409.
    @Test
    void insufficientFunds_returns409() {
        ResponseEntity<Map<String, String>> r = handler.handleInsufficient(
                new InsufficientFundsException("Nedovoljno sredstava"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("message", "Nedovoljno sredstava");
    }

    @Test
    void insufficientHoldings_returns409() {
        ResponseEntity<Map<String, String>> r = handler.handleInsufficient(
                new InsufficientHoldingsException("Nedovoljno hartija"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
