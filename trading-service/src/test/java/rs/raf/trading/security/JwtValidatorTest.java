package rs.raf.trading.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testovi za JwtValidator — plain JUnit (bez Spring konteksta).
 * Koristi isti HS256 secret kao test properties.
 */
class JwtValidatorTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";
    private static final String WRONG_SECRET =
            "wrong-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private JwtValidator jwtValidator;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        jwtValidator = new JwtValidator(TEST_SECRET);
        secretKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /** Pomocna metoda za pravljenje validnog ACCESS tokena sa zadatim sub/role. */
    private String buildToken(SecretKey key, String subject, String role, long expiryMs) {
        return Jwts.builder()
                .subject(subject)
                .claim("type", "access")
                .claim("role", role)
                .claim("active", true)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Refresh token kako ga banka-core {@code JwtService.generateRefreshToken}
     * emituje: {@code type=refresh}, bez {@code role}/{@code active}, 7-dnevni TTL.
     */
    private String buildRefreshToken(SecretKey key, String subject) {
        return Jwts.builder()
                .subject(subject)
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 7))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Legacy token bez {@code type} claim-a (izdat pre SEC-03 fix-a). */
    private String buildLegacyToken(SecretKey key, String subject, String role) {
        return Jwts.builder()
                .subject(subject)
                .claim("role", role)
                .claim("active", true)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void validToken_returnsClaims_withCorrectSubjectAndRole() {
        String token = buildToken(secretKey, "stefan.jovanovic@gmail.com", "CLIENT", 60_000L);

        Optional<Claims> result = jwtValidator.validate(token);

        assertThat(result).isPresent();
        assertThat(result.get().getSubject()).isEqualTo("stefan.jovanovic@gmail.com");
        assertThat(result.get().get("role", String.class)).isEqualTo("CLIENT");
    }

    @Test
    void validToken_adminRole_returnsAdminRoleClaim() {
        String token = buildToken(secretKey, "marko.petrovic@banka.rs", "ADMIN", 60_000L);

        Optional<Claims> result = jwtValidator.validate(token);

        assertThat(result).isPresent();
        assertThat(result.get().getSubject()).isEqualTo("marko.petrovic@banka.rs");
        assertThat(result.get().get("role", String.class)).isEqualTo("ADMIN");
        assertThat(result.get().get("active", Boolean.class)).isTrue();
    }

    @Test
    void tokenSignedWithWrongSecret_returnsEmpty() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(WRONG_SECRET.getBytes(StandardCharsets.UTF_8));
        String token = buildToken(wrongKey, "user@example.com", "EMPLOYEE", 60_000L);

        Optional<Claims> result = jwtValidator.validate(token);

        assertThat(result).isEmpty();
    }

    @Test
    void expiredToken_returnsEmpty() {
        // Token koji je istekao 5 sekundi unazad
        String token = buildToken(secretKey, "user@example.com", "CLIENT", -5_000L);

        Optional<Claims> result = jwtValidator.validate(token);

        assertThat(result).isEmpty();
    }

    @Test
    void garbageString_returnsEmpty() {
        Optional<Claims> result = jwtValidator.validate("not.a.jwt.at.all");

        assertThat(result).isEmpty();
    }

    @Test
    void emptyString_returnsEmpty() {
        Optional<Claims> result = jwtValidator.validate("");

        assertThat(result).isEmpty();
    }

    // ── N1 (P0-T4): refresh-as-access guard ──────────────────────────────────

    /**
     * N1: validan, potpisan REFRESH token (type=refresh, 7-dnevni TTL) NE sme da
     * prodje kao Bearer access token na trading rutama. Pre fix-a je prolazio
     * (deljeni jwt.secret + bez type provere) → 7-dnevni refresh je bio validan
     * Bearer na /orders, /otc ... Usaglaseno sa banka-core JwtService koji refresh
     * token emituje sa {@code type=refresh}.
     */
    @Test
    void refreshToken_isRejected() {
        String refresh = buildRefreshToken(secretKey, "stefan.jovanovic@gmail.com");

        Optional<Claims> result = jwtValidator.validate(refresh);

        assertThat(result).isEmpty();
    }

    /** N1: access token (type=access) i dalje prolazi — ne lomimo legitiman put. */
    @Test
    void accessToken_withTypeClaim_isAccepted() {
        String token = buildToken(secretKey, "stefan.jovanovic@gmail.com", "CLIENT", 60_000L);

        Optional<Claims> result = jwtValidator.validate(token);

        assertThat(result).isPresent();
        assertThat(result.get().get("type", String.class)).isEqualTo("access");
    }

    /**
     * N1 backwards-compat: token bez {@code type} claim-a (izdat pre SEC-03)
     * tretira se kao access — isto kao banka-core {@code JwtService.isRefreshToken}
     * (samo {@code type=="refresh"} se odbija). Ne sme da pukne tokom prelaza.
     */
    @Test
    void legacyTokenWithoutTypeClaim_isAcceptedAsAccess() {
        String legacy = buildLegacyToken(secretKey, "user@example.com", "EMPLOYEE");

        Optional<Claims> result = jwtValidator.validate(legacy);

        assertThat(result).isPresent();
        assertThat(result.get().getSubject()).isEqualTo("user@example.com");
    }
}
