package rs.raf.notification.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Custom poslovne metrike za notification-service.
 *
 * <p>{@code banka2_emails_sent_total} je @Bean (singleton — jedan counter per
 * registry). {@code banka2_emails_failed_total} se NE registruje ovde kao @Bean:
 * nosi {@code reason} tag pa Micrometer registruje jedinstven Counter per
 * (name, tag-set) kombinaciju. Konzument ga registruje/inkrementira inline preko
 * {@code registry.counter("banka2_emails_failed_total", "reason", reason).increment()}
 * (vidi {@code NotificationConsumer.incrementFailureCounter}); emitovani razlozi
 * su {@code smtp_error} i {@code dlq}.
 */
@Configuration
public class NotificationMetricsConfig {

    @Bean
    public Counter emailsSentCounter(MeterRegistry registry) {
        return Counter.builder("banka2_emails_sent_total")
                .description("Ukupan broj uspesno poslatih email-ova")
                .register(registry);
    }
}
