package rs.raf.trading.audit.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.model.AuditLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * B7 — Audit log repozitorijum u trading-service domenu (port iz main PR #86).
 *
 * <p><b>Faza G (live-smoke fix):</b> opcioni filteri su preneti sa JPQL
 * {@code (:p IS NULL OR col = :p)} obrasca na Criteria-API ({@link AuditLogSpec}).
 * Stari obrazac je padao na PRAVOM PostgreSQL-u sa {@code ERROR: could not determine
 * data type of parameter $N} kada je filter {@code null} (PG ne moze da zakljuci tip
 * bind-a koji se javlja samo u {@code :p IS NULL}); H2 ga je tolerisao pa je bug
 * promakao kroz sve H2 testove i pao tek na live Docker stack-u ({@code GET /audit}
 * → 400). Criteria builder bind-uje tipizirane parametre → radi i na PG i na H2.
 * Metodne potpise NE menjamo (servis + testovi nepromenjeni) — implementacija je
 * sada {@code default} preko {@link JpaSpecificationExecutor#findAll(org.springframework.data.jpa.domain.Specification, Pageable)}.
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

    // Sc45 (TODO_testovi): filter audita po IMENU aktera. trading-service nema
    // Employee tabelu (zivi u banka-core), pa servis prvo razresi skup actorId-eva
    // preko BankaCoreClient.findEmployees(...) i prosledi ih ovde kao IN-listu.
    // Prazna lista => 0 rezultata (po dizajnu: ime ne odgovara nijednom akteru).
    default Page<AuditLog> findFilteredByActorIds(
            AuditActionType actionType,
            List<Long> actorIds,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable) {
        return findAll(AuditLogSpec.filteredByActorIds(actionType, actorIds, from, to), pageable);
    }

    List<AuditLog> findByTargetTypeAndTargetId(String targetType, Long targetId);
}
