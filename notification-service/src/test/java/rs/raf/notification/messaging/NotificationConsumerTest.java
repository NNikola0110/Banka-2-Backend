package rs.raf.notification.messaging;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import rs.raf.banka2.contracts.NotificationKind;
import rs.raf.banka2.contracts.NotificationMessage;
import rs.raf.notification.mail.MailNotificationService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.mockito.Mockito.verify;

class NotificationConsumerTest {

    private final MailNotificationService mail = Mockito.mock(MailNotificationService.class);
    private final NotificationConsumer consumer = new NotificationConsumer(mail);

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
}
