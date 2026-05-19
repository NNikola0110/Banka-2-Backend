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
    void handleIllegalArgument_returnsNotFound() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid actuary ID");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "Invalid actuary ID");
    }

    @Test
    void handleIllegalState_returnsForbidden() {
        IllegalStateException ex = new IllegalStateException("Limit exceeded");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Limit exceeded");
    }
}
