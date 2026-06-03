package rs.raf.trading.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cita `Authorization: Bearer <jwt>`, validira ga lokalno i postavlja
 * security context (principal = email). Token koji je prisutan ali nevalidan →
 * 401. Bez tokena → propusti (security config odlucuje; permitAll rute rade).
 *
 * <p><b>Autoriteti (Faza 2f-5a):</b> JWT nosi samo {@code role} claim
 * ({@code ADMIN}/{@code EMPLOYEE}/{@code CLIENT}) — bez permisija. Filter uvek
 * postavlja {@code ROLE_<role>}; za svaku ulogu dodatno razresava per-permisija
 * autoritete preko {@link TradingPermissionResolver} (banka-core interni API +
 * Caffeine kes): zaposleni dobijaju {@code SUPERVISOR}/{@code AGENT}/
 * {@code TRADE_STOCKS} ..., a klijenti sa {@code canTradeStocks=true} dobijaju
 * {@code TRADE_STOCKS} (P0-2). Bez razresavanja bi supervizor — JWT
 * {@code role=EMPLOYEE} — dobijao 403 na supervizorske rute / {@code @PreAuthorize},
 * a svaki klijent 403 na {@code POST /orders}. Razresavanje je rezilijentno:
 * pad lookup-a → samo {@code ROLE_<role>}, request ne puca; banka-core vraca
 * praznu listu za korisnike bez permisija.
 *
 * <p><b>Auth gating (P0-T4):</b> (N1) refresh token se odbija u
 * {@link JwtValidator#validate} (type=refresh) → 7-dnevni refresh vise nije validan
 * Bearer na trading rutama; (N2) access token sa {@code active=false} claim-om
 * (deaktiviran nalog) se ovde odbija sa 401 pre postavljanja context-a (mirror
 * banka-core {@code JwtAuthenticationFilter} {@code isEnabled()} provere). Locked
 * status i banka-core blacklist consult (N3) ostaju P1 — nisu dostupni trading-u
 * bez novog banka-core seam-a / deljenog store-a.
 */
@Component
public class TradingJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtValidator jwtValidator;
    private final TradingPermissionResolver permissionResolver;

    public TradingJwtAuthenticationFilter(JwtValidator jwtValidator,
                                          TradingPermissionResolver permissionResolver) {
        this.jwtValidator = jwtValidator;
        this.permissionResolver = permissionResolver;
    }

    /**
     * {@code /internal/**} rute autentifikuje {@code InternalAuthFilter} preko
     * X-Internal-Key — JWT filter ih ne dira (interni pozivalac, banka-core
     * {@code interbank} seam, ne nosi Bearer token).
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(7);
        Optional<Claims> claimsOpt = jwtValidator.validate(token);
        if (claimsOpt.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Nevazeci ili istekao token\"}");
            return;
        }
        Claims claims = claimsOpt.get();
        // N2 (P0-T4, mirror B7 banka-core JwtAuthenticationFilter): deaktiviran
        // nalog ne sme da zadrzi trading pristup. banka-core JwtService stavlja
        // `active` flag na svaki access token; ako je EKSPLICITNO false, odbij sa
        // 401 i ne razresavaj permisije ni postavljaj context. `active` je snapshot
        // u trenutku izdavanja tokena (do 15min stale) — to je najjaca provera koju
        // trading moze da uradi bez per-request banka-core lookup-a. Tokeni bez
        // `active` claim-a (legacy/pre-B7) tretiraju se kao aktivni (backwards-compat).
        // NAPOMENA: locked status NIJE dostupan trading-u (nema claim-a ni internal
        // lookup polja) — vidi P1 napomenu; pun real-time enforce zahteva blacklist
        // consult (N3, P1).
        if (Boolean.FALSE.equals(claims.get("active", Boolean.class))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Nalog je deaktiviran\"}");
            return;
        }
        String email = claims.getSubject();
        String role = claims.get("role", String.class);
        var auth = new UsernamePasswordAuthenticationToken(
                email, null, buildAuthorities(role, email));
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }

    /**
     * Gradi autoritete: {@code ROLE_<role>} + per-permisija autoritete razresene
     * preko banka-core. Lookup se radi za svaku poznatu ulogu — zaposleni dobijaju
     * {@code SUPERVISOR}/{@code AGENT}/{@code TRADE_STOCKS} ..., a klijenti sa
     * {@code canTradeStocks=true} dobijaju {@code TRADE_STOCKS} (P0-2). Resolver je
     * rezilijentan (pad → prazna lista) pa klijent bez trade prava i nepoznat
     * korisnik dobijaju samo {@code ROLE_<role>}. Tokeni bez {@code role} claim-a
     * ne dobijaju nista.
     */
    private List<GrantedAuthority> buildAuthorities(String role, String email) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (role == null) {
            return authorities;
        }
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        // Razresi permisije za svaku ulogu: zaposleni → SUPERVISOR/AGENT/TRADE_STOCKS,
        // klijent sa canTradeStocks=true → TRADE_STOCKS. banka-core vraca praznu listu
        // za korisnike bez permisija (resolver je rezilijentan na pad lookup-a).
        for (String permission : permissionResolver.resolvePermissions(email)) {
            if (permission != null && !permission.isBlank()) {
                authorities.add(new SimpleGrantedAuthority(permission));
            }
        }
        return authorities;
    }
}
