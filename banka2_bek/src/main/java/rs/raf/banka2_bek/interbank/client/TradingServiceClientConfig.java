package rs.raf.banka2_bek.interbank.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient za banka-core → trading-service interni {@code /internal/portfolio/**}
 * seam (faza 2f). Mirror trading-service-ovog {@code BankaCoreClientConfig}.
 *
 * <p>Posle 2f cutover-a {@code portfolios}/{@code listings} tabele zive samo u
 * trading_db; banka-core {@code interbank} paket vise ne radi in-process JPA
 * pristup tim tabelama nego ih cita/menja preko ovog klijenta.
 */
@Configuration
public class TradingServiceClientConfig {

    @Bean(name = "tradingServiceRestClient")
    public RestClient tradingServiceRestClient(
            @Value("${tradingservice.base-url}") String baseUrl,
            @Value("${internal.api-key}") String internalApiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Key", internalApiKey)
                .requestFactory(factory)
                .build();
    }
}
