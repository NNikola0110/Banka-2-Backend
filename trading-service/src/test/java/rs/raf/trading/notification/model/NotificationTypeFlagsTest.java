package rs.raf.trading.notification.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit testovi za {@link NotificationType#isSendsEmail()} flag-ove.
 *
 * <p>Spec Celina 3 (order lifecycle) i Celina 4 (OTC) eksplicitno trazi da
 * korisnik dobija email obavestenja. Posto trading-service nema lokalni
 * in-app store (publishuje na RabbitMQ kao IN_APP_GENERIC), {@code sendsEmail}
 * mora biti {@code true} za trgovinske evente da email notifikacije ne bi
 * tihno otisli u prazno.
 */
@DisplayName("NotificationType — sendsEmail flag")
class NotificationTypeFlagsTest {

    @Test
    @DisplayName("ORDER_* lifecycle events salju email (Celina 3)")
    void orderLifecycleEvents_sendEmail() {
        assertThat(NotificationType.ORDER_PENDING.isSendsEmail()).isTrue();
        assertThat(NotificationType.ORDER_APPROVED.isSendsEmail()).isTrue();
        assertThat(NotificationType.ORDER_DECLINED.isSendsEmail()).isTrue();
        assertThat(NotificationType.ORDER_EXECUTED.isSendsEmail()).isTrue();
        assertThat(NotificationType.ORDER_PARTIAL_FILL.isSendsEmail()).isTrue();
        assertThat(NotificationType.ORDER_CANCELLED.isSendsEmail()).isTrue();
    }

    @Test
    @DisplayName("OTC_* events salju email (Celina 4)")
    void otcEvents_sendEmail() {
        assertThat(NotificationType.OTC_COUNTER_OFFER.isSendsEmail()).isTrue();
        assertThat(NotificationType.OTC_ACCEPTED.isSendsEmail()).isTrue();
        assertThat(NotificationType.OTC_DECLINED.isSendsEmail()).isTrue();
        assertThat(NotificationType.OTC_CONTRACT_EXPIRING.isSendsEmail()).isTrue();
    }

    @Test
    @DisplayName("RECURRING_ORDER_SKIPPED salje email (B8 scheduler diagnostic)")
    void recurringOrderSkipped_sendsEmail() {
        assertThat(NotificationType.RECURRING_ORDER_SKIPPED.isSendsEmail()).isTrue();
    }

    @Test
    @DisplayName("GENERAL fallback NE salje email (interni fallback)")
    void generalFallback_doesNotSendEmail() {
        assertThat(NotificationType.GENERAL.isSendsEmail()).isFalse();
    }

    @Test
    @DisplayName("Svi trgovinski tipovi osim GENERAL imaju sendsEmail=true")
    void allTradingTypes_exceptGeneral_sendEmail() {
        for (NotificationType type : NotificationType.values()) {
            if (type == NotificationType.GENERAL) {
                assertThat(type.isSendsEmail())
                        .as("GENERAL je fallback i ne sme da salje email")
                        .isFalse();
            } else {
                assertThat(type.isSendsEmail())
                        .as("Trgovinski tip %s mora da salje email", type)
                        .isTrue();
            }
        }
    }
}
