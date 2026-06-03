package rs.raf.trading.notification.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>C-notif-email (02.06): pinuje EMAIL-notif kontrakt za trgovinske evente.</b>
 *
 * <p>Istorija: P2-notif-reliability-2 je sve order/OTC tipove iskljucio iz email
 * kanala ({@code sendsEmail=false}) zbog straha od "email flood-a po order tick-u".
 * Taj strah je bio precenjen — spec TODO_testovi Sc20-25/Sc60-63 EKSPLICITNO trazi
 * email za svaki order/OTC lifecycle event, a svaki od njih se emituje TACNO JEDNOM
 * po smislenom (terminalnom/diskretnom) eventu, NE po svakom scheduler tick-u:
 * <ul>
 *   <li>ORDER_PENDING/APPROVED/DECLINED/CANCELLED — jedan diskretan lifecycle prelaz;</li>
 *   <li>ORDER_EXECUTED — okida se SAMO pri prelasku u DONE ({@code justCompleted}),
 *       ne po fill tick-u;</li>
 *   <li>ORDER_PARTIAL_FILL — jedan email po STVARNOM parcijalnom fill-u (Sc24);
 *       prazan tick (cena previsoka / AON odlozen) izadje PRE notifikacije → 0 emailova;</li>
 *   <li>OTC_* — diskretna korisnicka akcija / dnevni expiry-warning okidan tacno na
 *       dan now+3;</li>
 *   <li>FUND_PAYOUT — jedan event po ClientFundTransaction-u (Sc35/36/49/50).</li>
 * </ul>
 *
 * <p>Anti-flood invarijanta na nivou EMISIJE (tick-bez-fill → 0 emailova; DONE → 1;
 * svaki stvarni fill → 1) pinovana je u {@code SingleOrderExecutorTest} (Sc23/Sc24).
 * Ovaj kontrakt-test pinuje FLAG semantiku: spec-required eventi salju email, dok
 * tipovi sa DEDIKOVANIM email template-om ili ne-korisnicki interni tipovi ostaju
 * in-app-only (da ne bi pravili dupli ili sum email).
 */
class NotificationTypeContractParityTest {

    /**
     * Spec-required EMAIL eventi (TODO_testovi Sc20-25/60-63 + TestoviCelina4
     * Sc35/36/49/50). Svaki MORA biti email + in-app, i emituje se tacno jednom po
     * smislenom eventu.
     */
    private static final Set<NotificationType> SPEC_EMAIL_TYPES = Set.of(
            NotificationType.ORDER_PENDING,
            NotificationType.ORDER_APPROVED,
            NotificationType.ORDER_DECLINED,
            NotificationType.ORDER_EXECUTED,
            NotificationType.ORDER_PARTIAL_FILL,
            NotificationType.ORDER_CANCELLED,
            NotificationType.OTC_COUNTER_OFFER,
            NotificationType.OTC_ACCEPTED,
            NotificationType.OTC_DECLINED,
            NotificationType.OTC_CONTRACT_EXPIRING,
            NotificationType.FUND_PAYOUT,
            NotificationType.PRICE_ALERT_TRIGGERED
    );

    /**
     * Tipovi koji NAMERNO ostaju in-app-only (email se NE salje iz ovog generic
     * notify kanala):
     * <ul>
     *   <li>MARGIN_ACCOUNT_BLOCKED — email ide preko DEDIKOVANOG branded
     *       {@code MARGIN_ACCOUNT_BLOCKED} RabbitMQ kind-a; generic email bi bio dupli;</li>
     *   <li>TAX_CALCULATION_FAILED — operativni in-app alert supervizorima (ne email noise);</li>
     *   <li>RECURRING_ORDER_SKIPPED — in-app-only (van C-notif-email scope-a);</li>
     *   <li>GENERAL — interni fallback, oba kanala iskljucena.</li>
     * </ul>
     */
    private static final Set<NotificationType> IN_APP_ONLY_TYPES = Set.of(
            NotificationType.MARGIN_ACCOUNT_BLOCKED,
            NotificationType.TAX_CALCULATION_FAILED,
            NotificationType.RECURRING_ORDER_SKIPPED,
            NotificationType.GENERAL
    );

    @Test
    void specRequiredEvents_sendEmailAndInApp() {
        for (NotificationType type : SPEC_EMAIL_TYPES) {
            assertThat(type.isSendsEmail())
                    .as("%s je spec-required EMAIL event (Sc20-25/60-63/35-36/49-50) → mora sendsEmail=true", type)
                    .isTrue();
            assertThat(type.isSendsInApp())
                    .as("%s treba da je i in-app vidljiv (bell)", type)
                    .isTrue();
        }
    }

    @Test
    void inAppOnlyTypes_doNotSendGenericEmail() {
        for (NotificationType type : IN_APP_ONLY_TYPES) {
            assertThat(type.isSendsEmail())
                    .as("%s NE sme da salje generic email (dedikovan template / interni alert / van scope-a)", type)
                    .isFalse();
        }
    }

    @Test
    void marginBlocked_isInAppOnly_noDoubleEmail() {
        // KRITICNO (P2-notif-reliability-2 R1 381): email ide preko dedikovanog
        // MARGIN_ACCOUNT_BLOCKED branded RabbitMQ kind-a. Generic notify email bi bio
        // dupli mejl — zato OSTAJE sendsEmail=false.
        assertThat(NotificationType.MARGIN_ACCOUNT_BLOCKED.isSendsEmail()).isFalse();
        assertThat(NotificationType.MARGIN_ACCOUNT_BLOCKED.isSendsInApp()).isTrue();
    }

    @Test
    void general_isSilentOnBothChannels() {
        assertThat(NotificationType.GENERAL.isSendsEmail()).isFalse();
        assertThat(NotificationType.GENERAL.isSendsInApp()).isFalse();
    }

    @Test
    void everyTypeIsClassified_noUnpinnedFlag() {
        // Svaki enum tip mora biti klasifikovan (spec-email ILI in-app-only) — hvata
        // novododat tip cija email-semantika nije svesno odlucena.
        for (NotificationType type : NotificationType.values()) {
            boolean classified = SPEC_EMAIL_TYPES.contains(type) || IN_APP_ONLY_TYPES.contains(type);
            assertThat(classified)
                    .as("Tip %s nije klasifikovan — dodaj ga u SPEC_EMAIL_TYPES ili IN_APP_ONLY_TYPES "
                            + "i svesno odluci da li salje email", type)
                    .isTrue();
        }
    }
}
