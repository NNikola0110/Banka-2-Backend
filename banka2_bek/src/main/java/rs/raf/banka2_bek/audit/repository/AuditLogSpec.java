package rs.raf.banka2_bek.audit.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.model.AuditLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Faza G (live-smoke fix) — Criteria-API filteri za banka-core audit log.
 *
 * <p><b>Zasto Specification umesto JPQL {@code (:p IS NULL OR col = :p)}:</b> kada
 * je opcioni filter {@code null}, taj bind se u JPQL-u pojavljuje SAMO u {@code :p IS NULL},
 * pa PostgreSQL ne moze da zakljuci tip parametra ({@code ERROR: could not determine
 * data type of parameter $N}). H2 to toleriše → bug promakao kroz H2 testove i pao
 * tek na pravom PG-u ({@code GET /audit} → 400). Criteria builder bind-uje tipizirane
 * parametre, pa radi i na PG i na H2; {@code null} filter → predikat se ne dodaje.
 */
public final class AuditLogSpec {

    private AuditLogSpec() {}

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
}
