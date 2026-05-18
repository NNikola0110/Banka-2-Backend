package rs.raf.trading.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Cross-cutting bean-ovi trading-service. RestTemplate koristi stock.ListingServiceImpl
 * za AlphaVantage GLOBAL_QUOTE pozive (cene akcija).
 */
@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
