package rs.raf.trading.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Aktivira trgovinske {@code @Scheduled} poslove (order engine 10s/30s, price
 * refresh 15min, OTC contract expiry, option scheduler, tax scheduler, fund
 * value snapshot, actuary limit reset, margin maintenance check).
 *
 * <p><b>Faza 2f-5a (cutover finalizacija):</b> do sada je {@code @EnableScheduling}
 * NAMERNO izostajao sa {@code TradingServiceApplication} (copy-first faza) — svi
 * kopirani {@code @Scheduled} bili su uspavani jer je monolit jos uvek vrteo iste
 * poslove. Cutover (2f) gasi monolitnu kopiju, pa trading-service preuzima
 * trgovinske schedulere.
 *
 * <p>{@code @EnableScheduling} stoji ovde (na zasebnoj {@code @Configuration})
 * gejtovano property-jem {@code trading.scheduling.enabled} (default {@code true}).
 * Test profil ({@code application-test.properties}) ga gasi
 * ({@code trading.scheduling.enabled=false}) — Spring Boot 4.0.6 nema nativni
 * {@code spring.task.scheduling.enabled} prekidac, pa je gejtovani
 * {@code @Configuration} cisti idiom. {@code @SpringBootTest} kontekst se podize
 * bez aktivnih schedulera; scheduler testovi ionako pozivaju metode eksplicitno
 * ({@code @Scheduled} je za njih inertan), pa property gas nista ne lomi i
 * eliminise rizik da 10s order-engine ciklus okine usred testa sa mockovanim
 * {@code BankaCoreClient}-om.
 */
@Configuration
@ConditionalOnProperty(name = "trading.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
