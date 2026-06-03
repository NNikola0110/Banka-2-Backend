package rs.raf.banka2_bek.account.controller.exception_handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccountExceptionHandlerTest {

    private AccountExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AccountExceptionHandler();
    }

    @Test
    void handleAccessDenied_returnsForbidden() {
        // P1-error-contract-1: AccessDeniedException → 403 (ne 400 preko RuntimeException catch-all).
        AccessDeniedException ex = new AccessDeniedException("Racun ne pripada klijentu");

        ResponseEntity<Map<String, String>> response = handler.handleAccessDenied(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Racun ne pripada klijentu");
    }

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid account number");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Invalid account number");
    }

    @Test
    void handleIllegalState_returnsForbidden() {
        IllegalStateException ex = new IllegalStateException("Account is frozen");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Account is frozen");
    }

    @Test
    void handleRuntime_returnsBadRequest() {
        RuntimeException ex = new RuntimeException("Something went wrong");

        ResponseEntity<Map<String, String>> response = handler.handleRuntime(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Something went wrong");
    }

    @Test
    void handleRuntime_withNullMessage_returnsUnexpectedError() {
        RuntimeException ex = new RuntimeException((String) null);

        ResponseEntity<Map<String, String>> response = handler.handleRuntime(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Unexpected error");
    }

    // R1 308 — nepostojeci racun je 404 (EntityNotFoundException), ne 400.
    @Test
    void handleNotFound_returns404_withMessageAndError() {
        jakarta.persistence.EntityNotFoundException ex =
                new jakarta.persistence.EntityNotFoundException("Account with ID 999 not found.");

        ResponseEntity<Map<String, String>> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("message", "Account with ID 999 not found.");
        assertThat(response.getBody()).containsEntry("error", "Account with ID 999 not found.");
    }
}
