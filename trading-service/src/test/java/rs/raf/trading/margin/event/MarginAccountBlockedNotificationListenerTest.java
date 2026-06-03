package rs.raf.trading.margin.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import rs.raf.banka2.contracts.NotificationKind;
import rs.raf.banka2.contracts.NotificationMessage;
import rs.raf.banka2.contracts.NotificationRabbit;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;

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
 * margin-call Spring event na RabbitMQ (email) + IN-APP notifikaciju (R1 381).
 */
class MarginAccountBlockedNotificationListenerTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    // W2-T1: koristimo pravi SimpleMeterRegistry counter (laksi od mocka,
    // dozvoljava nam i da verifikujemo da inkrement zaista poveca count).
    private final Counter marginCallsTotal = new SimpleMeterRegistry().counter("banka2_margin_calls_total");
    private final MarginAccountBlockedNotificationListener listener =
            new MarginAccountBlockedNotificationListener(rabbitTemplate, marginCallsTotal, notificationService);

    private MarginAccountBlockedEvent event(Long userId, String email) {
        return new MarginAccountBlockedEvent(userId, 55L, email, "5000.00", "4800.00", "200.00");
    }

    @Test
    void onMarginAccountBlocked_publishesNotificationMessage() {
        listener.onMarginAccountBlocked(event(7L, "client@test.com"));

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

    // ── P2-notif-reliability-2 (R1 381): margin blokada salje i IN-APP notify ──

    @Test
    void onMarginAccountBlocked_emitsInAppNotification() {
        listener.onMarginAccountBlocked(event(7L, "client@test.com"));

        verify(notificationService).notify(
                eq(7L), eq("CLIENT"), eq(NotificationType.MARGIN_ACCOUNT_BLOCKED),
                anyString(), anyString(), eq("MARGIN_ACCOUNT"), eq(55L));
    }

    @Test
    void onMarginAccountBlocked_emitsInAppEvenWhenEmailMissing() {
        // Email vlasnika nedostupan (npr. banka-core lookup pao) — email se preskace,
        // ali in-app (bell) i dalje mora da se posalje.
        listener.onMarginAccountBlocked(event(7L, null));

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(notificationService).notify(
                eq(7L), eq("CLIENT"), eq(NotificationType.MARGIN_ACCOUNT_BLOCKED),
                anyString(), anyString(), eq("MARGIN_ACCOUNT"), eq(55L));
    }

    @Test
    void onMarginAccountBlocked_skipsInAppWhenNoOwnerUserId() {
        // Company margin racun (userId==null) → in-app se preskace, ali email ide.
        listener.onMarginAccountBlocked(event(null, "company@test.com"));

        verify(notificationService, never()).notify(
                any(), anyString(), any(), anyString(), anyString(), anyString(), any());
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void onMarginAccountBlocked_skipsEmailWhenEmailMissing() {
        listener.onMarginAccountBlocked(event(7L, null));

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void onMarginAccountBlocked_swallowsRabbitFailure() {
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Best-effort: pad RabbitMQ-a NE sme da prekine margin-call transakciju.
        assertThatNoException().isThrownBy(() ->
                listener.onMarginAccountBlocked(event(7L, "client@test.com")));
    }

    @Test
    void onMarginAccountBlocked_swallowsInAppFailure() {
        doThrow(new RuntimeException("banka-core down"))
                .when(notificationService).notify(
                        any(), anyString(), any(), anyString(), anyString(), anyString(), any());

        // In-app pad NE sme da prekine email kanal ni obradu.
        assertThatNoException().isThrownBy(() ->
                listener.onMarginAccountBlocked(event(7L, "client@test.com")));
        // Email kanal i dalje radi.
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
    }
}
