package rs.raf.notification.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

final class HtmlMailSender {

    private HtmlMailSender() {
    }

    static void sendHtmlMail(
            JavaMailSender mailSender,
            String fromAddress,
            String toEmail,
            String subject,
            String html
    )
    {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            // [P1-notif-svc-1 / 1528 / 1742] strip CR/LF iz subject-a —
            // user-controlled title (sendInAppNotificationMail) inace omogucava
            // CRLF header injection (npr. dodatni Bcc:/X- header).
            helper.setSubject(sanitizeHeader(subject));
            helper.setText(html, true);
            // NB: mailSender.send(...) baca org.springframework.mail.MailException
            // (transient SMTP/transport/auth) — namerno NE hvatamo je ovde, da bi
            // je consumer klasifikovao kao transient (requeue + backoff retry).
            mailSender.send(mimeMessage);
        }
        catch (MessagingException e) {
            // [P1-notif-svc-1 / 1525 / 1745] MIME-construction greska (npr.
            // AddressException — neispravan primalac) je PERMANENTNA (poison) —
            // retry je nikad nece resiti. Mapiraj u PermanentMailException → DLQ,
            // NE u generic RuntimeException (koji bi se mesao sa transient slojem).
            throw new PermanentMailException("Failed to build/send HTML email (permanent)", e);
        }
    }

    /**
     * [P1-notif-svc-1 / 1528 / 1742] Uklanja CR/LF (i susedne kontrolne znake) iz
     * vrednosti email header-a (subject) da bi sprecio header/CRLF injection.
     * Null → prazan string.
     */
    private static String sanitizeHeader(String value) {
        if (value == null) {
            return "";
        }
        // Zameni svaki CR/LF (i opcione okolne razmake) jednim space-om i trim-uj.
        return value.replaceAll("[\\r\\n]+", " ").trim();
    }
}

