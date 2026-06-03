package rs.raf.banka2_bek.assistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import rs.raf.banka2_bek.assistant.client.CallerTokenHolder;

import java.time.Duration;

/**
 * Bean providers za Arbitro asistent.
 *
 * Razlozi za nazvane (named) bean-ove:
 * <ul>
 *   <li>{@code assistantTaskExecutor} — Spring auto-config kreira
 *       {@code applicationTaskExecutor} I {@code taskScheduler}, oba
 *       implementiraju {@link TaskExecutor} → autowire by type je
 *       ambiguous. Pravimo svoj namenski pool.</li>
 *   <li>{@code assistantObjectMapper} — neki integration testovi rade sa
 *       {@code @WebMvcTest} ili custom konfiguracijom koja iskljucuje
 *       Jackson auto-config. Pravljenjem zasebnog bean-a osiguravamo da
 *       Arbitro ima ObjectMapper i u tim test kontekstima.</li>
 * </ul>
 *
 * <p>NAPOMENA: Arbitro HTTP klijenti (LlmHttpClient, WikipediaToolClient,
 * RagToolClient) NE koriste Spring RestClient. Ranije pokusana implementacija
 * sa RestClient + custom HttpMessageConverter-ima nije pouzdano serijalizovala
 * body preko Docker network-a (FastAPI je primao "input: null" 422 greske).
 * Klijenti su prebaceni na {@link java.net.http.HttpClient} — low-level Java 11+
 * API koji deterministicki salje string body preko zice.
 */
@Configuration
public class AssistantConfig {

    @Bean(name = "assistantTaskExecutor")
    public TaskExecutor assistantTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("arbitro-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        // Faza 2f: Arbitro chat trci na ovom pool-u (runChat), van niti zahteva.
        // TradingServiceClient prosledjuje JWT pozivaoca; chat tok (buildPreview,
        // get_recent_orders, wizard slot resolveri) mora videti taj token.
        // Decorator snimi CallerTokenHolder vrednost sa niti koja PREDAJE zadatak
        // (nit zahteva, gde je CallerTokenFilter token vec postavio) i re-instalira
        // je na radnu nit za trajanje zadatka; uvek brise u finally da recikliranje
        // niti ne iznese token u sledeci zadatak.
        executor.setTaskDecorator(callerTokenPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * {@link TaskDecorator} koji propagira {@link CallerTokenHolder} sa niti
     * koja predaje zadatak na radnu nit pool-a (faza 2f). Vidi
     * {@link #assistantTaskExecutor()}.
     */
    private TaskDecorator callerTokenPropagatingDecorator() {
        return runnable -> {
            String capturedToken = CallerTokenHolder.get();
            return () -> {
                CallerTokenHolder.set(capturedToken);
                try {
                    runnable.run();
                } finally {
                    CallerTokenHolder.clear();
                }
            };
        };
    }

    /**
     * {@link RestClient} ka {@code trading-service} PUBLIC API-ju (faza 2f).
     *
     * <p>Koristi ga {@code TradingServiceClient} iz Arbitro {@code assistant}
     * paketa — write handler-i i read tool-ovi vise ne zovu trgovinske servise
     * in-process nego javne kontrolere {@code trading-service}-a. Bearer token
     * pozivaoca se dodaje per-poziv u {@code TradingServiceClient} (ne kao
     * {@code defaultHeader}) jer je token specifican za zahtev.
     *
     * <p>{@code tradingservice.base-url} je isti property koji koristi
     * {@code interbank} {@code TradingServiceClientConfig} (interni seam, 2f-2).
     */
    @Bean(name = "tradingServicePublicRestClient")
    public RestClient tradingServicePublicRestClient(
            @Value("${tradingservice.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Bean(name = "interbankTaskExecutor")
    public TaskExecutor interbankTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("interbank-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // N4 — bez rejection handler-a, kad se queue (50) napuni ili pool gasi,
        // submit() baca RejectedExecutionException i 2PC task se TIHO gubi → Payment
        // ostaje PROCESSING zauvek bez ijednog InterbankTransaction reda. CallerRunsPolicy
        // izvrsava odbijeni task na pozivajucoj (afterCommit) niti umesto da ga odbaci —
        // settlement se i dalje desi (sinhrono u tom slucaju). Payment-level reconciler
        // (InterbankPaymentReconciliationScheduler) hvata sve preostale rupe.
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "assistantObjectMapper")
    public ObjectMapper assistantObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
