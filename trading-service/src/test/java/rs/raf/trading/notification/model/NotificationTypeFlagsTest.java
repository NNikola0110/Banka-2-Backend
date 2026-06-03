package rs.raf.trading.notification.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit testovi za {@link NotificationType#isSendsEmail()} / {@link NotificationType#isSendsInApp()}
 * flag-ove.
 *
 * <p><b>C-notif-email (02.06):</b> order/OTC/fund lifecycle eventi su EMAIL + in-app
 * (spec TODO_testovi Sc20-25/Sc60-63 + TestoviCelina4 Sc35/36/49/50 eksplicitno
 * trazi email). Svaki se emituje TACNO JEDNOM po smislenom eventu (ne per scheduler
 * tick) — anti-flood je na nivou EMISIJE (vidi {@code SingleOrderExecutorTest}
 * Sc23/Sc24), ne gasenjem email flag-a. In-app-only ostaju samo tipovi sa
 * dedikovanim email template-om (MARGIN_ACCOUNT_BLOCKED), interni operativni alerti
 * (TAX_CALCULATION_FAILED), van-scope (RECURRING_ORDER_SKIPPED) i GENERAL fallback.
 */
@DisplayName("NotificationType — sendsEmail/sendsInApp flag")
class NotificationTypeFlagsTest {

    @Test
    @DisplayName("ORDER_* lifecycle eventi salju email + in-app (Sc20-25)")
    void orderLifecycleEvents_emailAndInApp() {
        for (NotificationType t : Set.of(
                NotificationType.ORDER_PENDING,
                NotificationType.ORDER_APPROVED,
                NotificationType.ORDER_DECLINED,
                NotificationType.ORDER_EXECUTED,
                NotificationType.ORDER_PARTIAL_FILL,
                NotificationType.ORDER_CANCELLED)) {
            assertThat(t.isSendsEmail()).as("%s salje email (Sc20-25)", t).isTrue();
            assertThat(t.isSendsInApp()).as("%s in-app vidljiv", t).isTrue();
        }
    }

    @Test
    @DisplayName("OTC_* eventi salju email + in-app (Sc60-63)")
    void otcEvents_emailAndInApp() {
        for (NotificationType t : Set.of(
                NotificationType.OTC_COUNTER_OFFER,
                NotificationType.OTC_ACCEPTED,
                NotificationType.OTC_DECLINED,
                NotificationType.OTC_CONTRACT_EXPIRING)) {
            assertThat(t.isSendsEmail()).as("%s salje email (Sc60-63)", t).isTrue();
            assertThat(t.isSendsInApp()).as("%s in-app vidljiv", t).isTrue();
        }
    }

    @Test
    @DisplayName("FUND_PAYOUT salje email + in-app (Sc35/36/49/50)")
    void fundPayout_emailAndInApp() {
        assertThat(NotificationType.FUND_PAYOUT.isSendsEmail()).isTrue();
        assertThat(NotificationType.FUND_PAYOUT.isSendsInApp()).isTrue();
    }

    @Test
    @DisplayName("RECURRING_ORDER_SKIPPED je IN-APP-ONLY (van C-notif-email scope-a)")
    void recurringOrderSkipped_isInAppOnly() {
        assertThat(NotificationType.RECURRING_ORDER_SKIPPED.isSendsEmail()).isFalse();
        assertThat(NotificationType.RECURRING_ORDER_SKIPPED.isSendsInApp()).isTrue();
    }

    @Test
    @DisplayName("MARGIN_ACCOUNT_BLOCKED je IN-APP-ONLY (email ide preko dedikovanog template-a — bez duplog)")
    void marginBlocked_isInAppOnly() {
        assertThat(NotificationType.MARGIN_ACCOUNT_BLOCKED.isSendsEmail()).isFalse();
        assertThat(NotificationType.MARGIN_ACCOUNT_BLOCKED.isSendsInApp()).isTrue();
    }

    @Test
    @DisplayName("TAX_CALCULATION_FAILED je IN-APP-ONLY (operativni alert, ne email noise)")
    void taxCalculationFailed_isInAppOnly() {
        assertThat(NotificationType.TAX_CALCULATION_FAILED.isSendsEmail()).isFalse();
        assertThat(NotificationType.TAX_CALCULATION_FAILED.isSendsInApp()).isTrue();
    }

    @Test
    @DisplayName("PRICE_ALERT_TRIGGERED salje email (jednokratno po prelasku praga)")
    void priceAlert_sendsEmail() {
        assertThat(NotificationType.PRICE_ALERT_TRIGGERED.isSendsEmail()).isTrue();
        assertThat(NotificationType.PRICE_ALERT_TRIGGERED.isSendsInApp()).isTrue();
    }

    @Test
    @DisplayName("GENERAL fallback NE salje email niti in-app (interni fallback)")
    void generalFallback_isSilent() {
        assertThat(NotificationType.GENERAL.isSendsEmail()).isFalse();
        assertThat(NotificationType.GENERAL.isSendsInApp()).isFalse();
    }
}
