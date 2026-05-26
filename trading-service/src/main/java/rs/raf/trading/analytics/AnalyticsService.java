package rs.raf.trading.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * [W3-T2] Read-only servisni sloj nad {@link AnalyticsDailyRepository}.
 *
 * <p>Dva zadatka: filtrirati po datumu (+/- {@code metric_name}) i mapirati
 * raw JSONB string u {@code Map&lt;String,Object&gt;}. Ako je JSON neispravan
 * — log warning, vrati prazan map (defensive — Spark output u teoriji uvek
 * validan, ali ne zelimo da single corrupt row sruši ceo response).
 *
 * <p>{@code ObjectMapper} se instancira lokalno (paritet sa
 * {@link rs.raf.trading.internalapi.service.InternalFundService}) jer
 * trading-service-ova Spring config ne registruje {@code ObjectMapper} bean
 * (nema {@code spring-boot-starter-web} u app classpath na "test" profilu,
 * pa ne treba ni autowire). Lokalna instanca je thread-safe za read use.
 */
@Slf4j
@Service
public class AnalyticsService {

    private final AnalyticsDailyRepository repository;
    private final ObjectMapper objectMapper;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Autowired
    public AnalyticsService(AnalyticsDailyRepository repository) {
        this.repository = repository;
        this.objectMapper = new ObjectMapper();
    }

    /** Test-only ctor — dozvoljava injekciju mock-a ili custom mapper-a. */
    AnalyticsService(AnalyticsDailyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<AnalyticsDailyDto> findDaily(LocalDate date, String metricName) {
        List<AnalyticsDailyEntity> rows;
        if (metricName != null && !metricName.isBlank()) {
            rows = repository.findByMetricDateAndMetricNameOrderByValueDesc(date, metricName);
        } else {
            rows = repository.findByMetricDateOrderByMetricNameAsc(date);
        }
        return rows.stream().map(this::toDto).toList();
    }

    private AnalyticsDailyDto toDto(AnalyticsDailyEntity entity) {
        return new AnalyticsDailyDto(
                entity.getId(),
                entity.getMetricDate(),
                entity.getMetricName(),
                parseDimensions(entity.getDimensions()),
                entity.getValue(),
                entity.getComputedAt()
        );
    }

    private Map<String, Object> parseDimensions(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Neispravan JSON u analytics_daily.dimensions: '{}' — vracam prazan map", json, e);
            return Collections.emptyMap();
        }
    }
}
