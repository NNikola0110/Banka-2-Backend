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
 * postavlja {@code ROLE_<role>}; za zaposlene ({@code EMPLOYEE}/{@code ADMIN})
 * dodatno razresava per-permisija autoritete ({@code SUPERVISOR}, {@code AGENT},
 * {@code TRADE_STOCKS} ...) preko {@link TradingPermissionResolver} (banka-core
 * interni API + Caffeine kes). Bez toga bi supervizor — JWT {@code role=EMPLOYEE} —
 * dobijao 403 na supervizorske rute / {@code @PreAuthorize}. Razresavanje je
 * rezilijentno: pad lookup-a → samo {@code ROLE_<role>}, request ne puca.
 * Klijenti nemaju employee permisije pa se za njih lookup preskace.
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
        String email = claims.getSubject();
        String role = claims.get("role", String.class);
        var auth = new UsernamePasswordAuthenticationToken(
                email, null, buildAuthorities(role, email));
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }

    /**
     * Gradi autoritete: {@code ROLE_<role>} + (za zaposlene) per-permisija
     * autoritete razresene preko banka-core. Klijentima ({@code role=CLIENT}) i
     * tokenima bez {@code role} claim-a ne treba permisija lookup.
     */
    private List<GrantedAuthority> buildAuthorities(String role, String email) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (role == null) {
            return authorities;
        }
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        // Permisije ima samo zaposleni — banka-core JWT role je ADMIN ili EMPLOYEE
        // (CLIENT nema employee permisije).
        if ("EMPLOYEE".equals(role) || "ADMIN".equals(role)) {
            for (String permission : permissionResolver.resolvePermissions(email)) {
                if (permission != null && !permission.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority(permission));
                }
            }
        }
        return authorities;
    }
}
