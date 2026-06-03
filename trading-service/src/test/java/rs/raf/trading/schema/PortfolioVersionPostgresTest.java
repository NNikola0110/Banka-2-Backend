package rs.raf.trading.schema;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

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

import rs.raf.trading.persistence.PortfolioVersionColumnMigration;
import rs.raf.trading.portfolio.model.Portfolio;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Bug 4 (REAL money/execution) — Testcontainers PG dokaz da seed-ovani portfolio
 * sa {@code version = NULL} vise NE lomi UPDATE (optimistic-lock commit), nakon
 * {@link PortfolioVersionColumnMigration} backfill-a.
 *
 * <p>Pre fix-a: {@code trading-seed.sql} je INSERT-ovao portfolije bez
 * {@code version} kolone (nullable, bez default-a) → Hibernate optimistic locking
 * ne moze da poredi {@code NULL} verziju pa svaki UPDATE (SELL rezervacija,
 * order-execution {@code updatePortfolio}) puca na commit-u → BUY orderi trajno
 * zaglavljeni (hartije se ne pripisu iako je settlement commit-ovao).
 *
 * <p>Ovaj test reprodukuje "null-version seed" put protiv PRAVOG PostgreSQL-a
 * (H2 ne reprodukuje verno PG optimistic-lock + ALTER COLUMN ponasanje):
 * <ol>
 *   <li>Hibernate kreira {@code portfolios} semu (ddl-auto=update).</li>
 *   <li>Raw SQL INSERT BEZ {@code version} kolone → red sa {@code version IS NULL}
 *       (verno starom seed-u).</li>
 *   <li>{@link PortfolioVersionColumnMigration} backfill ({@code version=0} +
 *       {@code SET DEFAULT 0}).</li>
 *   <li>Hibernate UPDATE (managed merge, mutira {@code reservedQuantity}) →
 *       MORA da commit-uje (bez optimistic-lock fail-a) i {@code version} se
 *       inkrementira sa 0 na 1.</li>
 * </ol>
 *
 * <p>{@code @EnabledIf("dockerAvailable")}: ceo test se gracefully preskace bez
 * Docker-a — isti obrazac kao {@code TradingSchemaPostgresDdlTest}.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class PortfolioVersionPostgresTest {

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
        java.util.Map<String, Object> props = new java.util.HashMap<>();
        props.put(AvailableSettings.JAKARTA_JDBC_URL, postgres.getJdbcUrl());
        props.put(AvailableSettings.JAKARTA_JDBC_USER, postgres.getUsername());
        props.put(AvailableSettings.JAKARTA_JDBC_PASSWORD, postgres.getPassword());
        props.put(AvailableSettings.JAKARTA_JDBC_DRIVER, "org.postgresql.Driver");
        props.put(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect");
        props.put(AvailableSettings.HBM2DDL_AUTO, "update");
        props.put("hibernate.type.preferred_boolean_jdbc_type", "INTEGER");
        props.put("hibernate.hbm2ddl.halt_on_error", "false");
        // Physical naming camelCase → snake_case — bez ovoga standalone Hibernate
        // bootstrap zadrzava camelCase kolone (userId, averageBuyPrice), pa raw-SQL
        // INSERT/migracija (user_id, version) ne bi nasla kolone. Hibernate-ova
        // ugradjena CamelCaseToUnderscoresNamingStrategy daje identican snake_case
        // rezultat kao Spring Boot prod sema + trading-seed.sql (version → version).
        props.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY,
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");

        registry = new StandardServiceRegistryBuilder()
                .applySettings(props)
                .build();
        Metadata metadata = new MetadataSources(registry)
                .addAnnotatedClass(Portfolio.class)
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

    private JdbcTemplate jdbc() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(ds);
    }

    /**
     * Simulira PRE-FIX bazu: dropuje DB default sa {@code version} kolone (sveza
     * sema sa {@code @ColumnDefault("0")} bi inace dala 0, ne NULL). Ovim
     * rekonstruisemo istorijsko stanje "nullable kolona bez default-a", verno
     * stanju pre Bug 4 fix-a u kojem je trading-seed.sql proizvodio NULL verzije.
     */
    private void simulatePreFixSchema() throws Exception {
        try (Connection c = postgres.createConnection("");
             Statement st = c.createStatement()) {
            st.execute("ALTER TABLE portfolios ALTER COLUMN version DROP DEFAULT");
        }
    }

    /**
     * Insert-uje portfolio red BEZ version kolone (verno starom seed-u) i vraca ID.
     * Vraca generisani IDENTITY id.
     */
    private long insertSeedRowWithNullVersion() throws Exception {
        try (Connection c = postgres.createConnection("");
             Statement st = c.createStatement()) {
            st.executeUpdate(
                    "INSERT INTO portfolios "
                    + "(user_id, user_role, listing_id, listing_ticker, listing_name, "
                    + " listing_type, quantity, average_buy_price, public_quantity, "
                    + " reserved_quantity, last_modified) "
                    + "VALUES (1, 'CLIENT', 10, 'AAPL', 'Apple', 'STOCK', 50, 145.0000, "
                    + " 0, 0, NOW())",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private Long readVersion(long id) throws Exception {
        try (Connection c = postgres.createConnection("");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT version FROM portfolios WHERE id = " + id)) {
            rs.next();
            long v = rs.getLong("version");
            return rs.wasNull() ? null : v;
        }
    }

    @Test
    void seededNullVersionRow_afterMigration_updateCommits_andVersionIncrements() throws Exception {
        // Rekonstruisi PRE-FIX bazu (kolona bez DB default-a) pa insert-uj seed red.
        simulatePreFixSchema();
        long id = insertSeedRowWithNullVersion();

        // Sanity: seed put ostavlja version = NULL (reprodukuje bug-uslov).
        assertThat(readVersion(id))
                .as("seed INSERT bez version kolone → version IS NULL (pre migracije)")
                .isNull();

        // Backfill (production migration kod) → version 0 + DEFAULT 0.
        new PortfolioVersionColumnMigration(jdbc()).backfillPortfolioVersion();

        assertThat(readVersion(id))
                .as("posle backfill-a version mora biti 0 (ne NULL)")
                .isEqualTo(0L);

        // Hibernate managed UPDATE (mutira reservedQuantity, kao SELL rezervacija)
        // MORA da commit-uje bez optimistic-lock fail-a; version 0 -> 1.
        assertThatCode(() -> {
            EntityManager em = emf.createEntityManager();
            try {
                em.getTransaction().begin();
                Portfolio p = em.find(Portfolio.class, id);
                assertThat(p.getVersion()).isEqualTo(0L);
                p.setReservedQuantity(5);
                p.setLastModified(LocalDateTime.now());
                em.getTransaction().commit();
            } finally {
                em.close();
            }
        }).as("UPDATE seed-ovanog portfolija MORA da commit-uje (bez 'No active transaction')")
                .doesNotThrowAnyException();

        assertThat(readVersion(id))
                .as("optimistic version se inkrementirao 0 -> 1 na commit-u")
                .isEqualTo(1L);
    }

    @Test
    void freshEntity_persistsWithVersionZero_andUpdateIncrements() {
        EntityManager em = emf.createEntityManager();
        long id;
        try {
            em.getTransaction().begin();
            Portfolio fresh = Portfolio.builder()
                    .userId(2L)
                    .userRole("CLIENT")
                    .listingId(11L)
                    .listingTicker("MSFT")
                    .listingName("Microsoft")
                    .listingType("STOCK")
                    .quantity(30)
                    .averageBuyPrice(new BigDecimal("380.5000"))
                    .publicQuantity(0)
                    .reservedQuantity(0)
                    .lastModified(LocalDateTime.now())
                    .build();
            em.persist(fresh);
            em.getTransaction().commit();
            id = fresh.getId();
            // Field-init = 0L → nova instanca nikad nema null version.
            assertThat(fresh.getVersion()).isEqualTo(0L);
        } finally {
            em.close();
        }

        // Drugi UPDATE u zasebnoj tx → version 0 -> 1, commit OK.
        EntityManager em2 = emf.createEntityManager();
        try {
            em2.getTransaction().begin();
            Portfolio p = em2.find(Portfolio.class, id);
            p.setQuantity(25);
            em2.getTransaction().commit();
            assertThat(p.getVersion()).isEqualTo(1L);
        } finally {
            em2.close();
        }
    }
}
