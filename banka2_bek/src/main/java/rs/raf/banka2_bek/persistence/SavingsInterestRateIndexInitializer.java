package rs.raf.banka2_bek.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * TEST-savings-3 — kreira PARTIAL UNIQUE INDEX na {@code savings_interest_rates}
 * posle Hibernate schema migration-a (isti obrazac kao
 * {@link CardSlotIndexInitializer}).
 *
 * <p>Poslovno pravilo: za svaki {@code (currency_id, term_months)} sme da postoji
 * NAJVISE JEDAN aktivan red, ali proizvoljno mnogo istorijskih (deaktiviranih)
 * redova. JPA {@code @UniqueConstraint}/{@code @Index} ne podrzava partial
 * {@code WHERE} klauzulu, pa je custom DDL jedini portabilan nacin u PostgreSQL-u.
 *
 * <p>Index garantuje:
 * <ul>
 *   <li>{@code (currency_id, term_months)} je jedinstven SAMO medju {@code active=true}
 *       redovima — {@code upsertOnce} (deaktiviraj tekuci → insert nov aktivan) radi
 *       proizvoljno mnogo puta (ranije je 3. promena pucala na 3-kolonskom unique-u
 *       gde je {@code active} bio puni deo kljuca).</li>
 *   <li>Dva paralelna {@code upsertOnce} ne mogu da ostave dva aktivna reda za isti
 *       par — DB odbija drugi insert sa duplicate-key greskom (race-condition guard
 *       nezavisno od {@code @Version}/@Retryable na service-u).</li>
 * </ul>
 *
 * <p>Migracija postojecih baza: {@code ddl-auto=update} je na ranijim deploy-ima
 * mogao da kreira stari 3-kolonski {@code uk_savings_rates_currency_term_active}
 * constraint. Pre kreiranja partial indeksa ga DROP-ujemo {@code IF EXISTS} (idempotentno).
 *
 * <p>H2 (test profile) ne podrzava partial {@code WHERE} sintaksu — i drugi failure
 * modovi (privilegije, tabela jos ne postoji) se NE smeju propagirati iz
 * {@code @PostConstruct} jer rusi app start; tiho se loguju kao WARN.
 */
@Service
@ConditionalOnProperty(name = "banka2.indexes.savings-rate.enabled",
        havingValue = "true", matchIfMissing = true)
public class SavingsInterestRateIndexInitializer {

    private static final Logger log =
            LoggerFactory.getLogger(SavingsInterestRateIndexInitializer.class);

    private static final String INDEX_NAME = "uk_savings_rates_active";
    private static final String LEGACY_CONSTRAINT = "uk_savings_rates_currency_term_active";

    private static final String DROP_LEGACY_CONSTRAINT_SQL =
            "ALTER TABLE savings_interest_rates DROP CONSTRAINT IF EXISTS " + LEGACY_CONSTRAINT;
    // Hibernate u nekim verzijama materijalizuje @UniqueConstraint kao index (ne constraint).
    private static final String DROP_LEGACY_INDEX_SQL =
            "DROP INDEX IF EXISTS " + LEGACY_CONSTRAINT;

    private static final String CREATE_INDEX_SQL = """
            CREATE UNIQUE INDEX IF NOT EXISTS %s
            ON savings_interest_rates (currency_id, term_months)
            WHERE active = true
            """.formatted(INDEX_NAME);

    private final JdbcTemplate jdbcTemplate;

    public SavingsInterestRateIndexInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureActiveRateIndex() {
        dropLegacyQuietly(DROP_LEGACY_CONSTRAINT_SQL);
        dropLegacyQuietly(DROP_LEGACY_INDEX_SQL);
        try {
            jdbcTemplate.execute(CREATE_INDEX_SQL);
            log.info("TEST-savings-3: PARTIAL UNIQUE INDEX {} provided on savings_interest_rates",
                    INDEX_NAME);
        } catch (Exception e) {
            // H2 (test) ne podrzava partial WHERE u CREATE UNIQUE INDEX-u — i ostali
            // failure modovi ne smeju da sruse app start iz @PostConstruct.
            log.warn("Could not create {} (likely H2 test profile): {}",
                    INDEX_NAME, e.getMessage());
        }
    }

    private void dropLegacyQuietly(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            // Stari constraint/index ne postoji ili je vec drop-ovan — best-effort.
            log.debug("Legacy savings-rate uniqueness drop skipped ({}): {}", sql, e.getMessage());
        }
    }
}
