package rs.raf.trading.schema;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.model.UserMarginAccount;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-schema-1 PG-DDL smoke za trading-service entitete protiv PRAVOG PostgreSQL-a
 * (Testcontainers) — H2 (test DB) ne reprodukuje vernо PG numeric(precision,scale)
 * niti UNIQUE-constraint ponasanje pod {@code ddl-auto=update}, pa ova provera ide
 * protiv pravog postgres kontejnera.
 *
 * <p>Pokriva:
 * <ul>
 *   <li><b>R4-1772</b>: {@code actuary_info.daily_limit}/{@code used_limit} moraju biti
 *       {@code numeric(19,4)} (scale-4), ne PG default {@code numeric(38,2)}.</li>
 *   <li><b>R1-469</b>: {@code margin_accounts} ima UNIQUE constraint na {@code account_id}
 *       (§57: jedan marzni racun po baznom racunu) — drugi insert sa istim account_id puca.</li>
 * </ul>
 *
 * <p>{@code @EnabledIf("dockerAvailable")}: ceo test se gracefully preskace bez Docker-a
 * (CI/lokalno) — isti obrazac kao {@code DividendPayoutPostgresDdlIT} /
 * {@code OhlcvRecorderIntegrationTest}.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class TradingSchemaPostgresDdlTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static StandardServiceRegistry registry;
    private static EntityManagerFactory emf;

    @BeforeAll
    static void bootstrap() {
        Map<String, Object> props = new HashMap<>();
        props.put(AvailableSettings.JAKARTA_JDBC_URL, postgres.getJdbcUrl());
        props.put(AvailableSettings.JAKARTA_JDBC_USER, postgres.getUsername());
        props.put(AvailableSettings.JAKARTA_JDBC_PASSWORD, postgres.getPassword());
        props.put(AvailableSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver");
        props.put(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
        props.put(AvailableSettings.HBM2DDL_AUTO, "update");
        props.put("hibernate.type.preferred_boolean_jdbc_type", "INTEGER");
        props.put("hibernate.hbm2ddl.halt_on_error", "false");

        registry = new StandardServiceRegistryBuilder()
                .applySettings(props)
                .build();
        Metadata metadata = new MetadataSources(registry)
                .addAnnotatedClass(ActuaryInfo.class)
                .addAnnotatedClass(MarginAccount.class)
                .addAnnotatedClass(UserMarginAccount.class)
                .buildMetadata();
        emf = metadata.buildSessionFactory();
    }

    @AfterAll
    static void tearDown() {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    // ── R4-1772: ActuaryInfo precision/scale ─────────────────────────────────

    @Test
    void actuaryInfo_limitColumns_areNumeric19Scale4_R4_1772() throws Exception {
        assertThat(columnNumeric("actuary_info", "daily_limit"))
                .as("daily_limit mora biti numeric(19,4) (ne PG default numeric(38,2))")
                .isEqualTo(new int[]{19, 4});
        assertThat(columnNumeric("actuary_info", "used_limit"))
                .as("used_limit mora biti numeric(19,4)")
                .isEqualTo(new int[]{19, 4});
    }

    @Test
    void actuaryInfo_persistScale4Value_roundTripsExactly_R4_1772() {
        EntityManager em = emf.createEntityManager();
        Long id;
        try {
            em.getTransaction().begin();
            ActuaryInfo a = new ActuaryInfo();
            a.setEmployeeId(9001L);
            a.setActuaryType(ActuaryType.AGENT);
            a.setDailyLimit(new BigDecimal("12345.6789"));
            a.setUsedLimit(new BigDecimal("0.0001"));
            a.setNeedApproval(true);
            em.persist(a);
            em.getTransaction().commit();
            id = a.getId();
        } finally {
            em.close();
        }

        EntityManager em2 = emf.createEntityManager();
        try {
            ActuaryInfo found = em2.find(ActuaryInfo.class, id);
            // scale-4 kolona cuva sve 4 decimale (numeric(38,2) bi odsekao na 2).
            assertThat(found.getDailyLimit()).isEqualByComparingTo("12345.6789");
            assertThat(found.getUsedLimit()).isEqualByComparingTo("0.0001");
            assertThat(found.getDailyLimit().scale()).isEqualTo(4);
        } finally {
            em2.close();
        }
    }

    // ── R1-469: margin_accounts UNIQUE(account_id) ───────────────────────────

    @Test
    void marginAccounts_hasUniqueConstraintOnAccountId_R1_469() {
        // Prvi insert prolazi.
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em.persist(newMargin(700L, 5000L));
            em.getTransaction().commit();
        } finally {
            em.close();
        }

        // Drugi insert sa ISTIM account_id mora pucati (UNIQUE constraint, §57).
        // NAPOMENA: MarginAccount koristi @GeneratedValue(IDENTITY), pa Hibernate INSERT
        // izvrsava ODMAH na persist() (radi dohvata generisanog ID-a) — UNIQUE violacija
        // se javlja na persist()/flush(), NE tek na commit(). Zato asercija obuhvata
        // ceo persist→flush put (ranije je wrap-ovala samo commit pa je izuzetak iz
        // persist-a iskakao van assertThatThrownBy-a → false ERROR na pravom PG-u).
        EntityManager em2 = emf.createEntityManager();
        try {
            em2.getTransaction().begin();
            assertThatThrownBy(() -> {
                em2.persist(newMargin(701L, 5000L));
                em2.flush();
                em2.getTransaction().commit();
            })
                    .as("drugi margin racun sa istim account_id mora prekrsiti UNIQUE constraint")
                    .isInstanceOf(PersistenceException.class);
        } finally {
            if (em2.getTransaction().isActive()) {
                em2.getTransaction().rollback();
            }
            em2.close();
        }
    }

    private static UserMarginAccount newMargin(Long userId, Long accountId) {
        return UserMarginAccount.builder()
                .accountId(accountId)
                .accountNumber("acc-" + accountId)
                .userId(userId)
                .currency("RSD")
                .initialMargin(new BigDecimal("10000.0000"))
                .loanValue(BigDecimal.ZERO)
                .maintenanceMargin(new BigDecimal("5000.0000"))
                .bankParticipation(new BigDecimal("0.4000"))
                .reservedMargin(BigDecimal.ZERO)
                .status(MarginAccountStatus.ACTIVE)
                .build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Vraca {numeric_precision, numeric_scale} iz information_schema za datu kolonu. */
    private int[] columnNumeric(String table, String column) throws Exception {
        try (Connection c = connect();
             ResultSet rs = c.getMetaData().getColumns(null, null, table, column)) {
            if (!rs.next()) {
                throw new AssertionError("Kolona " + table + "." + column + " ne postoji");
            }
            int precision = rs.getInt("COLUMN_SIZE");
            int scale = rs.getInt("DECIMAL_DIGITS");
            return new int[]{precision, scale};
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
