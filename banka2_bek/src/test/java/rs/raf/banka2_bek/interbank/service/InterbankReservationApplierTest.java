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
    @Mock private InterbankFxService interbankFxService;

    private InterbankReservationApplier applier;

    @BeforeEach
    void setUp() {
        // P0-3: dodat InterbankFxService + bankRegistrationNumber za cross-currency
        // recipient settlement. Postojeci testovi gadjaju samo same-currency/stock noge,
        // pa fxService mock ostaje nekoriscen (lenient nije potreban).
        applier = new InterbankReservationApplier(
                accountRepository, tradingServiceClient, interbankFxService, "22200022");
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
    @DisplayName("R1-681: commitMonas (sender debit) → balance i reservedAmount umanjeni, availableBalance netaknut")
    void commitMonas_senderDebit_consumesReservation() {
        // Posle reserveMonas: balance 500, availableBalance 300 (200 rezervisano),
        // reservedAmount 200. commitMonas trosi rezervaciju.
        Account acct = account("222000001", new BigDecimal("500"), new BigDecimal("300"),
                new BigDecimal("200"));
        when(accountRepository.findForUpdateByAccountNumber("222000001"))
                .thenReturn(Optional.of(acct));

        applier.commitMonas("222000001", new BigDecimal("200"));

        assertThat(acct.getBalance()).isEqualByComparingTo("300");        // 500 - 200
        assertThat(acct.getReservedAmount()).isEqualByComparingTo("0");   // 200 - 200
        assertThat(acct.getAvailableBalance()).isEqualByComparingTo("300"); // netaknut
        verify(accountRepository).save(acct);
    }

    @Test
    @DisplayName("TEST-accounts-4: invarijanta availableBalance == balance − reservedAmount drzi kroz reserve→commit ciklus")
    void monasCycle_holdsAvailableEqualsBalanceMinusReserved() {
        // TEST-accounts-4: postojeci testovi proveravaju reserveMonas i commitMonas
        // izolovano. Ovaj pinuje glavnu invarijantu novcane noge END-TO-END:
        // posle SVAKE operacije mora vaziti availableBalance == balance − reservedAmount.
        // Pocetno: balance 1000, available 1000, reserved 0 (invarijanta vazi: 1000-0=1000).
        Account acct = account("222000042", new BigDecimal("1000"), new BigDecimal("1000"),
                BigDecimal.ZERO);
        when(accountRepository.findForUpdateByAccountNumber("222000042"))
                .thenReturn(Optional.of(acct));
        assertInvariant(acct);

        // reserve 300 → balance 1000, available 700, reserved 300 (1000-300=700).
        applier.reserveMonas("222000042", new BigDecimal("300"));
        assertThat(acct.getAvailableBalance()).isEqualByComparingTo("700");
        assertThat(acct.getReservedAmount()).isEqualByComparingTo("300");
        assertInvariant(acct);

        // commit 300 (sender debit) → balance 700, reserved 0, available 700 (700-0=700).
        applier.commitMonas("222000042", new BigDecimal("300"));
        assertThat(acct.getBalance()).isEqualByComparingTo("700");
        assertThat(acct.getReservedAmount()).isEqualByComparingTo("0");
        assertThat(acct.getAvailableBalance()).isEqualByComparingTo("700");
        assertInvariant(acct);
    }

    @Test
    @DisplayName("TEST-accounts-4: reserve→release vraca racun u pocetno stanje, invarijanta odrzana")
    void monasReserveThenRelease_restoresAndHoldsInvariant() {
        Account acct = account("222000043", new BigDecimal("500"), new BigDecimal("500"),
                BigDecimal.ZERO);
        when(accountRepository.findForUpdateByAccountNumber("222000043"))
                .thenReturn(Optional.of(acct));

        applier.reserveMonas("222000043", new BigDecimal("120"));
        assertInvariant(acct);

        // release vraca rezervaciju: available natrag na 500, reserved natrag na 0.
        applier.releaseMonas("222000043", new BigDecimal("120"));
        assertThat(acct.getAvailableBalance()).isEqualByComparingTo("500");
        assertThat(acct.getReservedAmount()).isEqualByComparingTo("0");
        assertThat(acct.getBalance()).isEqualByComparingTo("500");
        assertInvariant(acct);
    }

    /** Pinuje invarijantu novcane noge: raspolozivo = ukupno − rezervisano. */
    private void assertInvariant(Account a) {
        assertThat(a.getAvailableBalance())
                .as("availableBalance == balance − reservedAmount")
                .isEqualByComparingTo(a.getBalance().subtract(a.getReservedAmount()));
    }

    // ─── N5: cross-currency recipient credit — conservation + FX pinning ───────

    @Test
    @DisplayName("N5: commitRecipientCredit same-currency → primalac +amount, bez pool kretanja (regression)")
    void commitRecipientCredit_sameCurrency_noPoolMovement() {
        Account recipient = accountWithCcy("222000007", "RSD",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(accountRepository.findForUpdateByAccountNumber("222000007"))
                .thenReturn(Optional.of(recipient));

        applier.commitRecipientCredit("222000007", new BigDecimal("250"), "RSD", null);

        assertThat(recipient.getBalance()).isEqualByComparingTo("250");
        assertThat(recipient.getAvailableBalance()).isEqualByComparingTo("250");
        // Same-currency ne dira bankine pool racune.
        verify(accountRepository, org.mockito.Mockito.never())
                .findBankAccountForUpdateByCurrency(any(), any());
    }

    @Test
    @DisplayName("N5: cross-currency → source-ccy pool prima 'amount' (konzervacija 0), target pool isplacuje, pinned rate")
    void commitRecipientCredit_crossCurrency_sourcePoolConserved_andRatePinned() {
        // Primalac EUR racun; wire stize u RSD. Pinned mid-rate = 0.008 (1 RSD = 0.008 EUR).
        BigDecimal amountRsd = new BigDecimal("117000");
        BigDecimal pinnedRate = new BigDecimal("0.008");
        BigDecimal converted = amountRsd.multiply(pinnedRate).setScale(4, java.math.RoundingMode.HALF_UP); // 936.0000
        BigDecimal fee = converted.multiply(new BigDecimal("0.005")).setScale(4, java.math.RoundingMode.HALF_UP); // 4.6800
        BigDecimal recipientCredit = converted.subtract(fee); // 931.3200

        Account recipient = accountWithCcy("222000008", "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        Account eurPool = accountWithCcy("222BANKEUR", "EUR",
                new BigDecimal("100000"), new BigDecimal("100000"), BigDecimal.ZERO);
        Account rsdPool = accountWithCcy("222BANKRSD", "RSD",
                new BigDecimal("0"), new BigDecimal("0"), BigDecimal.ZERO);

        when(accountRepository.findForUpdateByAccountNumber("222000008"))
                .thenReturn(Optional.of(recipient));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "EUR"))
                .thenReturn(Optional.of(eurPool));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "RSD"))
                .thenReturn(Optional.of(rsdPool));
        // Pinned-rate quote — koristi quoteInboundSettlementWithRate (mid-rate = pinnedRate).
        when(interbankFxService.quoteInboundSettlementWithRate(
                eq(amountRsd), eq("RSD"), eq("EUR"), eq(pinnedRate)))
                .thenReturn(new InterbankFxService.InterbankFxQuote(
                        amountRsd, recipientCredit, fee, pinnedRate, pinnedRate, "RSD", "EUR"));

        applier.commitRecipientCredit("222000008", amountRsd, "RSD", pinnedRate);

        // Primalac dobija "Krajnju vrednost" u EUR.
        assertThat(recipient.getBalance()).isEqualByComparingTo(recipientCredit);
        // Target (EUR) pool: −converted +fee = −recipientCredit.
        assertThat(eurPool.getBalance()).isEqualByComparingTo(
                new BigDecimal("100000").subtract(recipientCredit));
        // N5 KONZERVACIJA: source (RSD) pool prima TACNO 'amount'.
        assertThat(rsdPool.getBalance()).isEqualByComparingTo(amountRsd);
        assertThat(rsdPool.getAvailableBalance()).isEqualByComparingTo(amountRsd);
        // Pinned rate je iskoriscen (ne live quote).
        verify(interbankFxService).quoteInboundSettlementWithRate(amountRsd, "RSD", "EUR", pinnedRate);
        verify(interbankFxService, org.mockito.Mockito.never())
                .quoteInboundSettlement(any(), any(), any());
    }

    @Test
    @DisplayName("N5: cross-currency overdraft (target pool nema converted) → InterbankProtocolException, recipient ne kreditiran")
    void commitRecipientCredit_crossCurrency_overdraft_rejected() {
        BigDecimal amountRsd = new BigDecimal("117000");
        BigDecimal pinnedRate = new BigDecimal("0.008");
        BigDecimal converted = new BigDecimal("936.0000");
        BigDecimal fee = new BigDecimal("4.6800");
        BigDecimal recipientCredit = converted.subtract(fee);

        Account recipient = accountWithCcy("222000009", "EUR",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        // EUR pool ima samo 10 — nedovoljno za payout (936).
        Account eurPool = accountWithCcy("222BANKEUR", "EUR",
                new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO);

        when(accountRepository.findForUpdateByAccountNumber("222000009"))
                .thenReturn(Optional.of(recipient));
        when(accountRepository.findBankAccountForUpdateByCurrency("22200022", "EUR"))
                .thenReturn(Optional.of(eurPool));
        when(interbankFxService.quoteInboundSettlementWithRate(
                eq(amountRsd), eq("RSD"), eq("EUR"), eq(pinnedRate)))
                .thenReturn(new InterbankFxService.InterbankFxQuote(
                        amountRsd, recipientCredit, fee, pinnedRate, pinnedRate, "RSD", "EUR"));

        assertThatThrownBy(() -> applier.commitRecipientCredit("222000009", amountRsd, "RSD", pinnedRate))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class)
                .hasMessageContaining("INSUFFICIENT_ASSET");

        // Recipient NIJE kreditiran (overdraft odbijen pre kretanja).
        assertThat(recipient.getBalance()).isEqualByComparingTo("0");
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

    private Account accountWithCcy(String number, String ccyCode, BigDecimal balance,
                                   BigDecimal available, BigDecimal reserved) {
        Account a = account(number, balance, available, reserved);
        rs.raf.banka2_bek.currency.model.Currency ccy = new rs.raf.banka2_bek.currency.model.Currency();
        ccy.setCode(ccyCode);
        a.setCurrency(ccy);
        return a;
    }
}
