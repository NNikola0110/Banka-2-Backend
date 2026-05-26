package rs.raf.trading.prediction;

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
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [W3-T2] Mockito web-layer test za {@link PricePredictionController}.
 *
 * <p>Servis ovde nema posebne logike — kontroler direktno zove repozitorijum,
 * mapira u DTO i vraca 200/404. Testovi pokrivaju oba slucaja + dva path
 * varianta (sa i bez "FOREX-" prefiksa u simbolu — npr. AAPL vs USDEUR).
 */
@ExtendWith(MockitoExtension.class)
class PricePredictionControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PricePredictionRepository repository;

    @InjectMocks
    private PricePredictionController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private PricePredictionEntity entity(String symbol) {
        return PricePredictionEntity.builder()
                .id(1L)
                .symbol(symbol)
                .predictionDate(LocalDate.of(2026, 5, 27))
                .predictedClose(new BigDecimal("152.50"))
                .lowerBound(new BigDecimal("148.20"))
                .upperBound(new BigDecimal("156.80"))
                .modelVersion("rf_v1")
                .computedAt(LocalDateTime.of(2026, 5, 26, 4, 0))
                .build();
    }

    @Test
    @DisplayName("GET /listings/AAPL/prediction -> 200 + DTO sa close + bounds")
    void getLatest_happyPath_returns200WithDto() throws Exception {
        when(repository.findFirstBySymbolOrderByComputedAtDescPredictionDateDesc("AAPL"))
                .thenReturn(Optional.of(entity("AAPL")));

        mockMvc.perform(get("/listings/AAPL/prediction"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.predictedClose").value(152.50))
                .andExpect(jsonPath("$.lowerBound").value(148.20))
                .andExpect(jsonPath("$.upperBound").value(156.80))
                .andExpect(jsonPath("$.modelVersion").value("rf_v1"));
    }

    @Test
    @DisplayName("GET /listings/UNKNOWN/prediction -> 404 (nema predikcije)")
    void getLatest_noPrediction_returns404() throws Exception {
        when(repository.findFirstBySymbolOrderByComputedAtDescPredictionDateDesc("UNKNOWN"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/listings/UNKNOWN/prediction"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET sa FOREX-style simbolom (slash u path-u ne dozvoljava — koristi se '-' separator)")
    void getLatest_forexStyleSymbol_works() throws Exception {
        when(repository.findFirstBySymbolOrderByComputedAtDescPredictionDateDesc("USDEUR"))
                .thenReturn(Optional.of(entity("USDEUR")));

        mockMvc.perform(get("/listings/USDEUR/prediction"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("USDEUR"));
    }
}
