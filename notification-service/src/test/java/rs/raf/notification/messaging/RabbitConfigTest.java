package rs.raf.notification.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-notif-svc-1 / 1529] DLQ ima TTL + max-length + overflow drop-head, a glavni
 * email queue ima DLX wiring (poison poruke ne nestaju nevidljivo / ne rastu beskonacno).
 */
class RabbitConfigTest {

    private final RabbitConfig config = new RabbitConfig();

    @Test
    void emailQueue_hasDlxArguments() {
        Queue q = config.emailQueue();
        assertThat(q.getArguments()).containsEntry("x-dead-letter-exchange", RabbitConfig.DLX_EXCHANGE);
        assertThat(q.getArguments()).containsEntry("x-dead-letter-routing-key", RabbitConfig.DLQ_ROUTING_KEY);
        assertThat(q.isDurable()).isTrue();
    }

    @Test
    void deadLetterQueue_hasTtlAndMaxLength() {
        Queue dlq = config.emailDeadLetterQueue();
        assertThat(dlq.getArguments()).containsEntry("x-message-ttl", RabbitConfig.DLQ_TTL_MILLIS);
        assertThat(dlq.getArguments()).containsEntry("x-max-length", RabbitConfig.DLQ_MAX_LENGTH);
        assertThat(dlq.getArguments()).containsEntry("x-overflow", "drop-head");
        assertThat(dlq.isDurable()).isTrue();
    }

    /**
     * R4 1818 (defense-in-depth): JSON converter je {@link JacksonJsonMessageConverter}
     * sa whitelist-ovanim trusted paketom. Trusted-packages getter nije javan u
     * Spring AMQP 4, pa pin-ujemo da je bean konstruisan i da je ocekivanog tipa —
     * sama whitelist se ne moze deserijalizovati van {@code rs.raf.banka2.contracts}.
     */
    @Test
    void jsonMessageConverter_isJacksonConverter() {
        MessageConverter converter = config.jsonMessageConverter();
        assertThat(converter).isInstanceOf(JacksonJsonMessageConverter.class);
    }
}
