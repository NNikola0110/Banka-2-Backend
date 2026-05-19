package rs.raf.trading.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.order.exception.UnsupportedCurrencyException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test {@link BankTradingAccountResolver} — money-seam adaptacija monolitnog
 * testa (faza 2c). Monolit je citao bankin trading racun iz
 * {@code AccountRepository}; trading-service ga razresava preko banka-core
 * internog seam-a ({@link BankaCoreClient#getBankTradingAccount}). Asercija se
 * pomera sa "repo upit" na "BankaCoreClient poziv sa ispravnom valutom".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankTradingAccountResolver")
class BankTradingAccountResolverTest {

    @Mock
    private BankaCoreClient bankaCoreClient;

    @InjectMocks
    private BankTradingAccountResolver resolver;

    private InternalAccountDto bankAccount(Long id, String currency) {
        return new InternalAccountDto(id, "999000000000000" + id, "Banka 2",
                new BigDecimal("1000000.00"), new BigDecimal("1000000.00"),
                BigDecimal.ZERO, currency, "ACTIVE", null, null, "BANK_TRADING");
    }

    @Test
    @DisplayName("resolve vraca bankin trading racun za podrzane valute (RSD, USD, EUR)")
    void resolve_returnsAccountForSupportedCurrency() {
        InternalAccountDto rsd = bankAccount(1L, "RSD");
        InternalAccountDto usd = bankAccount(2L, "USD");
        InternalAccountDto eur = bankAccount(3L, "EUR");

        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(rsd);
        when(bankaCoreClient.getBankTradingAccount("USD")).thenReturn(usd);
        when(bankaCoreClient.getBankTradingAccount("EUR")).thenReturn(eur);

        assertThat(resolver.resolve("RSD")).isSameAs(rsd);
        assertThat(resolver.resolve("USD")).isSameAs(usd);
        assertThat(resolver.resolve("EUR")).isSameAs(eur);

        verify(bankaCoreClient).getBankTradingAccount("RSD");
        verify(bankaCoreClient).getBankTradingAccount("USD");
        verify(bankaCoreClient).getBankTradingAccount("EUR");
    }

    @Test
    @DisplayName("resolve baca UnsupportedCurrencyException kada banka-core vrati 404 za valutu")
    void resolve_throwsUnsupportedCurrencyException_forUnknownCurrency() {
        when(bankaCoreClient.getBankTradingAccount("JPY"))
                .thenThrow(new BankaCoreClientException(404, "banka-core 404"));

        assertThatThrownBy(() -> resolver.resolve("JPY"))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasMessageContaining("JPY");
    }

    @Test
    @DisplayName("resolve vraca racun koji banka-core seam vrati")
    void resolve_returnsAccountFromSeam() {
        InternalAccountDto first = bankAccount(7L, "RSD");

        when(bankaCoreClient.getBankTradingAccount("RSD")).thenReturn(first);

        InternalAccountDto result = resolver.resolve("RSD");

        assertThat(result).isSameAs(first);
        assertThat(result.accountCategory()).isEqualTo("BANK_TRADING");
    }
}
