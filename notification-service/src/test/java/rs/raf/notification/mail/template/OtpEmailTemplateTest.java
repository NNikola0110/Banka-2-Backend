package rs.raf.notification.mail.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtpEmailTemplateTest {

    private OtpEmailTemplate template;

    @BeforeEach
    void setUp() {
        template = new OtpEmailTemplate();
    }

    @Test
    void buildSubject_returnsNonEmptyString() {
        assertThat(template.buildSubject()).isNotBlank();
    }

    @Test
    void buildSubject_containsBanka2() {
        assertThat(template.buildSubject()).contains("Banka 2");
    }

    @Test
    void buildSubject_containsVerifikacioni() {
        assertThat(template.buildSubject()).contains("verifikacioni kod");
    }

    @Test
    void buildBody_containsOtpCode() {
        String body = template.buildBody("123456", 5);
        assertThat(body).contains("123456");
    }

    @Test
    void buildBody_containsExpiryMinutes() {
        String body = template.buildBody("999888", 10);
        assertThat(body).contains("10 minuta");
    }

    @Test
    void buildBody_containsHtmlStructure() {
        String body = template.buildBody("000000", 5);
        assertThat(body).contains("<!DOCTYPE html>");
        assertThat(body).contains("<html lang=\"sr\">");
        assertThat(body).contains("</html>");
    }

    @Test
    void buildBody_containsGradientHeader() {
        String body = template.buildBody("111111", 5);
        assertThat(body).contains("linear-gradient(135deg,#6366f1,#7c3aed)");
    }

    @Test
    void buildBody_containsBanka2Branding() {
        String body = template.buildBody("111111", 5);
        assertThat(body).contains("Banka 2");
    }

    @Test
    void buildBody_containsTransactionWarning() {
        String body = template.buildBody("111111", 5);
        assertThat(body).contains("transakciju");
    }

    @Test
    void buildBody_containsAttemptInfo() {
        // Maksimalan broj pokusaja se enforce-uje u banka-core (OTP servis), ne ovde,
        // pa email koristi genericku formulaciju kako poruka ne bi mogla da odlutava
        // od stvarnog limita.
        String body = template.buildBody("111111", 5);
        assertThat(body).contains("ogranicen broj pokusaja");
        assertThat(body).doesNotContain("3 pokusaja");
    }

    @Test
    void buildBody_containsAutoMessageFooter() {
        String body = template.buildBody("111111", 5);
        assertThat(body).contains("automatska poruka");
    }

    // ── [P1-notif-svc-1 / 1528] HTML injection u code ──────────────────────

    @Test
    void buildBody_escapesHtmlInCode() {
        String body = template.buildBody("<img src=x onerror=alert(1)>", 5);
        assertThat(body).doesNotContain("<img src=x");
        assertThat(body).contains("&lt;img");
    }
}
