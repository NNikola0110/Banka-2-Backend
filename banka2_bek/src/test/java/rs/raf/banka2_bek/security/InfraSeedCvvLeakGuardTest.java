package rs.raf.banka2_bek.security;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-500-CVV (PCI-DSS Req 3.2) GUARD — sprecava da inline {@code seed.sql} kopija
 * unutar {@code Banka-2-Infrastructure} ConfigMap manifesta (npr. {@code
 * seed-job.yaml}, {@code trading-seed-job.yaml}) tiho divergira od ocisceneg
 * root {@code Banka-2-Backend/seed.sql} i re-uvede legacy {@code cards.cvv}
 * kolonu / plaintext CVV vrednosti u deploy putanju fakultetskog K8s klastera.
 *
 * <p><b>Zasto:</b> root {@code seed.sql} je ocisten (CVV kolona uklonjena,
 * {@code Card.cvv} je {@code @Transient}, {@link rs.raf.banka2_bek.persistence.CardCvvColumnMigration}
 * DROP-uje legacy kolonu). Ali K8s seed Job NE mountuje root {@code seed.sql} —
 * on ima INLINE kopiju u ConfigMap-u. Ako ta kopija zadrzi {@code cvv} u
 * {@code INSERT INTO cards (...)} listi i plaintext literale, imamo DVE posledice:
 * <ol>
 *   <li><b>PCI leak ostaje ZIVA</b> u repo-u i re-insertuje se u klaster DB na seed-u;</li>
 *   <li><b>REGRESIJA seed Job-a</b>: posto sema vise NEMA {@code cvv} kolonu
 *       (fresh deploy) ili je {@code CardCvvColumnMigration} drop-uje (postojeca baza),
 *       {@code psql} sa stop-on-error puca sa
 *       "column \"cvv\" of relation \"cards\" does not exist" i ABORTUJE ceo Job —
 *       klaster ostaje bez demo kartica/recipijenata/downstream seed podataka.</li>
 * </ol>
 *
 * <p>Test je <b>workspace-aware</b> (isti obrazac kao {@code InfraSecretsLeakGuardTest}):
 * ako susedni infra repo nije prisutan (backend-only CI checkout), gracefully se
 * skipuje umesto da lazno pukne.
 */
class InfraSeedCvvLeakGuardTest {

    /** Plaintext CVV literali koji su bili seed-ovani u {@code seed-job.yaml}. */
    private static final List<String> LEAKED_CVVS =
            List.of("123", "456", "789", "321", "654", "987", "111", "222");

    /**
     * {@code cvv} u listi kolona jednog {@code INSERT INTO cards (...)} statement-a.
     * Match-uje multiline kolone-blok izmedju {@code cards (} i prvog {@code )}.
     */
    private static final Pattern CARDS_INSERT_COLUMN_LIST =
            Pattern.compile("INSERT\\s+INTO\\s+cards\\s*\\(([^)]*)\\)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern CVV_COLUMN_TOKEN =
            Pattern.compile("(?<![A-Za-z0-9_])cvv(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE);

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

    private static List<Path> infraSeedManifests(Path infra) throws IOException {
        try (Stream<Path> walk = Files.walk(infra)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().replace('\\', '/').contains("/.git/"))
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".yaml") || n.endsWith(".yml");
                    })
                    .filter(InfraSeedCvvLeakGuardTest::containsCardsInsert)
                    .toList();
        }
    }

    private static boolean containsCardsInsert(Path file) {
        try {
            return CARDS_INSERT_COLUMN_LIST.matcher(Files.readString(file, StandardCharsets.UTF_8))
                    .find();
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    @DisplayName("R1-500-CVV: nijedan infra seed manifest ne sme imati cvv kolonu u INSERT INTO cards")
    void noCvvColumnInInfraCardsInsert() throws IOException {
        Path infra = infraRepoRoot();
        Assumptions.assumeTrue(infra != null,
                "Banka-2-Infrastructure repo nije prisutan u workspace-u — guard skip (backend-only CI checkout)");

        List<String> offenders = new ArrayList<>();
        for (Path manifest : infraSeedManifests(infra)) {
            String content = Files.readString(manifest, StandardCharsets.UTF_8);
            Matcher m = CARDS_INSERT_COLUMN_LIST.matcher(content);
            while (m.find()) {
                String columnList = m.group(1);
                if (CVV_COLUMN_TOKEN.matcher(columnList).find()) {
                    offenders.add(infra.relativize(manifest)
                            + " -> INSERT INTO cards (" + columnList.trim() + ")");
                }
            }
        }

        assertThat(offenders)
                .as("R1-500-CVV: ovi infra seed manifesti zadrzavaju legacy `cvv` kolonu u "
                        + "INSERT INTO cards — sema vise nema tu kolonu pa seed Job puca; "
                        + "i plaintext CVV ostaje u deploy putanji. Ukloniti `cvv` iz liste "
                        + "kolona i odgovarajuce literale (mirror Banka-2-Backend/seed.sql): %s",
                        offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("R1-500-CVV: nijedan infra seed manifest ne sme sadrzati plaintext CVV literale u cards bloku")
    void noPlaintextCvvLiteralsInInfraCardsBlock() throws IOException {
        Path infra = infraRepoRoot();
        Assumptions.assumeTrue(infra != null,
                "Banka-2-Infrastructure repo nije prisutan u workspace-u — guard skip");

        List<String> offenders = new ArrayList<>();
        for (Path manifest : infraSeedManifests(infra)) {
            String content = Files.readString(manifest, StandardCharsets.UTF_8);
            // Skeniraj samo cards INSERT VALUES blok (do terminatora ';') da izbegnemo
            // false-positive na nepovezanim '123'/'456' u drugim seed redovima.
            for (String cardsBlock : cardsInsertBlocks(content)) {
                for (String cvv : LEAKED_CVVS) {
                    // Plaintext CVV literal: '123' sa SQL-string navodnicima u VALUES redu.
                    if (cardsBlock.contains("'" + cvv + "'")) {
                        offenders.add(infra.relativize(manifest) + " -> '" + cvv + "'");
                    }
                }
            }
        }

        assertThat(offenders)
                .as("R1-500-CVV (PCI-DSS Req 3.2): plaintext CVV literali u infra cards seed bloku "
                        + "— moraju se ukloniti (CVV se ne cuva at-rest): %s", offenders)
                .isEmpty();
    }

    /** Vraca substring od svakog {@code INSERT INTO cards ...} do prvog {@code ;}. */
    private static List<String> cardsInsertBlocks(String content) {
        List<String> blocks = new ArrayList<>();
        Matcher m = CARDS_INSERT_COLUMN_LIST.matcher(content);
        while (m.find()) {
            int start = m.start();
            int end = content.indexOf(';', m.end());
            blocks.add(content.substring(start, end < 0 ? content.length() : end));
        }
        return blocks;
    }
}
