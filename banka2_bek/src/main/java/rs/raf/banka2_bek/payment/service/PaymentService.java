package rs.raf.banka2_bek.payment.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import rs.raf.banka2_bek.payment.dto.CreatePaymentRequestDto;
import rs.raf.banka2_bek.payment.dto.PaymentListItemDto;
import rs.raf.banka2_bek.payment.dto.PaymentResponseDto;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.transaction.dto.TransactionListItemDto;
import rs.raf.banka2_bek.transaction.dto.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface PaymentService {

    PaymentResponseDto createPayment(CreatePaymentRequestDto request);

    /**
     * Preflight validacija placanja BEZ persistovanja. Provera vlasnistva,
     * stanja, limita i postojanja primaoca. Koristi se iz `POST /payments/request-otp`
     * (Sc T2-009 fix) da bi se odbacio nevazece placanje PRE generisanja OTP koda.
     * Baca IllegalArgumentException sa user-friendly porukom ako validacija puca.
     */
    void validatePayment(CreatePaymentRequestDto request);

    /**
     * T2-012 audit trail: kada OTP istekne / korisnik unese 3 puta pogresan kod,
     * persistira ABORTED payment red da bi se sacuvao audit. Best-effort —
     * ako podaci nisu kompletni, vraca null bez exception-a (klijent vec
     * dobija 403 status koji ne treba zamenjivati internim 500).
     *
     * @return id zapisa ako je persistovan, inace null
     */
    Long recordAbortedPayment(CreatePaymentRequestDto request, String reason);

    /**
     * TODO_final Mobile bonus #7 — Quick Approve flow. Korisnik dolazi sa
     * Mobile UI deep-link-om iz FCM push notifikacije, sa paymentId + OTP
     * kodom (TOTP).
     *
     * <p>Validira: 1) payment postoji (else {@link rs.raf.banka2_bek.payment.exception.PaymentNotFoundException}),
     * 2) ownership (else {@link rs.raf.banka2_bek.payment.exception.PaymentNotOwnedException}),
     * 3) status — COMPLETED je idempotent (vraca payload), REJECTED/ABORTED/CANCELLED je
     * {@link rs.raf.banka2_bek.payment.exception.PaymentAlreadyFinalizedException},
     * 4) TTL — payment.createdAt + 5min &lt; now baca
     * {@link rs.raf.banka2_bek.payment.exception.PaymentTimeoutException},
     * 5) OTP gate — {@link rs.raf.banka2_bek.payment.exception.OtpLockedException} za 3-strike,
     * {@link rs.raf.banka2_bek.payment.exception.OtpInvalidException} za pogresan kod.</p>
     *
     * <p>Posle uspesnog approve-a: payment dispatched, audit log
     * {@link rs.raf.banka2_bek.audit.model.AuditActionType#PAYMENT_QUICK_APPROVED}
     * persistovan, in-app notifikacija PAYMENT_CONFIRMED publish-ovana.</p>
     *
     * @param paymentId Payment id iz Mobile deep-link-a
     * @param userEmail Authenticated user-ov email (iz JWT principal)
     * @param otpCode 6-cifreni TOTP kod
     * @return PaymentResponseDto sa final statusom (COMPLETED ili PROCESSING za interbank)
     */
    PaymentResponseDto quickApprove(Long paymentId, String userEmail, String otpCode);

    default Page<PaymentListItemDto> getPayments(Pageable pageable) {
        return getPayments(pageable, null, null, null, null, null, null);
    }

    Page<PaymentListItemDto> getPayments(
            Pageable pageable,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            String accountNumber,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            PaymentStatus status
    );

    PaymentResponseDto getPaymentById(Long paymentId);

    byte[] getPaymentReceipt(Long paymentId);

    default Page<TransactionListItemDto> getPaymentHistory(Pageable pageable) {
        return getPaymentHistory(pageable, null, null, null, null, null);
    }

    Page<TransactionListItemDto> getPaymentHistory(
            Pageable pageable,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            TransactionType type
    );
}
