package rs.raf.trading.otc.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.trading.otc.model.OtcNegotiationHistory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * B10 — JPA repozitorijum za istoriju OTC pregovora (port iz main PR #89).
 */
public interface OtcNegotiationHistoryRepository
        extends JpaRepository<OtcNegotiationHistory, Long> {

    List<OtcNegotiationHistory> findByNegotiationIdOrderByCreatedAtAsc(Long negotiationId);

    // Faza G (live-smoke fix): cast(:p as tip) na null-check strani — bez njega PG
    // ne moze da zakljuci tip null parametra (ERROR: could not determine data type
    // of parameter $N). status/modifiedById su String/Long (ne enum), pa je cast
    // dovoljan; isti PG-safe obrazac kao TransactionRepository/PaymentRepository.
    @Query("SELECT h FROM OtcNegotiationHistory h " +
            "WHERE (cast(:status as string) IS NULL OR h.status = :status) " +
            "  AND (cast(:modifiedById as long) IS NULL OR h.modifiedById = :modifiedById) " +
            "  AND (cast(:from as timestamp) IS NULL OR h.createdAt >= :from) " +
            "  AND (cast(:to as timestamp) IS NULL OR h.createdAt <= :to) " +
            "ORDER BY h.createdAt DESC")
    Page<OtcNegotiationHistory> findWithFilters(
            @Param("status") String status,
            @Param("modifiedById") Long modifiedById,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
}
