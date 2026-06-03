package rs.raf.trading.order.exception;

/**
 * Baca se kada order operacija (approve/decline/cancel) nije validna za TRENUTNO
 * stanje ordera (npr. approve nad ne-PENDING orderom). Mapira se na HTTP 409
 * Conflict u {@code OrderExceptionHandler}.
 *
 * <p>R1 410 (P2-error-contract-2): state-transition violation je 409 (stanje
 * resursa konfliktuje sa zahtevom), za razliku od authz-denial-a (403, koji baca
 * {@code AccessDeniedException}) i validacione greske (400, {@code IllegalArgumentException}).
 * Pre fix-a su svi {@code IllegalStateException} u order kontroleru mapirani na 403,
 * sto je za state-conflict bilo pogresno.
 */
public class OrderStateConflictException extends RuntimeException {
    public OrderStateConflictException(String message) {
        super(message);
    }
}
