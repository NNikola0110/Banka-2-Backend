package rs.raf.trading.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Validira JWT izdat od banka-core (HS256, deljeni secret). Bez baze korisnika —
 * proverava potpis + isticanje i vraca claim-ove. Mora koristiti ISTI secret i
 * algoritam kao banka-core JwtService.
 */
@Component
public class JwtValidator {

    private final SecretKey key;

    public JwtValidator(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Vraca claim-ove ako je token validan (potpis + nije istekao + nije refresh);
     * inace prazno.
     *
     * <p><b>N1 (P0-T4) — refresh-as-access guard:</b> trading-service deli isti
     * {@code jwt.secret} sa banka-core, pa je refresh token (7-dnevni TTL) potpisno
     * validan i ovde. Bez ove provere bi se prihvatao kao Bearer access token na
     * trgovinskim rutama ({@code /orders}, {@code /otc} ...). Odbijamo svaki token
     * cija je {@code type} oznaka {@code "refresh"} — usaglaseno sa banka-core
     * {@code JwtService.generateRefreshToken} (emituje {@code type=refresh}) i sa
     * {@code JwtAuthenticationFilter.isRefreshToken} guardom (B7/SEC-03). Tokeni bez
     * {@code type} claim-a (izdati pre uvodjenja type-a) tretiraju se kao access —
     * backwards-compat, identicno banka-core ponasanju.
     */
    public Optional<Claims> validate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // N1: refresh token NE sme da prodje kao access. Samo eksplicitan
            // type=refresh se odbija; null/nepoznat type ostaje access (backwards-compat).
            if ("refresh".equals(claims.get("type", String.class))) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
