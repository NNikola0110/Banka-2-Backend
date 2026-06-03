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
                                "/auth-employee/resend-activation",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/v3/api-docs",
                                // /actuator/health/** pokriva: /actuator/health (aggregate),
                                // /actuator/health/liveness (K8s liveness probe) i
                                // /actuator/health/readiness (K8s readiness probe). Wildcard
                                // path matcher ('/**') propusta sve sub-path-eve bez auth.
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus"
                        ).permitAll()
                        // R1 364 (P2-authz-method-1): menjacnica — kurs lista i kalkulator
                        // vise NISU permitAll. Spec (Celina 2 §Menjacnica) opisuje ulogovanog
                        // klijenta; anonimni pristup je trosio eksternu Fixer/FX kvotu bez
                        // autentifikacije (DoS na kvotu / besplatan FX feed). Sad zahtevaju
                        // authenticated() (svaki ulogovan korisnik sme da vidi kurs/kalkulise).
                        .requestMatchers("/exchange-rates", "/exchange/calculate").authenticated()
                        .requestMatchers("/employees/**").hasAnyRole("ADMIN", "EMPLOYEE")
                        // Self-lookup za klijenta — vraca svoj zapis po JWT email.
                        // MORA biti PRE generic /clients/** matchera (longest-prefix wins).
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/clients/me").authenticated()
                        .requestMatchers("/clients/**").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/accounts/requests/*/approve").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/accounts/requests/*/reject").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/accounts/requests").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers("/accounts/bank").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers("/accounts/all/**").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers("/accounts/client/**").hasAnyRole("ADMIN", "EMPLOYEE")
                        // P0-B9 N1 (IDOR/authz): kreiranje racuna i promena statusa racuna
                        // su ZAPOSLENICKE operacije. Bez ovih matcher-a generic
                        // anyRequest().authenticated() je puštao SVAKOG klijenta da:
                        //  (a) POST /accounts kreira racun (i silent-fallback na prvog
                        //      zaposlenog kao "kreatora"), i
                        //  (b) PATCH /accounts/{id}/status promeni status TUDJEG racuna
                        //      (zero-authz — npr. da reaktivira blokiran/zatvoren racun).
                        // MORA biti PRE generic /accounts ruta (npr. /accounts/{id},
                        // /accounts/my) koje ostaju client-accessible. Longest-prefix +
                        // HTTP-metod specificnost: POST /accounts i PATCH .../status su
                        // uze od generic GET /accounts/{id}.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/accounts").hasAnyRole("ADMIN", "EMPLOYEE")
                        .requestMatchers(org.springframework.http.HttpMethod.PATCH, "/accounts/*/status").hasAnyRole("ADMIN", "EMPLOYEE")
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
                        // P0-B9 N3 (IDOR/authz): GET /loans (svi krediti, employee portal)
                        // je curio SVE kredite svih klijenata bilo kom autentifikovanom
                        // klijentu. Klijent svoje kredite vidi iskljucivo preko
                        // GET /loans/my (gore). Pojedinacni GET /loans/{id} i
                        // /loans/{id}/installments ostaju authenticated na URL nivou —
                        // ownership (kredit pripada klijentu) se proverava u service-u
                        // (LoanServiceImpl), jer klijent legitimno cita SVOJ kredit a
                        // employee bilo koji. Exact-path match (bez '/**') da NE uhvati
                        // /loans/{id}/* sub-rute.
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/loans").hasAnyRole("ADMIN", "EMPLOYEE")
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
                        // P1-9: FE poll ruta za inter-bank 2PC/OTC SAGA progres —
                        // JWT-authenticated (browser), NE X-Api-Key. MORA biti PRE
                        // generic /interbank/** matcher-a (Spring uzima prvi match).
                        .requestMatchers("/interbank/payments/**").authenticated()
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
                        // SEC-04: Audit log (B7) — citanje je strogo ograniceno na
                        // ADMIN i SUPERVISOR. Bez ovog matcher-a, generic
                        // anyRequest().authenticated() pravilo bi dozvolilo svakom
                        // klijentskom JWT-u da reach-uje /audit/** rute (CLIENT je
                        // authenticated() ali NE sme da vidi revizioni dnevnik).
                        // MORA biti deklarisan PRE anyRequest() matcher-a.
                        .requestMatchers("/audit/**").hasAnyAuthority("ROLE_ADMIN", "ADMIN", "SUPERVISOR")
                        // W3-T2: Spark fraud detection alerti — istom obrazcu kao /audit/**:
                        // ADMIN + SUPERVISOR only. CLIENT-i nikad ne smeju da vide ni risk
                        // score ni feature vektore drugih korisnika. MORA biti deklarisan
                        // PRE anyRequest() matcher-a.
                        .requestMatchers("/admin/fraud-alerts/**").hasAnyAuthority(
                                "ROLE_ADMIN", "ADMIN", "SUPERVISOR")
                        // Interni API (Faza 2 - SAGA seam ka trading-service).
                        // InternalAuthFilter validira X-Internal-Key i postavlja ROLE_INTERNAL.
                        // MORA biti deklarisan PRE anyRequest() matcher-a.
                        .requestMatchers("/internal/**").hasAuthority("ROLE_INTERNAL")
                        .anyRequest().authenticated()
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // P1-error-contract-1: bez custom entrypoint-a Spring 6 za anonimni
                // zahtev na zasticenoj ruti vraca Http403ForbiddenEntryPoint (403 PRAZNO),
                // ne 401. FE interceptor (api.ts) radi /auth/refresh SAMO na 401 →
                // istekao access token bi izbacio korisnika iako ima validan refresh.
                // authenticationEntryPoint → 401 JSON (anoniman/istekao token),
                // accessDeniedHandler → 403 JSON (autentifikovan ali bez prava).
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                SecurityErrorResponder.writeJson(response,
                                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                                        SecurityErrorResponder.SESSION_EXPIRED_MESSAGE))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                SecurityErrorResponder.writeJson(response,
                                        org.springframework.http.HttpStatus.FORBIDDEN,
                                        SecurityErrorResponder.ACCESS_DENIED_MESSAGE))
                )
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
                        // P2-config-2 (R4 1786): uskladjeno sa FE nginx Permissions-Policy
                        // (Banka-2-Frontend/nginx.conf) — geolocation=(self) za /branches
                        // Leaflet mapu, microphone=(self) za Arbitro voice (Web Speech API);
                        // camera/payment/usb disabled.
                        // Ranije je Spring imao geolocation=()/microphone=() (drift) sto je
                        // blokiralo te feature-e na direktan BE pristup.
                        // Bug 6 (03.06): uklonjen `interest-cohort=()` — moderni browseri ga
                        // odbacuju kao nepoznat policy-directive (warning u konzoli).
                        .permissionsPolicyHeader(p -> p.policy(
                                "geolocation=(self), microphone=(self), camera=(), "
                                        + "payment=(), usb=()"))
                );

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * R1-710: {@link InternalAuthFilter} je {@code @Component} koji nasledjuje
     * {@code OncePerRequestFilter} — Spring Boot ga zato AUTO-registruje kao servlet
     * filter U GLAVNOM servlet chain-u, a mi ga DODATNO dodajemo u Spring Security chain
     * ({@code addFilterBefore} gore). Posledica: filter se izvrsava DVAPUT za {@code /internal/**}
     * zahteve (jednom u servlet chain-u, jednom u security chain-u). Ovaj
     * {@link FilterRegistrationBean} sa {@code setEnabled(false)} gasi servlet-container
     * auto-registraciju; jedino merodavna ostaje Security-chain registracija (gde filter
     * mora da postavi {@code ROLE_INTERNAL} pre {@code AuthorizationFilter}-a).
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<InternalAuthFilter>
            internalAuthFilterServletDisable(InternalAuthFilter internalAuthFilter) {
        var registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(internalAuthFilter);
        registration.setEnabled(false);
        return registration;
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