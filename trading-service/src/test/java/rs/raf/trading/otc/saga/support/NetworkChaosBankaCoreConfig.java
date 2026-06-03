package rs.raf.trading.otc.saga.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import rs.raf.trading.client.BankaCoreClient;

/**
 * {@code @Import}-uje se u {@code OtcSagaNetworkChaosInProcessTest} da bi
 * {@link NetworkChaosBankaCoreClient} (transport-fault dvojnik) prepokrio realni
 * {@link BankaCoreClient}. {@code @Primary} osigurava da orchestrator inject-uje
 * dvojnik. Identican pattern kao {@link FakeBankaCoreConfig}, samo sa chaos
 * podklasom (dodaje arm-* hook-ove za mrezni fault).
 */
@TestConfiguration
public class NetworkChaosBankaCoreConfig {

    @Bean
    @Primary
    public BankaCoreClient networkChaosBankaCoreClient() {
        return new NetworkChaosBankaCoreClient();
    }
}
