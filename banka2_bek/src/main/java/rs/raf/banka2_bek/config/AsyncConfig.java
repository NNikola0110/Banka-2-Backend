package rs.raf.banka2_bek.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Aktivira Spring {@code @Async} obradu (proxy bean-ova sa {@code @Async} metodama).
 *
 * <p><b>Bug fix (06.06.2026):</b> {@code @EnableAsync} NIJE postojao nigde u aplikaciji,
 * pa je {@code @Async} anotacija na
 * {@link rs.raf.banka2_bek.interbank.service.InterbankPaymentAsyncService#executeAsync}
 * bila <b>mrtvo slovo</b> — Spring ju je ignorisao i metoda se izvrsavala
 * <b>sinhrono, inline</b> na pozivajucoj niti. Posto je pozvana iz
 * {@code TransactionSynchronization.afterCommit()} (gde je transakcioni kontekst jos
 * uvek vezan dok se ne odradi {@code afterCompletion}), {@code @Transactional(propagation=NEVER)}
 * je bacao <i>"Existing transaction found for transaction marked with propagation 'never'"</i> —
 * pa je SVAKO medjubankarsko placanje padalo na OTP koraku. Sa ukljucenim {@code @Async}
 * (named {@code interbankTaskExecutor}), {@code executeAsync} se izvrsava na zasebnoj niti
 * pool-a BEZ transakcionog konteksta → {@code NEVER} prolazi i 2PC tece u pozadini kako je
 * i zamisljeno.
 *
 * <p>{@code @EnableAsync} stoji ovde (na zasebnoj {@code @Configuration}) gejtovano
 * property-jem {@code banka2.async.enabled} (default {@code true} u produkciji), simetricno
 * sa {@link SchedulingConfig}. Test profil ({@code application-test.properties}) ga gasi
 * ({@code banka2.async.enabled=false}) tako da {@code @Async} ostaje inertan u
 * {@code @SpringBootTest} kontekstu — eliminise async-timing flakiness (test koji proveri
 * status placanja sinhrono ne trci sa background settlement-om). Jedini {@code @Async}
 * metod u aplikaciji je {@code InterbankPaymentAsyncService.executeAsync}, pa je efekat
 * potpuno ciljan.
 */
@Configuration
@ConditionalOnProperty(name = "banka2.async.enabled", havingValue = "true", matchIfMissing = true)
@EnableAsync
public class AsyncConfig {
}
