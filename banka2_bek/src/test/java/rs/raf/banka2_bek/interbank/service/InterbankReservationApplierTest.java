package rs.raf.banka2_bek.interbank.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.CommitStockRequest;
import rs.raf.banka2.contracts.internal.CommitStockResponse;
import rs.raf.banka2.contracts.internal.ReleaseStockRequest;
import rs.raf.banka2.contracts.internal.ReleaseStockResponse;
import rs.raf.banka2.contracts.internal.ReserveStockRequest;
import rs.raf.banka2.contracts.internal.ReserveStockResponse;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.interbank.client.TradingServiceClientException;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit testovi za {@link InterbankReservationApplier}.
 *
 * <p>Hartijske noge ({@code reserveStock}/{@code commitStock}/{@code releaseStock})
 * su u fazi 2f prevezane na trading-service HTTP seam — testovi verifikuju da se
 * {@link TradingServiceInternalClient} pozove korektno i da se
 * {@link TradingServiceClientException} prevede u {@code InterbankProtocolException}.
 * Novcane noge ({@code reserveMonas}/{@code commitMonas}/{@code releaseMonas})
 * ostaju in-process JPA — verifikuje se da pogadjaju {@code AccountRepository}.
 */
@ExtendWith(MockitoExtension.class)
class InterbankReservationApplierTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TradingServiceInternalClient tradingServiceClient;

    private InterbankReservationApplier applier;

    @BeforeEach
    void setUp() {
        applier = new InterbankReservationApplier(accountRepository, tradingServiceClient);
    }

    // ─── stock noge (trading-service seam) ────────────────────────────────────

    @Test
    @DisplayName("reserveStock: delegira na trading-service klijent sa ReserveStockRequest")
    void reserveStock_delegatesToClient() {
        when(tradingServiceClient.reserveStock(eq("idem-1"), any(ReserveStockRequest.class)))
                .thenReturn(new ReserveStockResponse(1L, 7L, "AAPL", 10, 10));

        applier.reserveStock("idem-1", 42L, "CLIENT", "AAPL", 10);

        verify(tradingServiceClient).reserveStock("idem-1",
                new ReserveStockRequest(42L, "CLIENT", "AAPL", 10));
    }

    @Test
    @DisplayName("reserveStock: TradingServiceClientException → InterbankProtocolException")
    void reserveStock_clientError_translatedToProtocolException() {
        when(tradingServiceClient.reserveStock(any(), any(ReserveStockRequest.class)))
                .thenThrow(new TradingServiceClientException(409, "INSUFFICIENT_QUANTITY on listing 7"));

        assertThatThrownBy(() -> applier.reserveStock("idem-1", 42L, "CLIENT", "AAPL", 10))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class)
                .hasMessageContaining("INSUFFICIENT_QUANTITY");
    }

    @Test
    @DisplayName("commitStock: delegira na trading-service klijent sa CommitStockRequest")
    void commitStock_delegatesToClient() {
        when(tradingServiceClient.commitStock(eq("idem-2"), any(CommitStockRequest.class)))
                .thenReturn(new CommitStockResponse(1L, 7L, "AAPL", 30));

        applier.commitStock("idem-2", 42L, "CLIENT", "AAPL", 10, true);

        verify(tradingServiceClient).commitStock("idem-2",
                new CommitStockRequest(42L, "CLIENT", "AAPL", 10, true));
    }

    @Test
    @DisplayName("releaseStock: delegira na trading-service klijent sa ReleaseStockRequest")
    void releaseStock_delegatesToClient() {
        when(tradingServiceClient.releaseStock(eq("idem-3"), any(ReleaseStockRequest.class)))
                .thenReturn(new ReleaseStockResponse(1L, "AAPL", 0));

        applier.releaseStock("idem-3", 42L, "CLIENT", "AAPL", 5);

        verify(tradingServiceClient).releaseStock("idem-3",
                new ReleaseStockRequest(42L, "CLIENT", "AAPL", 5));
    }

    @Test
    @DisplayName("releaseStock: TradingServiceClientException → InterbankProtocolException")
    void releaseStock_clientError_translatedToProtocolException() {
        when(tradingServiceClient.releaseStock(any(), any(ReleaseStockRequest.class)))
                .thenThrow(new TradingServiceClientException(404, "NO_SUCH_ASSET"));

        assertThatThrownBy(() -> applier.releaseStock("idem-3", 42L, "CLIENT", "AAPL", 5))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class)
                .hasMessageContaining("NO_SUCH_ASSET");
    }

    // ─── monas noge (in-process JPA, accounts ostaju u banka-core) ────────────

    @Test
    @DisplayName("reserveMonas: smanjuje availableBalance, povecava reservedAmount")
    void reserveMonas_movesAvailableToReserved() {
        Account acct = account("222000001", new BigDecimal("1000"), new BigDecimal("1000"),
                BigDecimal.ZERO);
        when(accountRepository.findForUpdateByAccountNumber("222000001"))
                .thenReturn(Optional.of(acct));

        applier.reserveMonas("222000001", new BigDecimal("300"));

        assertThat(acct.getAvailableBalance()).isEqualByComparingTo("700");
        assertThat(acct.getReservedAmount()).isEqualByComparingTo("300");
        verify(accountRepository).save(acct);
    }

    @Test
    @DisplayName("reserveMonas: nedovoljna sredstva → InterbankProtocolException")
    void reserveMonas_insufficient_throws() {
        Account acct = account("222000001", new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ZERO);
        when(accountRepository.findForUpdateByAccountNumber("222000001"))
                .thenReturn(Optional.of(acct));

        assertThatThrownBy(() -> applier.reserveMonas("222000001", new BigDecimal("300")))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class)
                .hasMessageContaining("INSUFFICIENT_ASSET");
    }

    @Test
    @DisplayName("commitMonas debit=true → balance + availableBalance povecani")
    void commitMonas_debit_credits() {
        Account acct = account("222000001", new BigDecimal("500"), new BigDecimal("500"),
                BigDecimal.ZERO);
        when(accountRepository.findForUpdateByAccountNumber("222000001"))
                .thenReturn(Optional.of(acct));

        applier.commitMonas("222000001", new BigDecimal("200"), true);

        assertThat(acct.getBalance()).isEqualByComparingTo("700");
        assertThat(acct.getAvailableBalance()).isEqualByComparingTo("700");
    }

    private Account account(String number, BigDecimal balance, BigDecimal available,
                            BigDecimal reserved) {
        Account a = new Account();
        a.setAccountNumber(number);
        a.setBalance(balance);
        a.setAvailableBalance(available);
        a.setReservedAmount(reserved);
        return a;
    }
}
