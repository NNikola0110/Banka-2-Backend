package rs.raf.trading.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Cross-cutting bean-ovi trading-service. RestTemplate koristi stock.ListingServiceImpl
 * za AlphaVantage GLOBAL_QUOTE pozive (cene akcija).
 */
@Configuration
public class AppConfig {

    /**
     * P2-perf-nplus1-1 (R5 1904): connect/read timeout na RestTemplate-u.
     * Bez timeout-a default je INFINITE — ako AlphaVantage (ili bilo koji
     * upstream) visi, price-refresh scheduler tick visi zauvek i (uz
     * single-thread pool, takodje fiksiran u ovom batch-u) gladuje sve ostale
     * @Scheduled poslove (order engine, SAGA tajmeri, margin check). Konzervativni
     * timeout-i (connect 5s, read 10s) garantuju da spor upstream pukne brzo
     * umesto da zaglavi scheduler thread. Override preko env nije potreban —
     * ovo su sigurni gornji limiti za jedan eksterni quote poziv.
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }
}
