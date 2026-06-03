package rs.raf.banka2_bek.interbank.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.exchange.CurrencyConversionService;
import rs.raf.banka2_bek.exchange.CurrencyConversionService.ConversionResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InterbankFxService")
class InterbankFxServiceTest {

    @Mock
    private CurrencyConversionService currencyConversionService;

    @InjectMocks
    private InterbankFxService fxService;

    @Test
    @DisplayName("Inbound settlement same-currency: rate=1, fee=0, recipient = amount (regression guard)")
    void quoteInboundSettlement_sameCurrency_noFeeNoConversion() {
        InterbankFxService.InterbankFxQuote quote = fxService.quoteInboundSettlement(
                new BigDecimal("250"), "RSD", "RSD");

        assertThat(quote.sourceCurrency()).isEqualTo(quote.targetCurrency());
        assertThat(quote.commission()).isEqualByComparingTo("0");
        assertThat(quote.targetAmount()).isEqualByComparingTo("250");
        assertThat(quote.midRate()).isEqualByComparingTo("1");
        assertThat(quote.effectiveRate()).isEqualByComparingTo("1");
        verify(currencyConversionService, never()).convertForPurchase(
                any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Inbound settlement cross-currency: Banka B konvertuje mid-rate, naplacuje 0.5% proviziju; recipient = converted − fee")
    void quoteInboundSettlement_crossCurrency_convertsMinusCommission() {
        // 117000 RSD → mid-rate 0.008547 → 999.9990 EUR; fee = 0.5% = 5.0000;
        // recipient credit = 994.9990 EUR ("Krajnja vrednost").
        when(currencyConversionService.convertForPurchase(
                new BigDecimal("117000"), "RSD", "EUR", false))
                .thenReturn(new ConversionResult(
                        new BigDecimal("999.9990"),
                        BigDecimal.ZERO,
                        new BigDecimal("0.008547"),
                        new BigDecimal("0.008547")));

        InterbankFxService.InterbankFxQuote quote = fxService.quoteInboundSettlement(
                new BigDecimal("117000"), "RSD", "EUR");

        assertThat(quote.sourceCurrency()).isNotEqualTo(quote.targetCurrency());
        assertThat(quote.sourceAmount()).isEqualByComparingTo("117000");
        assertThat(quote.commission()).isEqualByComparingTo("5.0000");
        assertThat(quote.targetAmount()).isEqualByComparingTo("994.9990");
        assertThat(quote.midRate()).isEqualByComparingTo("0.008547");
        assertThat(quote.sourceCurrency()).isEqualTo("RSD");
        assertThat(quote.targetCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("Inbound settlement: negativan iznos baca IllegalArgumentException")
    void quoteInboundSettlement_negativeAmount_throws() {
        assertThatThrownBy(() -> fxService.quoteInboundSettlement(
                new BigDecimal("-1"), "RSD", "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("Inbound settlement: zero iznos baca IllegalArgumentException")
    void quoteInboundSettlement_zeroAmount_throws() {
        assertThatThrownBy(() -> fxService.quoteInboundSettlement(
                BigDecimal.ZERO, "USD", "RSD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("Inbound settlement: null source currency baca IllegalArgumentException")
    void quoteInboundSettlement_nullSourceCurrency_throws() {
        assertThatThrownBy(() -> fxService.quoteInboundSettlement(
                new BigDecimal("100"), null, "RSD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Currency code se vraca uppercase u quote-u")
    void quoteInboundSettlement_returnsUppercaseCurrencyCodes() {
        InterbankFxService.InterbankFxQuote quote = fxService.quoteInboundSettlement(
                new BigDecimal("100"), "rsd", "rsd");

        assertThat(quote.sourceCurrency()).isEqualTo("RSD");
        assertThat(quote.targetCurrency()).isEqualTo("RSD");
    }

    // Mockito helpers (lokalni jer su any() iz org.mockito.ArgumentMatchers)
    private static <T> T any() { return org.mockito.ArgumentMatchers.any(); }
    private static boolean anyBoolean() { return org.mockito.ArgumentMatchers.anyBoolean(); }
}
