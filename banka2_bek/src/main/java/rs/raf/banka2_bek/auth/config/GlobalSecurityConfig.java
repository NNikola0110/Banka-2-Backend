package rs.raf.banka2_bek.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import rs.raf.banka2_bek.internalapi.config.InternalAuthFilter;

import java.util.List;


@Configuration
@EnableMethodSecurity
public class GlobalSecurityConfig  {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InterbankAuthFilter interbankAuthFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final InternalAuthFilter internalAuthFilter;

    public GlobalSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                                InterbankAuthFilter interbankAuthFilter,
                                AuthRateLimitFilter authRateLimitFilter,
                                InternalAuthFilter internalAuthFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.interbankAuthFilter = interbankAuthFilter;
        this.authRateLimitFilter = authRateLimitFilter;
        this.internalAuthFilter = internalAuthFilter;
    }

    /**
     * CORS origin lista cita se iz {@code CORS_ALLOWED_ORIGINS} env var-a
     * (comma-separated). Ako je var empty — koristimo dev default
     * (localhost:3000 + 5173). Production override:
     * {@code CORS_ALLOWED_ORIGINS=https://banka.example.com,https://admin.example.com}.
     */
    // Default-i pokrivaju sve dev FE port-ove:
    //   3000 — standardni nginx host port (CLAUDE.md default)
    //   3500 — fallback kad Hyper-V/WinNAT na Windows-u rezervise 2996-3095 range
    //   5173 — Vite dev server (`npm run dev` direktno, bez Docker-a)
    @org.springframework.beans.factory.annotation.Value("${cors.allowed-origins:http://localhost:3000,http://localhost:3500,http://localhost:5173}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // SSE async-dispatch fix: kad SSE emitter za Arbitro chat zavrsi
                        // streamovanje, Spring vrati request kroz filter chain na ASYNC
                        // dispatch type. SecurityContextHolder ThreadLocal je u tom
                        // trenutku prazan (clean async thread), pa AuthorizationFilter
                        // baca AccessDenied posle response-a koji je vec committed.
                        // Browser to vidi kao ERR_INCOMPLETE_CHUNKED_ENCODING.
                        // Standardni Spring obrazac je permitAll() na ASYNC dispatch —
                        // originalna REQUEST dispatch je vec autorizovana, ASYNC je
                        // samo nastavak iste request-response transakcije.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                        // ============================================================
                        // TODO [Razvojni ciklus - nove rute za bezbednosnu konfiguraciju]
                        //
                        // Pri implementaciji novih feature-a dodati requestMatchers za sledece rute
                        // (pratiti postojeci obrazac u ovom fajlu — npr. /actuaries/** za role-based,
                        // /otc/** za authenticated):
                        //   - /audit/**                    -> ADMIN + SUPERVISOR samo         [B7 - Audit log]
                        //   - /otc/negotiation-history/**  -> authenticated()                 [B10 - Istorija OTC pregovora]
                        // Specificne rute moraju doci PRE generickih matcher-a.
                        // Spec: Zadaci_Backend.pdf.
                        //
                        // Napomena: zadaci B5 (Price Alert), B6 (Watchlist), B8 (Trajni nalozi)
                        // i B9 (Dividende) su trgovinski — njihovi skeleton paketi su premesteni
                        // u `trading-service` (Faza 2e); rute /price-alerts, /watchlists,
                        // /recurring-orders, /dividends konfigurisu se u TradingSecurityConfig.
                        // ============================================================
                        // [B1] In-app notifications: svi autentifikovani korisnici (klijenti +
                        // zaposleni) mogu citati, oznacavati kao procitano i listati svoje
                        // notifikacije. Vlasnistvo se proverava na service sloju.
                        .requestMatchers("/notifications/**").authenticated()
                        .requestMatchers(
                                "/error",
                                "/auth/register",
                                "/auth/login",
                                "/auth/password_reset/request",
                                "/auth/password_reset/confirm",
                                "/auth/refresh",
                                "/auth/logout",
                                "/auth-employee/activate",
                                "/auth-employee/activation-token/*/status",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/v3/api-docs",
                                "/exchange-rates",
                                "/exchange/calculate",
                                "/actuator/health",
                                "/actuator/info",
                                "/actuator/prometheus"
                        ).permitAll()
                        .requestMatchers("/employees/**").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers("/clients/**").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/accounts/requests/*/approve").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/accounts/requests/*/reject").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/accounts/requests").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers("/accounts/bank").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers("/accounts/all/**").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers("/accounts/client/**").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers("/cards/*/unblock").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers("/cards/*/deactivate").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/cards/requests/*/approve").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/cards/requests/*/reject").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/cards/requests").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/loans/requests/my").authenticated()
                        // Spec Celina 2 §33: klijent moze da podnese zahtev za kredit
                        // (POST /loans). Approve/reject ostaje ADMIN/EMPLOYEE preko
                        // PATCH /loans/requests/* ruta dole.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/loans").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/loans/*/early-repayment").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/loans/my").authenticated()
                        .requestMatchers("/loans/requests/**").hasAnyRole("ADMIN", "EMPLOYEE")
                        // ============================================================
                        // Trgovinske rute (/orders /portfolio /tax /funds /actuaries
                        // /listings /exchanges /options /margin-accounts /otc /profit-bank)
                        // su iseljene u `trading-service` (pod-faza 2f cutover) — monolit
                        // vise nema te kontrolere. Gateway (nginx api-gateway) rutira te
                        // prefikse na trading-service:8082; trading-service ima sopstvenu
                        // route-guard konfiguraciju. /interbank/** (ukljucujuci
                        // /interbank/otc/**) OSTAJE na monolitu — inter-bank protokol.
                        // ============================================================
                        // FE-facing wrapper za inter-bank OTC: /interbank/otc/** je
                        // JWT-authenticated (klijent/supervizor pristupaju iz svog
                        // browser-a), NE X-Api-Key. MORA biti DEKLARISAN PRE generic
                        // /interbank/** matcher-a (Spring uzima prvi match).
                        .requestMatchers("/interbank/otc/**").authenticated()
                        // Inter-bank /interbank endpoint je JEDINSTVEN ulaz za druge banke;
                        // InterbankAuthFilter validira X-Api-Key i postavlja ROLE_INTERBANK
                        // authority pre nego sto request stigne ovde (vidi protokol §2.10).
                        // /interbank — JEDINSTVEN ulaz za 2PC poruke izmedju banaka (§2.11)
                        // /public-stock, /negotiations/**, /user/{rn}/{id} — §3.x OTC pozivi
                        // Sve trazi ROLE_INTERBANK koji InterbankAuthFilter postavlja na osnovu
                        // valjanog X-Api-Key headera (§2.10).
                        .requestMatchers("/interbank/**", "/public-stock",
                                "/negotiations/**", "/user/*/**")
                                .hasAuthority("ROLE_INTERBANK")
                        // Arbitro asistent — svi autentifikovani korisnici (klijenti + zaposleni)
                        .requestMatchers("/assistant/**").authenticated()
                        // Stedna knjizica (Celina 2): klijentske rute su authenticated,
                        // admin rute zahtevaju ADMIN ili SUPERVISOR rolu.
                        // /admin/savings/** mora biti PRE anyRequest da uzme prednost.
                        .requestMatchers(HttpMethod.POST, "/savings/deposits").authenticated()
                        .requestMatchers(HttpMethod.GET, "/savings/deposits/my").authenticated()
                        .requestMatchers(HttpMethod.GET, "/savings/deposits/*").authenticated()
                        .requestMatchers(HttpMethod.GET, "/savings/deposits/*/transactions").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/savings/deposits/*/auto-renew").authenticated()
                        .requestMatchers(HttpMethod.POST, "/savings/deposits/*/withdraw-early").authenticated()
                        .requestMatchers(HttpMethod.GET, "/savings/rates").authenticated()
                        .requestMatchers("/admin/savings/**").hasAnyAuthority("ROLE_ADMIN", "ADMIN", "SUPERVISOR")
                        // Interni API (Faza 2 - SAGA seam ka trading-service).
                        // InternalAuthFilter validira X-Internal-Key i postavlja ROLE_INTERNAL.
                        // MORA biti deklarisan PRE anyRequest() matcher-a.
                        .requestMatchers("/internal/**").hasAuthority("ROLE_INTERNAL")
                        .anyRequest().authenticated()
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        )
                // Filter chain order: rate limit najdublje, pa interbank API key,
                // pa interni API key, pa JWT auth. Svaki invalidan auth pokusaj puca
                // pre nego sto stigne do JWT validacije (sprecava brute-force i
                // side-channel napade).
                .addFilterBefore(authRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(interbankAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Security response headers (defense-in-depth) — primenjuju se na sve
                // odgovore. nginx ima svoje header-e takodje, ali Spring dodaje za
                // direktan dev pristup BE-u (8080) gde nginx ne stoji.
                .headers(headers -> headers
                        .contentTypeOptions(c -> {})       // X-Content-Type-Options: nosniff
                        .frameOptions(f -> f.deny())       // X-Frame-Options: DENY (clickjacking)
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)) // 1 godina HSTS
                        .referrerPolicy(r -> r.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(p -> p.policy(
                                "geolocation=(), microphone=(), camera=()"))
                );

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Origin-i iz env var-a (comma-separated). Production: prebaci na
        // https://banka.example.com,https://admin.example.com — sve ostale rejectaj.
        List<String> origins = java.util.Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Specificni header-i umesto "*" — wildcard sa allowCredentials=true je
        // forbidden po CORS spec-u (browser ce odbiti) i smanjuje attack surface.
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "X-Requested-With",
                "X-Api-Key", "X-Forwarded-For", "Cache-Control"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L); // preflight cache 1h

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}