package rs.raf.banka2_bek.exchange;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.exchange.dto.ExchangeRateDto;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CurrencyConversionService")
class CurrencyConversionServiceTest {

    @Mock
    private ExchangeService exchangeService;

    @InjectMocks
    private CurrencyConversionService service;

    // Kursevi: "koliko target jedinica za 1 RSD"
    // 1 RSD = 0.009090 USD  -> 1 USD = 110 RSD
    // 1 RSD = 0.008547 EUR  -> 1 EUR ~ 117 RSD
    private List<ExchangeRateDto> sampleRates() {
        return List.of(
                new ExchangeRateDto("RSD", 1.0),
                new ExchangeRateDto("USD", 0.009090),
                new ExchangeRateDto("EUR", 0.008547)
        );
    }

    @Test
    @DisplayName("convert vraca isti iznos kada su valute iste i ne poziva exchange servis")
    void convert_returnsAmount_whenSameCurrency() {
        BigDecimal amount = new BigDecimal("123.4567");

        BigDecimal result = service.convert(amount, "USD", "USD");

        assertThat(result).isSameAs(amount);
        verify(exchangeService, never()).getAllRates();
    }

    @Test
    @DisplayName("convert mnozi iznos sa kursom za razlicite valute")
    void convert_multipliesAmountByRate_forDifferentCurrencies() {
        when(exchangeService.getAllRates()).thenReturn(sampleRates());

        // 100 USD -> RSD; cross = rsdRate / usdRate = 1.0 / 0.009090 ~ 110.0110
        BigDecimal result = service.convert(new BigDecimal("100"), "USD", "RSD");

        // ocekujemo ~ 11001.1000 (4 decimale)
        assertThat(result.scale()).isEqualTo(4);
        assertThat(result).isBetween(new BigDecimal("11000.0000"), new BigDecimal("11002.0000"));
    }

    @Test
    @DisplayName("convert zaokruzuje rezultat na 4 decimale (HALF_UP)")
    void convert_roundsToFourDecimals() {
        when(exchangeService.getAllRates()).thenReturn(sampleRates());

        BigDecimal result = service.convert(new BigDecimal("1"), "USD", "RSD");

        assertThat(result.scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("getRate vraca ONE za istu valutu i ne poziva exchange servis")
    void getRate_returnsOne_forSameCurrency() {
        BigDecimal rate = service.getRate("EUR", "EUR");

        assertThat(rate).isEqualByComparingTo(BigDecimal.ONE);
        verify(exchangeService, never()).getAllRates();
    }

    @Test
    @DisplayName("getRate delegira na ExchangeService za razlicite valute")
    void getRate_delegatesToExchangeService_forDifferentCurrencies() {
        when(exchangeService.getAllRates()).thenReturn(sampleRates());

        BigDecimal rate = service.getRate("USD", "RSD");

        // 1 USD ~ 110 RSD
        assertThat(rate).isBetween(new BigDecimal("109.0"), new BigDecimal("111.0"));
        verify(exchangeService).getAllRates();
    }

    @Test
    @DisplayName("getRate baca UnsupportedCurrencyException kada exchange servis ne zna valutu")
    void getRate_throwsUnsupportedCurrencyException_forUnknownCurrency() {
        when(exchangeService.getAllRates()).thenReturn(sampleRates());

        assertThatThrownBy(() -> service.getRate("JPY", "RSD"))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasMessageContaining("JPY");
    }
}
