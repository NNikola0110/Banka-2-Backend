package rs.raf.banka2_bek.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import rs.raf.banka2_bek.auth.service.CustomUserDetailsService;
import rs.raf.banka2_bek.auth.service.JwtBlacklistService;
import rs.raf.banka2_bek.auth.service.JwtService;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final JwtBlacklistService blacklistService;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   CustomUserDetailsService userDetailsService,
                                   JwtBlacklistService blacklistService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.blacklistService = blacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        // Opciono.1 — odbaci blacklisted (logged-out) tokene pre signature check-a.
        if (blacklistService.isBlacklisted(token)) {
            // P1-error-contract-1: JSON telo {"message":...} umesto praznog tela,
            // da Mobile/FE prikazu "Sesija je istekla" umesto generic login greske.
            SecurityErrorResponder.writeJson(response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                    SecurityErrorResponder.SESSION_EXPIRED_MESSAGE);
            return;
        }

        try {
            // SEC-03: odbaci refresh tokene — oni imaju 7-dnevni TTL i sluze SAMO za
            // /auth/refresh endpoint. Ako neko pokusa da koristi refresh token kao
            // Bearer access token, oborimo zahtev sa 401. Tokeni generisani pre fix-a
            // nemaju "type" claim — tretiraju se kao access (backwards-compat).
            if (jwtService.isRefreshToken(token)) {
                SecurityErrorResponder.writeJson(response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                        SecurityErrorResponder.SESSION_EXPIRED_MESSAGE);
                return;
            }

            String email = jwtService.extractEmail(token);

            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                // N1: aktivnost/locked status se enforce-uje pri SVAKOM zahtevu, ne samo
                // pri login-u. Bez ovoga, deaktiviran ili zakljucan nalog sa jos-vazecim
                // access token-om (do 15 min) ili refresh-izdatim novim access token-om
                // (do 7 dana) zadrzava pun pristup. Odbacujemo sa 401 i NE pozivamo
                // downstream chain — kao kod blacklist/refresh-token grane.
                if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
                    SecurityErrorResponder.writeJson(response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                            SecurityErrorResponder.SESSION_EXPIRED_MESSAGE);
                    return;
                }

                if (jwtService.isTokenValid(token, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            SecurityErrorResponder.writeJson(response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                    SecurityErrorResponder.SESSION_EXPIRED_MESSAGE);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
