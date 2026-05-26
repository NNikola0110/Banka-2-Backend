package rs.raf.banka2_bek.payment.exception;

/**
 * Quick Approve (Mobile bonus #7) — payment id ne postoji u DB. Mapira u HTTP 404.
 */
public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(String message) {
        super(message);
    }
}
