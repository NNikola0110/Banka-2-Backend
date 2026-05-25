package rs.raf.trading.notification.model;

import lombok.Getter;

/**
 * [B4 — port iz banka2_bek] Notifikacioni tipovi za trgovinski domen.
 *
 * <p>Trading-service publish-uje samo trgovinske evente (order lifecycle + OTC).
 * Ostali tipovi (PAYMENT, TRANSFER, LIMIT_CHANGE, CARD_*, LOAN_*, ACCOUNT_LOCKED)
 * zive u banka2_bek/notification/model i nisu deo trgovinskog domena.
 *
 * <p>Dva nezavisna flag-a:
 * <ul>
 *   <li>{@code sendsEmail} — kontrolise RabbitMQ {@code IN_APP_GENERIC} publish ka
 *       {@code notification-service} (email kanal).</li>
 *   <li>{@code sendsInApp} — kontrolise cross-DB poziv ka banka-core
 *       {@code POST /internal/notifications}, sto perzistira notifikaciju u
 *       {@code notifications} tabelu i pojavljuje je u FE NotificationBell-u.
 *       Svi user-facing eventi imaju {@code true}; izrazito interni/debug
 *       eventi imaju {@code false}.</li>
 * </ul>
 */
@Getter
public enum NotificationType {

    // Order lifecycle events — spec Celina 3 trazi email obavestenja
    ORDER_PENDING(true, true),
    ORDER_APPROVED(true, true),
    ORDER_DECLINED(true, true),
    ORDER_EXECUTED(true, true),
    ORDER_PARTIAL_FILL(true, true),
    ORDER_CANCELLED(true, true),

    // OTC events — spec Celina 4 trazi email obavestenja
    OTC_COUNTER_OFFER(true, true),
    OTC_ACCEPTED(true, true),
    OTC_DECLINED(true, true),
    OTC_CONTRACT_EXPIRING(true, true),

    // [B8 — Nikola Djurovic] Recurring order events
    RECURRING_ORDER_SKIPPED(true, true),

    // [B5 — Aleksa Vucinic] Price Alert okidan — spec Celina 3 trazi email obavestenje
    PRICE_ALERT_TRIGGERED(true, true),

    // Fallback — interni, ne salje email niti in-app (debug/sysadmin)
    GENERAL(false, false);

    private final boolean sendsEmail;
    private final boolean sendsInApp;

    NotificationType(boolean sendsEmail, boolean sendsInApp) {
        this.sendsEmail = sendsEmail;
        this.sendsInApp = sendsInApp;
    }
}
