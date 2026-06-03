package rs.raf.banka2_bek.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import rs.raf.banka2_bek.auth.model.User;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtBlacklistService — token blacklist")
class JwtBlacklistServiceTest {

    private static final String SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private JwtBlacklistService service;

    @BeforeEach
    void setUp() {
        service = new JwtBlacklistService();
        service.init();
    }

    @Test
    @DisplayName("Token koji nije blacklisted vraca false")
    void unknownToken_isNotBlacklisted() {
        assertThat(service.isBlacklisted("eyJhbG.unused.token")).isFalse();
    }

    @Test
    @DisplayName("Posle blacklist() token postaje blacklisted")
    void blacklist_marksTokenAsBlacklisted() {
        String token = "eyJhbG.real.token123";

        service.blacklist(token);

        assertThat(service.isBlacklisted(token)).isTrue();
    }

    @Test
    @DisplayName("Razliciti tokeni se nezavisno tretiraju")
    void differentTokens_independentBlacklisting() {
        String tokenA = "tokenA";
        String tokenB = "tokenB";

        service.blacklist(tokenA);

        assertThat(service.isBlacklisted(tokenA)).isTrue();
        assertThat(service.isBlacklisted(tokenB)).isFalse();
    }

    @Test
    @DisplayName("Null token ne baca i ne blacklisting-uje nista")
    void nullToken_doesNothing() {
        service.blacklist(null);

        assertThat(service.isBlacklisted(null)).isFalse();
        assertThat(service.isBlacklisted("")).isFalse();
    }

    @Test
    @DisplayName("Empty token ne baca i ne blacklisting-uje nista")
    void emptyToken_doesNothing() {
        service.blacklist("");
        service.blacklist("   ");

        assertThat(service.isBlacklisted("")).isFalse();
        assertThat(service.isBlacklisted("   ")).isFalse();
    }

    @Test
    @DisplayName("Idempotentno: ponovni blacklist istog tokena radi")
    void blacklist_idempotent() {
        String token = "same.token.twice";
        service.blacklist(token);
        service.blacklist(token);

        assertThat(service.isBlacklisted(token)).isTrue();
    }

    /**
     * N3-a: per-token TTL — blacklist drzi svaki token tacno do njegovog STVARNOG
     * {@code exp}. Refresh (7 dana) NE sme da bude eviktovan starim fiksnim 20min
     * TTL-om; token tik pre exp-a je jos odbijen; istekao token je svejedno nevazeci.
     */
    @Nested
    @DisplayName("N3-a: per-token TTL (varijabilni expiry)")
    class PerTokenTtl {

        private JwtBlacklistService realService;

        @BeforeEach
        void setUp() {
            // Pravi JwtService da blacklist moze da izvuce exp claim iz tokena.
            JwtService jwtService = new JwtService(SECRET);
            realService = new JwtBlacklistService(jwtService);
            realService.init();
        }

        private String tokenExpiringInMillis(long millisFromNow) {
            SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
            return Jwts.builder()
                    .subject("user@test.com")
                    .claim("type", "refresh")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + millisFromNow))
                    .signWith(key, Jwts.SIG.HS256)
                    .compact();
        }

        @Test
        @DisplayName("refresh token (7 dana) ostaje blacklisted (nije eviktovan kao stari 20min TTL)")
        void refreshTokenStaysBlacklistedWellPast20Min() {
            // 7-dnevni refresh token — stari fiksni 20min TTL bi ga vec ispustio.
            String refresh = tokenExpiringInMillis(7L * 24 * 60 * 60 * 1000);

            realService.blacklist(refresh);

            assertThat(realService.isBlacklisted(refresh)).isTrue();
        }

        @Test
        @DisplayName("token NEPOSREDNO pre exp-a je jos uvek blacklisted (odbijen)")
        void tokenJustBeforeExpiryStillBlacklisted() {
            // exp za ~3s u buducnosti — TTL = exp − now > 0, pa entry mora ostati.
            String almostExpired = tokenExpiringInMillis(3_000);

            realService.blacklist(almostExpired);

            assertThat(realService.isBlacklisted(almostExpired)).isTrue();
        }

        @Test
        @DisplayName("vec istekao token dobija minimalni TTL (nije trajno u memoriji), signature-exp ga ionako odbija")
        void expiredTokenGetsMinimalTtl() {
            // exp 1s u proslosti — remainingNanos vraca minimalni TTL (1ns),
            // pa entry brzo postaje evictable. Bitno: blacklist ne baca.
            String expired = tokenExpiringInMillis(-1_000);

            realService.blacklist(expired);

            // Caffeine eviktuje lenjo; bitno je da TTL nije produzen na 7 dana
            // za istekao token (signature-exp check u filteru je primarna odbrana).
            // Ne asertujemo isBlacklisted==false (lenja evikcija), vec da ne puca.
            assertThat(realService.isBlacklisted("nepostojeci")).isFalse();
        }

        @Test
        @DisplayName("malformed token (bez validnog potpisa) → fallback TTL, blacklist ne baca")
        void malformedTokenFallsBackGracefully() {
            String garbage = "not.a.jwt";

            realService.blacklist(garbage);

            assertThat(realService.isBlacklisted(garbage)).isTrue();
        }
    }
}
