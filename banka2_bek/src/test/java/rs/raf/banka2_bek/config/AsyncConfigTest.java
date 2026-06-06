package rs.raf.banka2_bek.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regresioni test za bug (06.06.2026): {@code @EnableAsync} MORA biti aktivan u produkciji
 * (default), inace je {@code @Async} na
 * {@code InterbankPaymentAsyncService.executeAsync} mrtvo slovo i medjubankarsko placanje pada
 * sa <i>"Existing transaction found for transaction marked with propagation 'never'"</i>
 * (executeAsync se izvrsi sinhrono u {@code afterCommit()} gde je tx kontekst jos vezan).
 *
 * <p>Prisustvo {@link AsyncAnnotationBeanPostProcessor} bean-a je dokaz da je {@code @EnableAsync}
 * stvarno obradjeno. Test profil gasi async ({@code banka2.async.enabled=false}) radi
 * async-timing stabilnosti — pa proveravamo i gating eksplicitno.
 */
class AsyncConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfig.class);

    @Test
    void asyncProcessingEnabledByDefault() {
        // matchIfMissing=true → bez property-ja async MORA biti ukljucen (produkcija)
        runner.run(ctx -> assertThat(ctx).hasSingleBean(AsyncAnnotationBeanPostProcessor.class));
    }

    @Test
    void asyncProcessingEnabledWhenPropertyTrue() {
        runner.withPropertyValues("banka2.async.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(AsyncAnnotationBeanPostProcessor.class));
    }

    @Test
    void asyncProcessingDisabledWhenPropertyFalse() {
        // test profil: async iskljucen → nema async post-processor-a (inertan @Async)
        runner.withPropertyValues("banka2.async.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AsyncAnnotationBeanPostProcessor.class));
    }
}
