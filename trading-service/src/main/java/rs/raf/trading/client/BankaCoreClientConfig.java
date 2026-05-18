package rs.raf.trading.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class BankaCoreClientConfig {

    @Bean
    public RestClient bankaCoreRestClient(@Value("${bankacore.base-url}") String baseUrl,
                                          @Value("${internal.api-key}") String internalApiKey) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Key", internalApiKey)
                .requestFactory(requestFactory)
                .build();
    }
}
