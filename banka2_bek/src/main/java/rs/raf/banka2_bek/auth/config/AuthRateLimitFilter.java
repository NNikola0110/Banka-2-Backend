package rs.raf.banka2_bek.auth.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import rs.raf.banka2_bek.monitoring.BusinessMetrics;

import java.io.IOException;
import java.time.Duration;

/**
 * Per-IP rate limiting na auth endpoint-ima koji uzimaju kredencijale ili token-e:
 * <ul>
 *   <li>{@code POST /auth/login} — sprecava brute-force lozinki</li>
 *   <li>{@code POST /auth/refresh} — sprecava refresh-token spam-ovanje</li>
 *   <li>{@code POST /auth/password_reset/request} — sprecava email bombing</li>
 *   <li>{@code POST /auth-employee/activate} — sprecava token guess</li>
 * </ul>
 *
 * Limit: <b>10 zahteva u 60s po IP-u</b>. Token-bucket algoritam (Bucket4j) — ako
 * korisnik kucka pravilan password, prosao je u <50ms i ima jos 9 token-a; ako napadac
 * gada 1000 lozinki/min iz iste IP, dobija 429 Too Many Requests posle 10. zahteva.
 *
 * <p>In-memory mapa po IP-u. Za multi-instance produkciju treba prebaciti na
 * Bucket4j JCache backend (Caffeine/Redis), ali za jednu BE instancu je dovoljno.
 *
 * <p>Filter se izvrsava PRE {@code JwtAuthenticationFilter}-a — neuspeli login
 * pokusaji ne moraju ni da idu kroz JWT auth chain.
 */
