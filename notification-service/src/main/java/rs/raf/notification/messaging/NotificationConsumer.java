package rs.raf.notification.messaging;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.mail.MailException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import rs.raf.banka2.contracts.NotificationMessage;
import rs.raf.notification.mail.MailNotificationService;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Konzumira NotificationMessage sa RabbitMQ i delegira na MailNotificationService.
 *
 * <p><b>BE-NTF-01 (manual ack + DLQ routing):</b> consumer eksplicitno odlucuje
 * sta uraditi sa porukom na osnovu vrste greske:
 * <ul>
 *   <li><b>OK</b> — basicAck (+ {@link ProcessedMessageTracker#markProcessed} za dedup).</li>
 *   <li><b>Transient ({@link MailException} — SMTP outage/transport)</b> —
 *       basicNack(requeue=true) DOK broj pokusaja po kljucu ne dostigne
 *       {@code maxAttempts}; tada NACK(requeue=false) → DLQ. Ovo kapira
 *       busy-loop koji bi inace nastao requeue-om bez backoff-a
 *       ([P1-notif-svc-1 / 1527]).</li>
 *   <li><b>Poison ({@code PermanentMailException}, NPE, NumberFormatException,
 *       neispravan payload…)</b> — basicNack(requeue=false). Poruka ide u DLX → DLQ;
 *       ne ulazi u beskonacnu retry petlju.</li>
 * </ul>
 *
 * <p><b>Idempotencija ([P1-notif-svc-1 / 1530 / 1850]):</b> at-least-once delivery
 * znaci da consumer moze da padne IZMEDJU uspesnog {@code mailSender.send()} i
 * {@code basicAck} → redelivery → duplikat. {@link ProcessedMessageTracker} pamti
 * uspesno-poslate kljuceve (TTL) → redelivery vec-poslate poruke se ack-uje i
 * preskace (bez ponovnog slanja). Kljuc je AMQP {@code messageId} ako je postavljen,
 * inace deterministicki hash sadrzaja (kind + sortirani data).
 *
 * <p>Kanal i delivery tag su {@code @Nullable} samo iz pragmatickog razloga:
 * postojeci unit testovi pozivaju {@code consumer.handle(message)} sa jednim
 * argumentom. U produkciji ih Spring AMQP uvek injectuje (queue je vezana na
 * manual ack container factory). Kad su null, fallback je legacy swallow-and-log
 * ponasanje (dedup/retry tracking se i dalje primenjuje).
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final MailNotificationService mail;
    private final ProcessedMessageTracker tracker;
    /**
     * W2-T1: Micrometer registry za on-demand registraciju email counter-a.
     * Koristi se za {@code banka2_emails_sent_total} (no tags) i
     * {@code banka2_emails_failed_total{reason="smtp_error|dlq"}}.
     * Nullable za backward-compat unit test konstrukciju.
     */
    @Nullable
    private final MeterRegistry registry;

    /** Backward-compat constructor — koriste ga unit testovi bez Micrometer-a. */
    public NotificationConsumer(MailNotificationService mail) {
        this(mail, new ProcessedMessageTracker(), null);
    }

    /** Test-friendly constructor sa eksplicitnim tracker-om (deterministicki TTL/maxAttempts). */
    NotificationConsumer(MailNotificationService mail, ProcessedMessageTracker tracker) {
        this(mail, tracker, null);
    }

    /**
     * Primarni Spring constructor — Spring injectuje {@link MeterRegistry} i
     * {@link ProcessedMessageTracker} (oba bean-ovi).
     */
    @Autowired
    public NotificationConsumer(MailNotificationService mail,
                                ProcessedMessageTracker tracker,
                                @Nullable MeterRegistry registry) {
        this.mail = mail;
        this.tracker = tracker;
        this.registry = registry;
    }

    /**
     * Backward-compat overload bez channel/tag/headera — koriste ga unit testovi.
     * Production putanja koristi {@link #handle(NotificationMessage, Channel, Long, String, Boolean)}.
     */
    public void handle(NotificationMessage message) {
        handle(message, null, null, null, Boolean.FALSE);
    }

    @RabbitListener(queues = rs.raf.banka2.contracts.NotificationRabbit.EMAIL_QUEUE)
    public void handle(NotificationMessage message,
                       @Nullable Channel channel,
                       @Nullable @Header(value = AmqpHeaders.DELIVERY_TAG, required = false) Long deliveryTag,
                       @Nullable @Header(value = AmqpHeaders.MESSAGE_ID, required = false) String messageId,
                       @Nullable @Header(value = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered) {
        Map<String, String> d = message.data();
        String dedupKey = resolveDedupKey(message, messageId);

        // [P1-notif-svc-1 / 1530 / 1850] dedup: ako je poruka vec uspesno poslata
        // (redelivery posle send-OK-ack-fail), ack-uj i preskoci ponovno slanje.
        if (tracker.isProcessed(dedupKey)) {
            log.info("Duplikat (vec poslat) kind={} key={} — ack + skip (dedup)", message.kind(), dedupKey);
            ackOrSkip(channel, deliveryTag);
            return;
        }

        try {
            boolean emailSent = dispatch(message, d);
            tracker.markProcessed(dedupKey);
            // W2-T1 + P2-notif-reliability-2 (R4 1793): brojaj SAMO stvarno poslate
            // email-ove. IN_APP_GENERIC bez 'email' kljuca se uspesno obradi (ack),
            // ali NIJE poslat email → counter se NE inkrementuje (inace bi
            // banka2_emails_sent_total lagao).
            if (emailSent) {
                incrementCounter("banka2_emails_sent_total");
            }
            ackOrSkip(channel, deliveryTag);
        } catch (MailException ex) {
            // Transient SMTP outage. Bez backoff-a, requeue=true bi vrteo busy-loop;
            // brojimo pokusaje i posle maxAttempts saljemo u DLQ (requeue=false).
            boolean exhausted = tracker.recordFailureAndCheckExhausted(dedupKey);
            if (exhausted) {
                log.error("Transient mail failure ISCRPEO {} pokusaja za kind={} key={} — saljem u DLQ",
                        tracker.getMaxAttempts(), message.kind(), dedupKey, ex);
                incrementFailureCounter("dlq");
                tracker.clear(dedupKey);
                nackOrSkip(channel, deliveryTag, false);
            } else {
                log.warn("Transient mail failure za kind={} key={} — requeue (pokusaj < {})",
                        message.kind(), dedupKey, tracker.getMaxAttempts(), ex);
                incrementFailureCounter("smtp_error");
                nackOrSkip(channel, deliveryTag, true);
            }
        } catch (RuntimeException ex) {
            // Poison payload (PermanentMailException, NPE, NumberFormatException,
            // IllegalArgumentException…) — re-deliveries ce uvek pucati. DLQ (requeue=false).
            log.error("Poison message za kind={} key={} — saljem u DLQ (requeue=false)",
                    message.kind(), dedupKey, ex);
            incrementFailureCounter("dlq");
            tracker.clear(dedupKey);
            nackOrSkip(channel, deliveryTag, false);
        }
    }

    /**
     * [P1-notif-svc-1 / 1530] Stabilan dedup kljuc: AMQP messageId ako postoji,
     * inace deterministicki sadrzajni kljuc (kind + sortirani data). Sadrzajni
     * fallback dedup-uje samo TACAN duplikat iste poruke (sva polja ista).
     */
    private String resolveDedupKey(NotificationMessage message, @Nullable String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            return "mid:" + messageId;
        }
        // Deterministicki redosled kljuceva → stabilan hash nezavisno od map ordering-a.
        Map<String, String> sorted = new TreeMap<>(message.data());
        return "content:" + message.kind() + ":" + sorted;
    }

    /** W2-T1: bezbedan inkrement — preskace se ako registry nije configured (unit test path). */
    private void incrementCounter(String name) {
        if (registry == null) {
            return;
        }
        try {
            registry.counter(name).increment();
        } catch (RuntimeException ex) {
            log.warn("Failed to increment counter {}: {}", name, ex.getMessage());
        }
    }

    private void incrementFailureCounter(String reason) {
        if (registry == null) {
            return;
        }
        try {
            registry.counter("banka2_emails_failed_total", "reason", reason).increment();
        } catch (RuntimeException ex) {
            log.warn("Failed to increment failure counter (reason={}): {}", reason, ex.getMessage());
        }
    }

    /**
     * Rutira poruku na odgovarajuci mail metod.
     *
     * @return {@code true} ako je email stvarno poslat; {@code false} ako je poruka
     *         legitimno obradjena ali email NIJE poslat (npr. IN_APP_GENERIC bez
     *         'email' kljuca) — tada se {@code banka2_emails_sent_total} NE
     *         inkrementuje (R4 1793).
     * @throws PoisonMessageException za nepoznat {@link NotificationKind}
     *         (R3 1589) — buduci/nepodrzan kind ide u DLQ umesto tihog ACK-a sa
     *         laznim sent-counter-om.
     */
    private boolean dispatch(NotificationMessage message, Map<String, String> d) {
        switch (message.kind()) {
                case PASSWORD_RESET ->
                        mail.sendPasswordResetMail(require(d, "email"), require(d, "token"));
                case EMPLOYEE_ACCOUNT_CREATED ->
                        mail.sendActivationMail(require(d, "email"), d.get("firstName"), require(d, "activationToken"));
                case EMPLOYEE_ACTIVATION_CONFIRMED ->
                        mail.sendActivationConfirmationMail(require(d, "email"), d.get("firstName"));
                case ACCOUNT_CREATED ->
                        mail.sendAccountCreatedConfirmationMail(require(d, "email"), d.get("firstName"),
                                d.get("accountNumber"), d.get("accountType"));
                case OTP ->
                        mail.sendOtpMail(require(d, "email"), require(d, "code"), requireInt(d, "expiryMinutes"));
                case PAYMENT_CONFIRMED ->
                        mail.sendPaymentConfirmationMail(require(d, "email"), requireDecimal(d, "amount"),
                                d.get("currency"), d.get("fromAccount"), d.get("toAccount"),
                                requireDate(d, "date"), d.get("status"));
                case CARD_BLOCKED ->
                        mail.sendCardBlockedMail(require(d, "email"), d.get("last4Digits"),
                                requireDate(d, "blockDate"));
                case CARD_UNBLOCKED ->
                        mail.sendCardUnblockedMail(require(d, "email"), d.get("last4Digits"));
                case LOAN_REQUEST_SUBMITTED ->
                        mail.sendLoanRequestSubmittedMail(require(d, "email"), d.get("loanType"),
                                requireDecimal(d, "amount"), d.get("currency"));
                case LOAN_APPROVED ->
                        mail.sendLoanApprovedMail(require(d, "email"), d.get("loanNumber"),
                                requireDecimal(d, "amount"), d.get("currency"),
                                requireDecimal(d, "monthlyPayment"), requireDate(d, "startDate"));
                case LOAN_REJECTED ->
                        mail.sendLoanRejectedMail(require(d, "email"), d.get("loanType"),
                                requireDecimal(d, "amount"), d.get("currency"));
                case INSTALLMENT_PAID ->
                        mail.sendInstallmentPaidMail(require(d, "email"), d.get("loanNumber"),
                                requireDecimal(d, "installmentAmount"), d.get("currency"),
                                requireDecimal(d, "remainingDebt"));
                case INSTALLMENT_FAILED ->
                        mail.sendInstallmentFailedMail(require(d, "email"), d.get("loanNumber"),
                                requireDecimal(d, "amountDue"), d.get("currency"),
                                requireDate(d, "nextRetryDate"));
                case MARGIN_ACCOUNT_BLOCKED ->
                        mail.sendMarginAccountBlockedMail(require(d, "email"), d.get("maintenanceMargin"),
                                d.get("initialMargin"), d.get("deficit"));
                case ACCOUNT_LOCKED ->
                        mail.sendAccountLockedMail(require(d, "email"), requireInt(d, "lockMinutes"));
            case IN_APP_GENERIC -> {
                String email = d.get("email");
                if (email == null || email.isBlank()) {
                    log.warn("IN_APP_GENERIC poruka nema 'email' kljuc — preskacemo slanje");
                    // R4 1793: legitimno obradjeno (ack), ali email NIJE poslat.
                    return false;
                }
                mail.sendInAppNotificationMail(email, d.get("firstName"), d.get("title"), d.get("body"));
            }
            // R3 1589: nepoznat/nepodrzan kind (npr. dodat u contracts a ovde jos
            // ne mapiran) NE sme da se tiho ack-uje sa laznim emails_sent — poison → DLQ.
            default ->
                    throw new PoisonMessageException("Nepodrzan NotificationKind: " + message.kind());
        }
        return true;
    }

    // ── [P1-notif-svc-1 / 1531] Strict parse guards ────────────────────────
    // Missing/malformed obavezni kljuc → PoisonMessageException (jasna poison
    // klasifikacija → DLQ), umesto sirovog NPE/NFE/DateTimeParseException.

    private static String require(Map<String, String> d, String key) {
        String v = d.get(key);
        if (v == null || v.isBlank()) {
            throw new PoisonMessageException("Nedostaje obavezan kljuc '" + key + "'");
        }
        return v;
    }

    private static int requireInt(Map<String, String> d, String key) {
        String v = require(d, key);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            throw new PoisonMessageException("Kljuc '" + key + "' nije ceo broj: '" + v + "'", ex);
        }
    }

    private static BigDecimal requireDecimal(Map<String, String> d, String key) {
        String v = require(d, key);
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException ex) {
            throw new PoisonMessageException("Kljuc '" + key + "' nije decimalan broj: '" + v + "'", ex);
        }
    }

    private static LocalDate requireDate(Map<String, String> d, String key) {
        String v = require(d, key);
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException ex) {
            throw new PoisonMessageException("Kljuc '" + key + "' nije ISO datum: '" + v + "'", ex);
        }
    }

    /** Ack ako su channel/tag dostupni (production). U unit testovima oba su null pa se preskace. */
    private void ackOrSkip(@Nullable Channel channel, @Nullable Long deliveryTag) {
        if (channel == null || deliveryTag == null) {
            return;
        }
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException io) {
            log.warn("basicAck failed za tag={}", deliveryTag, io);
        }
    }

    /** Nack sa eksplicitnim requeue flag-om. {@code requeue=true} → glavni queue;
     *  {@code requeue=false} → DLX (kako je konfigurisan u {@link RabbitConfig}). */
    private void nackOrSkip(@Nullable Channel channel, @Nullable Long deliveryTag, boolean requeue) {
        if (channel == null || deliveryTag == null) {
            return;
        }
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException io) {
            log.warn("basicNack failed za tag={} requeue={}", deliveryTag, requeue, io);
        }
    }
}
