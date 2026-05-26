package rs.raf.banka2_bek.payment.exception;

/**
 * Quick Approve (Mobile bonus #7) — payment.fromAccount ne pripada current user-u.
 * Mapira u HTTP 403 Forbidden.
 */
public class PaymentNotOwnedException extends RuntimeException {
    public PaymentNotOwnedException(String message) {
        super(message);
    }
}
