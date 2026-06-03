package rs.raf.trading.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

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

import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.model.AuditLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faza G (live-smoke fix) — reproducer + verifikacija fix-a za PG
 * untyped-null-param bug u audit filter upitima, protiv PRAVOG PostgreSQL-a
 * (Testcontainers).
 *
 * <p><b>Bug:</b> {@code GET /audit?page=0&size=5} (supervizor) je na live Docker
 * stack-u (pravi PG) vracao HTTP 400 sa
 * {@code JDBC exception: ERROR: could not determine data type of parameter $5}.
 * Uzrok: JPQL opcioni-filter obrazac {@code (:param IS NULL OR col = :param)} — kada
 * je {@code :param} {@code null}, taj bind se pojavljuje SAMO u {@code :param IS NULL}
 * (poredba je short-circuit-ovana), pa PostgreSQL ne moze da zakljuci tip bind-a.
 * H2 (test DB) to toleriše → bug je promakao kroz SVE H2 testove i pao tek na PG-u.
 *
 * <p>Ovaj test radi protiv PRAVOG {@code postgres:16-alpine}-a (NE H2) i:
 * <ol>
 *   <li>reprodukuje bug: stari JPQL {@code (:p IS NULL OR ...)} sa SVIM-NULL
 *       parametrima → puca sa "could not determine data type" na PG-u;</li>
 *   <li>dokazuje fix: Criteria-API put (identican {@code AuditLogSpec.filtered})
 *       sa SVIM-NULL parametrima → vraca sve redove, BEZ greske;</li>
 *   <li>dokazuje da filtriranje i dalje radi: non-null {@code actionType}/{@code actorId}/
 *       {@code from}/{@code to} → tacan podskup redova.</li>
 * </ol>
 *
 * <p>{@code @EnabledIf("dockerAvailable")}: bez Docker-a se gracefully preskace
 * (isti obrazac kao {@code DividendPayoutPostgresDdlIT}/{@code TradingSchemaPostgresDdlTest}).
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class AuditLogFilterPostgresTest {

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

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 6, 1, 10, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 6, 2, 10, 0);

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
                .addAnnotatedClass(AuditLog.class)
                .buildMetadata();
        emf = metadata.buildSessionFactory();

        seed();
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

    private static void seed() {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            AuditLog a0 = audit(5L, AuditActionType.LIMIT_CHANGED);
            AuditLog a1 = audit(7L, AuditActionType.ORDER_APPROVED);
            em.persist(a0);
            em.persist(a1);
            em.flush();
            // created_at je @CreationTimestamp (== now()); override-ujemo ga native
            // update-om na fiksne T0/T1 da bi [from,to] filter bio deterministican.
            setCreatedAt(em, a0.getId(), T0);
            setCreatedAt(em, a1.getId(), T1);
            em.getTransaction().commit();
        } finally {
            em.close();
        }
    }

    private static void setCreatedAt(EntityManager em, Long id, LocalDateTime ts) {
        em.createNativeQuery("UPDATE audit_logs SET created_at = :ts WHERE id = :id")
                .setParameter("ts", ts)
                .setParameter("id", id)
                .executeUpdate();
    }

    private static AuditLog audit(Long actorId, AuditActionType type) {
        return AuditLog.builder()
                .actorId(actorId)
                .actorType("EMPLOYEE")
                .actionType(type)
                .description("seed " + type.name())
                .targetType("EMPLOYEE")
                .targetId(99L)
                .build();
    }

    // ── (1) REPRODUCER: stari JPQL puca na PRAVOM PG-u sa svim-null params ────────

    @Test
    void oldJpqlPattern_withAllNullParams_failsOnRealPostgres_couldNotDetermineDataType() {
        EntityManager em = emf.createEntityManager();
        try {
            TypedQuery<AuditLog> q = em.createQuery("""
                    SELECT a FROM AuditLog a
                    WHERE (:actionType IS NULL OR a.actionType = :actionType)
                      AND (:actorId IS NULL OR a.actorId = :actorId)
                      AND (:from IS NULL OR a.createdAt >= :from)
                      AND (:to IS NULL OR a.createdAt <= :to)
                    """, AuditLog.class);
            q.setParameter("actionType", (AuditActionType) null);
            q.setParameter("actorId", (Long) null);
            q.setParameter("from", (LocalDateTime) null);
            q.setParameter("to", (LocalDateTime) null);

            // Na PRAVOM PostgreSQL-u ovo baca PSQLException-wrapped PersistenceException:
            // "ERROR: could not determine data type of parameter $N".
            assertThatThrownBy(q::getResultList)
                    .isInstanceOf(PersistenceException.class)
                    .hasMessageContaining("could not determine data type");
        } finally {
            em.close();
        }
    }

    // ── (2) FIX: Criteria-API put (== AuditLogSpec.filtered) radi sa svim-null ───

    @Test
    void criteriaFilter_withAllNullParams_returnsAllRows_onRealPostgres() {
        List<AuditLog> result = criteriaFiltered(null, null, null, null);
        assertThat(result)
                .as("svi-null filteri → svi redovi, BEZ 'could not determine data type' greske")
                .hasSize(2);
    }

    // ── (3) FIX: filtriranje po non-null parametrima i dalje tacno ───────────────

    @Test
    void criteriaFilter_byActionType_filtersCorrectly_onRealPostgres() {
        List<AuditLog> result = criteriaFiltered(AuditActionType.LIMIT_CHANGED, null, null, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActionType()).isEqualTo(AuditActionType.LIMIT_CHANGED);
        assertThat(result.get(0).getActorId()).isEqualTo(5L);
    }

    @Test
    void criteriaFilter_byActorId_filtersCorrectly_onRealPostgres() {
        List<AuditLog> result = criteriaFiltered(null, 7L, null, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getActorId()).isEqualTo(7L);
        assertThat(result.get(0).getActionType()).isEqualTo(AuditActionType.ORDER_APPROVED);
    }

    @Test
    void criteriaFilter_byDateRange_filtersCorrectly_onRealPostgres() {
        // [from, to] koji obuhvata samo prvi red (T0), pre T1.
        List<AuditLog> result = criteriaFiltered(
                null, null,
                LocalDateTime.of(2026, 5, 31, 0, 0),
                LocalDateTime.of(2026, 6, 1, 23, 59));
        assertThat(result)
                .as("date-range [31.05–01.06] hvata samo red kreiran 01.06 (ne 02.06)")
                .allSatisfy(a -> assertThat(a.getCreatedAt())
                        .isBeforeOrEqualTo(LocalDateTime.of(2026, 6, 1, 23, 59)));
        // Bar prvi red (actor 5, T0) mora biti unutra; drugi (T1=02.06) van.
        assertThat(result).extracting(AuditLog::getActorId).contains(5L).doesNotContain(7L);
    }

    /**
     * Criteria-API put identican {@code AuditLogSpec.filtered}: null filter → predikat
     * se ne dodaje (bez bind-a), non-null → tipiziran bind preko Criteria builder-a.
     */
    private List<AuditLog> criteriaFiltered(AuditActionType actionType, Long actorId,
                                            LocalDateTime from, LocalDateTime to) {
        EntityManager em = emf.createEntityManager();
        try {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<AuditLog> cq = cb.createQuery(AuditLog.class);
            Root<AuditLog> root = cq.from(AuditLog.class);
            List<Predicate> predicates = new ArrayList<>();
            if (actionType != null) {
                predicates.add(cb.equal(root.get("actionType"), actionType));
            }
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            cq.where(cb.and(predicates.toArray(new Predicate[0])));
            cq.orderBy(cb.desc(root.get("createdAt")));
            return em.createQuery(cq).getResultList();
        } finally {
            em.close();
        }
    }
}
