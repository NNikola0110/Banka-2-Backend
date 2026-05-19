package rs.raf.notification.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import rs.raf.banka2.contracts.NotificationMessage;
import rs.raf.notification.mail.MailNotificationService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Konzumira NotificationMessage sa RabbitMQ i delegira na MailNotificationService. */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final MailNotificationService mail;

    public NotificationConsumer(MailNotificationService mail) {
        this.mail = mail;
    }

    // Parametar je tipiziran (NotificationMessage) -> Jackson2JsonMessageConverter
    // deserijalizuje prema tipu metode, ignorise __TypeId__ header; setTrustedPackages
    // NE treba. (Ne menjati param na Object/Message - to menja mod deserijalizacije.)
    @RabbitListener(queues = rs.raf.banka2.contracts.NotificationRabbit.EMAIL_QUEUE)
    public void handle(NotificationMessage message) {
        Map<String, String> d = message.data();
        try {
            switch (message.kind()) {
                case PASSWORD_RESET ->
                        mail.sendPasswordResetMail(d.get("email"), d.get("token"));
                case EMPLOYEE_ACCOUNT_CREATED ->
                        mail.sendActivationMail(d.get("email"), d.get("firstName"), d.get("activationToken"));
                case EMPLOYEE_ACTIVATION_CONFIRMED ->
                        mail.sendActivationConfirmationMail(d.get("email"), d.get("firstName"));
                case ACCOUNT_CREATED ->
                        mail.sendAccountCreatedConfirmationMail(d.get("email"), d.get("firstName"),
                                d.get("accountNumber"), d.get("accountType"));
                case OTP ->
                        mail.sendOtpMail(d.get("email"), d.get("code"), Integer.parseInt(d.get("expiryMinutes")));
                case PAYMENT_CONFIRMED ->
                        mail.sendPaymentConfirmationMail(d.get("email"), new BigDecimal(d.get("amount")),
                                d.get("currency"), d.get("fromAccount"), d.get("toAccount"),
                                LocalDate.parse(d.get("date")), d.get("status"));
                case CARD_BLOCKED ->
                        mail.sendCardBlockedMail(d.get("email"), d.get("last4Digits"),
                                LocalDate.parse(d.get("blockDate")));
                case CARD_UNBLOCKED ->
                        mail.sendCardUnblockedMail(d.get("email"), d.get("last4Digits"));
                case LOAN_REQUEST_SUBMITTED ->
                        mail.sendLoanRequestSubmittedMail(d.get("email"), d.get("loanType"),
                                new BigDecimal(d.get("amount")), d.get("currency"));
                case LOAN_APPROVED ->
                        mail.sendLoanApprovedMail(d.get("email"), d.get("loanNumber"),
                                new BigDecimal(d.get("amount")), d.get("currency"),
                                new BigDecimal(d.get("monthlyPayment")), LocalDate.parse(d.get("startDate")));
                case LOAN_REJECTED ->
                        mail.sendLoanRejectedMail(d.get("email"), d.get("loanType"),
                                new BigDecimal(d.get("amount")), d.get("currency"));
                case INSTALLMENT_PAID ->
                        mail.sendInstallmentPaidMail(d.get("email"), d.get("loanNumber"),
                                new BigDecimal(d.get("installmentAmount")), d.get("currency"),
                                new BigDecimal(d.get("remainingDebt")));
                case INSTALLMENT_FAILED ->
                        mail.sendInstallmentFailedMail(d.get("email"), d.get("loanNumber"),
                                new BigDecimal(d.get("amountDue")), d.get("currency"),
                                LocalDate.parse(d.get("nextRetryDate")));
                case MARGIN_ACCOUNT_BLOCKED ->
                        mail.sendMarginAccountBlockedMail(d.get("email"), d.get("maintenanceMargin"),
                                d.get("initialMargin"), d.get("deficit"));
                case IN_APP_GENERIC -> {
                    String email = d.get("email");
                    if (email == null || email.isBlank()) {
                        log.warn("IN_APP_GENERIC poruka nema 'email' kljuc — preskacemo slanje");
                        return;
                    }
                    mail.sendInAppNotificationMail(email, d.get("firstName"), d.get("title"), d.get("body"));
                }
            }
        } catch (RuntimeException ex) {
            // Best-effort: neuspeh slanja se loguje sa stack trace-om (ex kao zadnji
            // SLF4J argument), ne rusi consumer. ex.getMessage() bi za NPE bio null.
            log.warn("Neuspeh obrade notifikacije kind={}", message.kind(), ex);
        }
    }
}
