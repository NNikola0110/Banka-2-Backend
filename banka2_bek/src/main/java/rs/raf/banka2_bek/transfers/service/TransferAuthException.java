package rs.raf.banka2_bek.transfers.service;

/**
 * R1 340 (P2-cleanup-deadcode-1): autentifikacioni problem u transfer flow-u
 * (nema principal-a, korisnik se ne moze razresiti) → HTTP 401 UNAUTHORIZED.
 *
 * <p>Pre fix-a su ovo bili bare {@code RuntimeException}-i koje je
 * {@code TransferExceptionHandler} mapirao po SADRZAJU poruke (string-match):
 * "User is not authenticated" → 401, ali "Authenticated client not found" je
 * (jer sadrzi "not found") padao na 404, a "Unable to resolve user email" na 500.
 * Tipizovan izuzetak uklanja fragilno parsiranje poruke i daje deterministicki 401.
 */
public class TransferAuthException extends RuntimeException {
    public TransferAuthException(String message) {
        super(message);
    }
}
