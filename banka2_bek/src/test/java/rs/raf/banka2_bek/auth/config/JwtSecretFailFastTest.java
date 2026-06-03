package rs.raf.banka2_bek.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N4 — banka-core JWT_SECRET fail-fast.
 *
 * <p>Pre fix-a, {@code application.properties} je imao javni hardkodovan dev
 * default ({@code ${JWT_SECRET:dev-only-...}}): ako {@code JWT_SECRET} env nije
 * postavljen, app bi se podigla sa secret-om koji je vidljiv svakom sa pristupom
 * repo-u — napadac moze da potpise validan JWT za BILO KOJI nalog (token forge).</p>
 *
 * <p>Fix: {@code ${JWT_SECRET:?...}} — Spring fail-fast-uje pri startu ako env
 * nije postavljen, i javni default je uklonjen. Ovaj test cuva tu invarijantu
 * (deterministican, ne podize Spring kontekst).</p>
 */
class JwtSecretFailFastTest {

    private String readApplicationProperties() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertThat(in).as("application.properties mora postojati na classpath-u").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("N4: jwt.secret koristi ${JWT_SECRET} placeholder (ne hardkodovan literal)")
    void jwtSecretUsesEnvPlaceholder() throws IOException {
        String content = readApplicationProperties();
        assertThat(content).contains("jwt.secret=${JWT_SECRET");
    }

    @Test
    @DisplayName("N4: javni hardkodovan dev default je uklonjen (fail-fast)")
    void noHardcodedPublicDefaultSecret() throws IOException {
        String content = readApplicationProperties();
        // Stari javni default string NE sme vise da postoji kao fallback vrednost.
        assertThat(content)
                .doesNotContain("${JWT_SECRET:dev-only-jwt-secret-do-not-use-in-prod");
        // Fail-fast marker ":?" mora biti prisutan na jwt.secret liniji.
        assertThat(content).containsPattern("jwt\\.secret=\\$\\{JWT_SECRET:\\?");
    }
}
