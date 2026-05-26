package rs.raf.banka2_bek.payment.exception;

/**
 * Quick Approve (Mobile bonus #7) — payment.createdAt + 5min &lt; now (TTL istekao).
 * Mapira u HTTP 410 Gone.
 *
 * <p>5-minutni prozor: Mobile deep-link iz FCM push-a vazi samo 5 minuta od
 * trenutka kreiranja payment-a (zbog OTP TTL-a i UX ocekivanja korisnika).</p>
 */
public class PaymentTimeoutException extends RuntimeException {
    public PaymentTimeoutException(String message) {
        super(message);
    }
}
