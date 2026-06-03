package rs.raf.trading.actuary.controller.exception_handler;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.trading.actuary.controller.ActuaryController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scoped exception handler za {@link ActuaryController}.
 * {@code @Order(HIGHEST_PRECEDENCE)} garantuje prednost nad app-wide
 * {@code TradingGlobalExceptionHandler}-om za izuzetke koje OBA hvataju.
 *
 * <p>R1-186 / error-contract: {@code IllegalArgumentException} je VALIDACIONA
 * greska (npr. "novi dnevni limit ispod usedLimit-a") → mapira se na
 * {@code 400 Bad Request}, NE 404. Genuini "ne postoji" slucajevi u servisu
 * bacaju {@link EntityNotFoundException} → 404.
 */
@RestControllerAdvice(assignableTypes = ActuaryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActuaryExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EntityNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        // R1-186: validaciona greska (below-used-limit, nevalidan limit) → 400.
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /**
     * R1 440 — telo nosi i {@code message} (FE/Mobile-first parsing) i {@code error}
     * (legacy potrosaci). Standardizacija {@code {error}} vs {@code {message}} oblika.
     */
    private ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        Map<String, String> b = new LinkedHashMap<>();
        b.put("message", message);
        b.put("error", message);
        return ResponseEntity.status(status).body(b);
    }
}
