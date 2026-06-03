package rs.raf.notification.mail.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarginAccountBlockedEmailTemplateTest {

    private MarginAccountBlockedEmailTemplate template;

    @BeforeEach
    void setUp() {
        template = new MarginAccountBlockedEmailTemplate();
    }

    @Test
    void buildSubject_returnsNonEmptyString() {
        assertThat(template.buildSubject()).isNotBlank();
    }

    @Test
    void buildBody_containsMarginValues() {
        String body = template.buildBody("1000.00", "1100.00", "100.00");
        assertThat(body).contains("1000.00");
        assertThat(body).contains("1100.00");
        assertThat(body).contains("100.00");
    }

    @Test
    void buildBody_nullValue_rendersDash() {
        String body = template.buildBody("1000.00", null, "100.00");
        assertThat(body).contains("-");
    }

    @Test
    void buildBody_containsHtmlStructure() {
        String body = template.buildBody("1000.00", "1100.00", "100.00");
        assertThat(body).contains("<!DOCTYPE html>");
        assertThat(body).contains("</html>");
    }

    // ── [P2-input-validation-1 / R1 385] HTML injection u value ──────────────

    @Test
    void buildBody_escapesHtmlInValue() {
        String body = template.buildBody("<script>alert(1)</script>", "1100.00", "100.00");
        assertThat(body).doesNotContain("<script>alert(1)</script>");
        assertThat(body).contains("&lt;script&gt;");
    }
}
