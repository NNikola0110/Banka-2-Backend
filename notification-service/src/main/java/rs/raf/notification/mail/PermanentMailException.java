package rs.raf.notification.mail;

/**
 * [P1-notif-svc-1 / 1525 / 1745] Oznacava PERMANENTNU (poison) gresku slanja
 * email-a — poruka se NIKAD nece uspesno poslati bez izmene sadrzaja
 * (npr. neispravna email adresa / malformiran MIME).
 *
 * <p>Razlikovanje od tranzijentnih gresaka je kljucno za routing:
 * <ul>
 *   <li>{@code PermanentMailException} (poison) → consumer NACK(requeue=false) → DLQ
 *       (ne ulazi u beskonacnu retry petlju).</li>
 *   <li>{@code org.springframework.mail.MailException} (transient — SMTP outage /
 *       transport) → consumer NACK(requeue=true) uz backoff retry.</li>
 * </ul>
 *
 * <p>Ranije je {@code HtmlMailSender} sve {@code jakarta.mail.MessagingException}
 * wrap-ovao u plain {@code RuntimeException} → consumer ih je tretirao kao poison
 * (DLQ), pa su i tranzijentne MIME/transport greske gubile notifikaciju bez retry-a
 * (invertovana klasifikacija).
 */
public class PermanentMailException extends RuntimeException {

    public PermanentMailException(String message, Throwable cause) {
        super(message, cause);
    }
}
