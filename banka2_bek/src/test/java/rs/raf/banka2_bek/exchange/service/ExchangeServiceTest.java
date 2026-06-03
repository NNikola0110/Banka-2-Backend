package rs.raf.banka2_bek.exchange.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.exchange.dto.CalculateExchangeResponseDto;
import rs.raf.banka2_bek.exchange.dto.ExchangeRateDto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private ExchangeService exchangeService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(exchangeService, "apiKey", "test-key");
        ReflectionTestUtils.setField(exchangeService, "apiUrl", "https://data.fixer.io/api/latest");
    }
    private void mockRates() {
        Map<String, Object> rates = new HashMap<>();
        rates.put("RSD", 117.35);
        rates.put("EUR", 1.0);
        rates.put("USD", 1.15);
        rates.put("CHF", 0.91);
        rates.put("GBP", 0.87);
        rates.put("JPY", 183.02);
        rates.put("CAD", 1.58);
        rates.put("AUD", 1.65);

        Map<String, Object> body = new HashMap<>();
        body.put("rates", rates);

        String expectedUrl =
                "https://data.fixer.io/api/latest?access_key=test-key&symbols=RSD,EUR,CHF,USD,GBP,JPY,CAD,AUD";

        when(restTemplate.getForEntity(expectedUrl, Map.class))
                .thenReturn(ResponseEntity.ok(body));
    }


    @Test
    void shouldReturnAllExchangeRates() {
        Map<String, Object> rates = new HashMap<>();
        rates.put("RSD", 117.35);
        rates.put("EUR", 1.0);
        rates.put("USD", 1.15);
        rates.put("CHF", 0.91);
        rates.put("GBP", 0.87);
        rates.put("JPY", 183.02);
        rates.put("CAD", 1.58);
        rates.put("AUD", 1.65);

        Map<String, Object> body = new HashMap<>();
        body.put("rates", rates);

        ResponseEntity<Map> responseEntity = ResponseEntity.ok(body);

        String expectedUrl =
                "https://data.fixer.io/api/latest?access_key=test-key&symbols=RSD,EUR,CHF,USD,GBP,JPY,CAD,AUD";

        when(restTemplate.getForEntity(expectedUrl, Map.class)).thenReturn(responseEntity);

        List<ExchangeRateDto> result = exchangeService.getAllRates();

        assertNotNull(result);
        assertEquals(8, result.size());

        ExchangeRateDto rsd = result.stream()
                .filter(r -> r.getCurrency().equals("RSD"))
                .findFirst()
                .orElse(null);

        ExchangeRateDto eur = result.stream()
                .filter(r -> r.getCurrency().equals("EUR"))
                .findFirst()
                .orElse(null);

        ExchangeRateDto usd = result.stream()
                .filter(r -> r.getCurrency().equals("USD"))
                .findFirst()
                .orElse(null);

        assertNotNull(rsd);
        assertEquals(1.0, rsd.getRate());

        assertNotNull(eur);
        assertEquals(0.00852, eur.getRate(), 0.00001);

        assertNotNull(usd);
        assertEquals(0.0098, usd.getRate(), 0.0001);
    }

    @Test
    void shouldReturnFallbackRatesWhenBodyIsNull() {
        String expectedUrl =
                "https://data.fixer.io/api/latest?access_key=test-key&symbols=RSD,EUR,CHF,USD,GBP,JPY,CAD,AUD";

        when(restTemplate.getForEntity(expectedUrl, Map.class))
                .thenReturn(ResponseEntity.ok(null));

        List<ExchangeRateDto> result = exchangeService.getAllRates();

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(8, result.size());
    }

    @Test
    void shouldThrowExceptionWhenRsdRateMissing() {
        Map<String, Object> rates = new HashMap<>();
        rates.put("EUR", 1.0);
        rates.put("USD", 1.15);

        Map<String, Object> body = new HashMap<>();
        body.put("rates", rates);

        String expectedUrl =
                "https://data.fixer.io/api/latest?access_key=test-key&symbols=RSD,EUR,CHF,USD,GBP,JPY,CAD,AUD";

        when(restTemplate.getForEntity(expectedUrl, Map.class))
                .thenReturn(ResponseEntity.ok(body));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> exchangeService.getAllRates());

        assertEquals("RSD rate not found.", ex.getMessage());
    }
    @Test
    void shouldReturnFallbackRatesWhenRatesMissing() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);

        String expectedUrl =
                "https://data.fixer.io/api/latest?access_key=test-key&symbols=RSD,EUR,CHF,USD,GBP,JPY,CAD,AUD";

        when(restTemplate.getForEntity(expectedUrl, Map.class))
                .thenReturn(ResponseEntity.ok(body));

        List<ExchangeRateDto> result = exchangeService.getAllRates();

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(8, result.size());
    }

    //======================================
    @Test
    void calculate_rsdToRsd_returnsSameAmount() {
        CalculateExchangeResponseDto result = exchangeService.calculate(100.0,"RSD");
        assertEquals(100.0, result.getConvertedAmount());
        assertEquals(1.0, result.getExchangeRate());
    }

    @Test
    void calculate_rsdToEur_appliesSellRateAndCommission() {
        mockRates();
        // eurRate = 1/117.35 = 0.008522
        // sellRate = 0.008522 * 1.02 = 0.008692
        // converted = 1000 * 0.008692 * 0.995
        double eurRate = 1.0 / 117.35;
        double sellRate = eurRate * 1.02;
        double expected = Math.round((1000.0 * sellRate * 0.995) * 10000.0) / 10000.0;

        CalculateExchangeResponseDto result = exchangeService.calculate(1000.0, "EUR");

        assertEquals(expected, result.getConvertedAmount(), 0.0001);
    }

    @Test
    void calculate_rsdToUsd_appliesSellRateAndCommission() {
        mockRates();
        double usdRate = 1.15 / 117.35;
        double sellRate = usdRate * 1.02;
        double expected = Math.round((500.0 * sellRate * 0.995) * 10000.0) / 10000.0;

        CalculateExchangeResponseDto result = exchangeService.calculate(500.0, "USD");

        assertEquals(expected, result.getConvertedAmount(), 0.0001);
    }

    @Test
    void calculate_unsupportedCurrency_throwsException() {
        mockRates();
        // R1 446: nepostojeca valuta je NOT_FOUND (EntityNotFoundException → 404), ne 400.
        jakarta.persistence.EntityNotFoundException ex = assertThrows(
                jakarta.persistence.EntityNotFoundException.class,
                () -> exchangeService.calculate(100.0, "XYZ"));
        assertEquals("Currency not supported: XYZ", ex.getMessage());
    }

