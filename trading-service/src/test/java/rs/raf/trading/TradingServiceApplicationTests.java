package rs.raf.trading;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.security.JwtValidator;
import rs.raf.trading.security.TradingJwtAuthenticationFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-load test — verifikuje da se pun Spring kontekst podiže:
 * JPA (H2 u test profilu), Spring Security, JWT validator, BankaCoreClient.
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
}
