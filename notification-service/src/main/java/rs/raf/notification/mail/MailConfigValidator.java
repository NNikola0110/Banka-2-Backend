package rs.raf.notification.mail;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * P2-config-2 (R3 1596): startup validacija mail konfiguracije.
 *
 * <p>Problem: {@code spring.mail.properties.mail.smtp.auth=true} +
 * {@code spring.mail.password=${MAIL_PASSWORD:}} (prazan default) → svaki slanje
 * email-a baca {@code MailAuthenticationException}. Consumer to klasifikuje kao
 * transient (requeue + backoff), pa SVAKA notifikacija prodje kroz maxAttempts
 * retry-a pre nego sto zavrsi u DLQ — tiho, bez ijednog uspesnog email-a.
 *
 * <p>Resenje (fail-fast vs. dev-default asimetrija, paritet sa
 * {@code POSTGRES_PASSWORD :?required}):
 * <ul>
 *   <li><b>prod</b> ({@code notification.mail.fail-fast-on-missing-password=true},
 *       postavlja se u K8s/Infra) — ako je {@code smtp.auth=true} a password prazan,
 *       aplikacija PUKNE pri startu (jasna greska umesto tihog DLQ-busy-loop-a).</li>
 *   <li><b>dev/test</b> (default {@code false}) — samo glasan WARN; lokalni
 *       docker-compose run nema realne SMTP kredencijale i ne sme da pukne.</li>
 * </ul>
 *
 * <p>NB: ne diramo samu vrednost {@code MAIL_PASSWORD} (Luka rotira tajne) — samo
 * proveravamo da je postavljena kad se mail auth zahteva.
 */
@Component
public class MailConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(MailConfigValidator.class);

    private final boolean smtpAuth;
    private final String mailPassword;
    private final boolean failFastOnMissingPassword;

    public MailConfigValidator(
            @Value("${spring.mail.properties.mail.smtp.auth:false}") boolean smtpAuth,
            @Value("${spring.mail.password:}") String mailPassword,
            @Value("${notification.mail.fail-fast-on-missing-password:false}") boolean failFastOnMissingPassword) {
        this.smtpAuth = smtpAuth;
        this.mailPassword = mailPassword;
        this.failFastOnMissingPassword = failFastOnMissingPassword;
    }

    @PostConstruct
    void validate() {
        boolean missingPassword = mailPassword == null || mailPassword.isBlank();
        if (smtpAuth && missingPassword) {
            String msg = "spring.mail.properties.mail.smtp.auth=true ali MAIL_PASSWORD nije postavljen — "
                    + "SMTP autentikacija ce padati na SVAKI email (MailAuthenticationException → retry → DLQ), "
                    + "nijedna notifikacija nece biti poslata. Postavi MAIL_PASSWORD (Secret 'mail-credentials').";
            if (failFastOnMissingPassword) {
                throw new IllegalStateException(msg);
            }
            log.warn(msg + " (dev mode: nastavljam bez fail-fast — postavi "
                    + "notification.mail.fail-fast-on-missing-password=true u prod-u)");
        }
    }
}
