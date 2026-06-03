package rs.raf.trading.actuary.controller.exception_handler;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActuaryExceptionHandlerTest {

    private ActuaryExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ActuaryExceptionHandler();
    }

    @Test
    void handleNotFound_returnsNotFound() {
        EntityNotFoundException ex = new EntityNotFoundException("Actuary not found");

        ResponseEntity<Map<String, String>> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "Actuary not found");
    }

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        // R1-186: IllegalArgumentException je validaciona greska (npr. below-used-limit)
        // → 400 Bad Request, NE 404. Genuini "ne postoji" baca EntityNotFoundException.
        IllegalArgumentException ex = new IllegalArgumentException("New daily limit below used limit");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "New daily limit below used limit");
    }

    @Test
    void handleIllegalState_returnsForbidden() {
        IllegalStateException ex = new IllegalStateException("Limit exceeded");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Limit exceeded");
    }

    // R1 440 — telo nosi i `message` (FE/Mobile-first) i `error` (legacy).
    @Test
    void body_containsBothMessageAndError() {
        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(
                new IllegalArgumentException("New daily limit below used limit"));
        assertThat(response.getBody()).containsEntry("message", "New daily limit below used limit");
        assertThat(response.getBody()).containsEntry("error", "New daily limit below used limit");
    }
}
