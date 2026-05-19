package rs.raf.notification.mail;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import rs.raf.notification.mail.template.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailNotificationServiceExtendedTest {

    @Mock private JavaMailSender mailSender;
    @Mock private PasswordResetEmailTemplate passwordResetEmailTemplate;
    @Mock private ActivationEmailTemplate activationEmailTemplate;
    @Mock private ActivationConfirmedEmailTemplate activationConfirmedEmailTemplate;
    @Mock private AccountCreatedConfirmationEmailTemplate accountCreatedConfirmationEmailTemplate;
    @Mock private OtpEmailTemplate otpEmailTemplate;
    @Mock private TransactionEmailTemplate transactionEmailTemplate;
    @Mock private MarginAccountBlockedEmailTemplate marginAccountBlockedEmailTemplate;
    @Mock private MimeMessage mimeMessage;

    private MailNotificationService service;

    @BeforeEach
    void setUp() {
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        service = new MailNotificationService(mailSender, passwordResetEmailTemplate, activationEmailTemplate,
                activationConfirmedEmailTemplate, accountCreatedConfirmationEmailTemplate, otpEmailTemplate,
                transactionEmailTemplate, marginAccountBlockedEmailTemplate, "noreply@banka.rs",
                "http://localhost:3000", "/reset-password", "http://localhost:3000", "/activate-account");
    }

    @Test void sendPasswordResetMail_sends() {
        when(passwordResetEmailTemplate.buildSubject()).thenReturn("Reset");
        when(passwordResetEmailTemplate.buildBody(any())).thenReturn("<html></html>");
        service.sendPasswordResetMail("u@b.rs", "tok");
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendActivationMail_sends() {
        when(activationEmailTemplate.buildSubject()).thenReturn("Activate");
        when(activationEmailTemplate.buildBody(any(), any())).thenReturn("<html></html>");
        service.sendActivationMail("u@b.rs", "Marko", "tok");
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendActivationConfirmationMail_sends() {
        when(activationConfirmedEmailTemplate.buildSubject()).thenReturn("Confirmed");
        when(activationConfirmedEmailTemplate.buildBody(any())).thenReturn("<html></html>");
        service.sendActivationConfirmationMail("u@b.rs", "Marko");
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendAccountCreatedConfirmationMail_sends() {
        when(accountCreatedConfirmationEmailTemplate.buildSubject()).thenReturn("Account");
        when(accountCreatedConfirmationEmailTemplate.buildBody(any(), any(), any())).thenReturn("<html></html>");
        service.sendAccountCreatedConfirmationMail("u@b.rs", "Marko", "111", "CURRENT");
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendOtpMail_sends() {
        when(otpEmailTemplate.buildSubject()).thenReturn("OTP");
        when(otpEmailTemplate.buildBody(any(), anyInt())).thenReturn("<html></html>");
        service.sendOtpMail("u@b.rs", "123456", 5);
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendPaymentConfirmationMail_sends() {
        when(transactionEmailTemplate.buildPaymentSubject()).thenReturn("Payment");
        when(transactionEmailTemplate.buildPaymentBody(any(), any(), any(), any(), any(), any())).thenReturn("<html></html>");
        service.sendPaymentConfirmationMail("u@b.rs", BigDecimal.TEN, "RSD", "111", "222", LocalDate.now(), "OK");
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendCardBlockedMail_sends() {
        when(transactionEmailTemplate.buildCardBlockedSubject()).thenReturn("Blocked");
        when(transactionEmailTemplate.buildCardBlockedBody(any(), any())).thenReturn("<html></html>");
        service.sendCardBlockedMail("u@b.rs", "1234", LocalDate.now());
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendCardUnblockedMail_sends() {
        when(transactionEmailTemplate.buildCardUnblockedSubject()).thenReturn("Unblocked");
        when(transactionEmailTemplate.buildCardUnblockedBody(any())).thenReturn("<html></html>");
        service.sendCardUnblockedMail("u@b.rs", "1234");
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendLoanRequestSubmittedMail_sends() {
        when(transactionEmailTemplate.buildLoanRequestSubject()).thenReturn("Loan");
        when(transactionEmailTemplate.buildLoanRequestBody(any(), any(), any())).thenReturn("<html></html>");
        service.sendLoanRequestSubmittedMail("u@b.rs", "CASH", BigDecimal.TEN, "RSD");
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendLoanApprovedMail_sends() {
        when(transactionEmailTemplate.buildLoanApprovedSubject()).thenReturn("Approved");
        when(transactionEmailTemplate.buildLoanApprovedBody(any(), any(), any(), any(), any())).thenReturn("<html></html>");
        service.sendLoanApprovedMail("u@b.rs", "LN1", BigDecimal.TEN, "RSD", BigDecimal.ONE, LocalDate.now());
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendLoanRejectedMail_sends() {
        when(transactionEmailTemplate.buildLoanRejectedSubject()).thenReturn("Rejected");
        when(transactionEmailTemplate.buildLoanRejectedBody(any(), any(), any())).thenReturn("<html></html>");
        service.sendLoanRejectedMail("u@b.rs", "CASH", BigDecimal.TEN, "RSD");
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendInstallmentPaidMail_sends() {
        when(transactionEmailTemplate.buildInstallmentPaidSubject()).thenReturn("Paid");
        when(transactionEmailTemplate.buildInstallmentPaidBody(any(), any(), any(), any())).thenReturn("<html></html>");
        service.sendInstallmentPaidMail("u@b.rs", "LN1", BigDecimal.TEN, "RSD", BigDecimal.ONE);
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendInstallmentFailedMail_sends() {
        when(transactionEmailTemplate.buildInstallmentFailedSubject()).thenReturn("Failed");
        when(transactionEmailTemplate.buildInstallmentFailedBody(any(), any(), any(), any())).thenReturn("<html></html>");
        service.sendInstallmentFailedMail("u@b.rs", "LN1", BigDecimal.TEN, "RSD", LocalDate.now());
        verify(mailSender).send(mimeMessage);
    }

    @Test void sendMarginAccountBlockedMail_sends() {
        when(marginAccountBlockedEmailTemplate.buildSubject()).thenReturn("Margin blocked");
        when(marginAccountBlockedEmailTemplate.buildBody(any(), any(), any())).thenReturn("<html></html>");
        service.sendMarginAccountBlockedMail("u@b.rs", "5000.00", "4800.00", "200.00");
        verify(mailSender).send(mimeMessage);
    }
}
