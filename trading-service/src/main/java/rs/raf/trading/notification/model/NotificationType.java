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

    // Order lifecycle events — email + in-app.
    //
    // <p><b>C-notif-email (02.06): spec TODO_testovi Sc20-25 EKSPLICITNO trazi
    // email za svaki order lifecycle event.</b> P2-notif-reliability-2 je ranije
    // sve order tipove iskljucio iz email kanala ({@code sendsEmail=false}) zbog
    // straha od "email flood-a po order tick-u". Taj strah je bio precenjen: SVAKI
    // od ovih tipova se emituje TACNO JEDNOM po smislenom (terminalnom) eventu, NE
    // po svakom scheduler tick-u:
    // <ul>
    //   <li>{@code ORDER_PENDING} — jednom pri kreiranju (OrderServiceImpl create);</li>
    //   <li>{@code ORDER_APPROVED}/{@code ORDER_DECLINED} — jednom po supervizorskoj
    //       akciji (approveOrder/declineOrder);</li>
    //   <li>{@code ORDER_EXECUTED} — jednom kad order predje u DONE
    //       ({@code SingleOrderExecutor} ga okida SAMO pod {@code justCompleted}, NE po
    //       fill tick-u);</li>
    //   <li>{@code ORDER_CANCELLED} — jednom po auto-otkazivanju (settlement prosao /
    //       blokiran margin racun);</li>
    //   <li>{@code ORDER_PARTIAL_FILL} — jednom po STVARNOM parcijalnom fill-u (Sc24
    //       "izvrši 4 od 10"). Prazan tick (cena previsoka / AON odlozen) izadje PRE
    //       notifikacije → 0 emailova; email ide iskljucivo kad se kolicina stvarno
    //       popuni. Spec Sc24 doslovno trazi "email obavestenje o delimicnom
    //       izvrsenju" — to JESTE jedan email po fill-u, ne flood po no-op tick-u.</li>
    // </ul>
    // Invarijanta (tick-bez-fill → 0 emailova; DONE → 1 email; svaki fill → 1 email)
    // pinovana u {@code SingleOrderExecutorTest} (Sc23/Sc24) +
    // {@code NotificationTypeContractParityTest}.
    ORDER_PENDING(true, true),
    ORDER_APPROVED(true, true),
    ORDER_DECLINED(true, true),
    ORDER_EXECUTED(true, true),
    ORDER_PARTIAL_FILL(true, true),
    ORDER_CANCELLED(true, true),

    // OTC events — email + in-app (spec TODO_testovi Sc60-63 trazi email).
    // Svaki je diskretan jednokratni event (kontraponuda poslata / prihvatanje /
    // odustajanje / dnevni expiry-warning okidan tacno na dan now+3), NE per-tick →
    // tacno jedan email po eventu, bez flood-a.
    OTC_COUNTER_OFFER(true, true),
    OTC_ACCEPTED(true, true),
    OTC_DECLINED(true, true),
    OTC_CONTRACT_EXPIRING(true, true),

    // C-notif-email (02.06): isplata/likvidacija iz fonda — email + in-app.
    // Spec TestoviCelina4 Sc35/36/49/50 trazi da klijent dobije obavestenje pri
    // isplati (uspesnoj ili pokrenutoj likvidaciji). Jedan event po
    // ClientFundTransaction-u → tacno jedan email, bez flood-a. Zamenjuje raniji
    // log-only sendPushNotification stub (R1 490).
    FUND_PAYOUT(true, true),

    // [B8 — Nikola Djurovic] Recurring order events — in-app only (paritet banka-core)
    RECURRING_ORDER_SKIPPED(false, true),

    // [B5 — Aleksa Vucinic] Price Alert okidan — spec Celina 3 trazi email obavestenje.
    // sendsEmail=true je OK: alarm se atomicno deaktivira pri okidanju
    // (PriceAlertService.deactivateAlertIfActive) → tacno JEDAN email po prelasku
    // praga, NIJE per-tick flood.
    PRICE_ALERT_TRIGGERED(true, true),

    // P2-notif-reliability-2 (R1 381): margin-call blokada — IN-APP only.
    // Email salje MarginAccountBlockedNotificationListener preko dedikovanog
    // MARGIN_ACCOUNT_BLOCKED RabbitMQ kind-a (branded template), pa bi
    // sendsEmail=true ovde napravio dupli email (isti R1 380 obrazac).
    MARGIN_ACCOUNT_BLOCKED(false, true),

    // OT-1061: mesecni tax obracun preskocen za korisnika (FX kurs nedostupan) →
    // supervizor mora da bude obavesten da pokrene retry. IN-APP only (bell) —
    // operativni alert, ne email noise. Ranije je TaxScheduler slao GENERAL +
    // recipientId=null (DOUBLE no-op) pa supervizor NIKAD nije primio nista; sada
    // se salje ovaj in-app-sending tip svakom razresenom supervizoru.
    TAX_CALCULATION_FAILED(false, true),

    // Fallback — interni, ne salje email niti in-app (debug/sysadmin)
    GENERAL(false, false);

    private final boolean sendsEmail;
    private final boolean sendsInApp;

    NotificationType(boolean sendsEmail, boolean sendsInApp) {
        this.sendsEmail = sendsEmail;
        this.sendsInApp = sendsInApp;
    }
}
