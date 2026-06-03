package rs.raf.banka2_bek.exchange.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2_bek.auth.config.GlobalExceptionHandler;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.exchange.dto.CalculateExchangeResponseDto;
import rs.raf.banka2_bek.exchange.dto.ExchangeRateDto;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST-exchange-1: Glavni HTTP testovi za {@link ExchangeController} su ranije bili
 * zakomentarisani ("rad u toku") — sada re-enabled kao deterministicki standalone
 * MockMvc test (bez {@code @WebMvcTest} security-slice-a koji je verovatno bio uzrok
 * nestabilnosti). {@link GlobalExceptionHandler} se eksplicitno wire-uje da se pokrije
 * error-contract (nepoznata valuta → 400 preko catch-all {@code handleRuntimeException}).
 *
 * <p>Napomena: {@code @Positive} validacija na {@code amount} se NE testira ovde —
 * metod-validacija zahteva {@code MethodValidationPostProcessor} koji standalone setup
 * ne primenjuje (pokriveno na servisnom/web-MVC nivou drugde).
 */
@ExtendWith(MockitoExtension.class)
class ExchangeControllerIntegrationTest {

    @Mock
    private ExchangeService exchangeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ExchangeController controller = new ExchangeController(exchangeService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldReturnExchangeRates() throws Exception {
        List<ExchangeRateDto> mockRates = List.of(
                new ExchangeRateDto("RSD", 1.0),
                new ExchangeRateDto("EUR", 0.008521),
                new ExchangeRateDto("USD", 0.009772)
        );

        when(exchangeService.getAllRates()).thenReturn(mockRates);

        mockMvc.perform(get("/exchange-rates"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$[0].currency").value("RSD"))
                .andExpect(jsonPath("$[0].rate").value(1.0))
                .andExpect(jsonPath("$[1].currency").value("EUR"))
                .andExpect(jsonPath("$[1].rate").value(0.008521))
                .andExpect(jsonPath("$[2].currency").value("USD"))
                .andExpect(jsonPath("$[2].rate").value(0.009772));
    }

    // ===================== GET /exchange/calculate =====================

    @Test
    void calculate_rsdToEur_returns200() throws Exception {
        when(exchangeService.calculateCross(1000.0, "RSD", "EUR"))
                .thenReturn(new CalculateExchangeResponseDto(8.45, 0.00845, "RSD", "EUR"));

        mockMvc.perform(get("/exchange/calculate")
                        .param("amount", "1000")
                        .param("toCurrency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.convertedAmount").value(8.45))
                .andExpect(jsonPath("$.fromCurrency").value("RSD"))
                .andExpect(jsonPath("$.toCurrency").value("EUR"));
    }

    @Test
    void calculate_eurToUsd_returns200() throws Exception {
        when(exchangeService.calculateCross(100.0, "EUR", "USD"))
                .thenReturn(new CalculateExchangeResponseDto(95.23, 0.9523, "EUR", "USD"));

        mockMvc.perform(get("/exchange/calculate")
                        .param("amount", "100")
                        .param("fromCurrency", "EUR")
                        .param("toCurrency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.convertedAmount").value(95.23))
                .andExpect(jsonPath("$.fromCurrency").value("EUR"))
                .andExpect(jsonPath("$.toCurrency").value("USD"));
    }

    @Test
    void calculate_missingAmount_returns400() throws Exception {
        // amount je required @RequestParam → MissingServletRequestParameterException → 400.
        mockMvc.perform(get("/exchange/calculate")
                        .param("toCurrency", "EUR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void calculate_missingToCurrency_returns400() throws Exception {
        mockMvc.perform(get("/exchange/calculate")
                        .param("amount", "100"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void calculate_defaultFromCurrencyIsRsd() throws Exception {
        when(exchangeService.calculateCross(100.0, "RSD", "EUR"))
                .thenReturn(new CalculateExchangeResponseDto(0.845, 0.00845, "RSD", "EUR"));

        // Ne saljemo fromCurrency — treba da bude RSD po defaultu.
        mockMvc.perform(get("/exchange/calculate")
                        .param("amount", "100")
                        .param("toCurrency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromCurrency").value("RSD"));
    }

    @Test
    void calculate_unsupportedCurrency_returns400() throws Exception {
        // Servis baca Runtime.. → GlobalExceptionHandler.handleRuntimeException → 400.
        when(exchangeService.calculateCross(anyDouble(), eq("RSD"), eq("XYZ")))
                .thenThrow(new RuntimeException("Currency not supported: XYZ"));

        mockMvc.perform(get("/exchange/calculate")
                        .param("amount", "100")
                        .param("toCurrency", "XYZ"))
                .andExpect(status().isBadRequest());
    }
}
