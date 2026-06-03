package rs.raf.banka2_bek.auth.exception;

/**
 * Bacaja se na {@code /auth/refresh} kada je refresh token nevalidan, isteklo/
 * revoke-ovan (blacklist) ili nije refresh-tipa. Mapira se na HTTP 401 Unauthorized.
 *
 * <p>R1 296 (P2-error-contract-2): pre fix-a je {@code AuthService.refreshToken}
 * bacao bare {@code RuntimeException("Invalid refresh token")} koji je catch-all
 * mapirao u 400 Bad Request. Nevalidan kredencijal je 401, ne 400 — klijent treba
 * da zna da mora ponovo da se autentifikuje (a ne da je payload los).
 */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
