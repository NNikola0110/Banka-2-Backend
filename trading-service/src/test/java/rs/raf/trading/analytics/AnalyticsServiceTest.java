package rs.raf.trading.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [W3-T2] Unit testovi za {@link AnalyticsService} — fokus na JSONB parsing.
 *
 * <p>Servis koristi pravi {@link ObjectMapper} (nije mockovan) jer testiramo
 * njegovo ponasanje za malformatirani JSON; mockovanjem mappera dobili bismo
 * tautoloske assertove.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private AnalyticsDailyRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AnalyticsService service() {
        return new AnalyticsService(repository, objectMapper);
    }

    private AnalyticsDailyEntity entity(String dimensionsJson) {
        return AnalyticsDailyEntity.builder()
                .id(1L)
                .metricDate(LocalDate.of(2026, 5, 26))
                .metricName("top_movers")
                .dimensions(dimensionsJson)
                .value(new BigDecimal("12.34"))
                .computedAt(LocalDateTime.of(2026, 5, 26, 4, 0))
                .build();
    }

    @Test
    @DisplayName("findDaily bez filtera poziva findByMetricDate")
    void findDaily_noMetricName_callsDateOnlyQuery() {
        LocalDate date = LocalDate.of(2026, 5, 26);
        when(repository.findByMetricDateOrderByMetricNameAsc(date))
                .thenReturn(List.of(entity("{\"symbol\":\"AAPL\"}")));

        List<AnalyticsDailyDto> result = service().findDaily(date, null);

        assertThat(result).hasSize(1);
        verify(repository).findByMetricDateOrderByMetricNameAsc(date);
    }

    @Test
    @DisplayName("findDaily sa metricName poziva findByMetricDateAndMetricName")
    void findDaily_withMetricName_callsCombinedQuery() {
        LocalDate date = LocalDate.of(2026, 5, 26);
        when(repository.findByMetricDateAndMetricNameOrderByValueDesc(date, "top_movers"))
                .thenReturn(List.of(entity("{\"symbol\":\"AAPL\"}")));

        service().findDaily(date, "top_movers");

        verify(repository).findByMetricDateAndMetricNameOrderByValueDesc(date, "top_movers");
    }

    @Test
    @DisplayName("findDaily sa praznim metricName (blank) tretira se kao null filter")
    void findDaily_blankMetricName_treatedAsNoFilter() {
        LocalDate date = LocalDate.of(2026, 5, 26);
        when(repository.findByMetricDateOrderByMetricNameAsc(date))
                .thenReturn(List.of());

        service().findDaily(date, "   ");

        verify(repository).findByMetricDateOrderByMetricNameAsc(date);
    }

    @Test
    @DisplayName("toDto parsira validan JSONB u Map")
    void toDto_validJson_parsesIntoMap() {
        when(repository.findByMetricDateOrderByMetricNameAsc(LocalDate.of(2026, 5, 26)))
                .thenReturn(List.of(entity("{\"symbol\":\"AAPL\",\"sector\":\"tech\"}")));

        AnalyticsDailyDto dto = service().findDaily(LocalDate.of(2026, 5, 26), null).get(0);

        assertThat(dto.dimensions())
                .containsEntry("symbol", "AAPL")
                .containsEntry("sector", "tech");
    }

    @Test
    @DisplayName("toDto vraca prazan map za null/blank dimensions")
    void toDto_nullDimensions_returnsEmptyMap() {
        when(repository.findByMetricDateOrderByMetricNameAsc(LocalDate.of(2026, 5, 26)))
                .thenReturn(List.of(entity(null), entity("")));

        List<AnalyticsDailyDto> result = service().findDaily(LocalDate.of(2026, 5, 26), null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).dimensions()).isEmpty();
        assertThat(result.get(1).dimensions()).isEmpty();
    }

    @Test
    @DisplayName("toDto vraca prazan map za neispravan JSON (defensive)")
    void toDto_invalidJson_returnsEmptyMap() {
        when(repository.findByMetricDateOrderByMetricNameAsc(LocalDate.of(2026, 5, 26)))
                .thenReturn(List.of(entity("this is not json {[}")));

        List<AnalyticsDailyDto> result = service().findDaily(LocalDate.of(2026, 5, 26), null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).dimensions()).isEmpty();
    }
}
