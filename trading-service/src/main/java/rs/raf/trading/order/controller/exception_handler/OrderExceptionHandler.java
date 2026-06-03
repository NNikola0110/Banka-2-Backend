package rs.raf.trading.order.controller.exception_handler;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.trading.order.controller.OrderController;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.exception.InsufficientHoldingsException;
import rs.raf.trading.order.exception.OrderStateConflictException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scoped exception handler za {@link OrderController}.
 * {@code @Order(HIGHEST_PRECEDENCE)} garantuje prednost nad app-wide
 * {@code TradingGlobalExceptionHandler}-om za izuzetke koje OBA hvataju.
 */
@RestControllerAdvice(assignableTypes = OrderController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrderExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EntityNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * R1 410 — pravi state-conflict (npr. "Only PENDING orders can be approved")
     * je 409 Conflict, ne 403. Authz-denial ("You dont have access") ostaje
     * {@link AccessDeniedException}→403. Ostali {@link IllegalStateException} (legacy
     * invarijante) zadrzava 403 radi kompatibilnosti.
     */
    @ExceptionHandler(OrderStateConflictException.class)
    public ResponseEntity<Map<String, String>> handleStateConflict(OrderStateConflictException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage() != null ? ex.getMessage() : "Forbidden");
    }

    /**
     * R1 409 — nedovoljno sredstava/hartija je 409 Conflict, ne 400 (payload validan,
     * stanje resursa konfliktuje). Uskladjeno sa banka-core reserve 409.
     */
    @ExceptionHandler({InsufficientFundsException.class, InsufficientHoldingsException.class})
    public ResponseEntity<Map<String, String>> handleInsufficient(RuntimeException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** R1 440 — telo nosi i {@code message} (FE/Mobile-first) i {@code error} (legacy). */
    private ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        Map<String, String> b = new LinkedHashMap<>();
        b.put("message", message);
        b.put("error", message);
        return ResponseEntity.status(status).body(b);
    }
}
