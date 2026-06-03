package rs.raf.banka2_bek.auth.exception;

/**
 * Bacaja se kad kreiranje korisnika/klijenta/registracija naidje na vec postojeci
 * email. Mapira se na HTTP 409 Conflict u {@code GlobalExceptionHandler}.
 *
 * <p>R5 1884 / R1 296 (P2-error-contract-2): pre fix-a su {@code ClientServiceImpl}
 * i {@code AuthService.register} bacali GO bare {@code RuntimeException} koji je
 * catch-all mapirao u 400 Bad Request — pogresno, duplikat resursa je 409 Conflict.
 * Pravi TOCTOU race (paralelne registracije iste adrese koje obe prodju pre-check)
 * je pokriven globalnim {@code DataIntegrityViolationException → 409} handlerom (DB
 * unique constraint je autoritativni guard).
 */
public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String message) {
        super(message);
    }
}
