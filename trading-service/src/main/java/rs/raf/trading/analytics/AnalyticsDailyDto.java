package rs.raf.trading.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * [W3-T2] Izlazni DTO za jedan red {@code analytics_daily} tabele.
 *
 * <p>{@code dimensions} je {@link Map} (parsovano iz JSONB stringa u
 * {@link AnalyticsService#toDto}), pa FE moze direktno da indeksira
 * po dinamickim kljucevima (npr. {@code dimensions.symbol},
 * {@code dimensions.sector}). Spark job upisuje JSONB sa imenima polja
 * specificnim za svaku metriku — bez fiksiranog schema-a.
 */
public record AnalyticsDailyDto(
        Long id,
        LocalDate metricDate,
        String metricName,
        Map<String, Object> dimensions,
        BigDecimal value,
        LocalDateTime computedAt
) {
}
