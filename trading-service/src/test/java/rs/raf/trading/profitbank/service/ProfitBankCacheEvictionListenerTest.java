package rs.raf.trading.profitbank.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import rs.raf.trading.order.event.OrderCompletedEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test {@link ProfitBankCacheEvictionListener} — verifikuje da
 * {@link OrderCompletedEvent} za zaposlenog evict-uje {@code actuary-profit}
 * Caffeine cache, a da klijentski event ne dira cache.
 *
 * <p>Minimalan Spring kontekst: {@link ProfitBankCacheConfig} (donosi
 * {@code @EnableCaching} + Caffeine {@code CacheManager}) + listener.
 * {@code @CacheEvict} se aktivira samo kroz Spring cache proxy — zato pravi
 * kontekst, ne sirov {@code new}.</p>
 *
 * <p>NAPOMENA (copy-first ekstrakcija, faza 2d-E): {@code profitbank} u
 * monolitu nije imao testove — ovo je nov test pisan za trading-service.</p>
 */
@SpringJUnitConfig({ProfitBankCacheConfig.class, ProfitBankCacheEvictionListener.class})
@ExtendWith(org.springframework.test.context.junit.jupiter.SpringExtension.class)
class ProfitBankCacheEvictionListenerTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private CacheManager cacheManager;

    private Cache actuaryProfitCache;

    @BeforeEach
    void setUp() {
        actuaryProfitCache = cacheManager.getCache(ProfitBankCacheConfig.ACTUARY_PROFIT_CACHE);
        assertThat(actuaryProfitCache).isNotNull();
        actuaryProfitCache.clear();
    }

    @Test
    void employeeOrderCompleted_evictsCache() {
        actuaryProfitCache.put("key", "stale-value");
        assertThat(actuaryProfitCache.get("key")).isNotNull();

        eventPublisher.publishEvent(new OrderCompletedEvent(1L, 7L, "EMPLOYEE", null));

        assertThat(actuaryProfitCache.get("key")).isNull();
    }

    /**
     * R1 803: {@code OrderCompletedEvent.userRole()} dolazi iz
     * {@code order.getUserRole()} koji nosi domenski string (EMPLOYEE/CLIENT/FUND),
     * NIKAD Spring-prefiks {@code ROLE_EMPLOYEE}. Ranija {@code || == 'ROLE_EMPLOYEE'}
     * grana je bila mrtva i uklonjena; ovaj test sad pinuje da prefiks-string NE
     * evict-uje (dokumentuje stvarni event-contract).
     */
    @Test
    void roleEmployeeOrderCompleted_doesNotEvictCache_deadBranchRemoved_R1_803() {
        actuaryProfitCache.put("key", "fresh-value");

        eventPublisher.publishEvent(new OrderCompletedEvent(2L, 8L, "ROLE_EMPLOYEE", null));

        assertThat(actuaryProfitCache.get("key")).isNotNull();
        assertThat(actuaryProfitCache.get("key").get()).isEqualTo("fresh-value");
    }

    @Test
    void clientOrderCompleted_doesNotEvictCache() {
        actuaryProfitCache.put("key", "fresh-value");

        eventPublisher.publishEvent(new OrderCompletedEvent(3L, 99L, "CLIENT", null));

        // SpEL condition filtrira CLIENT — cache ostaje netaknut
        assertThat(actuaryProfitCache.get("key")).isNotNull();
        assertThat(actuaryProfitCache.get("key").get()).isEqualTo("fresh-value");
    }

    @Test
    void fundOrderCompleted_doesNotEvictCache() {
        actuaryProfitCache.put("key", "fresh-value");

        eventPublisher.publishEvent(new OrderCompletedEvent(4L, 50L, "FUND", 50L));

        assertThat(actuaryProfitCache.get("key")).isNotNull();
        assertThat(actuaryProfitCache.get("key").get()).isEqualTo("fresh-value");
    }
}
