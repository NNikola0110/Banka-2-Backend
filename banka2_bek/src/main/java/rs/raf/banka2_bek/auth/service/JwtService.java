package rs.raf.banka2_bek.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.employee.model.Employee;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private static final long ACCESS_TOKEN_EXPIRATION = 1000 * 60 * 15; // 15 min
    private static final long REFRESH_TOKEN_EXPIRATION = 1000 * 60 * 60 * 24 * 7; // 7 dana

    private final SecretKey key;

    public JwtService(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("type", "access") // SEC-03: explicit type guard against refresh-token reuse
                .claim("role", user.getRole())
                .claim("active", user.isActive())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPIRATION))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String generateAccessToken(Employee employee) {
        // Determine role based on permissions
        String role = employee.getPermissions() != null && employee.getPermissions().contains("ADMIN")
                ? "ADMIN" : "EMPLOYEE";
        return Jwts.builder()
                .subject(employee.getEmail())
                .claim("type", "access") // SEC-03: explicit type guard against refresh-token reuse
                .claim("role", role)
                .claim("active", employee.getActive())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPIRATION))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String generateRefreshToken(User user) {
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + REFRESH_TOKEN_EXPIRATION))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String generateRefreshToken(Employee employee) {
        return Jwts.builder()
                .subject(employee.getEmail())
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + REFRESH_TOKEN_EXPIRATION))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String extractEmail(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        String email = extractEmail(token);
        return email.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    /**
     * Vraca {@code exp} claim tokena. Koristi {@link JwtBlacklistService} da
     * izracuna per-token TTL (blacklist drzi svaki token tacno do njegovog
     * STVARNOG isteka — access ~15min, refresh ~7 dana — umesto fiksnog TTL-a
     * koji bi evict-ovao refresh token mnogo pre njegovog exp-a). Baca ako
     * potpis/format nisu validni.
     */
    public Date extractExpiration(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getExpiration();
    }

    public boolean isRefreshToken(String token) {
        String type = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("type", String.class);
        return "refresh".equals(type);
    }
}
