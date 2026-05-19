package rs.raf.trading.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Cita `Authorization: Bearer <jwt>`, validira ga lokalno i postavlja
 * security context (principal = email, authority = ROLE_<role>). Token koji
 * je prisutan ali nevalidan → 401. Bez tokena → propusti (security config
 * odlucuje; permitAll rute rade).
 */
@Component
public class TradingJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtValidator jwtValidator;

    public TradingJwtAuthenticationFilter(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
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
        var authorities = role == null ? List.<SimpleGrantedAuthority>of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var auth = new UsernamePasswordAuthenticationToken(email, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }
}
