package rs.raf.banka2_bek.notification.model;

import lombok.Getter;

/**
 * [B1 — Foundation] Central registry of all notification types in the system.
 *
 * <p><b>Two orthogonal channel flags:</b>
 * <ul>
 *   <li>{@code sendsEmail} — kad je {@code true}, {@code NotificationServiceImpl.notify()}
 *       publishuje {@code IN_APP_GENERIC} poruku na RabbitMQ preko
 *       {@link rs.raf.banka2_bek.notification.NotificationPublisher#sendInAppGenericMail}.
 *       {@code notification-service} consumer rendera branded generic email template.</li>
 *   <li>{@code sendsInApp} — [BE-NTF-02] kad je {@code true}, persistira se in-app
 *       notifikacioni red u banka_core_db (vidljiv kroz notification bell na FE-u).
 *       Sluzi za per-type in-app suppression: neki dogadjaji su samo email,
 *       neki samo in-app, vecina oba.</li>
 * </ul>
 * Default za sve trenutno definisane tipove je {@code sendsInApp = true} —
 * cuva backward-compat (pre BE-NTF-02 svi su upisivani u DB). {@code GENERAL}
 * fallback takodje cuva in-app vidljivost (in-app zapis je default ako tip
 * nije eksplicitno mappovan).
 */
@Getter
public enum NotificationType {

    // [B4 — Petar] Financial / account events.
    //
    // <p><b>P2-notif-reliability-2 (R1 380): sendsEmail=false na tipovima koji vec
    // imaju DEDIKOVANI email na pozivnom mestu.</b> Pre fix-a, npr. placanje je
    // slalo DVA emaila: (1) {@code sendPaymentConfirmationMail} (branded payment
    // template) na call-site-u + (2) {@code notify(PAYMENT)} koji je zbog
    // {@code sendsEmail=true} dodatno publish-ovao {@code IN_APP_GENERIC} email.
    // Isti dupli-email obrazac vazi za TRANSFER, CARD_BLOCKED/UNBLOCKED i sve
    // LOAN_* (svaki ima svoj dedikovani template). Ovi tipovi sad samo persistuju
    // in-app red (bell), a email salje iskljucivo dedikovani template → jedan email.
    //
    // <p>{@code LIMIT_CHANGE} ostaje {@code sendsEmail=true} — NEMA dedikovan
    // email template, pa je {@code notify()}-okinut generic email JEDINI email
    // kanal za promenu limita (uklanjanje bi ostavilo korisnika bez ikakvog mejla).
    PAYMENT(false, true),
    TRANSFER(false, true),
    LIMIT_CHANGE(true, true),
    CARD_BLOCKED(false, true),
    CARD_UNBLOCKED(false, true),
    LOAN_CREATED(false, true),
    LOAN_APPROVED(false, true),
    LOAN_REJECTED(false, true),

    // [B4 — Petar] Order lifecycle events — in-app only (no email noise per order tick)
    ORDER_PENDING(false, true),
    ORDER_APPROVED(false, true),
    ORDER_DECLINED(false, true),
    ORDER_EXECUTED(false, true),
    ORDER_PARTIAL_FILL(false, true),
    ORDER_CANCELLED(false, true),

    // [B4 — Petar] OTC events — in-app only
    OTC_COUNTER_OFFER(false, true),
    OTC_ACCEPTED(false, true),
    OTC_DECLINED(false, true),
    OTC_CONTRACT_EXPIRING(false, true),

    // [B2 — Andjela] Account security events — both channels (kriticno, email + in-app)
    ACCOUNT_LOCKED(true, true),

    // [B5 — Aleksa Vucinic] Price alert triggered by scheduler when threshold crossed.
    PRICE_ALERT_TRIGGERED(true, true),

    // P2-notif-reliability-2 (R1 381): margin-call blokada (cross-DB iz trading-a) —
    // in-app only; branded email salje trading preko MARGIN_ACCOUNT_BLOCKED kind-a.
    MARGIN_ACCOUNT_BLOCKED(false, true),

    // [B8 — Nikola Djurovic] Recurring order events — in-app only
    RECURRING_ORDER_SKIPPED(false, true),

    // OT-1061: tax obracun preskocen (FX nedostupan) → supervizor alert iz
    // trading-service-a (cross-DB in-app POST). In-app only (bell). Mora postojati
    // u banka-core enum-u da se ne mapira u GENERAL fallback pri perzistenciji.
    TAX_CALCULATION_FAILED(false, true),

    // C-notif-email (02.06): isplata/likvidacija iz fonda (cross-DB in-app POST iz
    // trading-service-a). In-app only ovde — email salje trading preko svog
    // IN_APP_GENERIC kanala (FUND_PAYOUT sendsEmail=true u trading enum-u). Ovaj
    // ulaz postoji samo da se in-app red perzistira pod tacnim tipom (ne GENERAL
    // fallback). Spec TestoviCelina4 Sc35/36/49/50.
    FUND_PAYOUT(false, true),

    // [B1] Fallback type for ad-hoc notifications — in-app default channel
    GENERAL(false, true);

    private final boolean sendsEmail;
    private final boolean sendsInApp;

    NotificationType(boolean sendsEmail, boolean sendsInApp) {
        this.sendsEmail = sendsEmail;
        this.sendsInApp = sendsInApp;
    }
}
