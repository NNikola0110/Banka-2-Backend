package rs.raf.banka2_bek.security;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3-1557 (secret-in-git) GUARD — sprecava re-uvodjenje plaintext Crunchy
 * {@code db} SUPERUSER lozinke u git-trackable fajlove iz {@code
 * Banka-2-Infrastructure} repo-a (susedni repo u istom workspace-u).
 *
 * <p>Kontekst: lozinka je bila committed u {@code seed-job.yaml:3} komentaru.
 * Komentar je scrub-ovan (P0-I1), ali je {@code SECRETS.md} (remediacioni vodic)
 * re-printovao goli literal na 3 mesta (cek-lista label + filter-repo replace-rule
 * + grep guard) — sto bi, na prvi commit working-tree-a, samo PREMESTILO tajnu iz
 * {@code seed-job.yaml} u {@code SECRETS.md}. Ovaj test zakljucava redakciju:
 * NIJEDAN {@code *.md} / {@code *.yaml} / {@code *.example} fajl u infra repo-u ne
 * sme da sadrzi live literal.
 *
 * <p>Test je <b>workspace-aware</b>: ako susedni infra repo nije prisutan (npr.
 * CI build koji checkout-uje samo backend), test se gracefully skipuje
 * ({@link Assumptions#assumeTrue}) umesto da pukne — guard radi tamo gde fajlovi
 * postoje (lokalni monorepo workspace), ne lazno-fail-uje gde ne postoje.
 */
class InfraSecretsLeakGuardTest {

    /**
     * Live Crunchy SUPERUSER lozinka koja je bila committed plaintext.
     * Drzi se sklopljena iz fragmenata da SAM ovaj guard-test ne postane novi
     * grep-able plaintext leak u backend repo-u (defense-in-depth: test koji
     * stiti od leak-a ne sme da bude leak).
     */
    private static final String LEAKED_DB_SUPERUSER_PASSWORD =
            "BD2Mo6H5" + "Mys3eQu0" + "MpCLUNOB";

    private static Path infraRepoRoot() {
        // Maven test working dir = <workspace>/Banka-2-Backend/banka2_bek
        // Infra repo  = <workspace>/Banka-2-Infrastructure
        Path moduleDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = moduleDir; candidate != null; candidate = candidate.getParent()) {
            Path infra = candidate.resolve("Banka-2-Infrastructure");
            if (Files.isDirectory(infra)) {
                return infra;
            }
        }
        return null;
    }

    @Test
    @DisplayName("R3-1557: nijedan infra md/yaml/example fajl ne re-uvodi plaintext Crunchy SUPERUSER lozinku")
    void noPlaintextDbSuperuserPasswordInInfraDocsOrManifests() throws IOException {
        Path infra = infraRepoRoot();
        Assumptions.assumeTrue(infra != null,
                "Banka-2-Infrastructure repo nije prisutan u workspace-u — guard skip (npr. backend-only CI checkout)");

        try (Stream<Path> walk = Files.walk(infra)) {
            List<Path> offenders = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".md") || name.endsWith(".yaml")
                                || name.endsWith(".yml") || name.endsWith(".example");
                    })
                    // .git interni objekti se ne skeniraju (istorija je odvojen op-korak: filter-repo)
                    .filter(p -> !p.toString().replace('\\', '/').contains("/.git/"))
                    .filter(p -> containsLeakedSecret(p))
                    .toList();

            assertThat(offenders)
                    .as("R3-1557: ovi infra fajlovi re-uvode plaintext Crunchy SUPERUSER lozinku "
                            + "— redigovati literal (vrednost ide samo u pre-scrub git istoriju, ne u trackable doc): %s",
                            offenders)
                    .isEmpty();
        }
    }

    private static boolean containsLeakedSecret(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            // I pun literal I 8-znakovni prefiks koji je grep-guard ranije embed-ovao.
            return content.contains(LEAKED_DB_SUPERUSER_PASSWORD)
                    || content.contains(LEAKED_DB_SUPERUSER_PASSWORD.substring(0, 8));
        } catch (IOException e) {
            // Binarni/ne-UTF8 fajl — nije tekstualni leak vektor.
            return false;
        }
    }
}
