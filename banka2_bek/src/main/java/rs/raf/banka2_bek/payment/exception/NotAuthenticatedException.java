package rs.raf.banka2_bek.payment.exception;

/**
 * R1-521: nedostatak autentikacije na payment putanjama. Semanticki je 401
 * Unauthorized (nema/nevazeci principal), NE 400 Bad Request — ranije se
 * koristio {@link IllegalArgumentException} ("Niste prijavljeni") koji je
 * mapirao na 400. Mapira u HTTP 401 preko PaymentRecipientExceptionHandler-a.
 */
public class NotAuthenticatedException extends RuntimeException {
    public NotAuthenticatedException(String message) {
        super(message);
    }
}
