package rs.raf.notification.monitoring;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DlqMonitoringConfigTest {

    private final DlqMonitoringConfig config = new DlqMonitoringConfig();

    @SuppressWarnings("unchecked")
    private ObjectProvider<RabbitAdmin> providerOf(RabbitAdmin admin) {
        ObjectProvider<RabbitAdmin> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(admin);
        return provider;
    }

    @Test
    void gauge_reportsDlqMessageCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RabbitAdmin admin = mock(RabbitAdmin.class);
        QueueInformation info = mock(QueueInformation.class);
        when(info.getMessageCount()).thenReturn(7L);
        when(admin.getQueueInfo(org.mockito.ArgumentMatchers.anyString())).thenReturn(info);

        Gauge gauge = config.dlqDepthGauge(registry, providerOf(admin));

        assertThat(gauge.value()).isEqualTo(7.0);
    }

    @Test
    void gauge_brokerUnavailable_returnsZeroWithoutThrowing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge gauge = config.dlqDepthGauge(registry, providerOf(null));
        // null admin → poslednja poznata (0), bez izuzetka
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void gauge_adminThrows_returnsLastKnownWithoutPropagating() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RabbitAdmin admin = mock(RabbitAdmin.class);
        when(admin.getQueueInfo(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("broker down"));

        Gauge gauge = config.dlqDepthGauge(registry, providerOf(admin));
        assertThat(gauge.value()).isEqualTo(0.0);
    }
}
