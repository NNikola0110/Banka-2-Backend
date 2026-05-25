package rs.raf.banka2_bek.notification.model;

import lombok.Getter;

/**
 * [B1 — Foundation] Central registry of all notification types in the system.
 *
 * <p>When {@code sendsEmail = true}, {@code NotificationServiceImpl.notify()} also
 * publishes an {@code IN_APP_GENERIC} message on RabbitMQ via
 * {@link rs.raf.banka2_bek.notification.NotificationPublisher#sendInAppGenericMail}.
 * The {@code notification-service} consumer routes it through the branded generic
 * in-app email template. Once B4 adds type-based dispatch in {@code notification-service},
 * each type can render its own template.
 */
@Getter
public enum NotificationType {

    // [B4 — Petar] Financial / account events
    PAYMENT(true),
    TRANSFER(true),
    LIMIT_CHANGE(true),
    CARD_BLOCKED(true),
    CARD_UNBLOCKED(true),
    LOAN_CREATED(true),
    LOAN_APPROVED(true),
    LOAN_REJECTED(true),

    // [B4 — Petar] Order lifecycle events
    ORDER_PENDING(false),
    ORDER_APPROVED(false),
    ORDER_DECLINED(false),
    ORDER_EXECUTED(false),
    ORDER_PARTIAL_FILL(false),
    ORDER_CANCELLED(false),

    // [B4 — Petar] OTC events
    OTC_COUNTER_OFFER(false),
    OTC_ACCEPTED(false),
    OTC_DECLINED(false),
    OTC_CONTRACT_EXPIRING(false),

    // [B2 — Andjela] Account security events
    ACCOUNT_LOCKED(true),

    // [B5 — Aleksa Vucinic] Price alert triggered by scheduler when threshold crossed.
    PRICE_ALERT_TRIGGERED(true),

    // [B8 — Nikola Djurovic] Recurring order events
    RECURRING_ORDER_SKIPPED(false),

    // [B1] Fallback type for ad-hoc notifications
    GENERAL(false);

    private final boolean sendsEmail;

    NotificationType(boolean sendsEmail) {
        this.sendsEmail = sendsEmail;
    }
}
