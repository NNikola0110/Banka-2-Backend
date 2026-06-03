package rs.raf.notification.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * P2-config-2 (R3 1596): mail fail-fast validacija.
 */
class MailConfigValidatorTest {

    private static MailConfigValidator validator(boolean smtpAuth, String password, boolean failFast) {
        return new MailConfigValidator(smtpAuth, password, failFast);
    }

    @Test
    @DisplayName("fail-fast=true + smtp.auth=true + prazan MAIL_PASSWORD → puca pri startu")
    void failFast_authTrue_blankPassword_throws() {
        MailConfigValidator v = validator(true, "", true);
        assertThatThrownBy(v::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_PASSWORD");
    }

    @Test
    @DisplayName("fail-fast=true + smtp.auth=true + null MAIL_PASSWORD → puca")
    void failFast_authTrue_nullPassword_throws() {
        MailConfigValidator v = validator(true, null, true);
        assertThatThrownBy(v::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("fail-fast=false (dev) + prazan password → samo WARN, ne puca")
    void devMode_blankPassword_doesNotThrow() {
        MailConfigValidator v = validator(true, "", false);
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fail-fast=true + password postavljen → ne puca")
    void failFast_passwordPresent_doesNotThrow() {
        MailConfigValidator v = validator(true, "app-password-xyz", true);
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("smtp.auth=false + prazan password → ne puca (auth se ne zahteva)")
    void authDisabled_blankPassword_doesNotThrow() {
        MailConfigValidator v = validator(false, "", true);
        assertThatCode(v::validate).doesNotThrowAnyException();
    }
}
