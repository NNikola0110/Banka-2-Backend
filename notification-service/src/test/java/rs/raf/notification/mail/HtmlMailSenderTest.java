package rs.raf.notification.mail;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtmlMailSenderTest {

    private MimeMessage newMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    void sendHtmlMail_sendsMimeMessage() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage msg = newMimeMessage();
        when(sender.createMimeMessage()).thenReturn(msg);

        HtmlMailSender.sendHtmlMail(sender, "from@test.com", "to@test.com", "Subject", "<h1>Hi</h1>");

        verify(sender).send(msg);
    }

    @Test
    void sendHtmlMail_wrapsMessagingExceptionAsPermanent() {
        JavaMailSender sender = mock(JavaMailSender.class);
        // Custom MimeMessage that throws MessagingException on setFrom
        MimeMessage throwingMsg = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            public void setFrom(Address address) throws MessagingException {
                throw new MessagingException("forced");
            }
        };
        when(sender.createMimeMessage()).thenReturn(throwingMsg);

        // [P1-notif-svc-1 / 1525 / 1745] MIME-construction greska je PERMANENTNA
        // (poison) — mapira se u PermanentMailException (→ DLQ), ne generic RuntimeException.
        assertThatThrownBy(() -> HtmlMailSender.sendHtmlMail(sender, "from@test.com", "to@test.com", "Subject", "<h1>Hi</h1>"))
                .isInstanceOf(PermanentMailException.class)
                .hasMessageContaining("permanent");
    }

    @Test
    void sendHtmlMail_propagatesMailExceptionAsTransient() {
        // mailSender.send(...) baca Spring MailException (transient SMTP outage) —
        // mora da propagira NETAKNUTO da bi je consumer klasifikovao kao transient
        // (requeue + backoff), NE wrap-ovati u PermanentMailException.
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage msg = newMimeMessage();
        when(sender.createMimeMessage()).thenReturn(msg);
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(sender).send(msg);

        assertThatThrownBy(() -> HtmlMailSender.sendHtmlMail(sender, "from@test.com", "to@test.com", "Subject", "<h1>Hi</h1>"))
                .isInstanceOf(org.springframework.mail.MailException.class)
                .isNotInstanceOf(PermanentMailException.class);
    }

    @Test
    void sendHtmlMail_stripsCrlfFromSubject() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage msg = newMimeMessage();
        when(sender.createMimeMessage()).thenReturn(msg);

        HtmlMailSender.sendHtmlMail(sender, "from@test.com", "to@test.com",
                "Naslov\r\nBcc: attacker@evil.com", "<h1>Hi</h1>");

        // Subject je sanitizovan (bez CR/LF) → MimeMessage drzi jedan-linijski subject
        String subject = msg.getSubject();
        assertThat(subject).doesNotContain("\r");
        assertThat(subject).doesNotContain("\n");
    }
}
