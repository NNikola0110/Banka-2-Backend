package rs.raf.banka2_bek.account.controller.exception_handler;

import rs.raf.banka2_bek.account.controller.AccountController;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AccountController.class)
public class AccountExceptionHandler {

    /**
     * R1 308: nepostojeci racun je 404, ne 400. {@code AccountServiceImplementation}
     * sada baca {@link EntityNotFoundException} za not-found; bez ovog eksplicitnog
     * handlera padao bi na globalni {@code GlobalExceptionHandler.handleEntityNotFound}
     * (takodje 404), ali ga drzimo lokalno radi konzistentnog {error,message} oblika.
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EntityNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * P1-error-contract-1: {@code AccessDeniedException} (npr. method-security
     * {@code @PreAuthorize} denial ili service-level ownership guard) mora dati 403,
     * ne 400. Bez ovog eksplicitnog handlera scoped {@code handleRuntime→400} bi je
     * uhvatio (ista RuntimeException hijerarhija).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage() != null ? ex.getMessage() : "Forbidden");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage() != null ? ex.getMessage() : "Unexpected error");
    }

    /**
     * R1 440 — telo nosi I {@code message} I {@code error} (isti tekst). FE/Mobile
     * citaju {@code message}-first (P1); legacy potrosaci {@code error}-a ostaju
     * kompatibilni.
     */
    private ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        Map<String, String> b = new LinkedHashMap<>();
        b.put("message", message);
        b.put("error", message);
        return ResponseEntity.status(status).body(b);
    }
}