@Component
// `auth.rate-limit.enabled=false` u test profile-u potpuno gasi filter (bean nije
// registrovan, GlobalSecurityConfig ga injektuje sa @Autowired(required=false) ekvivalentom
// preko Spring constructor injection-a). matchIfMissing=true znaci default = enabled.
@ConditionalOnProperty(name = "auth.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    /**
     * Kapacitet je config-driven preko {@code auth.rate-limit.capacity} property-ja.
     * Production vrednost = 10/min (sigurnost). Integration testovi koji ispaljuju
     * 50-100 zahteva po sekundi prema istom endpoint-u override-uju u
     * {@code application-test.properties} sa visokim capacity-jem (npr. 100000).
     */
    private final int capacity;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    /**
     * P1-auth-2 (R2 1367): da li se veruje {@code X-Forwarded-For} header-u za
     * razresavanje klijentskog IP-a. Kad je BE direktno izlozen (host port 8081,
     * bez reverse proxy-ja), napadac moze da posalje proizvoljan XFF header i
     * dobije svez bucket po zahtevu → rate-limit potpuno zaobidjen. Default je
     * {@code false} (koristi {@code remoteAddr}, ne moze se spoof-ovati na TCP
     * nivou). U deploy-u iza poznatog reverse proxy-ja (api-gateway nginx) se
     * postavlja na {@code true} preko {@code auth.rate-limit.trust-xff=true} —
     * tada XFF nosi pravi client IP koji proxy upisuje.
     */
    private final boolean trustForwardedFor;

    /**
     * R7 observability: inkrementira {@code banka2_rate_limit_hit_total} na svaki 429
     * (input za {@code RateLimitFloodActive} Prometheus alert). Nullable da unit-testovi
     * filtera mogu da ga konstruisu bez Micrometer registry-ja (increment je null-guarded).
     */
    private final BusinessMetrics businessMetrics;

    public AuthRateLimitFilter(@Value("${auth.rate-limit.capacity:10}") int capacity,
                               @Value("${auth.rate-limit.trust-xff:false}") boolean trustForwardedFor,
                               BusinessMetrics businessMetrics) {
        this.capacity = capacity;
        this.trustForwardedFor = trustForwardedFor;
        this.businessMetrics = businessMetrics;
    }

    /**
     * IP → Bucket cache.
     *
     * BE-AUTH-08 fix: prethodno je bila {@code ConcurrentHashMap} koja nikad nije
     * evict-ovala unose, sto pod botnet napadom (1000 razlicitih source IP-ova
     * iz iste mreze, ili spoofed XFF header-ima) raste neograniceno → memory leak.
     *
     * Caffeine cache sa {@code expireAfterAccess(15min)} (~3x window) i
     * {@code maximumSize(50_000)} resava oba problema:
     * <ul>
     *   <li>idle IP-ovi koji nisu udarili u zadnjih 15min se brisu (3x bigger od
     *       60s window-a — sigurno duže od bilo kog rate-limit zahteva)</li>
     *   <li>maximumSize bound osigurava da napad sa milionima jedinstvenih IP-ova
     *       ne moze da rasporedi sve dostupne memorije (Caffeine LRU evicts najstarije)</li>
     * </ul>
     *
     * Side-effect (acceptable): napadac koji sa istim IP-em udari samo jednom u
     * 15min i onda 60s+ kasnije ponovo, dobija fresh bucket — ali to je manje strogo
     * od originalnog ponasanja samo na granici (15min idle). U praksi: brute-force
     * pokusaji su sustained, evict nije problem.
     */
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(15))
            .maximumSize(50_000)
            .build();

    private static final java.util.Set<String> RATE_LIMITED_PATHS = java.util.Set.of(
            "/auth/login",
            "/auth/refresh",
            "/auth/password_reset/request",
            // P1-auth-2 (R2 1366): confirm troši reset token — bez rate-limit-a je
            // moguć brute-force UUID reset tokena. resend-activation šalje email —
            // bez limita je email-bombing vektor. Oba sad u istom bucket-u (10/min/IP).
            "/auth/password_reset/confirm",
            "/auth-employee/activate",
            "/auth-employee/resend-activation",
            // SEC-09: rate-limit OTP verifikacije po IP-u — kombinovano sa per-email
            // counter-om u OtpService (3 fail -> blocked=true). Filter koristi isti
            // capacity (default 10/min), gadja oba glavna OTP entry-point-a:
            // - /payments/verify: klijentski payment OTP flow (PaymentController)
            // - /otp/verify: alias / forward-compat (ako BE doda public route)
            "/payments/verify",
            "/otp/verify"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())
                || !RATE_LIMITED_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request, trustForwardedFor);
        // BE-AUTH-08: Caffeine.get(key, fn) je atomic + thread-safe; null vrednost
        // se nikad ne kesira jer newBucket() uvek vraca non-null.
        Bucket bucket = buckets.get(clientIp, ip -> newBucket());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            // R7 observability: zabelezi rate-limit hit pre pisanja odgovora
            // (feeds RateLimitFloodActive alert). Null-guard za unit-test konstrukciju.
            if (businessMetrics != null) {
                businessMetrics.recordRateLimitHit();
            }
            // 429 Too Many Requests — RFC 6585. Retry-After hint = 60s window.
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(WINDOW.getSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"Too many requests\",\"message\":\"Limit od " + capacity
                            + " zahteva u " + WINDOW.getSeconds() + "s je premasen. "
                            + "Pokusajte ponovo za " + WINDOW.getSeconds() + " sekundi.\"}");
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillIntervally(capacity, WINDOW).build())
                .build();
    }

    /**
     * Razresava client IP za rate-limit bucket.
     *
     * <p>P1-auth-2 (R2 1367): {@code X-Forwarded-For} je proizvoljan request
     * header — bilo ko ga moze postaviti. Ako mu se bezuslovno veruje, napadac
     * salje svez XFF po zahtevu i dobija fresh bucket svaki put → rate-limit je
     * potpuno zaobidjen. Zato se XFF cita SAMO kad je {@code trustForwardedFor}
     * (BE iza poznatog reverse proxy-ja koji header postavlja). Inace se koristi
     * {@code remoteAddr} (TCP peer — ne moze se spoof-ovati na aplikativnom sloju).
     */
    private static String resolveClientIp(HttpServletRequest request, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
