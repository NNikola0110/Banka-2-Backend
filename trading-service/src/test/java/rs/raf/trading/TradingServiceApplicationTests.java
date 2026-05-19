package rs.raf.trading;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.security.JwtValidator;
import rs.raf.trading.security.TradingJwtAuthenticationFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-load test — verifikuje da se pun Spring kontekst podiže:
 * JPA (H2 u test profilu), Spring Security, JWT validator, BankaCoreClient.
 *
 * <p>Dodatno verifikuje observability parity sa banka-core: Micrometer
 * Prometheus registry je auto-konfigurisan i {@code /actuator/prometheus}
 * scrape endpoint je registrovan kao bean. Sam scrape rezultat sadrzi
 * {@code application="trading-service"} tag (vidi application.properties).
 */
@SpringBootTest
@ActiveProfiles("test")
class TradingServiceApplicationTests {

    @Autowired
    private JwtValidator jwtValidator;

    @Autowired
    private TradingJwtAuthenticationFilter jwtFilter;

    @Autowired
    private BankaCoreClient bankaCoreClient;

    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Autowired
    private PrometheusScrapeEndpoint prometheusScrapeEndpoint;

    @Test
    void contextLoads() {
        // Verifikuje da se pun kontekst podiže (JPA + security + svi novi bean-ovi)
    }

    @Test
    void jwtValidatorBeanPresent() {
        assertThat(jwtValidator).as("JwtValidator mora biti u Spring kontekstu").isNotNull();
    }

    @Test
    void jwtFilterBeanPresent() {
        assertThat(jwtFilter).as("TradingJwtAuthenticationFilter mora biti u Spring kontekstu").isNotNull();
    }

    @Test
    void bankaCoreClientBeanPresent() {
        assertThat(bankaCoreClient).as("BankaCoreClient mora biti u Spring kontekstu").isNotNull();
    }

    @Test
    void prometheusEndpointIsExposed() {
        // Registry bean dokazuje da je micrometer-registry-prometheus na classpath-u.
        assertThat(prometheusMeterRegistry)
                .as("PrometheusMeterRegistry mora biti auto-konfigurisan").isNotNull();
        // Scrape endpoint bean dokazuje da je /actuator/prometheus izlozen.
        assertThat(prometheusScrapeEndpoint)
                .as("PrometheusScrapeEndpoint (/actuator/prometheus) mora biti registrovan").isNotNull();
        // Scrape rezultat sadrzi metrike + application tag (parity sa banka-core).
        String scrape = prometheusMeterRegistry.scrape();
        assertThat(scrape)
                .as("Prometheus scrape mora sadrzati JVM metrike sa application tagom")
                .isNotBlank()
                .contains("jvm_")
                .contains("application=\"trading-service\"");
    }
}
