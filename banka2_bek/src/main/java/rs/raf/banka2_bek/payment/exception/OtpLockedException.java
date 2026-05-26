package rs.raf.banka2_bek.payment.exception;

/**
 * Quick Approve (Mobile bonus #7) — OtpService.verify vratio blocked=true
 * (3+ uzastopnih fail-ova u rolling window). Mapira u HTTP 423 Locked.
 *
 * <p>OtpService TTL je 5 minuta. Posle isteka, klijent moze ponovo da pokusa
 * sa novim kodom (BE-AUTH-01 Caffeine cache).</p>
 */
public class OtpLockedException extends RuntimeException {
    public OtpLockedException(String message) {
        super(message);
    }
}
