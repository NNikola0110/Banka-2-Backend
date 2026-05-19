package rs.raf.trading.margin.event;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import rs.raf.banka2.contracts.NotificationKind;
import rs.raf.banka2.contracts.NotificationMessage;
import rs.raf.banka2.contracts.NotificationRabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Testovi za {@link MarginAccountBlockedNotificationListener} — premosti
 * margin-call Spring event na RabbitMQ.
 */
class MarginAccountBlockedNotificationListenerTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final MarginAccountBlockedNotificationListener listener =
            new MarginAccountBlockedNotificationListener(rabbitTemplate);

    @Test
    void onMarginAccountBlocked_publishesNotificationMessage() {
        listener.onMarginAccountBlocked(new MarginAccountBlockedEvent(
                "client@test.com", "5000.00", "4800.00", "200.00"));

        ArgumentCaptor<NotificationMessage> captor =
                ArgumentCaptor.forClass(NotificationMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(NotificationRabbit.EXCHANGE),
                eq(NotificationRabbit.EMAIL_ROUTING_KEY),
                captor.capture());

        NotificationMessage msg = captor.getValue();
        assertThat(msg.kind()).isEqualTo(NotificationKind.MARGIN_ACCOUNT_BLOCKED);
        assertThat(msg.data())
                .containsEntry("email", "client@test.com")
                .containsEntry("maintenanceMargin", "5000.00")
                .containsEntry("initialMargin", "4800.00")
                .containsEntry("deficit", "200.00");
    }

    @Test
    void onMarginAccountBlocked_skipsWhenEmailMissing() {
        listener.onMarginAccountBlocked(new MarginAccountBlockedEvent(
                null, "5000.00", "4800.00", "200.00"));

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void onMarginAccountBlocked_swallowsRabbitFailure() {
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Best-effort: pad RabbitMQ-a NE sme da prekine margin-call transakciju.
        assertThatNoException().isThrownBy(() ->
                listener.onMarginAccountBlocked(new MarginAccountBlockedEvent(
                        "client@test.com", "5000.00", "4800.00", "200.00")));
    }
}
