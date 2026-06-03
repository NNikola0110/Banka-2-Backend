package rs.raf.banka2_bek.fraud;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [W3-T2] Repozitorijum za {@link TransactionAnomalyEntity}.
 *
 * <p>{@code findAlerts} pokriva oba use-case-a iz spec-a:
 * <ul>
 *   <li>{@code since} (computed_at >=) — vremenski filter za "vidi novo"</li>
 *   <li>{@code minRisk} (risk_score >=) — supervizor moze da skreni
 *       sum/precise; default 0.0 znaci "sve"</li>
 *   <li>{@code onlyPending} — defaultno true (FE pokazuje pending tab);
 *       supervizor moze prebaciti na full list (audit case)</li>
 * </ul>
 */
@Repository
public interface TransactionAnomalyRepository extends JpaRepository<TransactionAnomalyEntity, Long> {

    // Faza G (live-smoke fix): cast(:p as tip) na null-check strani — PG ne moze da
    // zakljuci tip null bind-a (ERROR: could not determine data type of parameter $N).
    // onlyPending je primitivni boolean (uvek tipiziran), reviewStatus IS NULL je
    // kolona (ne param) — samo since/minRisk treba cast.
    @Query("""
        SELECT a FROM TransactionAnomalyEntity a
        WHERE (cast(:since as timestamp) IS NULL OR a.computedAt >= :since)
          AND (cast(:minRisk as big_decimal) IS NULL OR a.riskScore >= :minRisk)
          AND (:onlyPending = false OR a.reviewStatus IS NULL OR a.reviewStatus = 'pending')
        ORDER BY a.riskScore DESC, a.computedAt DESC
        """)
    Page<TransactionAnomalyEntity> findAlerts(
            @Param("since") LocalDateTime since,
            @Param("minRisk") BigDecimal minRisk,
            @Param("onlyPending") boolean onlyPending,
            Pageable pageable);
}