// ===================== calculateCross =====================

    @Test
    void calculateCross_fromRsd_delegatesToCalculate() {
        mockRates();
        CalculateExchangeResponseDto result = exchangeService.calculateCross(1000.0, "RSD", "EUR");
        assertEquals("RSD", result.getFromCurrency());
        assertEquals("EUR", result.getToCurrency());
        assertTrue(result.getConvertedAmount() > 0);
    }

    // ovaj opet
    @Test
    void calculateCross_eurToRsd_returnsCorrectAmount() {
        mockRates();


        double eurRate = Math.round((1.0 / 117.35) * 1_000_000.0) / 1_000_000.0;


        double sellRate = Math.round((1.0 / eurRate) * 1.02 * 1_000_000.0) / 1_000_000.0;

        double commission = 0.005;
        double expected = Math.round((100.0 * sellRate * (1 - commission)) * 10000.0) / 10000.0;

        CalculateExchangeResponseDto result = exchangeService.calculateCross(100.0, "EUR", "RSD");

        assertEquals(expected, result.getConvertedAmount(), 0.0001);
        assertEquals("EUR", result.getFromCurrency());
        assertEquals("RSD", result.getToCurrency());
    }

    @Test
    void calculateCross_eurToUsd_goesThroughRsd() {
        mockRates();
        CalculateExchangeResponseDto result = exchangeService.calculateCross(100.0, "EUR", "USD");
        assertEquals("EUR", result.getFromCurrency());
        assertEquals("USD", result.getToCurrency());

        assertTrue(result.getConvertedAmount() > 90.0);
        assertTrue(result.getConvertedAmount() < 120.0);
    }

    @Test
    void calculateCross_fromUnsupportedCurrency_throwsException() {
        mockRates();
        assertThrows(RuntimeException.class,
                () -> exchangeService.calculateCross(100.0, "XYZ", "EUR"));
    }

    @Test
    void calculateCross_toUnsupportedCurrency_throwsException() {
        mockRates();
        assertThrows(RuntimeException.class,
                () -> exchangeService.calculateCross(100.0, "EUR", "XYZ"));
    }

    // ===================== R1-654: access_key masking u log-u =====================

    @Test
    void maskApiKey_replacesAccessKeyValueInTheMiddle_keepsRestOfUrl() {
        String url = "https://data.fixer.io/api/latest?access_key=super-secret-123"
                + "&symbols=RSD,EUR";
        String masked = ExchangeService.maskApiKey(url);
        assertEquals("https://data.fixer.io/api/latest?access_key=***&symbols=RSD,EUR", masked);
        assertFalse(masked.contains("super-secret-123"), "kljuc ne sme procuriti u log-u");
    }

    @Test
    void maskApiKey_replacesAccessKeyValueAtEnd() {
        String url = "https://data.fixer.io/api/latest?symbols=RSD&access_key=tail-secret";
        String masked = ExchangeService.maskApiKey(url);
        assertEquals("https://data.fixer.io/api/latest?symbols=RSD&access_key=***", masked);
        assertFalse(masked.contains("tail-secret"));
    }

    @Test
    void maskApiKey_nullSafe_andNoOpWhenNoKey() {
        assertEquals(null, ExchangeService.maskApiKey(null));
        assertEquals("https://example.com/latest?symbols=RSD",
                ExchangeService.maskApiKey("https://example.com/latest?symbols=RSD"));
    }

    @Test
    void r1_672_mapsFixerDateOntoRates() {
        Map<String, Object> rates = new HashMap<>();
        rates.put("RSD", 117.35);
        rates.put("EUR", 1.0);
        rates.put("USD", 1.15);
        Map<String, Object> body = new HashMap<>();
        body.put("rates", rates);
        body.put("date", "2026-05-30"); // R1-672: pravi Fixer datum vazenja kursa

        String expectedUrl =
                "https://data.fixer.io/api/latest?access_key=test-key&symbols=RSD,EUR,CHF,USD,GBP,JPY,CAD,AUD";
        when(restTemplate.getForEntity(expectedUrl, Map.class)).thenReturn(ResponseEntity.ok(body));

        List<ExchangeRateDto> result = exchangeService.getAllRates();

        // svaki DTO nosi PRAVI Fixer datum, ne LocalDate.now() ctor default
        assertTrue(result.stream().allMatch(r -> "2026-05-30".equals(r.getDate())),
                "R1-672: svi kursevi moraju nositi Fixer 'date' polje");
    }

    @Test
    void r1_674_negativeCache_fallsBackGracefullyOnApiFailure() {
        // API odmah pada (nema prethodnog kesa) — negativni kes kesira NBS fallback
        // sa svezim timestamp-om, pa naredni poziv NE udara opet u API (thundering-herd
        // zastita). Verifikujemo: vraca neprazan fallback, i drugi poziv ne pravi novi
        // API poziv (cache hit).
        when(restTemplate.getForEntity(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("down"));

        List<ExchangeRateDto> firstCall = exchangeService.getAllRates();
        assertNotNull(firstCall);
        assertFalse(firstCall.isEmpty(), "na padu API-ja vraca NBS fallback, ne praznu listu");

        // Drugi poziv: negativni kes je svez → ne sme ponovo zvati API.
        List<ExchangeRateDto> secondCall = exchangeService.getAllRates();
        assertEquals(firstCall.size(), secondCall.size());
        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.times(1))
                .getForEntity(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(Map.class));
    }
}