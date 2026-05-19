package rs.raf.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Trading-service entry point.
 *
 * <p>Trgovinski {@code @Scheduled} poslovi se aktiviraju preko
 * {@link rs.raf.trading.config.SchedulingConfig} (gejtovan {@code @EnableScheduling},
 * property {@code trading.scheduling.enabled}, default {@code true}) — Faza 2f-5a
 * cutover. Test profil gasi schedulere da {@code @SpringBootTest} kontekst bude
 * deterministican.
 */
@SpringBootApplication
public class TradingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingServiceApplication.class, args);
    }
}
