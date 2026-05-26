package rs.raf.trading.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [W3-T2] Mockito web-layer test za {@link AnalyticsController}.
 *
 * <p>Pun MVC stack preko {@code standaloneSetup} (bez Spring kontexta) —
 * fokus je na request/response binding-u i delegaciji prema servisu. Role
 * authorization je konfigurisana centralno u {@code TradingSecurityConfig}
 * (testirano integration-style u {@code PriceAlertControllerIntegrationTest}
 * obrascu); ovde se ne reaktivira filter chain.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private AnalyticsController controller;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /admin/analytics/daily?date=... -> 200 + lista svih metrika za taj dan")
    void getDaily_noFilter_returnsAllMetricsForDate() throws Exception {
        AnalyticsDailyDto row1 = new AnalyticsDailyDto(
                1L, LocalDate.of(2026, 5, 26), "top_movers",
                Map.of("symbol", "AAPL"),
                new BigDecimal("12.34"),
                LocalDateTime.of(2026, 5, 26, 4, 0));
        AnalyticsDailyDto row2 = new AnalyticsDailyDto(
                2L, LocalDate.of(2026, 5, 26), "sector_perf",
                Map.of("sector", "tech"),
                new BigDecimal("2.10"),
                LocalDateTime.of(2026, 5, 26, 4, 0));

        when(analyticsService.findDaily(eq(LocalDate.of(2026, 5, 26)), isNull()))
                .thenReturn(List.of(row1, row2));

        mockMvc.perform(get("/admin/analytics/daily?date=2026-05-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].metricName").value("top_movers"))
                .andExpect(jsonPath("$[0].dimensions.symbol").value("AAPL"))
                .andExpect(jsonPath("$[1].metricName").value("sector_perf"));

        verify(analyticsService).findDaily(LocalDate.of(2026, 5, 26), null);
    }

    @Test
    @DisplayName("GET /admin/analytics/daily?date=...&metric_name=top_movers -> filtrira po metrici")
    void getDaily_withMetricNameFilter_passesFilterToService() throws Exception {
        AnalyticsDailyDto row = new AnalyticsDailyDto(
                1L, LocalDate.of(2026, 5, 26), "top_movers",
                Map.of("symbol", "AAPL"),
                new BigDecimal("12.34"),
                LocalDateTime.of(2026, 5, 26, 4, 0));

        when(analyticsService.findDaily(eq(LocalDate.of(2026, 5, 26)), eq("top_movers")))
                .thenReturn(List.of(row));

        mockMvc.perform(get("/admin/analytics/daily?date=2026-05-26&metric_name=top_movers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].metricName").value("top_movers"))
                .andExpect(jsonPath("$[0].value").value(12.34));

        verify(analyticsService).findDaily(LocalDate.of(2026, 5, 26), "top_movers");
    }

    @Test
    @DisplayName("GET /admin/analytics/daily bez date -> 400 (missing required param)")
    void getDaily_missingDateParam_returns400() throws Exception {
        mockMvc.perform(get("/admin/analytics/daily"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /admin/analytics/daily sa praznim rezultatima -> 200 + []")
    void getDaily_noResults_returnsEmptyArray() throws Exception {
        when(analyticsService.findDaily(eq(LocalDate.of(2099, 1, 1)), isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/admin/analytics/daily?date=2099-01-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("DTO se serijalizuje kao JSON sa map dimensions (sanity)")
    void dtoSerialization_dimensionsAsObject_notString() throws Exception {
        AnalyticsDailyDto row = new AnalyticsDailyDto(
                10L, LocalDate.of(2026, 5, 26), "klijent_activity",
                Map.of("client_count", 42, "region", "RS"),
                new BigDecimal("1.0"),
                LocalDateTime.of(2026, 5, 26, 4, 0));

        String json = objectMapper.writeValueAsString(row);
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

        // dimensions mora biti vraceno kao nested JSON objekat, ne kao string
        Object dims = parsed.get("dimensions");
        org.assertj.core.api.Assertions.assertThat(dims).isInstanceOf(Map.class);
    }
}
