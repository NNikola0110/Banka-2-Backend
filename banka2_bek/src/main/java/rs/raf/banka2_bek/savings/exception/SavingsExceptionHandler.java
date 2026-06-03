package rs.raf.banka2_bek.savings.exception;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice(basePackages = "rs.raf.banka2_bek.savings.controller")
public class SavingsExceptionHandler {

    @ExceptionHandler(SavingsDepositNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(SavingsDepositNotFoundException ex) {
        return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(AccessDeniedException ex) {
        return ResponseEntity.status(403).body(Map.of("message", ex.getMessage()));
    }

    /**
     * R1 356 — {@code IllegalStateException} (npr. raskid pre dospeca koji nije
     * dozvoljen, vec raskinut depozit) je state-conflict → 409 Conflict, NE 500
     * Internal Server Error (500 je signalizirao "nasa greska" za korisnicki/
     * stanje-zavisan slucaj).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("message", ex.getMessage()));
    }

    /**
     * R1-665 / R1-667 (P3-bc-transfer-exchange-loan-savings-1): {@code @Version}
     * concurrent update (npr. paralelni {@code toggleAutoRenew} + maturity cron nad
     * istim depozitom, ili dva toggle-a) je client-retry-able conflict → 409, NE
     * generic 500. Eksplicitno mapirano na savings scope-u (ne oslanja se na
     * redosled {@code @ControllerAdvice}-a sa globalnim handlerom). Pokriva i
     * Spring {@link OptimisticLockingFailureException} i sirovu jakarta
     * {@link jakarta.persistence.OptimisticLockException}.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<Map<String, String>> handleOptimisticLock(Exception ex) {
        return ResponseEntity.status(409).body(Map.of(
                "message", "Depozit je u medjuvremenu modifikovan. Osvezite stranicu i pokusajte ponovo."));
    }

    /**
     * R1-667: Bean Validation pad na savings DTO-u (npr. negativan iznos) je 400 Bad
     * Request sa porukom prve greske — eksplicitno na savings scope-u (paritet sa
     * globalnim {@code GlobalExceptionHandler}), da scoped advice ne bi tihо
     * propustio validaciju na catch-all.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("Validation error");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
