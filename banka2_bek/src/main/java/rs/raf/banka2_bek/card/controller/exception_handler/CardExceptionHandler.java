package rs.raf.banka2_bek.card.controller.exception_handler;

import rs.raf.banka2_bek.card.controller.CardController;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CardController.class)
public class CardExceptionHandler {

    /**
     * P1-error-contract-1: {@code @PreAuthorize} denial baca
     * {@code AuthorizationDeniedException extends AccessDeniedException extends RuntimeException}.
     * Bez ovog eksplicitnog handlera scoped {@code handleRuntime(RuntimeException)→400}
     * bi je uhvatio (najspecificniji-ali-ista-hijerarhija) i vratio 400 umesto 403.
     * Eksplicitan tip garantuje 403.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Forbidden"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Unexpected error"));
    }
}
