package rs.raf.notification.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import rs.raf.notification.messaging.RabbitConfig;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * [P1-notif-svc-1 / 1529] Vidljivost DLQ-a: registruje Micrometer gauge
 * {@code banka2_notification_dlq_depth} koji izlaze trenutni broj poruka u
 * {@code notification.email.dlq}. Bez ovoga poison notifikacije nestaju u DLQ
 * "nevidljivo" — sad se DLQ dubina scrape-uje na {@code /actuator/prometheus}
 * i moze da okine alert kad poraste.
 *
 * <p>Gauge se osvezava lazy (pri svakom scrape-u Micrometer poziva supplier koji
 * cita {@code RabbitAdmin.getQueueInfo}); ako broker nije dostupan (npr. unit/
 * context test bez RabbitMQ), supplier vraca poslednju poznatu vrednost i loguje
 * DEBUG — ne ruzi scrape.
 */
@Configuration
public class DlqMonitoringConfig {

    private static final Logger log = LoggerFactory.getLogger(DlqMonitoringConfig.class);

    private final AtomicInteger lastKnownDepth = new AtomicInteger(0);

    @Bean
    public Gauge dlqDepthGauge(MeterRegistry registry,
                               ObjectProvider<RabbitAdmin> rabbitAdminProvider) {
        return Gauge.builder("banka2_notification_dlq_depth",
                        () -> currentDlqDepth(rabbitAdminProvider))
                .description("Trenutni broj poruka u notification email DLQ")
                .register(registry);
    }

    private double currentDlqDepth(ObjectProvider<RabbitAdmin> rabbitAdminProvider) {
        RabbitAdmin admin = rabbitAdminProvider.getIfAvailable();
        if (admin == null) {
            return lastKnownDepth.get();
        }
        try {
            QueueInformation info = admin.getQueueInfo(RabbitConfig.DLQ_NAME);
            if (info != null) {
                lastKnownDepth.set((int) info.getMessageCount());
            }
        } catch (RuntimeException ex) {
            log.debug("DLQ depth scrape nije uspeo (broker nedostupan?): {}", ex.getMessage());
        }
        return lastKnownDepth.get();
    }
}
