package rs.raf.notification.mail.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationEmailTemplateTest {

    private ActivationEmailTemplate template;

    @BeforeEach
    void setUp() {
        template = new ActivationEmailTemplate();
    }

    @Test
    void buildSubject_returnsNonEmptyString() {
        assertThat(template.buildSubject()).isNotBlank();
    }

    @Test
    void buildBody_containsActivationLink() {
        String link = "http://localhost:3000/activate-account?token=abc123";
        String body = template.buildBody(link, "Marko");
        assertThat(body).contains(link);
    }

    @Test
    void buildBody_containsFirstName() {
        String body = template.buildBody("http://example.com/activate", "Marko");
        assertThat(body).contains("Marko");
    }

    @Test
    void buildBody_containsExpiryInfo() {
        String body = template.buildBody("http://example.com/activate", "Ana");
        assertThat(body).contains("24");
    }

    @Test
    void buildBody_nullFirstName_usesDefaultGreeting() {
        String body = template.buildBody("http://example.com/activate", null);
        assertThat(body).contains("Zdravo");
    }

    @Test
    void buildBody_blankFirstName_usesDefaultGreeting() {
        String body = template.buildBody("http://example.com/activate", "   ");
        assertThat(body).contains("Zdravo");
    }

    // ── [P2-input-validation-1 / R1 385] HTML injection u firstName ──────────

    @Test
    void buildBody_escapesHtmlInFirstName() {
        String body = template.buildBody("http://example.com/activate",
                "<script>alert('xss')</script>");
        assertThat(body).doesNotContain("<script>alert('xss')</script>");
        assertThat(body).contains("&lt;script&gt;");
    }

    @Test
    void buildBody_activationLinkRemainsUnescaped() {
        // Link je sistemski-generisan, NE escape-uje se (query parametri ostaju netaknuti).
        String link = "http://localhost:3000/activate-account?token=abc123";
        String body = template.buildBody(link, "Marko");
        assertThat(body).contains(link);
    }

    @Test
    void buildBody_preservesSerbianLatinInFirstName() {
        String body = template.buildBody("http://example.com/activate", "Đorđe");
        assertThat(body).contains("Đorđe");
    }
}
