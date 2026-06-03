package rs.raf.banka2_bek.transfers.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransferExceptionHandlerTest {

    private TransferExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TransferExceptionHandler();
    }

    // ── R1 340: TIPIZOVANO mapiranje (zamenjuje stari fragilni msg.contains string-match) ──

    @Test
    void handleNotFound_entityNotFound_returns404() {
        var ex = new jakarta.persistence.EntityNotFoundException("From account not found");

        ResponseEntity<Map<String, Object>> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("message", "From account not found");
    }

    @Test
    void handleBadRequest_insufficientFunds_returns400() {
        var ex = new IllegalArgumentException("Insufficient funds");

        ResponseEntity<Map<String, Object>> response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Insufficient funds");
    }

    @Test
    void handleBadRequest_notActive_returns400() {
        var ex = new IllegalArgumentException("Source account is not active");

        ResponseEntity<Map<String, Object>> response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handleBadRequest_mustBeDifferent_returns400() {
        var ex = new IllegalArgumentException("Accounts must be different");

        ResponseEntity<Map<String, Object>> response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handleBadRequest_differentCurrencies_returns400() {
        var ex = new IllegalArgumentException("Accounts must have different currencies");

        ResponseEntity<Map<String, Object>> response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void handleAuth_notAuthenticated_returns401() {
        var ex = new rs.raf.banka2_bek.transfers.service.TransferAuthException("User is not authenticated");

        ResponseEntity<Map<String, Object>> response = handler.handleAuth(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("message", "User is not authenticated");
    }

    @Test
    void handleAuth_clientNotFoundForAuthenticated_returns401_notMore404() {
        // R1 340: ranije je "Client not found for authenticated" sadrzao "not found" pa je
        // string-match davao 404; sad je tipizovan kao TransferAuthException → deterministicki 401.
        var ex = new rs.raf.banka2_bek.transfers.service.TransferAuthException(
                "Client not found for authenticated user");

        ResponseEntity<Map<String, Object>> response = handler.handleAuth(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void handleRuntime_unknownError_returns500() {
        RuntimeException ex = new RuntimeException("Something completely unexpected");

        ResponseEntity<Map<String, Object>> response = handler.handleRuntime(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("status", 500);
    }

    @Test
    void handleRuntime_nullMessage_returns500WithDefault() {
        RuntimeException ex = new RuntimeException((String) null);

        ResponseEntity<Map<String, Object>> response = handler.handleRuntime(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("message", "Internal server error");
    }

    // ── MethodArgumentNotValidException ───────────────────────────

    @Test
    void handleValidation_returnsBadRequest() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "amount", "Amount is required"));

        MethodParameter param = new MethodParameter(
                TransferExceptionHandlerTest.class.getDeclaredMethod("setUp"), -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).contains("amount: Amount is required");
    }

    // ── HttpMessageNotReadableException ───────────────────────────

    @Test
    void handleJsonParse_returnsInvalidFormat() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad json", (Throwable) null, null);

        ResponseEntity<Map<String, Object>> response = handler.handleJsonParse(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Invalid request format.");
    }
}
