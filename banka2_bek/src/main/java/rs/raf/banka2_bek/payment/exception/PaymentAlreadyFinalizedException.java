package rs.raf.banka2_bek.payment.exception;

/**
 * Quick Approve (Mobile bonus #7) — payment.status je vec u failure terminal
 * stanju (REJECTED, ABORTED, CANCELLED). Mapira u HTTP 409 Conflict.
 *
 * <p>NAPOMENA: COMPLETED status NIJE finalized failure — idempotent retry vraca
 * 200 OK (vidi {@link rs.raf.banka2_bek.payment.service.PaymentService#quickApprove}).</p>
 */
public class PaymentAlreadyFinalizedException extends RuntimeException {
    public PaymentAlreadyFinalizedException(String message) {
        super(message);
    }
}
