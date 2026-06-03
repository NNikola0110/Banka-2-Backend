package rs.raf.banka2_bek.payment.exception;

/**
 * Quick Approve (Mobile bonus #7) defensive guard — payment je u ne-COMPLETED,
 * settle-pending stanju, a {@code quickApprove} NE izvrsava stvarni settlement
 * (debit/credit/FX). Phase-2 FCM tek treba da wire-uje pravi settlement.
 *
 * <p>Trenutno (pre Phase-2 FCM) NIJEDAN code path ne kreira ovakav payment:
 * intra-bank {@code createPayment} settle-uje inline i odmah postavlja COMPLETED,
 * a inter-bank 2PC putanja sama vodi svoj commit (PROCESSING -&gt; COMPLETED/REJECTED).
 * Ova exception je odbrana od latentnog bug-a: kad bi buduci FCM flow kreirao
 * ne-settle-ovan PENDING payment, {@code quickApprove} NE SME da ga lazno
 * markira kao COMPLETED bez pomeranja novca — umesto toga eksplicitno odbija
 * zahtev (mapira u HTTP 501 Not Implemented).</p>
 *
 * <p>NAPOMENA: vec COMPLETED payment se NE odbija — idempotent retry vraca
 * 200 OK (vidi {@link rs.raf.banka2_bek.payment.service.PaymentService#quickApprove}).</p>
 */
public class QuickApproveSettlementNotWiredException extends RuntimeException {
    public QuickApproveSettlementNotWiredException(String message) {
        super(message);
    }
}
