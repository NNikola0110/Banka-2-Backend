package rs.raf.trading.audit.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.model.AuditLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Faza G (live-smoke fix) — Criteria-API filteri za audit log.
 *
 * <p><b>Zasto Specification umesto JPQL {@code (:p IS NULL OR col = :p)}:</b> kada
 * je opcioni filter parametar {@code null}, taj bind se u JPQL-u pojavljuje SAMO u
 * {@code :p IS NULL} (i u short-circuit-ovanoj poredbi), pa PostgreSQL ne moze da
 * zakljuci tip parametra ({@code ERROR: could not determine data type of parameter $N}).
 * H2 (test DB) to toleriše, pa je bug izmицao kroz sve H2 testove i pao TEK na
 * pravom PG-u (live Docker stack: {@code GET /audit} → HTTP 400).
 *
 * <p>Criteria builder bind-uje parametre sa razresenim Java tipom (enum
 * {@link AuditActionType}, {@link Long}, {@link LocalDateTime}, {@code List<Long>}),
 * pa PG uvek dobija tipiziran parametar; {@code null} filter → predikat se NE dodaje
 * (semantika "nema ogranicenja" je ocuvana, identicno kao pre).
 */
public final class AuditLogSpec {

    private AuditLogSpec() {}

    /**
     * Filtrira po opcionalnom {@code actionType}, {@code actorId} i {@code [from, to]}
     * vremenskom rasponu. Svi {@code null} parametri se ignorisu (bez ogranicenja).
     */
    public static Specification<AuditLog> filtered(
            AuditActionType actionType, Long actorId,
            LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
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
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Kao {@link #filtered} ali sa obaveznim {@code actorId IN (actorIds)} filterom
     * (Sc45 — filter audita po IMENU aktera; servis prvo razresi ime → skup id-eva).
     * Prazna lista → uvek-false predikat (0 rezultata), uskladjeno sa postojecim
     * guard-om u servisu (caller i tako ne zove ovaj put sa praznom listom).
     */
    public static Specification<AuditLog> filteredByActorIds(
            AuditActionType actionType, List<Long> actorIds,
            LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actionType != null) {
                predicates.add(cb.equal(root.get("actionType"), actionType));
            }
            if (actorIds == null || actorIds.isEmpty()) {
                // Prazna/null IN-lista → nijedan red (mirror SQL `IN ()` semantike).
                predicates.add(cb.disjunction());
            } else {
                predicates.add(root.get("actorId").in(actorIds));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
