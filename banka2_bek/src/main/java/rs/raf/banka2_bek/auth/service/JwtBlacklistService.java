package rs.raf.banka2_bek.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Opciono.1 — JWT token blacklist za logout.
 *
 * JWT je stateless po dizajnu, sto znaci da "logout" na BE strani zahteva
 * ili rotaciju JWT secreta (kompleksno i invalidira SVE tokene), ili
 * odrzavanje liste invalidovanih tokena dok god ne istekne TTL.
 *
 * Ova implementacija drzi {@link Cache} sa SHA-256 hash-om token-a kao
 * kljucem (ne pun JWT — sigurnost ako se cache exfiltrira).
 *
 * <h3>N3-a: per-token TTL (varijabilni expiry)</h3>
 *
 * Ranije je cache koristio fiksni {@code expireAfterWrite(20min)}. Posto je
 * refresh-token TTL 7 dana ({@link JwtService}), fiksni 20min eviktovao bi
 * blacklist-ovan (logout/ukraden) refresh token vec posle 20 minuta — narednih
 * ~7 dana token bi opet prolazio. Sada se SVAKI token cuva tacno do svog
 * STVARNOG {@code exp} claim-a: access ~15min, refresh ~7 dana. Posle exp-a
 * token je svejedno nevazeci (signature exp check ga odbija), pa nema svrhe
 * drzati ga duze u memoriji. Per-entry varijabilni TTL je realizovan preko
 * Caffeine {@link Expiry} (vrednost = epoch-millis exp-a; preostali TTL =
 * exp − now pri svakom create/update).
 *
 * <h3>BE-AUTH-07: HA / Multi-instance limitacija</h3>
 *
 * In-memory implementacija (Caffeine cache) NE preživljava restart servera
 * niti radi kroz multiple BE instance — svaka instanca ima nezavisan cache,
 * pa logout na instanci A ne sprecava token koriscenje na instanci B (sve
 * dok JWT exp ne istekne). Posledica: za KT3 demo (single instance, container
 * restart = fresh deploy + svi tokeni i tako neisteknuti) je dovoljno; za
 * production multi-instance deployment treba migracija na shared store.
 *
 * <h4>Redis backend ekstenzija (planirana, NIJE implementirana u ovoj rundi)</h4>
 *
 * Skeleton za buducu impl:
 * <ol>
 *   <li>Definisati interface {@code JwtBlacklist} sa {@code blacklist(token)} i
 *       {@code isBlacklisted(token)} metodama.</li>
 *   <li>Trenutnu klasu preimenovati u {@code JwtBlacklistServiceMemory} i implementirati
 *       interface; oznaceno
 *       {@code @ConditionalOnProperty(name="jwt.blacklist.backend", havingValue="memory", matchIfMissing=true)}.</li>
 *   <li>Kreirati {@code JwtBlacklistServiceRedis} (koristeci {@code spring-data-redis} +
 *       {@code RedisTemplate<String,String>.opsForValue().set(key, "1", ttl)} sa SETEX
 *       za atomicni per-token TTL = exp − now), oznacen
 *       {@code @ConditionalOnProperty(name="jwt.blacklist.backend", havingValue="redis")}.</li>
 *   <li>Konzumeri ({@code JwtAuthenticationFilter}, {@code AuthController}) injektuju interface,
 *       Spring resolva po property.</li>
 *   <li>Redis kljuc format: {@code jwt:blacklist:{sha256(token)}}; vrednost arbitrarna ("1").</li>
 *   <li>Multi-day infra task: deploy Redis u docker-compose + Bitnami/Redis stable image + secret
 *       management ({@code spring.data.redis.password} env) + HA failover preko Sentinel ako treba.</li>
 * </ol>
 *
 * Trenutni {@code @ConditionalOnProperty} na ovoj klasi obezbedjuje da se Memory backend
 * ne registruje ako neko vec doda Redis backend sa drugacijim {@code havingValue}.
 */
// BE-AUTH-07 fix: @ConditionalOnProperty omogucuje da se ova memory-only impl
// iskljuci u prilog Redis backend-a u buducnosti, bez bean kolizije. Default je
// memory (matchIfMissing=true) — postojeci deploy se ne menja.
@ConditionalOnProperty(
        name = "jwt.blacklist.backend",
        havingValue = "memory",
        matchIfMissing = true)
