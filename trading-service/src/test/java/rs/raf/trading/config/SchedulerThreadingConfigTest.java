package rs.raf.trading.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;
import rs.raf.trading.stock.service.implementation.ListingServiceImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-perf-nplus1-1 (R5 1904): strukturalni testovi za scheduler-starvation fix.
 *
 * <p>Pinuje 3 strukturalna invarijanta (perf je tesko unit-testirati za latenciju,
 * pa fiksiramo OBLIK fixa, ne wall-clock):
 * <ol>
 *   <li>{@code spring.task.scheduling.pool.size} je konfigurisan i &gt;= 5
 *       (Spring default je SINGLE-THREAD pool=1) — sprecava da spor tick gladuje
 *       order/SAGA poslove.</li>
 *   <li>{@link AppConfig#restTemplate()} ima connect/read timeout (default je
 *       INFINITE — spor AlphaVantage bi zaglavio scheduler thread zauvek).</li>
 *   <li>price-refresh ({@code ListingServiceImpl.scheduledRefresh}) koristi
 *       {@code fixedDelay} (ne {@code fixedRate}) — fixedRate dozvoljava
 *       preklapajuce cikluse.</li>
 * </ol>
 */
class SchedulerThreadingConfigTest {

    @Test
    @DisplayName("R5 1904: spring.task.scheduling.pool.size je konfigurisan >= 5 (ne single-thread)")
    void schedulingPoolSizeIsConfiguredAndAtLeastFive() throws Exception {
        Properties props = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));
        String raw = props.getProperty("spring.task.scheduling.pool.size");
        assertThat(raw).as("spring.task.scheduling.pool.size mora biti postavljen").isNotBlank();
        // Vrednost je ${TASK_SCHEDULING_POOL_SIZE:5} — izvuci default iza ':'.
        String defaultValue = raw.contains(":")
                ? raw.substring(raw.indexOf(':') + 1).replace("}", "").trim()
                : raw.trim();
        int poolSize = Integer.parseInt(defaultValue);
        assertThat(poolSize).as("pool size mora biti >= 5 (ne single-thread)").isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("R5 1904: RestTemplate ima connect + read timeout (ne INFINITE)")
    void restTemplateHasConnectAndReadTimeouts() throws Exception {
        RestTemplate restTemplate = new AppConfig().restTemplate();
        assertThat(restTemplate.getRequestFactory()).isInstanceOf(SimpleClientHttpRequestFactory.class);

        SimpleClientHttpRequestFactory factory =
                (SimpleClientHttpRequestFactory) restTemplate.getRequestFactory();

        int connectTimeout = readIntField(factory, "connectTimeout");
        int readTimeout = readIntField(factory, "readTimeout");

        assertThat(connectTimeout).as("connect timeout mora biti postavljen (> 0)").isGreaterThan(0);
        assertThat(readTimeout).as("read timeout mora biti postavljen (> 0)").isGreaterThan(0);
    }

    @Test
    @DisplayName("R5 1904: scheduledRefresh koristi fixedDelay (ne fixedRate)")
    void scheduledRefreshUsesFixedDelayNotFixedRate() throws Exception {
        Method m = ListingServiceImpl.class.getMethod("scheduledRefresh");
        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("scheduledRefresh mora imati @Scheduled").isNotNull();
        assertThat(scheduled.fixedDelay()).as("mora koristiti fixedDelay").isGreaterThan(0);
        assertThat(scheduled.fixedRate()).as("NE sme koristiti fixedRate (preklapajuci ciklusi)").isEqualTo(-1L);
    }

    private static int readIntField(Object target, String name) throws Exception {
        Field f = SimpleClientHttpRequestFactory.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
    }
}
