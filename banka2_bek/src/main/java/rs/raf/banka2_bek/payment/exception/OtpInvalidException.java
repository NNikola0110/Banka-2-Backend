package rs.raf.banka2_bek.payment.exception;

/**
 * Quick Approve (Mobile bonus #7) — OtpService.verify vratio verified=false
 * (ali NIJE jos blocked=true, tj. ima jos pokusaja). Mapira u HTTP 401 Unauthorized.
 */
public class OtpInvalidException extends RuntimeException {
    public OtpInvalidException(String message) {
        super(message);
    }
}
