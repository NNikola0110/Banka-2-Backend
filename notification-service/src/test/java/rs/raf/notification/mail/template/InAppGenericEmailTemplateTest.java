package rs.raf.notification.mail.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InAppGenericEmailTemplateTest {

    private InAppGenericEmailTemplate template;

    @BeforeEach
    void setUp() {
        template = new InAppGenericEmailTemplate();
    }

    @Test
    void buildBody_containsTitle() {
        String body = template.buildBody("Marko", "Novo obaveštenje", "Vaš nalog je ažuriran.");
        assertThat(body).contains("Novo obaveštenje");
    }

    @Test
    void buildBody_containsBodyText() {
        String body = template.buildBody("Ana", "Test", "Poruka za klijenta.");
        assertThat(body).contains("Poruka za klijenta.");
    }

    @Test
    void buildBody_containsFirstNameInGreeting() {
        String body = template.buildBody("Petar", "Naslov", "Sadrzaj");
        assertThat(body).contains("Petar");
    }

    @Test
    void buildBody_nullFirstName_usesNeutralGreeting() {
        String body = template.buildBody(null, "Naslov", "Sadrzaj");
        assertThat(body).contains("korisnice");
    }

    @Test
    void buildBody_blankFirstName_usesNeutralGreeting() {
        String body = template.buildBody("   ", "Naslov", "Sadrzaj");
        assertThat(body).contains("korisnice");
    }

    @Test
    void buildBody_containsHtmlStructure() {
        String body = template.buildBody("Test", "Naslov", "Sadrzaj");
        assertThat(body).contains("<!DOCTYPE html>");
        assertThat(body).contains("<html lang=\"sr\">");
        assertThat(body).contains("</html>");
    }

    @Test
    void buildBody_containsGradientHeader() {
        String body = template.buildBody("Test", "Naslov", "Sadrzaj");
        assertThat(body).contains("linear-gradient(135deg,#6366f1,#7c3aed)");
    }

    @Test
    void buildBody_containsBanka2Branding() {
        String body = template.buildBody("Test", "Naslov", "Sadrzaj");
        assertThat(body).contains("Banka 2");
    }

    @Test
    void buildBody_containsAutoMessageFooter() {
        String body = template.buildBody("Test", "Naslov", "Sadrzaj");
        assertThat(body).contains("automatska poruka");
    }

    @Test
    void buildBody_titleAppearsInHeader() {
        String body = template.buildBody("Marko", "Obaveštenje o plaćanju", "Iznos je 100 RSD.");
        // Naslov treba da bude i u <title> i u <h1> headeru
        assertThat(body.indexOf("Obaveštenje o plaćanju")).isLessThan(body.lastIndexOf("Obaveštenje o plaćanju"));
    }

    // ── [P1-notif-svc-1 / 1528 / 1742] HTML injection ──────────────────────

    @Test
    void buildBody_escapesHtmlInBody_noScriptInjection() {
        String body = template.buildBody("Ana", "Naslov",
                "<script>alert('xss')</script><img src=x onerror=alert(1)>");
        assertThat(body).doesNotContain("<script>");
        assertThat(body).doesNotContain("<img src=x");
        assertThat(body).contains("&lt;script&gt;");
        assertThat(body).contains("&lt;img");
    }

    @Test
    void buildBody_escapesHtmlInTitle() {
        String body = template.buildBody("Ana", "<b>injected</b>", "Sadrzaj");
        assertThat(body).doesNotContain("<b>injected</b>");
        assertThat(body).contains("&lt;b&gt;injected&lt;/b&gt;");
    }

    @Test
    void buildBody_escapesHtmlInFirstName() {
        String body = template.buildBody("<a href='evil'>Marko</a>", "Naslov", "Sadrzaj");
        assertThat(body).doesNotContain("<a href='evil'>");
        assertThat(body).contains("&lt;a href=");
    }

    @Test
    void buildBody_escapesAmpersandCompanyName() {
        // Naziv firme "A&B <Co>" sme da prolazi (citljiv) ali kao tekst, ne markup
        String body = template.buildBody("A&B <Co>", "Naslov", "Sadrzaj");
        assertThat(body).contains("A&amp;B &lt;Co&gt;");
    }

    @Test
    void buildBody_preservesSerbianLatinCharacters() {
        // HtmlUtils ne dira ne-ASCII — srpska latinica/dijakritici ostaju citljivi
        String body = template.buildBody("Đorđe", "Obaveštenje", "Vaš nalog je ažuriran čćšđž.");
        assertThat(body).contains("Đorđe");
        assertThat(body).contains("ažuriran čćšđž");
    }
}
