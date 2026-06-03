package rs.raf.banka2_bek.payment.controller.exception_handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice(basePackages = "rs.raf.banka2_bek.payment.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentRecipientExceptionHandler {

    /**
     * R1 330 — nepostojece placanje (getPaymentById) je 404, ne 400. Eksplicitan
     * scoped handler (HIGHEST_PRECEDENCE) garantuje 404 i konzistentan {message,...}
     * oblik na payment kontrolerima.
     */
    @ExceptionHandler(rs.raf.banka2_bek.payment.exception.PaymentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePaymentNotFound(
            rs.raf.banka2_bek.payment.exception.PaymentNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * R1 330 — IDOR ownership-guard: tudje placanje vraca 403 (ne 400). Bez ovog
     * eksplicitnog handlera {@code PaymentNotOwnedException} bi padao na globalni
     * {@code GlobalExceptionHandler} (takodje 403), ali ga drzimo lokalno radi
     * konzistentnog oblika i prioriteta.
     */
    @ExceptionHandler(rs.raf.banka2_bek.payment.exception.PaymentNotOwnedException.class)
    public ResponseEntity<Map<String, Object>> handlePaymentNotOwned(
            rs.raf.banka2_bek.payment.exception.PaymentNotOwnedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /**
     * R1-521 — nedostatak autentikacije na payment putanjama je 401 Unauthorized,
     * ne 400. Ranije se bacao {@code IllegalArgumentException} ("Niste prijavljeni")
     * koji je mapirao na 400 — sad ima dedikovan {@code NotAuthenticatedException}.
     */
    @ExceptionHandler(rs.raf.banka2_bek.payment.exception.NotAuthenticatedException.class)
    public ResponseEntity<Map<String, Object>> handleNotAuthenticated(
            rs.raf.banka2_bek.payment.exception.NotAuthenticatedException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return buildResponse(HttpStatus.BAD_REQUEST, errors);
    }

    /**
     * R4 1779 — INFO-DISCLOSURE fix: malformed JSON vise NE vraca sirov Jackson
     * {@code cause.getMessage()} (curio je naziv klase, lokacija parsera, ocekivani
     * tip i ceo stacktrace fragment). Vracamo STATICNU poruku "Invalid request
     * format."; pravi razlog logujemo server-side.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleJsonParse(HttpMessageNotReadableException ex) {
        log.warn("Payment request malformed JSON: {}", ex.getMostSpecificCause().getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Invalid request format.");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
