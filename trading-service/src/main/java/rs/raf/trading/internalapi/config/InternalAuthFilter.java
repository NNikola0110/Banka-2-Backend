package rs.raf.trading.internalapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Stiti {@code /internal/**} rute trading-service-a deljenim X-Internal-Key
 * tajnim kljucem (mirror banka-core {@code internalapi.config.InternalAuthFilter}).
 *
 * <p>Faza 2f: trading-service sada I PRIMA interne pozive — banka-core
 * {@code interbank} paket zove {@code /internal/portfolio/**} jer posle cutover-a
 * {@code portfolios}/{@code listings} tabele zive samo u trading_db.
 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    private final String apiKey;

    public InternalAuthFilter(@Value("${internal.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader("X-Internal-Key");
        if (provided == null || apiKey == null || apiKey.isBlank() || !apiKey.equals(provided)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Nevazeci X-Internal-Key\"}");
            return;
        }
        var auth = new UsernamePasswordAuthenticationToken(
                "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }
}
