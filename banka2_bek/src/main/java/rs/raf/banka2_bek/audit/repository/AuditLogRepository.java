package rs.raf.banka2_bek.audit.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.model.AuditLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * B7 — Audit log repozitorijum (port iz main PR #86, Stasa Dragovic).
 *
 * <p><b>Faza G (live-smoke fix):</b> {@code findFiltered} je prenet sa JPQL
 * {@code (:p IS NULL OR col = :p)} obrasca na Criteria-API ({@link AuditLogSpec}).
 * Stari obrazac je padao na PRAVOM PostgreSQL-u ({@code ERROR: could not determine
 * data type of parameter $N}) kada je filter {@code null}; H2 ga je tolerisao pa je
 * bug promakao kroz sve H2 testove i pao tek na live Docker stack-u. Metodni potpis
 * je nepromenjen (servis + testovi rade kao pre) — implementacija je sada
 * {@code default} preko {@link JpaSpecificationExecutor}.
 */
public interface AuditLogRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    // R1 700/720: sortiranje dolazi iskljucivo iz Pageable Sort-a (caller prosledjuje
    // Sort.by(DESC, "createdAt")) — bez ORDER BY u specifikaciji, da nema double-sort-a.
    default Page<AuditLog> findFiltered(
            AuditActionType actionType,
            Long actorId,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable) {
        return findAll(AuditLogSpec.filtered(actionType, actorId, from, to), pageable);
    }

    List<AuditLog> findByTargetTypeAndTargetId(String targetType, Long targetId);
}
