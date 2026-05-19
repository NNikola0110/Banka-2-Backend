package rs.raf.trading.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.FxRateDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.order.exception.UnsupportedCurrencyException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test {@link CurrencyConversionService} — money-seam adaptacija monolitnog
 * testa (faza 2c). Monolit je citao srednje kurseve iz lokalnog
 * {@code ExchangeService}; trading-service ih dobija preko banka-core internog
 * seam-a ({@link BankaCoreClient#getFxRates()}). Asercija se pomera sa
 * "ExchangeService poziv" na "BankaCoreClient poziv".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CurrencyConversionService")
class CurrencyConversionServiceTest {

    @Mock
    private BankaCoreClient bankaCoreClient;

    @InjectMocks
    private CurrencyConversionService service;

    // Kursevi: "koliko target jedinica za 1 RSD"
    // 1 RSD = 0.009090 USD  -> 1 USD = 110 RSD
    private List<FxRateDto> sampleRates() {
        return List.of(
                new FxRateDto("RSD", 1.0),
                new FxRateDto("USD", 0.009090),
                new FxRateDto("EUR", 0.008547)
        );
    }

    @Test
    @DisplayName("convert vraca isti iznos kada su valute iste i ne poziva banka-core")
    void convert_returnsAmount_whenSameCurrency() {
        BigDecimal amount = new BigDecimal("123.4567");

        BigDecimal result = service.convert(amount, "USD", "USD");

        assertThat(result).isSameAs(amount);
        verify(bankaCoreClient, never()).getFxRates();
    }

    @Test
    @DisplayName("convert mnozi iznos sa kursom za razlicite valute")
    void convert_multipliesAmountByRate_forDifferentCurrencies() {
        when(bankaCoreClient.getFxRates()).thenReturn(sampleRates());

        BigDecimal result = service.convert(new BigDecimal("100"), "USD", "RSD");

        assertThat(result.scale()).isEqualTo(4);
        assertThat(result).isBetween(new BigDecimal("11000.0000"), new BigDecimal("11002.0000"));
    }

    @Test
    @DisplayName("convert zaokruzuje rezultat na 4 decimale (HALF_UP)")
    void convert_roundsToFourDecimals() {
        when(bankaCoreClient.getFxRates()).thenReturn(sampleRates());

        BigDecimal result = service.convert(new BigDecimal("1"), "USD", "RSD");

        assertThat(result.scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("getRate vraca ONE za istu valutu i ne poziva banka-core")
    void getRate_returnsOne_forSameCurrency() {
        BigDecimal rate = service.getRate("EUR", "EUR");

        assertThat(rate).isEqualByComparingTo(BigDecimal.ONE);
        verify(bankaCoreClient, never()).getFxRates();
    }

    @Test
    @DisplayName("getRate delegira na BankaCoreClient za razlicite valute")
    void getRate_delegatesToBankaCore_forDifferentCurrencies() {
        when(bankaCoreClient.getFxRates()).thenReturn(sampleRates());

        BigDecimal rate = service.getRate("USD", "RSD");

        assertThat(rate).isBetween(new BigDecimal("109.0"), new BigDecimal("111.0"));
        verify(bankaCoreClient).getFxRates();
    }

    @Test
    @DisplayName("getRate baca UnsupportedCurrencyException kada banka-core ne zna valutu")
    void getRate_throwsUnsupportedCurrencyException_forUnknownCurrency() {
        when(bankaCoreClient.getFxRates()).thenReturn(sampleRates());

        assertThatThrownBy(() -> service.getRate("JPY", "RSD"))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasMessageContaining("JPY");
    }

    @Test
    @DisplayName("convertForPurchase vraca amount + 0 komisiju za iste valute")
    void convertForPurchase_sameCurrency_zeroCommission() {
        CurrencyConversionService.ConversionResult result =
                service.convertForPurchase(new BigDecimal("100"), "USD", "USD", true);

        assertThat(result.amount()).isEqualByComparingTo("100");
        assertThat(result.commission()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.midRate()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("convertForPurchase bez FX komisije vraca mid-rate iznos i 0 komisiju")
    void convertForPurchase_noCommission_returnsMidRateAmount() {
        when(bankaCoreClient.getFxRates()).thenReturn(sampleRates());

        CurrencyConversionService.ConversionResult result =
                service.convertForPurchase(new BigDecimal("100"), "USD", "RSD", false);

        assertThat(result.commission()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.amount()).isBetween(new BigDecimal("11000.0000"), new BigDecimal("11002.0000"));
    }

    @Test
    @DisplayName("convertForPurchase sa FX komisijom dodaje FX marzu i iskazuje komisiju")
    void convertForPurchase_withCommission_addsFxMargin() {
        when(bankaCoreClient.getFxRates()).thenReturn(sampleRates());

        CurrencyConversionService.ConversionResult result =
                service.convertForPurchase(new BigDecimal("100"), "USD", "RSD", true);

        // gross > mid (FX marza 1%), commission = gross - mid > 0
        assertThat(result.commission().signum()).isPositive();
        assertThat(result.effectiveRate()).isGreaterThan(result.midRate());
    }
}
