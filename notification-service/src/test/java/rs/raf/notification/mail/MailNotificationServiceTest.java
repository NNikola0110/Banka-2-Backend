package rs.raf.notification.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import rs.raf.notification.mail.template.AccountCreatedConfirmationEmailTemplate;
import rs.raf.notification.mail.template.ActivationConfirmedEmailTemplate;
import rs.raf.notification.mail.template.ActivationEmailTemplate;
import rs.raf.notification.mail.template.MarginAccountBlockedEmailTemplate;
import rs.raf.notification.mail.template.OtpEmailTemplate;
import rs.raf.notification.mail.template.PasswordResetEmailTemplate;
import rs.raf.notification.mail.template.TransactionEmailTemplate;

import java.util.Properties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailNotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;
    @Mock
    private PasswordResetEmailTemplate passwordResetTemplate;
    @Mock
    private ActivationEmailTemplate activationTemplate;
    @Mock
    private ActivationConfirmedEmailTemplate activationConfirmedTemplate;
    @Mock
    private AccountCreatedConfirmationEmailTemplate accountCreatedConfirmationEmailTemplate;
    @Mock
    private OtpEmailTemplate otpEmailTemplate;
    @Mock
    private TransactionEmailTemplate transactionEmailTemplate;
    @Mock
    private MarginAccountBlockedEmailTemplate marginAccountBlockedEmailTemplate;

    private MailNotificationService service;


    @BeforeEach
    void setUp() {
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        service = new MailNotificationService(
                mailSender,
                passwordResetTemplate,
                activationTemplate,
                activationConfirmedTemplate,
                accountCreatedConfirmationEmailTemplate,
                otpEmailTemplate,
                transactionEmailTemplate,
                marginAccountBlockedEmailTemplate,
                "from@test.com",
                "http://localhost:3000",
                "/reset-password",
                "http://localhost:3000",
                "/activate-account"
        );
    }

    @Test
    void sendPasswordResetMail_buildsCorrectLink() {
        when(passwordResetTemplate.buildSubject()).thenReturn("Reset");
        when(passwordResetTemplate.buildBody(anyString())).thenReturn("<html/>");

        service.sendPasswordResetMail("user@test.com", "abc123");

        verify(passwordResetTemplate).buildBody("http://localhost:3000/reset-password?token=abc123");
    }

    @Test
    void sendPasswordResetMail_sendsEmail() {
        when(passwordResetTemplate.buildSubject()).thenReturn("Reset");
        when(passwordResetTemplate.buildBody(anyString())).thenReturn("<html/>");

        service.sendPasswordResetMail("user@test.com", "abc123");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendActivationMail_buildsCorrectLink() {
        when(activationTemplate.buildSubject()).thenReturn("Activate");
        when(activationTemplate.buildBody(anyString(), anyString())).thenReturn("<html/>");

        service.sendActivationMail("emp@test.com", "Jovan", "token456");

        verify(activationTemplate).buildBody("http://localhost:3000/activate-account?token=token456", "Jovan");
    }

    @Test
    void sendActivationMail_sendsEmail() {
        when(activationTemplate.buildSubject()).thenReturn("Activate");
        when(activationTemplate.buildBody(anyString(), anyString())).thenReturn("<html/>");

        service.sendActivationMail("emp@test.com", "Jovan", "token456");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendActivationConfirmationMail_callsTemplateWithFirstName() {
        when(activationConfirmedTemplate.buildSubject()).thenReturn("Confirmed");
        when(activationConfirmedTemplate.buildBody(anyString())).thenReturn("<html/>");

        service.sendActivationConfirmationMail("ana@test.com", "Ana");

        verify(activationConfirmedTemplate).buildBody("Ana");
    }

    @Test
    void sendActivationConfirmationMail_sendsEmail() {
        when(activationConfirmedTemplate.buildSubject()).thenReturn("Confirmed");
        when(activationConfirmedTemplate.buildBody(anyString())).thenReturn("<html/>");

        service.sendActivationConfirmationMail("ana@test.com", "Ana");

        verify(mailSender).send(any(MimeMessage.class));
    }
}
