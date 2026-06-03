package rs.raf.banka2_bek.payment.model;

public enum PaymentStatus {
    // R1-643: PENDING je bio MRTVA vrednost — nijedan produkcioni put je nikad nije
    // setovao (default je PROCESSING; prelazi na COMPLETED/REJECTED/CANCELLED/ABORTED).
    // Postojala je samo u testovima kao "ne-final, ne-completed" sonda. Uklonjena da
    // ne ostane latentna rupa u quickApprove finalized-guard-u (koji je nije pokrivao).
    // Stvarno "u toku" stanje je PROCESSING.
    PROCESSING,
    COMPLETED,
    REJECTED,
    CANCELLED,
    /** T2-012: placanje otkazano zbog 3 neuspela OTP unosa (audit trail). */
    ABORTED
}
