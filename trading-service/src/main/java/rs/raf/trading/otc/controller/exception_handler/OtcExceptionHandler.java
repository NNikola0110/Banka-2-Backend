package rs.raf.trading.otc.controller.exception_handler;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.exception.InsufficientHoldingsException;
import rs.raf.trading.otc.controller.OtcController;
import rs.raf.trading.otc.controller.OtcNegotiationHistoryController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scoped exception handler za {@link OtcController} i
 * {@link OtcNegotiationHistoryController} (B10).
 * {@code @Order(HIGHEST_PRECEDENCE)} garantuje prednost nad app-wide
 * {@code TradingGlobalExceptionHandler}-om za izuzetke koje OBA hvataju.
 *
 * <p>NAMERNA DIVERGENCIJA statusa za {@code IllegalStateException} (vidi i
 * {@code InternalApiExceptionHandler}): u OTC modulu svaki {@code IllegalStateException}
 * je GENUINI state-conflict (npr. "Ugovor nije aktivan", "Settlement datum je prosao",
 * "Ponuda vise nije aktivna", "Nije na vama red") — semanticki 409 CONFLICT. Autorizacija
 * u OTC-u ide kroz {@code AccessDeniedException}&rarr;403 (ne preko {@code IllegalStateException}).
 * Suprotno tome, {@code ActuaryExceptionHandler}/{@code OrderExceptionHandler}/
 * {@code InvestmentFundExceptionHandler}/{@code ListingExceptionHandler} i globalni
 * {@code TradingGlobalExceptionHandler} mapiraju bare {@code IllegalStateException}&rarr;403
 * jer ga ti moduli koriste kao invarijant/authz-denial signal (pravi state-conflict tamo
 * nosi tipiziran izuzetak: {@code OrderStateConflictException}/{@code FundInactiveException}&rarr;409).
 * Status zato zavisi od MODULA koji baca, i to je svesna konvencija, ne propust.
 */
@RestControllerAdvice(assignableTypes = {OtcController.class, OtcNegotiationHistoryController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OtcExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EntityNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * U OTC modulu {@code IllegalStateException} je uvek state-conflict (ugovor/ponuda
     * u nedozvoljenom statusu, prosao settlement, pogresan red poteza) &rarr; 409 CONFLICT.
     * Authz ide kroz {@code AccessDeniedException}&rarr;403. Vidi klasni javadoc za
     * objasnjenje namerne per-modul divergencije statusa.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /**
     * R1 1007 — nedovoljno sredstava/hartija je 409 Conflict (uskladjeno sa
     * banka-core reserve 409 i {@code TradingGlobalExceptionHandler}), ne 400.
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
