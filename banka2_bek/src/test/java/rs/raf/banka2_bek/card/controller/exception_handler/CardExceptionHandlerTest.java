package rs.raf.banka2_bek.card.controller.exception_handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CardExceptionHandlerTest {

    private CardExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CardExceptionHandler();
    }

    @Test
    void handleAccessDenied_returnsForbidden() {
        AccessDeniedException ex = new AccessDeniedException("Access is denied");

        ResponseEntity<Map<String, String>> response = handler.handleAccessDenied(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Access is denied");
    }

    @Test
    void handlePreAuthorizeDenial_returnsForbiddenNot400() {
        // P1-error-contract-1: @PreAuthorize denial baca AuthorizationDeniedException
        // (extends AccessDeniedException). Pre fix-a scoped handleRuntime→400 ga je hvatao.
        AuthorizationDeniedException ex =
                new AuthorizationDeniedException("Access Denied", new AuthorizationDecision(false));

        ResponseEntity<Map<String, String>> response = handler.handleAccessDenied(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void handleAccessDenied_nullMessage_returnsForbiddenFallback() {
        AccessDeniedException ex = new AccessDeniedException(null);

        ResponseEntity<Map<String, String>> response = handler.handleAccessDenied(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Forbidden");
    }

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid card number");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Invalid card number");
    }

    @Test
    void handleIllegalState_returnsForbidden() {
        IllegalStateException ex = new IllegalStateException("Card is blocked");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Card is blocked");
    }

    @Test
    void handleRuntime_returnsBadRequest() {
        RuntimeException ex = new RuntimeException("Unexpected card error");

        ResponseEntity<Map<String, String>> response = handler.handleRuntime(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Unexpected card error");
    }

    @Test
    void handleRuntime_withNullMessage_returnsUnexpectedError() {
        RuntimeException ex = new RuntimeException((String) null);

        ResponseEntity<Map<String, String>> response = handler.handleRuntime(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Unexpected error");
    }
}
