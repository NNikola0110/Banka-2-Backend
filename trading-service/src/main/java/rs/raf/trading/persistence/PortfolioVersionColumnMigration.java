package rs.raf.trading.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Bug 4 (REAL money/execution) — backfill {@code portfolios.version} posle
 * Hibernate schema migration-a (isti obrazac kao banka-core
 * {@code CardCvvColumnMigration} / {@code SavingsInterestRateIndexInitializer}).
 *
 * <p><b>Zasto je potreban:</b> {@code Portfolio.version} je {@code @Version}
 * optimistic-locking kolona, ali je istorijski bila {@code nullable} bez DB
 * default-a, a {@code trading-seed.sql} je INSERT-ovao portfolije BEZ te kolone →
 * svi seed-ovani redovi su imali {@code version = NULL}. Hibernate ne moze da
 * poredi {@code NULL} verziju: prvi UPDATE seed-ovanog portfolija (SELL
 * rezervacija, order-execution {@code updatePortfolio}) puca na commit-u
 * ("Could not commit JPA transaction" → 400 "No active transaction"), a BUY
 * orderi ostaju trajno zaglavljeni iako je banka-core settlement vec commit-ovao
 * (conservation break — hartije se ne pripisu).
 *
 * <p><b>Sta radi (idempotentno, H2-safe):</b>
 * <ol>
 *   <li>{@code UPDATE portfolios SET version = 0 WHERE version IS NULL} —
 *       backfill svih null verzija (drugo pokretanje je no-op, 0 redova).</li>
 *   <li>{@code ALTER TABLE portfolios ALTER COLUMN version SET DEFAULT 0} —
 *       garantuje DB default da budući INSERT-i bez kolone ne uvedu nove NULL-ove.</li>
 * </ol>
 *
 * <p>Na svezoj semi ({@code @ColumnDefault("0")} + field-init {@code = 0L} na
 * entitetu) kolona vec ima default i nema null redova, pa su oba koraka no-op.
 * Na H2 (test profil) sintaksa {@code ALTER COLUMN ... SET DEFAULT} se moze
 * razlikovati po verziji — svaki failure mode se tiho loguje (WARN/DEBUG) jer se
 * iz {@code @PostConstruct} ne sme rusiti app start.
 */
@Service
@ConditionalOnProperty(name = "trading.migrations.portfolio-version-backfill.enabled",
        havingValue = "true", matchIfMissing = true)
public class PortfolioVersionColumnMigration {

    private static final Logger log = LoggerFactory.getLogger(PortfolioVersionColumnMigration.class);

    private static final String BACKFILL_NULL_VERSION_SQL =
            "UPDATE portfolios SET version = 0 WHERE version IS NULL";

    private static final String SET_DEFAULT_SQL =
            "ALTER TABLE portfolios ALTER COLUMN version SET DEFAULT 0";

    private final JdbcTemplate jdbcTemplate;

    public PortfolioVersionColumnMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void backfillPortfolioVersion() {
        try {
            int updated = jdbcTemplate.update(BACKFILL_NULL_VERSION_SQL);
            log.info("Bug 4: backfilled {} portfolios.version NULL row(s) → 0 "
                    + "(no-op if none / fresh schema)", updated);
        } catch (Exception e) {
            // Tabela jos ne postoji (startup race) ili privilegije — ne smemo da
            // srusimo app start iz @PostConstruct.
            log.warn("Could not backfill portfolios.version (likely table absent yet "
                    + "or H2 test profile): {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute(SET_DEFAULT_SQL);
            log.info("Bug 4: portfolios.version DEFAULT 0 ensured "
                    + "(idempotent; protects future INSERTs without the column)");
        } catch (Exception e) {
            // H2/dijalekt razlike u ALTER COLUMN SET DEFAULT — best-effort, ne fatalno.
            log.debug("Could not set portfolios.version DEFAULT 0 ({}): {}",
                    SET_DEFAULT_SQL, e.getMessage());
        }
    }
}
