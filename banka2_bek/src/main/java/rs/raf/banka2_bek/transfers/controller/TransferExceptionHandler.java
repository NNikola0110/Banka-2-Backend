package rs.raf.banka2_bek.transfers.controller;

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

@RestControllerAdvice(basePackages = "rs.raf.banka2_bek.transfers.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransferExceptionHandler {

    /**
     * P0-B9 N4 (IDOR): ownership guard u {@code TransferService.getTransferById}
     * baca {@link org.springframework.security.access.AccessDeniedException} kad
     * klijent pokusa da procita tudji transfer. Ovaj scoped handler je
     * HIGHEST_PRECEDENCE pa hvata RuntimeException PRE globalnog
     * {@code GlobalExceptionHandler.handleAccessDenied}; bez eksplicitnog mappinga
     * AccessDeniedException bi padala u generic else-granu -> 500. Mora -> 403.
     * Deklarisan PRE handleRuntime jer Spring bira najspecifičniji handler, ali
     * eksplicitan tip uklanja svaku dvosmislenost.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "Forbidden";
        return buildResponse(HttpStatus.FORBIDDEN, msg);
    }

    /**
     * R1 340 — TIPIZOVANO mapiranje (zamenjuje raniji fragilni {@code msg.contains(...)}
     * string-match koji je "Authenticated client not found" slao na 404, a "Unable to
     * resolve user email" / nepokrivene business-poruke tiho na 500).
     * {@code TransferService} sada baca konkretne tipove:
     * <ul>
     *   <li>{@link rs.raf.banka2_bek.transfers.service.TransferAuthException} → 401</li>
     *   <li>{@link jakarta.persistence.EntityNotFoundException} → 404</li>
     *   <li>{@link IllegalArgumentException} (validacija / insufficient / not-active /
     *       reserves — ocuvan postojeci 400 kontrakt) → 400</li>
     *   <li>{@link org.springframework.security.access.AccessDeniedException} → 403 (gore)</li>
     * </ul>
     * Nepoznat/neocekivan {@code RuntimeException} ostaje 500 (vise NE business-leak —
     * svi poznati ishodi su tipizovani).
     */
    @ExceptionHandler(rs.raf.banka2_bek.transfers.service.TransferAuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(
            rs.raf.banka2_bek.transfers.service.TransferAuthException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED,
                ex.getMessage() != null ? ex.getMessage() : "Unauthorized");
    }

    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(jakarta.persistence.EntityNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND,
                ex.getMessage() != null ? ex.getMessage() : "Not found");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST,
                ex.getMessage() != null ? ex.getMessage() : "Bad request");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "Internal server error";
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, msg);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return buildResponse(HttpStatus.BAD_REQUEST, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleJsonParse(HttpMessageNotReadableException ex) {
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
