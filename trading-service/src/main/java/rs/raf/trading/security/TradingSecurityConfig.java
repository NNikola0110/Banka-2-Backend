package rs.raf.trading.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import rs.raf.trading.internalapi.config.InternalAuthFilter;

/**
 * Stateless security za trading-service. JWT filter postavlja context;
 * actuator/OpenAPI su javni, ostalo trazi autentifikaciju.
 *
 * <p>{@code /internal/**} rute zasticene su X-Internal-Key-em — {@link InternalAuthFilter}
 * validira kljuc i postavlja {@code ROLE_INTERNAL} (banka-core {@code interbank}
 * seam ka {@code /internal/portfolio/**}, faza 2f). JWT filter ne dira
 * {@code /internal/**} (vidi {@code TradingJwtAuthenticationFilter.shouldNotFilter}).
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
                .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/internal/**").hasAuthority("ROLE_INTERNAL")
                .anyRequest().authenticated())
            .addFilterBefore(internalAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
