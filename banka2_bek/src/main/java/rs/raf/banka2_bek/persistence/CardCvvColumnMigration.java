package rs.raf.banka2_bek.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * R1-500-CVV (PCI-DSS Req 3.2) — uklanja legacy {@code cards.cvv} kolonu posle
 * Hibernate schema migration-a (isti obrazac kao {@link CardSlotIndexInitializer}).
 *
 * <p><b>Zasto je potreban:</b> {@code Card.cvv} je promenjen u
 * {@link jakarta.persistence.Transient @Transient} (CVV se vise NE cuva at-rest jer
 * se nikad nije ni verifikovao). Na svezoj semi ({@code ddl-auto=create}) kolona se
 * uopste ne kreira. Ali na postojecim prod bazama Hibernate {@code ddl-auto=update}
 * <b>NE dropuje kolone i ne relaksira NOT NULL</b>, pa bi stara
 * {@code cvv VARCHAR(3) NOT NULL} kolona ostala u tabeli: posto Hibernate vise ne
 * mapira {@code cvv}, INSERT nove kartice je vise ne navodi → DB bi odbio insert
 * ("null value in column cvv violates not-null constraint"). Ovaj migration to
 * resava DROP-om kolone, cime se istovremeno <b>trajno brisu i postojeci plaintext
 * CVV podaci</b> (PCI-DSS: ne sme se cuvati posle autorizacije).
 *
 * <p>{@code ALTER TABLE ... DROP COLUMN IF EXISTS} je idempotentan: drugo pokretanje
 * (kolona vec drop-ovana) je no-op. Na H2 (test profil, {@code ddl-auto=create})
 * kolona nikad ne postoji pa je takodje no-op; svaki failure mode se tiho loguje
 * jer se iz {@code @PostConstruct} ne sme rusiti app start.
 */
@Service
@ConditionalOnProperty(name = "banka2.migrations.card-cvv-drop.enabled",
        havingValue = "true", matchIfMissing = true)
public class CardCvvColumnMigration {

    private static final Logger log = LoggerFactory.getLogger(CardCvvColumnMigration.class);

    private static final String DROP_CVV_COLUMN_SQL =
            "ALTER TABLE cards DROP COLUMN IF EXISTS cvv";

    private final JdbcTemplate jdbcTemplate;

    public CardCvvColumnMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void dropLegacyCvvColumn() {
        try {
            jdbcTemplate.execute(DROP_CVV_COLUMN_SQL);
            log.info("R1-500-CVV: legacy plaintext cards.cvv column dropped "
                    + "(no-op if already absent / fresh schema)");
        } catch (Exception e) {
            // Tabela jos ne postoji, privilegije, ili dijalekt bez DROP COLUMN IF EXISTS
            // (H2 starije verzije) — ne smemo da srusimo app start iz @PostConstruct.
            log.warn("Could not drop legacy cards.cvv column (likely already absent "
                    + "or H2 test profile): {}", e.getMessage());
        }
    }
}
