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
}
