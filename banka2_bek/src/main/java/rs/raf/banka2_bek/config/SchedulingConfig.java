package rs.raf.banka2_bek.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Aktivira banka-core {@code @Scheduled} poslove (interbank retry/reconciliation,
 * savings kamata, loan rate, otp cleanup, agent-action expiry, assistant cleanup,
 * inter-bank OTC contract expiry, account spending reset).
 *
 * <p><b>R5/R6 config-drift fix (01.06.2026):</b> do sada je {@code @EnableScheduling}
 * stajao bezuslovno na {@link rs.raf.banka2_bek.Banka2BekApplication} — bez ikakvog
 * property prekidaca — pa su svi banka-core scheduleri okidali i u
 * {@code @SpringBootTest} kontekstu (sa mock klijentima), sto je latentni izvor
 * flaky test-ova (npr. {@code InterbankRetryScheduler} 120s, {@code AssistantConversationCleanupScheduler},
 * {@code AgentActionExpirationScheduler}). Trading-service je vec imao
 * {@code trading.scheduling.enabled} gate; banka-core ga sad dobija simetricno.
 *
 * <p>{@code @EnableScheduling} stoji ovde (na zasebnoj {@code @Configuration})
 * gejtovano property-jem {@code banka2.scheduling.enabled} (default {@code true} u
 * produkciji). Test profil ({@code application-test.properties}) ga gasi
 * ({@code banka2.scheduling.enabled=false}) — Spring Boot 4 nema nativni
 * {@code spring.task.scheduling.enabled} prekidac, pa je gejtovani
 * {@code @Configuration} cisti idiom. Scheduler testovi pozivaju metode eksplicitno
 * ({@code @Scheduled} je za njih inertan), pa property gas nista ne lomi a eliminise
 * rizik da retry/cleanup ciklus okine usred testa sa mock-ovanim {@code InterbankClient}-om.
 */
@Configuration
@ConditionalOnProperty(name = "banka2.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
