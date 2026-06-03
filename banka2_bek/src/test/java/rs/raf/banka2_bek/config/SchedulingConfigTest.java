package rs.raf.banka2_bek.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R5/R6 config-drift fix: dokazuje da je banka-core scheduling property-gate-ovan
 * ({@code banka2.scheduling.enabled}). Pre fix-a je {@code @EnableScheduling} bio
 * bezuslovan na {@code Banka2BekApplication}, pa su scheduleri okidali i u testu.
 */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void schedulingConfigPresentByDefault() {
        // matchIfMissing=true → produkcioni default je ON.
        runner.run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    void schedulingConfigPresentWhenEnabledTrue() {
        runner.withPropertyValues("banka2.scheduling.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    void schedulingConfigAbsentWhenDisabled() {
        // Test profil postavlja =false → @EnableScheduling se ne aktivira, scheduleri mirni.
        runner.withPropertyValues("banka2.scheduling.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SchedulingConfig.class));
    }
}
