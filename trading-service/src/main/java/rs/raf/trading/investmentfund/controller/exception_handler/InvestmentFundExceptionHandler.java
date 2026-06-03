package rs.raf.trading.investmentfund.controller.exception_handler;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.trading.investmentfund.controller.InvestmentFundController;
import rs.raf.trading.investmentfund.exception.FundInactiveException;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.exception.UnsupportedCurrencyException;

import java.util.Map;

/**
 * Scoped exception handler za {@link InvestmentFundController}.
 * {@code @Order(HIGHEST_PRECEDENCE)} garantuje prednost nad app-wide
 * {@code TradingGlobalExceptionHandler}-om za izuzetke koje OBA hvataju
 * (konzistentno sa {@code ActuaryExceptionHandler} iz C1).
 */
@RestControllerAdvice(assignableTypes = InvestmentFundController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InvestmentFundExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientFunds(InsufficientFundsException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedCurrency(UnsupportedCurrencyException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", ex.getMessage()));
    }

    /**
     * R1 485 — neaktivan fond je state-conflict (409 CONFLICT), ne 403. Specifičan
     * tip {@link FundInactiveException} pa ne dira ostale {@code IllegalStateException}
     * mapinge (npr. "Only supervisors can create funds" ostaje 403).
     */
    @ExceptionHandler(FundInactiveException.class)
    public ResponseEntity<Map<String, String>> handleFundInactive(FundInactiveException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("message", ex.getMessage()));
    }
}
