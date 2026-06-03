package rs.raf.notification.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMetricsConfigTest {

    private MeterRegistry registry;
    private NotificationMetricsConfig config;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        config = new NotificationMetricsConfig();
    }

    @Test
    void emailsSentCounter_isRegistered() {
        Counter c = config.emailsSentCounter(registry);
        assertThat(c.getId().getName()).isEqualTo("banka2_emails_sent_total");
    }
}
