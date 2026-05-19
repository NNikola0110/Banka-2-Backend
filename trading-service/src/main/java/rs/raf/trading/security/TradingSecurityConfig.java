package rs.raf.trading.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import rs.raf.trading.internalapi.config.InternalAuthFilter;

/**
 * Stateless security za trading-service. JWT filter postavlja context;
 * actuator/OpenAPI su javni, ostalo ima fino-granularne rute.
 *
 * <p><b>Faza 2f-5a (cutover):</b> trading-service je sad live vlasnik trgovinskih
 * endpoint-a iza api-gateway-a. Gateway samo rutira — autorizacija je OVDE.
 * Rute su preslikane iz monolitovog {@code GlobalSecurityConfig}-a (trgovinski
 * deo): {@code /orders}, {@code /listings}, {@code /actuaries}, {@code /portfolio},
 * {@code /tax}, {@code /exchanges}, {@code /options}, {@code /margin-accounts},
 * {@code /otc}, {@code /funds}, {@code /profit-bank}. Per-permisija autoritete
 * ({@code SUPERVISOR}, {@code ADMIN} ...) postavlja {@link TradingJwtAuthenticationFilter}
 * razresavanjem preko banka-core internog API-ja.
 *
 * <p>{@code /internal/**} rute zasticene su X-Internal-Key-em — {@link InternalAuthFilter}
 * validira kljuc i postavlja {@code ROLE_INTERNAL} (banka-core {@code interbank}
 * seam ka {@code /internal/portfolio/**}). JWT filter ne dira {@code /internal/**}
 * (vidi {@code TradingJwtAuthenticationFilter.shouldNotFilter}).
 */
@Configuration
@EnableMethodSecurity
public class TradingSecurityConfig {

    private final TradingJwtAuthenticationFilter jwtFilter;
    private final InternalAuthFilter internalAuthFilter;

    public TradingSecurityConfig(TradingJwtAuthenticationFilter jwtFilter,
                                 InternalAuthFilter internalAuthFilter) {
        this.jwtFilter = jwtFilter;
        this.internalAuthFilter = internalAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // ── Javne rute ───────────────────────────────────────────────
                .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // ── Interni API (X-Internal-Key) — banka-core interbank seam ──
                .requestMatchers("/internal/**").hasAuthority("ROLE_INTERNAL")
                // ── Orderi (Celina 3) ────────────────────────────────────────
                // Specificne rute PRE generickih (Spring uzima prvi match).
                .requestMatchers(HttpMethod.GET, "/orders").hasAnyRole("ADMIN", "EMPLOYEE")
                .requestMatchers(HttpMethod.POST, "/orders").authenticated()
                .requestMatchers(HttpMethod.GET, "/orders/my").authenticated()
                .requestMatchers(HttpMethod.GET, "/orders/{id}").authenticated()
                .requestMatchers("/orders/*/approve", "/orders/*/decline").hasAnyRole("ADMIN", "EMPLOYEE")
                // ── Berzanski katalog (listings) ─────────────────────────────
                .requestMatchers(HttpMethod.POST, "/listings/refresh").hasAnyRole("ADMIN", "EMPLOYEE")
                // ── Aktuari (supervizorski portal) ───────────────────────────
                .requestMatchers("/actuaries/**").hasAnyAuthority("ROLE_ADMIN", "ADMIN", "SUPERVISOR")
                // ── Portfolio ────────────────────────────────────────────────
                .requestMatchers("/portfolio/**").authenticated()
                // ── Porez ────────────────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/tax/my", "/tax/my/breakdown").authenticated()
                .requestMatchers("/tax/**").hasAnyAuthority("ROLE_ADMIN", "ADMIN", "SUPERVISOR")
                // ── Berze (exchanges) ────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/exchanges", "/exchanges/**").permitAll()
                .requestMatchers(HttpMethod.PATCH, "/exchanges/*/test-mode").hasAnyRole("ADMIN", "EMPLOYEE")
                // ── Opcije ───────────────────────────────────────────────────
                .requestMatchers(HttpMethod.GET, "/options", "/options/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/options/*/exercise").authenticated()
                .requestMatchers(HttpMethod.POST, "/options/generate").hasRole("ADMIN")
                // ── Margin racuni ────────────────────────────────────────────
                .requestMatchers(HttpMethod.POST, "/margin-accounts/*/withdraw").hasRole("CLIENT")
                .requestMatchers(HttpMethod.POST, "/margin-accounts/*/deposit").hasRole("CLIENT")
                .requestMatchers("/margin-accounts/**").authenticated()
                // ── OTC: po Celini 4 (Nova), samo SUPERVIZORI i KLIJENTI sa
                //    permisijom; agenti su iskljuceni (finalna provera u OtcService). ─
                .requestMatchers("/otc/**").hasAnyAuthority(
                        "ROLE_ADMIN", "ROLE_CLIENT", "ADMIN", "SUPERVISOR", "CLIENT")
                // ── Investicioni fondovi ─────────────────────────────────────
                .requestMatchers("/funds/**").authenticated()
                // ── Profit Banke: samo supervizori ───────────────────────────
                .requestMatchers("/profit-bank/**").hasAnyAuthority(
                        "ROLE_ADMIN", "ADMIN", "SUPERVISOR")
                .anyRequest().authenticated())
            .addFilterBefore(internalAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
