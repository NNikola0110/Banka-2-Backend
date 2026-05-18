package rs.raf.notification.mail.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetEmailTemplateTest {

    private PasswordResetEmailTemplate template;

    @BeforeEach
    void setUp() {
        template = new PasswordResetEmailTemplate();
    }

    @Test
    void buildSubject_returnsExpectedString() {
        assertThat(template.buildSubject()).contains("reset");
    }

    @Test
    void buildBody_containsResetLink() {
        String link = "http://localhost:3000/reset-password?token=xyz789";
        String body = template.buildBody(link);
        assertThat(body).contains(link);
    }

    @Test
    void buildBody_containsExpiryInfo() {
        String body = template.buildBody("http://example.com/reset?token=abc");
        assertThat(body).contains("30");
    }
}
