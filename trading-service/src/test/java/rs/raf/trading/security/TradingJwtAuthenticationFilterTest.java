package rs.raf.trading.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Testovi za TradingJwtAuthenticationFilter.
 * Koristi Spring MockHttpServletRequest/Response i Mockito za FilterChain.
 * {@code TradingPermissionResolver} je mockovan — po difoltu vraca praznu listu
 * permisija (testovi koji proveravaju permisije ga stub-uju eksplicitno).
 */
class TradingJwtAuthenticationFilterTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private TradingJwtAuthenticationFilter filter;
    private TradingPermissionResolver permissionResolver;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        secretKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        JwtValidator validator = new JwtValidator(TEST_SECRET);
        permissionResolver = mock(TradingPermissionResolver.class);
        when(permissionResolver.resolvePermissions(anyString())).thenReturn(List.of());
        filter = new TradingJwtAuthenticationFilter(validator, permissionResolver);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private String buildValidToken(String subject, String role) {
        return buildToken(subject, role, true);
    }

    /** Access token sa kontrolisanim {@code active} flag-om (N2). */
    private String buildToken(String subject, String role, boolean active) {
        return Jwts.builder()
                .subject(subject)
                .claim("type", "access")
                .claim("role", role)
                .claim("active", active)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /** Refresh token (type=refresh, 7-dnevni TTL) — banka-core generateRefreshToken oblik. */
    private String buildRefreshToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 7))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void validBearerToken_setsSecurityContextAndCallsChain() throws Exception {
        String token = buildValidToken("stefan.jovanovic@gmail.com", "CLIENT");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        // Chain mora biti pozvan
        verify(chain, times(1)).doFilter(request, response);

        // Security context mora imati autentifikaciju
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isEqualTo("stefan.jovanovic@gmail.com");

        // Mora imati ROLE_CLIENT authority
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_CLIENT");

        // HTTP status ne sme biti 401
        assertThat(response.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void invalidToken_returns401_andChainNotCalled() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer totally.invalid.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        // Chain NE sme biti pozvan
        verify(chain, never()).doFilter(any(), any());

        // HTTP status mora biti 401
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("error");
    }

    @Test
    void noAuthorizationHeader_callsChainWithEmptyContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Bez Authorization header-a
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        // Chain mora biti pozvan
        verify(chain, times(1)).doFilter(request, response);

        // Security context mora biti prazan (bez autentifikacije)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNull();

        // HTTP status ne sme biti 401
        assertThat(response.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void expiredToken_returns401_andChainNotCalled() throws Exception {
        // Token koji je istekao 5 sekundi unazad
        String expiredToken = Jwts.builder()
                .subject("user@example.com")
                .claim("role", "CLIENT")
                .issuedAt(new Date(System.currentTimeMillis() - 10_000L))
                .expiration(new Date(System.currentTimeMillis() - 5_000L))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + expiredToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void validTokenWithAdminRole_setsRoleAdminAuthority() throws Exception {
        String token = buildValidToken("marko.petrovic@banka.rs", "ADMIN");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void employeeToken_addsResolvedPermissionAuthorities() throws Exception {
        // Supervizor: JWT role je EMPLOYEE, permisije se razresavaju preko banka-core.
        when(permissionResolver.resolvePermissions("nikola.milenkovic@banka.rs"))
                .thenReturn(List.of("SUPERVISOR", "TRADE_STOCKS"));
        String token = buildValidToken("nikola.milenkovic@banka.rs", "EMPLOYEE");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_EMPLOYEE", "SUPERVISOR", "TRADE_STOCKS");
    }

    @Test
    void clientWithoutTradePermission_getsOnlyRoleClient() throws Exception {
        // Klijent bez canTradeStocks: resolver vraca praznu listu (default stub) →
        // samo ROLE_CLIENT, bez TRADE_STOCKS. Lookup SE I DALJE radi (mora — banka-core
        // odlucuje da li klijent sme da traduje), ali ne dodaje permisiju.
        String token = buildValidToken("stefan.jovanovic@gmail.com", "CLIENT");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(permissionResolver, times(1)).resolvePermissions("stefan.jovanovic@gmail.com");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_CLIENT");
    }

    @Test
    void clientWithTradeStocksPermission_getsTradeStocksAuthority() throws Exception {
        // P0-2: klijent sa canTradeStocks=true — banka-core razresi TRADE_STOCKS.
        // Filter mora dodati TRADE_STOCKS autoritet uz ROLE_CLIENT (inace 403 na POST /orders).
        when(permissionResolver.resolvePermissions("stefan.jovanovic@gmail.com"))
                .thenReturn(List.of("TRADE_STOCKS"));
        String token = buildValidToken("stefan.jovanovic@gmail.com", "CLIENT");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_CLIENT", "TRADE_STOCKS");
    }

    // ── N1 (P0-T4): refresh token NE sme da prodje kao Bearer access na trading ──

    @Test
    void refreshTokenAsBearer_returns401_andChainNotCalled() throws Exception {
        // N1: 7-dnevni refresh token (deljeni jwt.secret) — pre fix-a je prolazio
        // kao validan Bearer na trading rutama. Mora dati 401, chain se ne zove,
        // security context ostaje prazan.
        String refresh = buildRefreshToken("stefan.jovanovic@gmail.com");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + refresh);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // permisije se NE razresavaju za odbijen token
        verify(permissionResolver, never()).resolvePermissions(any());
    }

    // ── N2 (P0-T4): deaktiviran nalog (active=false claim) → 401 na trading ──

    @Test
    void deactivatedAccount_validAccessToken_returns401_andChainNotCalled() throws Exception {
        // N2 (mirror B7 banka-core): access token sa active=false (nalog deaktiviran
        // posle izdavanja tokena) ne sme da zadrzi trading pristup. active je claim
        // koji banka-core JwtService stavlja na svaki access token (snapshot).
        String token = buildToken("deactivated@banka.rs", "EMPLOYEE", false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void activeAccount_validAccessToken_passes() throws Exception {
        // N2 negativ-kontrola: active=true (default) i dalje prolazi.
        String token = buildToken("active@banka.rs", "EMPLOYEE", true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void legacyTokenWithoutActiveClaim_passes() throws Exception {
        // N2 backwards-compat: legacy access token bez active claim-a (izdat pre B7)
        // se NE odbija samo zbog nedostatka claim-a — tretira se kao aktivan.
        String legacy = Jwts.builder()
                .subject("legacy@banka.rs")
                .claim("type", "access")
                .claim("role", "EMPLOYEE")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + legacy);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isNotEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }
}
