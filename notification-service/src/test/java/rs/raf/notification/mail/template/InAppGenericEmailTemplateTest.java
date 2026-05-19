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
}
