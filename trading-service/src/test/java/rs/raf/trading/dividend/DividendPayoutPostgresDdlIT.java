package rs.raf.trading.dividend;

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

import rs.raf.trading.dividend.model.DividendPayout;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG-DDL smoke za {@link DividendPayout} protiv PRAVOG PostgreSQL-a (Testcontainers).
 *
 * <p><b>Zasto pravi PG umesto H2:</b> {@code tax_exempt} je {@code Boolean} mapiran
 * kao {@code INTEGER} ({@code hibernate.type.preferred_boolean_jdbc_type=INTEGER}).
 * Pre fix-a je entitet imao {@code @ColumnDefault("false")} sto na PostgreSQL-u
 * emituje {@code tax_exempt integer not null default false} — PG to ODBIJA
 * (boolean default izraz na integer koloni), pa se cela tabela {@code dividend_payouts}
 * NE kreira (sa {@code halt_on_error=false} boot prodje, ali svaki runtime
 * save/find puca → idempotency-guard prazan → dupla dividenda na cron retry-u).
 * H2 (MODE=PostgreSQL) ovo guta jer ima implicit boolean↔int cast, pa H2 testovi
 * NE vide bug. Ovaj test ga reprodukuje: pre fix-a tabela se ne kreira → persist puca;
 * posle fix-a ({@code @ColumnDefault("0")}) tabela nastane → persist/query rade.
 *
 * <p>Bootstrap je namerno standalone Hibernate ({@code Persistence}/{@code EntityManagerFactory})
 * a NE Spring slice ({@code @DataJpaTest} nije na classpath-u u Spring Boot 4 ovog modula)
 * — cilj je verifikovati DDL + persist/query nad PRAVIM PostgreSQL dijalektom, sto je
 * tacno put kojim Spring Data {@code save}/{@code findBy} prolazi (isti EntityManager).
 * Konfiguracija matchuje produkciju: {@code hbm2ddl.auto=update} +
 * {@code preferred_boolean_jdbc_type=INTEGER} + {@code PostgreSQLDialect}.
 *
 * <p>{@code @EnabledIf("dockerAvailable")}: ceo test se gracefully preskace ako
 * Docker daemon nije dostupan (CI/lokalno bez Docker-a) — isti obrazac kao
 * {@code OhlcvRecorderIntegrationTest}. Prvi run povlaci {@code postgres:16-alpine}
 * (~30-60s), potom je u kesu.
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class DividendPayoutPostgresDdlIT {

    /**
     * JUnit5 ExecutionCondition (evaluira se PRE Testcontainers @BeforeAll-a) —
     * ceo test se preskace ako Docker nije dostupan, umesto da pukne na startu
     * kontejnera.
     */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Container
    @SuppressWarnings("resource") // Testcontainers gasi static container preko JVM shutdown hook-a
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
        // Produkciono ponasanje: schema-update na pravom PG dijalektu.
        props.put(AvailableSettings.HBM2DDL_AUTO, "update");
        // Kljucni reproducer: Boolean -> INTEGER mapiranje (isto kao produkcija).
        props.put("hibernate.type.preferred_boolean_jdbc_type", "INTEGER");
        // Reprodukuj produkciono ponasanje: ne haltuj na DDL gresci — boot prodje,
        // (pre fix-a) tabela fali, pa persist/query padne sto je dokaz buga.
        props.put("hibernate.hbm2ddl.halt_on_error", "false");

        // Native Hibernate bootstrap (bez persistence.xml) — registrujemo SAMO
        // DividendPayout entitet, dovoljno za DDL + persist/query smoke.
        registry = new StandardServiceRegistryBuilder()
                .applySettings(props)
                .build();
        Metadata metadata = new MetadataSources(registry)
                .addAnnotatedClass(DividendPayout.class)
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

    /**
     * Pre fix-a: {@code dividend_payouts} se ne kreira na PG → persist baca
     * (tabela ne postoji). Posle fix-a: tabela nastane, persist + query rade.
     */
    @Test
    void dividendPayoutsTable_isCreatedOnRealPostgres_andPersistQueryWork() throws Exception {
        // 1) Tabela mora STVARNO postojati u PG katalogu (pre fix-a fali).
        assertThat(tableExists("dividend_payouts"))
                .as("dividend_payouts tabela mora biti kreirana na pravom PostgreSQL-u "
                        + "(pre fix-a @ColumnDefault(\"false\") na INTEGER koloni obara DDL)")
                .isTrue();

        // 2) tax_exempt mora biti INTEGER kolona (Boolean->INTEGER mapiranje).
        assertThat(taxExemptColumnIsIntegerType())
                .as("tax_exempt mora biti INTEGER kolona (preferred_boolean_jdbc_type=INTEGER)")
                .isTrue();

        // 3) persist(...) — pre fix-a puca jer tabela ne postoji.
        EntityManager em = emf.createEntityManager();
        Long id;
        try {
            em.getTransaction().begin();
            DividendPayout payout = DividendPayout.builder()
                    .ownerId(42L)
                    .ownerType("EMPLOYEE")
                    .stockListingId(7L)
                    .stockTicker("AAPL")
                    .quantity(100)
                    .priceOnDate(new BigDecimal("185.0000"))
                    .dividendYieldRate(new BigDecimal("0.001250"))
                    .grossAmount(new BigDecimal("23.1250"))
                    .tax(BigDecimal.ZERO)
                    .netAmount(new BigDecimal("23.1250"))
                    .creditedAccountId(900L)
                    .currencyCode("USD")
                    .paymentDate(LocalDate.of(2026, 6, 30))
                    .taxExempt(Boolean.TRUE)
                    .build();
            em.persist(payout);
            em.getTransaction().commit();
            id = payout.getId();
        } finally {
            em.close();
        }
        assertThat(id).isNotNull();

        // 4) Query nazad — isti put kojim Spring Data findBy... ide (JPQL/EntityManager).
        EntityManager em2 = emf.createEntityManager();
        try {
            // Idempotency-guard put: findByStockListingIdAndPaymentDate ekvivalent.
            List<DividendPayout> byListingDate = em2.createQuery(
                            "SELECT d FROM DividendPayout d "
                                    + "WHERE d.stockListingId = :sid AND d.paymentDate = :pd",
                            DividendPayout.class)
                    .setParameter("sid", 7L)
                    .setParameter("pd", LocalDate.of(2026, 6, 30))
                    .getResultList();
            assertThat(byListingDate).hasSize(1);
            assertThat(byListingDate.get(0).getTaxExempt()).isTrue();

            DividendPayout found = em2.find(DividendPayout.class, id);
            assertThat(found).isNotNull();
            assertThat(found.getStockTicker()).isEqualTo("AAPL");
            assertThat(found.getOwnerType()).isEqualTo("EMPLOYEE");
        } finally {
            em2.close();
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (Connection c = connect();
             ResultSet rs = c.getMetaData().getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private boolean taxExemptColumnIsIntegerType() throws Exception {
        try (Connection c = connect();
             ResultSet rs = c.getMetaData().getColumns(null, null, "dividend_payouts", "tax_exempt")) {
            if (!rs.next()) {
                return false;
            }
            String typeName = rs.getString("TYPE_NAME");
            return typeName != null && typeName.toLowerCase().contains("int");
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
