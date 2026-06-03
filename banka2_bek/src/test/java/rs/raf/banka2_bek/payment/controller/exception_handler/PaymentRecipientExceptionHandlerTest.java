package rs.raf.banka2_bek.payment.controller.exception_handler;

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

class PaymentRecipientExceptionHandlerTest {

    private PaymentRecipientExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentRecipientExceptionHandler();
    }

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid recipient");

        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Invalid recipient");
        assertThat(response.getBody()).containsEntry("status", 400);
    }

    @Test
    void handleIllegalState_returnsForbidden() {
        IllegalStateException ex = new IllegalStateException("Payment forbidden");

        ResponseEntity<Map<String, Object>> response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("message", "Payment forbidden");
        assertThat(response.getBody()).containsEntry("status", 403);
    }

    @Test
    void handleValidation_returnsBadRequest() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "amount", "Amount is required"));

        MethodParameter param = new MethodParameter(
                PaymentRecipientExceptionHandlerTest.class.getDeclaredMethod("setUp"), -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).contains("amount: Amount is required");
    }

    // R4 1779 — INFO-DISCLOSURE fix: malformed JSON vise NE prosledjuje sirov
    // Jackson cause.getMessage() (naziv klase, lokacija parsera, ocekivani tip).
    // Telo je STATICNO "Invalid request format." nezavisno od cause-a; pravi razlog
    // se loguje server-side. (Fix-aligned: stari testovi su asertirali leak-ovano telo.)
    @Test
    void handleJsonParse_doesNotLeakRawJacksonMessage() {
        String leakyJackson = "Cannot deserialize value of type `java.math.BigDecimal` from String "
                + "\"abc\": not a valid representation\n at [Source: line: 7, column: 42]";
        RuntimeException cause = new RuntimeException(leakyJackson);
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("Parse error", cause, null);

        ResponseEntity<Map<String, Object>> response = handler.handleJsonParse(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String message = (String) response.getBody().get("message");
        assertThat(message).isEqualTo("Invalid request format.");
        assertThat(message).doesNotContain("Source").doesNotContain("BigDecimal").doesNotContain("column");
    }

    @Test
    void handleJsonParse_causeMessageNull_returnsStaticMessage() {
        RuntimeException cause = new RuntimeException((String) null);
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("Parse error", cause, null);

        ResponseEntity<Map<String, Object>> response = handler.handleJsonParse(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("message")).isEqualTo("Invalid request format.");
    }

    // R1 330 — nepostojece placanje (getPaymentById) je 404, ne 400.
    @Test
    void handlePaymentNotFound_returns404() {
        rs.raf.banka2_bek.payment.exception.PaymentNotFoundException ex =
                new rs.raf.banka2_bek.payment.exception.PaymentNotFoundException("Placanje nije pronadjeno.");

        ResponseEntity<Map<String, Object>> response = handler.handlePaymentNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("message", "Placanje nije pronadjeno.");
        assertThat(response.getBody()).containsEntry("status", 404);
    }

    @Test
    void handlePaymentNotOwned_returns403() {
        rs.raf.banka2_bek.payment.exception.PaymentNotOwnedException ex =
                new rs.raf.banka2_bek.payment.exception.PaymentNotOwnedException("Placanje ne pripada korisniku.");

        ResponseEntity<Map<String, Object>> response = handler.handlePaymentNotOwned(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("status", 403);
    }
}
