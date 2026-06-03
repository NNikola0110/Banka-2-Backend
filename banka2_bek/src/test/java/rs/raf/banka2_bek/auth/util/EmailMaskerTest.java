package rs.raf.banka2_bek.auth.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** [P2-input-validation-1 / R4 1781] PII email maskiranje za logove. */
class EmailMaskerTest {

    @Test
    void mask_hidesLocalPartButKeepsDomain() {
        String masked = EmailMasker.mask("jovan.krunic000@gmail.com");
        assertThat(masked).doesNotContain("jovan.krunic000");
        assertThat(masked).contains("@gmail.com");
        assertThat(masked).startsWith("j");
    }

    @Test
    void mask_nullReturnsNonePlaceholder() {
        assertThat(EmailMasker.mask(null)).isEqualTo("(none)");
    }

    @Test
    void mask_blankReturnsNonePlaceholder() {
        assertThat(EmailMasker.mask("   ")).isEqualTo("(none)");
    }

    @Test
    void mask_shortLocalPart() {
        String masked = EmailMasker.mask("ab@x.com");
        assertThat(masked).doesNotContain("ab@");
        assertThat(masked).contains("@x.com");
    }

    @Test
    void mask_doesNotLeakFullAddress() {
        String email = "secret.user@bank.rs";
        String masked = EmailMasker.mask(email);
        assertThat(masked).isNotEqualTo(email);
        assertThat(masked).doesNotContain("secret.user");
    }

    @Test
    void mask_noAtSignDoesNotLeak() {
        String masked = EmailMasker.mask("notanemail");
        assertThat(masked).doesNotContain("notanemail");
    }
}
