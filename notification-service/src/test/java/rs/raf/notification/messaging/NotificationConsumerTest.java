package rs.raf.notification.messaging;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mail.MailSendException;
import rs.raf.banka2.contracts.NotificationKind;
import rs.raf.banka2.contracts.NotificationMessage;
import rs.raf.notification.mail.MailNotificationService;
import rs.raf.notification.mail.PermanentMailException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class NotificationConsumerTest {

    private final MailNotificationService mail = Mockito.mock(MailNotificationService.class);
    private final NotificationConsumer consumer = new NotificationConsumer(mail);

    private NotificationMessage otp(String code) {
        return new NotificationMessage(NotificationKind.OTP,
                Map.of("email", "a@b.rs", "code", code, "expiryMinutes", "5"));
    }

    @Test
    void passwordReset_delegatesToMail() {
        consumer.handle(new NotificationMessage(NotificationKind.PASSWORD_RESET,
                Map.of("email", "a@b.rs", "token", "tok123")));
        verify(mail).sendPasswordResetMail("a@b.rs", "tok123");
    }

    @Test
    void otp_parsesExpiryMinutes() {
        consumer.handle(new NotificationMessage(NotificationKind.OTP,
                Map.of("email", "a@b.rs", "code", "654321", "expiryMinutes", "5")));
        verify(mail).sendOtpMail("a@b.rs", "654321", 5);
    }

    @Test
    void paymentConfirmed_parsesAmountAndDate() {
        consumer.handle(new NotificationMessage(NotificationKind.PAYMENT_CONFIRMED,
                Map.of("email", "a@b.rs", "amount", "1500.50", "currency", "RSD",
                        "fromAccount", "111", "toAccount", "222",
                        "date", "2026-05-18", "status", "COMPLETED")));
        verify(mail).sendPaymentConfirmationMail("a@b.rs", new BigDecimal("1500.50"), "RSD",
                "111", "222", LocalDate.parse("2026-05-18"), "COMPLETED");
    }

    @Test
    void marginAccountBlocked_delegatesToMail() {
        consumer.handle(new NotificationMessage(NotificationKind.MARGIN_ACCOUNT_BLOCKED,
                Map.of("email", "a@b.rs", "maintenanceMargin", "5000.00",
                        "initialMargin", "4800.00", "deficit", "200.00")));
        verify(mail).sendMarginAccountBlockedMail("a@b.rs", "5000.00", "4800.00", "200.00");
    }

    @Test
    void mailFailure_doesNotPropagate() {
        Mockito.doThrow(new RuntimeException("smtp down"))
                .when(mail).sendCardUnblockedMail(Mockito.anyString(), Mockito.anyString());
        // Ne sme da baci - consumer hvata RuntimeException.
        consumer.handle(new NotificationMessage(NotificationKind.CARD_UNBLOCKED,
                Map.of("email", "a@b.rs", "last4Digits", "1234")));
    }

    @Test
    void inAppGeneric_delegatesToMail() {
        consumer.handle(new NotificationMessage(NotificationKind.IN_APP_GENERIC,
                Map.of("email", "a@b.rs", "firstName", "Ana",
                        "title", "Obaveštenje", "body", "Vaš nalog je ažuriran.")));
        verify(mail).sendInAppNotificationMail("a@b.rs", "Ana", "Obaveštenje", "Vaš nalog je ažuriran.");
    }

    @Test
    void inAppGeneric_missingEmail_doesNotDelegate() {
        consumer.handle(new NotificationMessage(NotificationKind.IN_APP_GENERIC,
                Map.of("title", "Naslov", "body", "Sadrzaj")));
        Mockito.verifyNoInteractions(mail);
    }

    // ── [P1-notif-svc-1 / 1530 / 1850] dedup pri redelivery-ju ─────────────

    @Test
    void duplicateContent_secondDeliveryIsAckedAndNotResent() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker());

        NotificationMessage msg = otp("111222");
        // Prva dostava: posalji + ack(tag=1)
        c.handle(msg, channel, 1L, null, Boolean.FALSE);
        // Druga dostava ISTE poruke (redelivery posle send-OK-ack-fail): tag=2
        c.handle(msg, channel, 2L, null, Boolean.TRUE);

        // Mejl poslat TACNO jednom (dedup)
        verify(m, times(1)).sendOtpMail("a@b.rs", "111222", 5);
        // Oba puta ack-ovano (druga je ack+skip)
        verify(channel).basicAck(eq(1L), Mockito.anyBoolean());
        verify(channel).basicAck(eq(2L), Mockito.anyBoolean());
    }

    @Test
    void distinctMessages_bothSent() {
        MailNotificationService m = mock(MailNotificationService.class);
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker());
        c.handle(otp("111"));
        c.handle(otp("222"));
        verify(m).sendOtpMail("a@b.rs", "111", 5);
        verify(m).sendOtpMail("a@b.rs", "222", 5);
    }

    @Test
    void messageIdHeader_usedForDedup() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker());

        // Dve poruke sa razlicitim sadrzajem ali ISTIM messageId → dedup po messageId
        c.handle(otp("aaa"), channel, 1L, "mid-1", Boolean.FALSE);
        c.handle(otp("bbb"), channel, 2L, "mid-1", Boolean.TRUE);

        verify(m, times(1)).sendOtpMail(anyString(), anyString(), anyInt());
    }

    // ── [P1-notif-svc-1 / 1527] transient backoff cap → DLQ ────────────────

    @Test
    void transientFailure_requeuesWhileUnderMaxThenDlqWhenExhausted() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        // maxAttempts=3, dug TTL
        ProcessedMessageTracker tracker = new ProcessedMessageTracker(60_000, 10_000, 3);
        NotificationConsumer c = new NotificationConsumer(m, tracker);

        Mockito.doThrow(new MailSendException("smtp down"))
                .when(m).sendOtpMail(anyString(), anyString(), anyInt());

        NotificationMessage msg = otp("999");
        c.handle(msg, channel, 1L, null, Boolean.FALSE); // pokusaj 1 → requeue
        c.handle(msg, channel, 2L, null, Boolean.TRUE);  // pokusaj 2 → requeue
        c.handle(msg, channel, 3L, null, Boolean.TRUE);  // pokusaj 3 == max → DLQ

        // 2x requeue=true, pa 1x requeue=false (DLQ)
        verify(channel, times(2)).basicNack(anyLong(), Mockito.anyBoolean(), eq(true));
        verify(channel, times(1)).basicNack(anyLong(), Mockito.anyBoolean(), eq(false));
        verify(channel, never()).basicAck(anyLong(), Mockito.anyBoolean());
    }

    // ── [P1-notif-svc-1 / 1525 / 1745] permanent (poison) → DLQ ────────────

    @Test
    void permanentMailException_goesToDlqNotRequeue() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker());

        Mockito.doThrow(new PermanentMailException("bad address", new RuntimeException()))
                .when(m).sendOtpMail(anyString(), anyString(), anyInt());

        c.handle(otp("123"), channel, 1L, null, Boolean.FALSE);

        // Poison → nack requeue=false (DLQ), nikad requeue=true
        verify(channel).basicNack(eq(1L), Mockito.anyBoolean(), eq(false));
        verify(channel, never()).basicNack(anyLong(), Mockito.anyBoolean(), eq(true));
    }

    // ── [P1-notif-svc-1 / 1531] strict parse guards ────────────────────────

    @Test
    void otpMissingExpiryMinutes_isPoison_goesToDlq() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker());

        // Nedostaje expiryMinutes → PoisonMessageException pre slanja
        c.handle(new NotificationMessage(NotificationKind.OTP,
                        Map.of("email", "a@b.rs", "code", "654321")),
                channel, 1L, null, Boolean.FALSE);

        verify(m, never()).sendOtpMail(anyString(), anyString(), anyInt());
        verify(channel).basicNack(eq(1L), Mockito.anyBoolean(), eq(false)); // DLQ
    }

    @Test
    void otpMalformedExpiryMinutes_isPoison_goesToDlq() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker());

        c.handle(new NotificationMessage(NotificationKind.OTP,
                        Map.of("email", "a@b.rs", "code", "654321", "expiryMinutes", "abc")),
                channel, 1L, null, Boolean.FALSE);

        verify(m, never()).sendOtpMail(anyString(), anyString(), anyInt());
        verify(channel).basicNack(eq(1L), Mockito.anyBoolean(), eq(false));
    }

    @Test
    void paymentMalformedAmount_isPoison_goesToDlq() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker());

        c.handle(new NotificationMessage(NotificationKind.PAYMENT_CONFIRMED,
                        Map.of("email", "a@b.rs", "amount", "not-a-number", "currency", "RSD",
                                "fromAccount", "111", "toAccount", "222",
                                "date", "2026-05-18", "status", "OK")),
                channel, 1L, null, Boolean.FALSE);

        verify(m, never()).sendPaymentConfirmationMail(anyString(), Mockito.any(), anyString(),
                anyString(), anyString(), Mockito.any(), anyString());
        verify(channel).basicNack(eq(1L), Mockito.anyBoolean(), eq(false));
    }

    @Test
    void successfulSend_isAcked() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker());

        c.handle(otp("777"), channel, 9L, null, Boolean.FALSE);

        verify(m).sendOtpMail("a@b.rs", "777", 5);
        verify(channel).basicAck(eq(9L), Mockito.anyBoolean());
    }

    // ── P2-notif-reliability-2 (R4 1793): IN_APP_GENERIC bez email NE inkrementuje sent-counter ──

    @Test
    void inAppGenericMissingEmail_doesNotIncrementSentCounter() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker(), registry);

        // IN_APP_GENERIC bez 'email' kljuca → legitimno obradjeno (ack), ali email NIJE
        // poslat → banka2_emails_sent_total NE sme da raste (inace counter laze).
        c.handle(new NotificationMessage(NotificationKind.IN_APP_GENERIC,
                        Map.of("title", "Naslov", "body", "Telo")),
                channel, 1L, null, Boolean.FALSE);

        verify(channel).basicAck(eq(1L), Mockito.anyBoolean()); // ack (ne DLQ)
        assertThat(registry.find("banka2_emails_sent_total").counter()).isNull();
    }

    @Test
    void successfulInAppGeneric_incrementsSentCounter() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker(), registry);

        c.handle(new NotificationMessage(NotificationKind.IN_APP_GENERIC,
                        Map.of("email", "a@b.rs", "title", "Naslov", "body", "Telo")),
                channel, 1L, null, Boolean.FALSE);

        verify(m).sendInAppNotificationMail("a@b.rs", null, "Naslov", "Telo");
        assertThat(registry.find("banka2_emails_sent_total").counter()).isNotNull();
        assertThat(registry.find("banka2_emails_sent_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void normalSend_incrementsSentCounter() throws Exception {
        MailNotificationService m = mock(MailNotificationService.class);
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker(), registry);

        c.handle(otp("555"), channel, 1L, null, Boolean.FALSE);

        assertThat(registry.find("banka2_emails_sent_total").counter().count()).isEqualTo(1.0);
    }

    // ── P2-notif-reliability-2 (R3 1589): dispatch switch je EXHAUSTIVAN ──

    /**
     * Pin R3 1589: svaki {@link NotificationKind} mora biti mapiran u dispatch
     * switch-u. Kad bi neki kind pao na {@code default}, dispatch baca
     * {@code PoisonMessageException} → consumer ga NACK-uje u DLQ (requeue=false)
     * i NE inkrementuje {@code banka2_emails_sent_total}. Ovaj test feeduje pun
     * superset svih data kljuceva, pa nijedan mapiran kind ne sme da zavrsi u DLQ
     * (sve bi uspesno ack-ovao). Ako se doda nov kind u contracts bez mapiranja
     * ovde, on bi pao u default → DLQ → test pada.
     */
    @Test
    void everyNotificationKind_isMapped_noDefaultFallthrough() throws Exception {
        for (NotificationKind kind : NotificationKind.values()) {
            MailNotificationService m = mock(MailNotificationService.class);
            Channel channel = mock(Channel.class);
            NotificationConsumer c = new NotificationConsumer(m, new ProcessedMessageTracker());

            c.handle(new NotificationMessage(kind, fullDataPayload()), channel, 1L, null, Boolean.FALSE);

            // Mapiran kind → ack (uspeh). Default (nepodrzan) bi bio NACK→DLQ.
            verify(channel, never().description("kind " + kind + " ne sme da padne u default/DLQ"))
                    .basicNack(anyLong(), Mockito.anyBoolean(), eq(false));
            verify(channel).basicAck(eq(1L), Mockito.anyBoolean());
        }
    }

    /** Superset svih data kljuceva koje bilo koji kind moze da zahteva (require). */
    private static Map<String, String> fullDataPayload() {
        return Map.ofEntries(
                Map.entry("email", "a@b.rs"),
                Map.entry("token", "tok"),
                Map.entry("firstName", "Ana"),
                Map.entry("activationToken", "act"),
                Map.entry("accountNumber", "111"),
                Map.entry("accountType", "TEKUCI"),
                Map.entry("code", "654321"),
                Map.entry("expiryMinutes", "5"),
                Map.entry("amount", "1500.50"),
                Map.entry("currency", "RSD"),
                Map.entry("fromAccount", "111"),
                Map.entry("toAccount", "222"),
                Map.entry("date", "2026-05-18"),
                Map.entry("status", "COMPLETED"),
                Map.entry("last4Digits", "1234"),
                Map.entry("blockDate", "2026-05-18"),
                Map.entry("loanType", "CASH"),
                Map.entry("loanNumber", "L-001"),
                Map.entry("monthlyPayment", "100.00"),
                Map.entry("startDate", "2026-05-18"),
                Map.entry("installmentAmount", "50.00"),
                Map.entry("remainingDebt", "500.00"),
                Map.entry("amountDue", "50.00"),
                Map.entry("nextRetryDate", "2026-05-21"),
                Map.entry("maintenanceMargin", "5000.00"),
                Map.entry("initialMargin", "4800.00"),
                Map.entry("deficit", "200.00"),
                Map.entry("lockMinutes", "30"),
                Map.entry("title", "Naslov"),
                Map.entry("body", "Telo"));
    }
}
