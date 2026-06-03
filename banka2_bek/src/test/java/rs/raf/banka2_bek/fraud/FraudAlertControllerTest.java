package rs.raf.banka2_bek.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2_bek.auth.config.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [W3-T2] Mockito web-layer test za {@link FraudAlertController}.
 *
 * <p>{@code standaloneSetup} sa {@link GlobalExceptionHandler} da se validation
 * fail (npr. invalid status) mapira na 400 (kao u prod-u).
 */
@ExtendWith(MockitoExtension.class)
class FraudAlertControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private FraudAlertService service;

    @InjectMocks
    private FraudAlertController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private FraudAlertDto sample(Long id, String reviewStatus) {
        return new FraudAlertDto(
                id, 9000L + id,
                new BigDecimal("0.85"),
                "{\"amount_zscore\":3.1}",
                "iforest_v1",
                LocalDateTime.of(2026, 5, 26, 4, 0),
                null, reviewStatus, null);
    }

    private FraudAlertPageDto pageOf(FraudAlertDto... items) {
        List<FraudAlertDto> list = List.of(items);
        return new FraudAlertPageDto(list, list.size(), 1, 0, 50);
    }

    @Test
    @DisplayName("GET /admin/fraud-alerts -> 200 sa default filterima (onlyPending=true)")
    void list_defaultFilters_returnsPendingAlerts() throws Exception {
        FraudAlertPageDto page = pageOf(sample(1L, null), sample(2L, null));
        when(service.findAlerts(isNull(), isNull(), eq(true), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/admin/fraud-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].riskScore").value(0.85));

        verify(service).findAlerts(isNull(), isNull(), eq(true), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /admin/fraud-alerts sa svim filterima -> prosleđuje ih servisu")
    void list_withAllFilters_passesToService() throws Exception {
        FraudAlertPageDto page = pageOf(sample(1L, null));
        LocalDateTime since = LocalDateTime.of(2026, 5, 25, 0, 0);

        when(service.findAlerts(eq(since), eq(new BigDecimal("0.7")), eq(false), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/admin/fraud-alerts")
                        .param("since", "2026-05-25T00:00:00")
                        .param("min_risk", "0.7")
                        .param("only_pending", "false")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        verify(service).findAlerts(eq(since), eq(new BigDecimal("0.7")),
                eq(false), any(Pageable.class));
    }

    @Test
    @DisplayName("POST /admin/fraud-alerts/{id}/review sa validnim status -> 200 + DTO")
    void review_validStatus_returns200() throws Exception {
        FraudAlertDto reviewed = new FraudAlertDto(
                42L, 9042L, new BigDecimal("0.85"),
                "{\"amount_zscore\":3.1}", "iforest_v1",
                LocalDateTime.of(2026, 5, 26, 4, 0),
                "marko.petrovic@banka.rs | sumnja na karticno keseni",
                "confirmed",
                LocalDateTime.of(2026, 5, 26, 10, 0));
        when(service.reviewAlert(eq(42L), any(ReviewFraudAlertDto.class))).thenReturn(reviewed);

        String body = "{\"status\":\"confirmed\",\"note\":\"sumnja na karticno keseni\"}";

        mockMvc.perform(post("/admin/fraud-alerts/42/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("confirmed"))
                .andExpect(jsonPath("$.reviewedBy").value("marko.petrovic@banka.rs | sumnja na karticno keseni"));
    }

    @Test
    @DisplayName("POST .../review sa invalid status -> 400")
    void review_invalidStatus_returns400() throws Exception {
        String body = "{\"status\":\"nesto_drugo\"}";

        mockMvc.perform(post("/admin/fraud-alerts/42/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST .../review bez status -> 400 (NotBlank)")
    void review_missingStatus_returns400() throws Exception {
        String body = "{\"note\":\"samo komentar\"}";

        mockMvc.perform(post("/admin/fraud-alerts/42/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ── [P2-input-validation-1 / R1 539] paginacija validacija ──────────────

    @Test
    @DisplayName("GET /admin/fraud-alerts sa negativnim page -> 400 (ne 500)")
    void list_negativePage_returns400() throws Exception {
        mockMvc.perform(get("/admin/fraud-alerts").param("page", "-1"))
                .andExpect(status().isBadRequest());
        verify(service, org.mockito.Mockito.never())
                .findAlerts(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /admin/fraud-alerts sa size<1 -> 400")
    void list_zeroSize_returns400() throws Exception {
        mockMvc.perform(get("/admin/fraud-alerts").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /admin/fraud-alerts sa ogromnim size -> capped na MAX (nije 500)")
    void list_hugeSize_cappedNotError() throws Exception {
        FraudAlertPageDto page = pageOf(sample(1L, null));
        when(service.findAlerts(isNull(), isNull(), eq(true), any(Pageable.class)))
                .thenReturn(page);

        org.mockito.ArgumentCaptor<Pageable> captor =
                org.mockito.ArgumentCaptor.forClass(Pageable.class);

        mockMvc.perform(get("/admin/fraud-alerts").param("size", "1000000"))
                .andExpect(status().isOk());

        verify(service).findAlerts(isNull(), isNull(), eq(true), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPageSize())
                .isLessThanOrEqualTo(200);
    }
}
