package rs.raf.trading.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Custom poslovne metrike za trading-service.
 * Order engine metrike su premestje iz banka-core monitoring sloja
 * (gde su bile DEAD posle Faza 2f cutover-a — order/OTC/option/margin
 * logika sad zivi u trading-service-u).
 *
 * <p>Sve metrike imaju automatski tag {@code application=trading_service}
 * koji dolazi iz {@code management.metrics.tags.application} property-ja.
 */
@Configuration
public class TradingMetricsConfig {

    @Bean
    public Counter ordersExecutedCounter(MeterRegistry registry) {
        return Counter.builder("banka2_orders_executed_total")
                .description("Ukupan broj uspesno izvrsenih order-a")
                .register(registry);
    }

    @Bean
    public Timer orderExecutionTimer(MeterRegistry registry) {
        return Timer.builder("banka2_order_execution_seconds")
                .description("Trajanje order execution flow-a")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .minimumExpectedValue(Duration.ofMillis(10))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);
    }

    @Bean
    public Counter otcIntraTotal(MeterRegistry registry) {
        return Counter.builder("banka2_otc_intra_total")
                .description("Ukupan broj intra-bank OTC kontrakta")
                .register(registry);
    }

    @Bean
    public Counter otcInterTotal(MeterRegistry registry) {
        return Counter.builder("banka2_otc_inter_total")
                .description("Ukupan broj inter-bank OTC kontrakta (SAGA)")
                .register(registry);
    }

    @Bean
    public Counter optionsTotal(MeterRegistry registry) {
        return Counter.builder("banka2_options_total")
                .description("Ukupan broj exercised opcija")
                .register(registry);
    }

    @Bean
    public Counter marginCallsTotal(MeterRegistry registry) {
        return Counter.builder("banka2_margin_calls_total")
                .description("Ukupan broj margin call event-a (account blocked)")
                .register(registry);
    }
}
