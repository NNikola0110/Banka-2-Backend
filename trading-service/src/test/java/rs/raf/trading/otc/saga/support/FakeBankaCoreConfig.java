package rs.raf.trading.otc.saga.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import rs.raf.trading.client.BankaCoreClient;

/**
 * {@code @Import}-uje se u W2 SAGA invarijant test da bi {@link FakeBankaCoreClient}
 * (verodostojni in-memory dvojnik) prepokrio realni {@link BankaCoreClient}
 * {@code @Component}. {@code @Primary} osigurava da orchestrator +
 * {@code CurrencyConversionService} + {@code TradingUserResolver} inject-uju
 * dvojnik (oba bean-a postoje u kontekstu — dvojnik pobedjuje po @Primary).
 *
 * <p>Test dohvata istu instancu autowire-ovanjem {@code @Primary} bean-a i
 * cast-ovanjem na {@link FakeBankaCoreClient} (ili autowire-om po konkretnom tipu).
 */
@TestConfiguration
public class FakeBankaCoreConfig {

    @Bean
    @Primary
    public BankaCoreClient fakeBankaCoreClient() {
        return new FakeBankaCoreClient();
    }
}
