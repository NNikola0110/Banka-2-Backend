package rs.raf.notification.mail.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountCreatedConfirmationEmailTemplateTest {

    private AccountCreatedConfirmationEmailTemplate template;

    @BeforeEach
    void setUp() {
        template = new AccountCreatedConfirmationEmailTemplate();
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
    void buildSubject_mentionsAccount() {
        assertThat(template.buildSubject()).containsIgnoringCase("račun");
    }

    @Test
    void buildBody_containsFirstName() {
        String body = template.buildBody("Marko", "2220001000000011", "Tekući");
        assertThat(body).contains("Marko");
    }

    @Test
    void buildBody_containsAccountNumber() {
        String body = template.buildBody("Ana", "2220001000000021", "Devizni");
        assertThat(body).contains("2220001000000021");
    }

    @Test
    void buildBody_containsAccountType() {
        String body = template.buildBody("Petar", "1234567890123456", "Štedni");
        assertThat(body).contains("Štedni");
    }

    @Test
    void buildBody_nullFirstName_usesDefaultGreeting() {
        String body = template.buildBody(null, "1234567890123456", "Tekući");
        assertThat(body).contains("Poštovani");
    }

    @Test
    void buildBody_blankFirstName_usesDefaultGreeting() {
        String body = template.buildBody("   ", "1234567890123456", "Tekući");
        assertThat(body).contains("Poštovani");
    }

    @Test
    void buildBody_containsHtmlStructure() {
        String body = template.buildBody("Test", "1234567890", "Tekući");
        assertThat(body).contains("<!DOCTYPE html>");
        assertThat(body).contains("<html lang=\"sr\">");
        assertThat(body).contains("</html>");
    }

    @Test
    void buildBody_containsGradientHeader() {
        String body = template.buildBody("Test", "1234567890", "Tekući");
        assertThat(body).contains("linear-gradient(135deg,#6366f1,#7c3aed)");
    }

    @Test
    void buildBody_containsBanka2Branding() {
        String body = template.buildBody("Test", "1234567890", "Tekući");
        assertThat(body).contains("Banka 2");
    }

    @Test
    void buildBody_containsAutoMessageFooter() {
        String body = template.buildBody("Test", "1234567890", "Tekući");
        assertThat(body).contains("automatska poruka");
    }

    @Test
    void buildBody_containsCheckmarkIcon() {
        String body = template.buildBody("Test", "1234567890", "Tekući");
        assertThat(body).contains("&#9745;");
    }

    @Test
    void buildBody_containsTipRacuna() {
        String body = template.buildBody("Test", "1234567890", "Poslovni");
        assertThat(body).contains("Tip računa");
        assertThat(body).contains("Broj računa");
    }

    // ── [P2-input-validation-1 / R1 385] HTML injection ──────────────────────

    @Test
    void buildBody_escapesHtmlInFirstName() {
        String body = template.buildBody("<script>alert(1)</script>", "1234567890", "Tekući");
        assertThat(body).doesNotContain("<script>alert(1)</script>");
        assertThat(body).contains("&lt;script&gt;");
    }

    @Test
    void buildBody_escapesHtmlInAccountType() {
        String body = template.buildBody("Marko", "1234567890", "<img src=x onerror=alert(1)>");
        assertThat(body).doesNotContain("<img src=x");
        assertThat(body).contains("&lt;img");
    }

    @Test
    void buildBody_escapesHtmlInAccountNumber() {
        String body = template.buildBody("Marko", "<b>1234</b>", "Tekući");
        assertThat(body).doesNotContain("<b>1234</b>");
        assertThat(body).contains("&lt;b&gt;1234&lt;/b&gt;");
    }

    @Test
    void buildBody_preservesSerbianLatinCharacters() {
        String body = template.buildBody("Đorđe", "1234567890", "Štedni čćšđž");
        assertThat(body).contains("Đorđe");
        assertThat(body).contains("Štedni čćšđž");
    }
}