@Service
public class JwtBlacklistService {

    /**
     * Fallback TTL kad se {@code exp} ne moze izvuci (malformed/nepotpisan token,
     * ili JwtService nije dostupan u unit-test kontekstu). Mora pokriti refresh TTL
     * (7 dana) da blacklist nikad ne istekne pre samog tokena. Token koji ionako
     * nema validan {@code exp} ce pasti vec na signature/exp proveri u filteru.
     */
    private static final Duration FALLBACK_TTL = Duration.ofDays(7);

    private final JwtService jwtService;

    private Cache<String, Long> blacklist;

    /**
     * {@link JwtService} se injektuje {@code @Lazy} — nema ciklusa (JwtService
     * ne zavisi od blacklist-a), ali Lazy cuva od bilo kakve buduce promene reda
     * inicijalizacije bean-ova. Koristi se SAMO za citanje {@code exp} claim-a.
     */
    @Autowired
    public JwtBlacklistService(@Lazy JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /** No-arg konstruktor za unit testove (per-token TTL pada na FALLBACK_TTL). */
    public JwtBlacklistService() {
        this.jwtService = null;
    }

    @PostConstruct
    public void init() {
        // N3-a: per-entry varijabilni TTL. Vrednost cache-a je epoch-millis exp-a;
        // preostali TTL pri svakom create/update = exp − now (>=1ns, nikad 0/neg).
        this.blacklist = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, Long>() {
                    @Override
                    public long expireAfterCreate(
                            String key, Long expiresAtMillis, long currentTime) {
                        return remainingNanos(expiresAtMillis);
                    }

                    @Override
                    public long expireAfterUpdate(
                            String key, Long expiresAtMillis,
                            long currentTime, long currentDuration) {
                        return remainingNanos(expiresAtMillis);
                    }

                    @Override
                    public long expireAfterRead(
                            String key, Long expiresAtMillis,
                            long currentTime, long currentDuration) {
                        // Citanje ne pomera istek — TTL ostaje vezan za exp tokena.
                        return currentDuration;
                    }
                })
                .maximumSize(50_000)
                .build();
    }

    private static long remainingNanos(long expiresAtMillis) {
        long remainingMillis = expiresAtMillis - System.currentTimeMillis();
        // Ako je token vec istekao, drzi minimalno (1ns) — odmah evictable;
        // signature-exp ga ionako odbija. Nikad ne vracamo 0/negativno (Caffeine API).
        if (remainingMillis <= 0) {
            return 1L;
        }
        return TimeUnit.MILLISECONDS.toNanos(remainingMillis);
    }

    /**
     * Dodaje token u blacklist do njegovog STVARNOG {@code exp} (per-token TTL).
     * Idempotentno — ponovni poziv samo refresh-uje vrednost (isti exp).
     */
    public void blacklist(String token) {
        if (token == null || token.isBlank()) return;
        blacklist.put(hash(token), resolveExpiresAtMillis(token));
    }

    /**
     * Vraca true ako je token na blacklist-i (logged out i jos nije isteklo).
     */
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) return false;
        return blacklist.getIfPresent(hash(token)) != null;
    }

    /**
     * Izvuce {@code exp} (epoch-millis) iz tokena radi per-token TTL-a. Ako
     * potpis/format nisu validni ili JwtService nije dostupan, pada na
     * {@link #FALLBACK_TTL} od sada (konzervativno: nikad ne istice pre tokena).
     */
    private long resolveExpiresAtMillis(String token) {
        if (jwtService != null) {
            try {
                Date exp = jwtService.extractExpiration(token);
                if (exp != null) {
                    return exp.getTime();
                }
            } catch (RuntimeException ignored) {
                // Malformed/nepotpisan token — fallback ispod.
            }
        }
        return System.currentTimeMillis() + FALLBACK_TTL.toMillis();
    }

    /** SHA-256 hex hash; sigurnije od cuvanja celog tokena u memoriji. */
    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 je obavezan u svakoj JVM implementaciji — ovo se nikad
            // ne desava. Fallback bi smanjio sigurnost (ne smemo cuvati clear-text).
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
