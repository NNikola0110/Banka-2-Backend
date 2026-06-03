package rs.raf.notification.mail.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationConfirmedEmailTemplateTest {

    private ActivationConfirmedEmailTemplate template;

    @BeforeEach
    void setUp() {
        template = new ActivationConfirmedEmailTemplate();
    }

    @Test
    void buildSubject_returnsNonEmptyString() {
        assertThat(template.buildSubject()).isNotBlank();
    }

    @Test
    void buildBody_containsFirstName() {
        String body = template.buildBody("Jovana");
        assertThat(body).contains("Jovana");
    }

    @Test
    void buildBody_containsActivationSuccessInfo() {
        String body = template.buildBody("Jovana");
        assertThat(body).isNotBlank();
        assertThat(body).contains("Jovana");
    }

    @Test
    void buildBody_nullFirstName_usesDefaultGreeting() {
        String body = template.buildBody(null);
        assertThat(body).contains("Zdravo");
    }

    @Test
    void buildBody_blankFirstName_usesDefaultGreeting() {
        String body = template.buildBody("   ");
        assertThat(body).contains("Zdravo");
    }

    // ── [P2-input-validation-1 / R1 385] HTML injection u firstName ──────────

    @Test
    void buildBody_escapesHtmlInFirstName() {
        String body = template.buildBody("<img src=x onerror=alert(1)>");
        assertThat(body).doesNotContain("<img src=x");
        assertThat(body).contains("&lt;img");
    }

    @Test
    void buildBody_preservesSerbianLatinInFirstName() {
        String body = template.buildBody("Đorđe čćšžđ");
        assertThat(body).contains("Đorđe čćšžđ");
    }
}
