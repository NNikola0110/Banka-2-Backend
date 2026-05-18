package rs.raf.notification.mail;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

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
    void sendHtmlMail_wrapsMessagingException() {
        JavaMailSender sender = mock(JavaMailSender.class);
        // Custom MimeMessage that throws MessagingException on setFrom
        MimeMessage throwingMsg = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            public void setFrom(Address address) throws MessagingException {
                throw new MessagingException("forced");
            }
        };
        when(sender.createMimeMessage()).thenReturn(throwingMsg);

        assertThatThrownBy(() -> HtmlMailSender.sendHtmlMail(sender, "from@test.com", "to@test.com", "Subject", "<h1>Hi</h1>"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to send HTML email");
    }
}
