package rs.raf.trading.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * [W3-T2] Spring Data repozitorijum za {@link AnalyticsDailyEntity}.
 * Read-only API — Spark write-uje, trading-service samo cita.
 */
@Repository
public interface AnalyticsDailyRepository extends JpaRepository<AnalyticsDailyEntity, Long> {

    /** Sve metrike za dati datum (kad FE filter ne pošalje {@code metric_name}). */
    List<AnalyticsDailyEntity> findByMetricDateOrderByMetricNameAsc(LocalDate metricDate);

    /** Konkretna metrika za dati datum (npr. samo {@code "top_movers"}). */
    List<AnalyticsDailyEntity> findByMetricDateAndMetricNameOrderByValueDesc(
            LocalDate metricDate, String metricName);
}
