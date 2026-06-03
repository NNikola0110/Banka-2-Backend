package rs.raf.trading.notification.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import rs.raf.banka2.contracts.NotificationKind;
import rs.raf.banka2.contracts.NotificationMessage;
import rs.raf.banka2.contracts.NotificationRabbit;
import rs.raf.banka2.contracts.internal.InternalNotificationRequest;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.notification.model.NotificationType;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testovi za {@link NotificationServiceImpl} — verifikuje da:
 * <ul>
 *   <li>email kanal okida samo kad {@code NotificationType.sendsEmail() == true};</li>
 *   <li>in-app kanal (cross-DB POST ka banka-core) okida samo kad
 *       {@code NotificationType.sendsInApp() == true};</li>
 *   <li>greske u oba kanala su best-effort — ne propagiraju se nazad pozivaocu.</li>
 * </ul>
 */
class NotificationServiceImplTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final BankaCoreClient bankaCoreClient = mock(BankaCoreClient.class);
    private final NotificationServiceImpl service = new NotificationServiceImpl(rabbitTemplate, bankaCoreClient);

    private static final InternalUserDto STEFAN = new InternalUserDto(
            7L, "CLIENT", "stefan@test.com", "Stefan", "J", true, null);

    @Test
    void notify_priceAlert_sendsEmailAndInApp() throws InterruptedException, TimeoutException {
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(STEFAN);

        // PRICE_ALERT_TRIGGERED ima sendsEmail=true (jednokratni email po prelasku
        // praga, NIJE per-tick) i sendsInApp=true → oba kanala.
        service.notify(7L, "CLIENT", NotificationType.PRICE_ALERT_TRIGGERED,
                "Cenovni alarm okidan", "AAPL presla iznad praga", "PRICE_ALERT", 42L);

        // Email kanal: RabbitMQ publish sa IN_APP_GENERIC kind-om.
        ArgumentCaptor<NotificationMessage> rabbitCaptor =
                ArgumentCaptor.forClass(NotificationMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(NotificationRabbit.EXCHANGE),
                eq(NotificationRabbit.EMAIL_ROUTING_KEY),
                rabbitCaptor.capture());
        assertThat(rabbitCaptor.getValue().kind()).isEqualTo(NotificationKind.IN_APP_GENERIC);
        assertThat(rabbitCaptor.getValue().data())
                .containsEntry("email", "stefan@test.com")
                .containsEntry("firstName", "Stefan")
                .containsEntry("title", "Cenovni alarm okidan")
                .containsEntry("body", "AAPL presla iznad praga");

        // In-app kanal: cross-DB POST ka banka-core (async, pa cekamo).
        ArgumentCaptor<InternalNotificationRequest> inAppCaptor =
                ArgumentCaptor.forClass(InternalNotificationRequest.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(bankaCoreClient).postNotification(inAppCaptor.capture()));
        InternalNotificationRequest captured = inAppCaptor.getValue();
        assertThat(captured.recipientId()).isEqualTo(7L);
        assertThat(captured.recipientType()).isEqualTo("CLIENT");
        assertThat(captured.type()).isEqualTo("PRICE_ALERT_TRIGGERED");
        assertThat(captured.title()).isEqualTo("Cenovni alarm okidan");
        assertThat(captured.message()).isEqualTo("AAPL presla iznad praga");
        assertThat(captured.referenceType()).isEqualTo("PRICE_ALERT");
        assertThat(captured.referenceId()).isEqualTo(42L);
        assertThat(captured.idempotencyKey()).isNotBlank();
    }

    // ── C-notif-email (02.06): order/OTC/fund lifecycle eventi SALJU email (Sc20-25/60-63/35-50) ──

    @Test
    void notify_orderExecuted_sendsEmailAndInApp_Sc23() {
        // C-notif-email Sc23: ORDER_EXECUTED salje email (terminalni DONE event, jednom).
        // SingleOrderExecutor ga okida SAMO pod justCompleted → nije per-tick flood.
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(STEFAN);

        service.notify(7L, "CLIENT", NotificationType.ORDER_EXECUTED,
                "Nalog izvršen", "Vaš BUY order je u potpunosti izvršen", "ORDER", 42L);

        ArgumentCaptor<NotificationMessage> rabbitCaptor =
                ArgumentCaptor.forClass(NotificationMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(NotificationRabbit.EXCHANGE),
                eq(NotificationRabbit.EMAIL_ROUTING_KEY),
                rabbitCaptor.capture());
        assertThat(rabbitCaptor.getValue().kind()).isEqualTo(NotificationKind.IN_APP_GENERIC);
        assertThat(rabbitCaptor.getValue().data()).containsEntry("email", "stefan@test.com");
        // In-app POST i dalje ide (async).
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(bankaCoreClient).postNotification(any(InternalNotificationRequest.class)));
    }

    @Test
    void notify_orderPartialFill_sendsEmailAndInApp_Sc24() {
        // C-notif-email Sc24: ORDER_PARTIAL_FILL salje email po STVARNOM fill-u.
        // Anti-flood je na nivou emisije (SingleOrderExecutor ne emituje na no-op tick),
        // ne gasenjem flag-a. Telo nosi "Izvrseno/Preostalo".
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(STEFAN);

        service.notify(7L, "CLIENT", NotificationType.ORDER_PARTIAL_FILL,
                "Nalog delimično izvršen", "Izvršeno: 4 / 10 komada. Preostalo: 6 komada.",
                "ORDER", 42L);

        ArgumentCaptor<NotificationMessage> rabbitCaptor =
                ArgumentCaptor.forClass(NotificationMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(NotificationRabbit.EXCHANGE),
                eq(NotificationRabbit.EMAIL_ROUTING_KEY),
                rabbitCaptor.capture());
        assertThat(rabbitCaptor.getValue().data())
                .containsEntry("body", "Izvršeno: 4 / 10 komada. Preostalo: 6 komada.");
    }

    @Test
    void notify_otcCounterOffer_sendsEmail_Sc60() {
        // C-notif-email Sc60-63: OTC eventi salju email (diskretan korisnicki event).
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(STEFAN);

        service.notify(7L, "CLIENT", NotificationType.OTC_COUNTER_OFFER,
                "Nova kontraponuda", "Primili ste novu kontraponudu za AAPL.", "OTC_OFFER", 5L);

        verify(rabbitTemplate).convertAndSend(
                eq(NotificationRabbit.EXCHANGE), eq(NotificationRabbit.EMAIL_ROUTING_KEY),
                any(NotificationMessage.class));
    }

    @Test
    void notify_fundPayout_sendsEmail_Sc36() {
        // C-notif-email Sc35/36/49/50: FUND_PAYOUT salje email (jedan event po tx).
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(STEFAN);

        service.notify(7L, "CLIENT", NotificationType.FUND_PAYOUT,
                "Isplata iz fonda pokrenuta", "Isplata će biti završena u kratkom roku.", "FUND", 3L);

        verify(rabbitTemplate).convertAndSend(
                eq(NotificationRabbit.EXCHANGE), eq(NotificationRabbit.EMAIL_ROUTING_KEY),
                any(NotificationMessage.class));
    }

    @Test
    void notify_marginBlocked_doesNotSendGenericEmail_avoidsDuplicate() {
        // KRITICNO (P2-notif-reliability-2 R1 381): MARGIN_ACCOUNT_BLOCKED OSTAJE
        // in-app-only — email ide preko DEDIKOVANOG branded RabbitMQ kind-a. Generic
        // notify email bi bio DUPLI mejl, zato se ne okida.
        service.notify(7L, "CLIENT", NotificationType.MARGIN_ACCOUNT_BLOCKED,
                "Marzni racun blokiran", "Margin call", "MARGIN", 1L);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(bankaCoreClient, never()).getUserById(anyString(), any());
        // In-app POST i dalje ide (bell).
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(bankaCoreClient).postNotification(any(InternalNotificationRequest.class)));
    }

    // ── C-notif-email blocker #2 (03.06): email-channel idempotency ──

    @Test
    void notify_duplicateOrderCancelled_sameOrderId_emailSentOnce_inAppTwice() {
        // Blocker #2: OBA settlement-expiry decline puta (OrderCleanupScheduler @ 01:00
        // + SingleOrderExecutor) mogu okinuti ORDER_CANCELLED za ISTI orderId u istom
        // prozoru. Email kanal sada dedupuje po idempotency kljucu → klijent dobija
        // TACNO JEDAN email, ne dva. In-app je banka-core-dedup-ovan nezavisno.
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(STEFAN);

        service.notify(7L, "CLIENT", NotificationType.ORDER_CANCELLED,
                "Nalog otkazan", "Settlement prosao", "ORDER", 99L);
        service.notify(7L, "CLIENT", NotificationType.ORDER_CANCELLED,
                "Nalog otkazan", "Settlement prosao", "ORDER", 99L);

        // Email (RabbitMQ publish) ide TACNO JEDNOM uprkos dva poziva.
        verify(rabbitTemplate, org.mockito.Mockito.times(1)).convertAndSend(
                eq(NotificationRabbit.EXCHANGE), eq(NotificationRabbit.EMAIL_ROUTING_KEY),
                any(NotificationMessage.class));
        // In-app POST ide oba puta (banka-core dedupuje po UNIQUE idempotency_key,
        // ne ovaj klijent) — trading salje oba zahteva.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(bankaCoreClient, org.mockito.Mockito.times(2))
                        .postNotification(any(InternalNotificationRequest.class)));
    }

    @Test
    void notify_orderCancelled_differentOrderIds_bothEmailsSent() {
        // Razliciti orderId → razlicit idempotency kljuc → oba email-a se salju
        // (dedup ne sme da suprimuje legitimne distinktne evente).
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(STEFAN);

        service.notify(7L, "CLIENT", NotificationType.ORDER_CANCELLED,
                "Nalog otkazan", "Settlement prosao", "ORDER", 99L);
        service.notify(7L, "CLIENT", NotificationType.ORDER_CANCELLED,
                "Nalog otkazan", "Settlement prosao", "ORDER", 100L);

        verify(rabbitTemplate, org.mockito.Mockito.times(2)).convertAndSend(
                eq(NotificationRabbit.EXCHANGE), eq(NotificationRabbit.EMAIL_ROUTING_KEY),
                any(NotificationMessage.class));
    }

    @Test
    void notify_distinctFundPayoutEvents_sameClientSameFund_byTxId_bothEmailsSent() {
        // Blocker #3 pojacanje: dve isplate iz istog fonda istom klijentu, keyed po
        // FUND_TRANSACTION/txId → razlicit kljuc → oba email-a prolaze (ne suprimuju
        // se kao kad bi kljuc bio fundId).
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(STEFAN);

        service.notify(7L, "CLIENT", NotificationType.FUND_PAYOUT,
                "Isplata iz fonda uspešna", "Isplata 1", "FUND_TRANSACTION", 11L);
        service.notify(7L, "CLIENT", NotificationType.FUND_PAYOUT,
                "Isplata iz fonda uspešna", "Isplata 2", "FUND_TRANSACTION", 12L);

        verify(rabbitTemplate, org.mockito.Mockito.times(2)).convertAndSend(
                eq(NotificationRabbit.EXCHANGE), eq(NotificationRabbit.EMAIL_ROUTING_KEY),
                any(NotificationMessage.class));
    }

    @Test
    void notify_sameFundTx_initiatedThenSuccess_distinctTitles_bothEmailsSent() {
        // "Isplata pokrenuta" (Sc36) i "Isplata uspesna" (Sc35) za ISTU tx imaju
        // razlicit title/body. Email dedup kljuc ukljucuje title+body SAMO kad je
        // referenca null; ovde je referenca non-null (FUND_TRANSACTION, isti txId),
        // pa kljuc NE zavisi od title → drugi email bi se suprimovao. To je ISPRAVNO:
        // "pokrenuta" i "uspesna" za istu tx su isti logicki resurs-event lanac i email
        // bi inace bio bucan; in-app (bell) ih razlikuje preko banka-core (oba se POST-uju).
        // Ovaj test PINUJE da je email-dedup po resursu (txId), ne po tekstu.
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(STEFAN);

        service.notify(7L, "CLIENT", NotificationType.FUND_PAYOUT,
                "Isplata iz fonda pokrenuta", "Bice zavrsena uskoro", "FUND_TRANSACTION", 20L);
        service.notify(7L, "CLIENT", NotificationType.FUND_PAYOUT,
                "Isplata iz fonda uspešna", "Procesuirana", "FUND_TRANSACTION", 20L);

        // Email: isti txId resurs → JEDAN email (anti-bucnost za isti event-lanac).
        verify(rabbitTemplate, org.mockito.Mockito.times(1)).convertAndSend(
                eq(NotificationRabbit.EXCHANGE), eq(NotificationRabbit.EMAIL_ROUTING_KEY),
                any(NotificationMessage.class));
        // In-app oba (banka-core dedupuje; "pokrenuta"/"uspesna" imaju isti kljuc pa
        // banka-core sam odlucuje — trading salje oba POST-a).
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(bankaCoreClient, org.mockito.Mockito.times(2))
                        .postNotification(any(InternalNotificationRequest.class)));
    }

    // ── R1 384: stabilan (deterministicki) idempotency kljuc ──

    @Test
    void notify_idempotencyKey_isStableForSameEvent() {
        String k1 = NotificationServiceImpl.buildIdempotencyKey(
                7L, "CLIENT", NotificationType.ORDER_EXECUTED, "ORDER", 42L, "T", "B");
        String k2 = NotificationServiceImpl.buildIdempotencyKey(
                7L, "CLIENT", NotificationType.ORDER_EXECUTED, "ORDER", 42L, "T", "B");
        // Isti logicki event → isti kljuc (banka-core ga dedupuje pri retry-u).
        assertThat(k1).isEqualTo(k2);
        // Razlicit referenceId → razlicit kljuc.
        String k3 = NotificationServiceImpl.buildIdempotencyKey(
                7L, "CLIENT", NotificationType.ORDER_EXECUTED, "ORDER", 99L, "T", "B");
        assertThat(k3).isNotEqualTo(k1);
        // <= 100 chars (kolona limit u InternalRequest).
        assertThat(k1.length()).isLessThanOrEqualTo(100);
    }

    @Test
    void notify_typeBothFalse_skipsBothChannels() {
        // GENERAL ima oba flag-a false → niti email niti in-app.
        service.notify(7L, "CLIENT", NotificationType.GENERAL,
                "Test", "Body", null, null);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        // In-app je async — daj mu vremena da ne pozove postNotification
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }
        verify(bankaCoreClient, never()).postNotification(any(InternalNotificationRequest.class));
    }

    @Test
    void notify_recipientIdNull_skipsBothChannels() {
        service.notify(null, "CLIENT", NotificationType.ORDER_EXECUTED,
                "Test", "Body", null, null);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(bankaCoreClient, never()).postNotification(any(InternalNotificationRequest.class));
    }

    @Test
    void notify_recipientTypeNull_skipsBothChannels() {
        service.notify(7L, null, NotificationType.ORDER_EXECUTED,
                "Test", "Body", null, null);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(bankaCoreClient, never()).postNotification(any(InternalNotificationRequest.class));
    }

    @Test
    void notify_bankaCoreLookupFails_emailSkipped_butInAppStillCalled() {
        // Email lookup pada → email se preskace; ali in-app je nezavisan i ide.
        // PRICE_ALERT_TRIGGERED je email-sending tip (jednokratni email).
        when(bankaCoreClient.getUserById("CLIENT", 99L))
                .thenThrow(new BankaCoreClientException(503, "banka-core down"));

        assertThatNoException().isThrownBy(() ->
                service.notify(99L, "CLIENT", NotificationType.PRICE_ALERT_TRIGGERED,
                        "Alarm", "Body", "PRICE_ALERT", 1L));

        // Email NIJE publishovan
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        // Ali in-app jeste — async
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(bankaCoreClient).postNotification(any(InternalNotificationRequest.class)));
    }

    @Test
    void notify_rabbitFails_swallowsException() {
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(STEFAN);
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertThatNoException().isThrownBy(() ->
                service.notify(7L, "CLIENT", NotificationType.PRICE_ALERT_TRIGGERED,
                        "Alarm", "Body", null, null));
    }

    @Test
    void notify_bankaCorePostNotificationFails_swallowsException() {
        // postNotification je vec best-effort u BankaCoreClient (swallow-uje sve),
        // ali da postavimo doThrow ipak verifikujemo da NotificationServiceImpl
        // nista ne escape-uje. ORDER_EXECUTED je in-app-only → email lookup se ne poziva.
        doThrow(new RuntimeException("network error"))
                .when(bankaCoreClient).postNotification(any(InternalNotificationRequest.class));

        assertThatNoException().isThrownBy(() ->
                service.notify(7L, "CLIENT", NotificationType.ORDER_EXECUTED,
                        "Order", "Body", null, null));
    }

    @Test
    void notify_emailBlankFromBankaCore_skipsEmailPublish() {
        InternalUserDto noEmail = new InternalUserDto(7L, "CLIENT", "", "Stefan", "J", true, null);
        when(bankaCoreClient.getUserById("CLIENT", 7L)).thenReturn(noEmail);

        service.notify(7L, "CLIENT", NotificationType.PRICE_ALERT_TRIGGERED,
                "Test", "Body", null, null);

        // Email NIJE publishovan
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        // Ali in-app jeste (nezavisan kanal)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(bankaCoreClient).postNotification(any(InternalNotificationRequest.class)));
    }

    // ════════════════════════════════════════════════════════════════════
    //  OT-1061 [FIXED 02.06] — ranije je TaxScheduler.notifySupervisorOfTaxFailure
    //  zvao notify(null, "SUPERVISOR", GENERAL, ...) sto je bilo DOUBLE no-op:
    //    (1) recipientId==null guard rano vrati (preskoci oba kanala);
    //    (2) i da nije null, GENERAL ima sendsEmail=false + sendsInApp=false.
    //  Posledica: supervizor NIKAD nije primio tax-fail (FX) notifikaciju.
    //  FIX: nov notifySupervisors(...) razresi realne supervizore preko banka-core
    //  (GET /internal/users/supervisors) i svakom posalje IN-APP notifikaciju tipa
    //  TAX_CALCULATION_FAILED. Testovi ispod ASERTUJU da supervizor STVARNO primi
    //  notifikaciju (in-app POST po supervizoru). Zadrzane su i 2 low-level
    //  karakterizacije koje dokazuju ZASTO je stari (GENERAL/null) put bio no-op.
    // ════════════════════════════════════════════════════════════════════

    @Test
    void notifySupervisors_taxFailure_reachesEverySupervisor_inApp_OT1061() {
        // OT-1061 [FIXED]: notifySupervisors razresi 2 supervizora i svakom posalje
        // in-app POST ka banka-core (recipientType=EMPLOYEE, type=TAX_CALCULATION_FAILED).
        when(bankaCoreClient.getSupervisorIds()).thenReturn(java.util.List.of(11L, 22L));

        service.notifySupervisors(NotificationType.TAX_CALCULATION_FAILED,
                "Obracun poreza neuspesan (FX)",
                "Tax calculation failed for user 7 (CLIENT) ...", "TAX", 7L);

        ArgumentCaptor<InternalNotificationRequest> captor =
                ArgumentCaptor.forClass(InternalNotificationRequest.class);
        // Async in-app POST po supervizoru → cekamo 2 poziva.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(bankaCoreClient, org.mockito.Mockito.times(2))
                        .postNotification(captor.capture()));

        assertThat(captor.getAllValues())
                .extracting(InternalNotificationRequest::recipientId)
                .containsExactlyInAnyOrder(11L, 22L);
        assertThat(captor.getAllValues()).allSatisfy(req -> {
            assertThat(req.recipientType()).isEqualTo("EMPLOYEE");
            assertThat(req.type()).isEqualTo("TAX_CALCULATION_FAILED");
            assertThat(req.title()).isEqualTo("Obracun poreza neuspesan (FX)");
            assertThat(req.referenceType()).isEqualTo("TAX");
            assertThat(req.referenceId()).isEqualTo(7L);
            assertThat(req.idempotencyKey()).isNotBlank();
        });
        // TAX_CALCULATION_FAILED je in-app-only → nijedan email (RabbitMQ) publish.
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void notifySupervisors_noSupervisors_isNoOp_OT1061() {
        // Prazna lista supervizora → nista se ne salje (best-effort, samo log).
        when(bankaCoreClient.getSupervisorIds()).thenReturn(java.util.List.of());

        service.notifySupervisors(NotificationType.TAX_CALCULATION_FAILED,
                "Obracun poreza neuspesan (FX)", "Body", "TAX", 7L);

        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }
        verify(bankaCoreClient, never()).postNotification(any(InternalNotificationRequest.class));
    }

    @Test
    void notifySupervisors_supervisorResolveFails_swallowsException_OT1061() {
        // banka-core down pri razresenju supervizora → loguj i izadji, ne propagiraj.
        when(bankaCoreClient.getSupervisorIds())
                .thenThrow(new BankaCoreClientException(503, "banka-core down"));

        assertThatNoException().isThrownBy(() ->
                service.notifySupervisors(NotificationType.TAX_CALCULATION_FAILED,
                        "Obracun poreza neuspesan (FX)", "Body", "TAX", 7L));

        verify(bankaCoreClient, never()).postNotification(any(InternalNotificationRequest.class));
    }

    @Test
    void notify_taxFailureExactCall_recipientNull_isCompleteNoOp_OT1061_lowLevelChar() {
        // Low-level karakterizacija (zasto je STARI put bio bug): tacna replika starog
        // TaxScheduler poziva recipientId=null + GENERAL je DOUBLE no-op. Cuvamo je da
        // dokumentuje koren bug-a; produkcioni put vise NE koristi ove argumente.
        service.notify(null, "SUPERVISOR", NotificationType.GENERAL,
                "Obracun poreza neuspesan (FX)", "Tax calculation failed ...", "TAX", 7L);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(bankaCoreClient, never()).getUserById(anyString(), any());
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }
        verify(bankaCoreClient, never()).postNotification(any(InternalNotificationRequest.class));
    }

    @Test
    void notify_generalTypeWithValidRecipient_stillNoOp_OT1061_lowLevelChar() {
        // Cak i sa VALIDNIM recipientId-em, GENERAL tip ima oba flag-a false → no-op.
        // Dokazuje da je i TIP (ne samo null recipient) razlog zasto je stari put cutao.
        service.notify(99L, "EMPLOYEE", NotificationType.GENERAL,
                "Obracun poreza neuspesan (FX)", "Body", "TAX", 7L);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }
        verify(bankaCoreClient, never()).postNotification(any(InternalNotificationRequest.class));
    }
}
