package rs.raf.banka2_bek.interbank.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.interbank.protocol.Asset;
import rs.raf.banka2_bek.interbank.protocol.CurrencyCode;
import rs.raf.banka2_bek.interbank.protocol.ForeignBankId;
import rs.raf.banka2_bek.interbank.protocol.MonetaryAsset;
import rs.raf.banka2_bek.interbank.protocol.Posting;
import rs.raf.banka2_bek.interbank.protocol.Transaction;
import rs.raf.banka2_bek.interbank.protocol.TxAccount;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration test za {@link InterbankPaymentAsyncService#executeAsync}: claim → execute → applyOutcome.
 *
 * <p>Sva Payment read/modify/write logika (status flip, P1-4 spending increment, NIT1 guard) je
 * preseljena u {@link InterbankPaymentSettlementService} (PRIMARY, read-write tx) zbog replica-lag
 * race-a — vidi {@code InterbankPaymentSettlementServiceTest} za pokrivenost te logike i regresioni
 * guard. Ovde proveravamo samo da AsyncService pravilno orkestrira i ne propagira 2PC abort.
 */
@ExtendWith(MockitoExtension.class)
class InterbankPaymentAsyncServiceTest {

    @Mock private TransactionExecutorService transactionExecutorService;
    @Mock private InterbankPaymentSettlementService settlementService;

    private InterbankPaymentAsyncService service;

    private static final int MY_RN = 222;
    private static final String TX_ID = "tx-async-1";
    private static final String FROM_ACCOUNT = "222100001";
    private static final Long PAYMENT_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new InterbankPaymentAsyncService(transactionExecutorService, settlementService);
    }

    @Test
    @DisplayName("claim=true → execute() runs THEN applyOutcome() mirrors the result")
    void executeAsync_claimed_runsExecuteThenApplyOutcome() {
        Transaction tx = interbankTx();
        when(settlementService.claimForSettlement(PAYMENT_ID)).thenReturn(true);

        service.executeAsync(PAYMENT_ID, tx);

        verify(transactionExecutorService).execute(tx);
        verify(settlementService).applyOutcome(PAYMENT_ID, tx);
    }

    @Test
    @DisplayName("claim=false (replay/terminal) → execute() and applyOutcome() are NOT called")
    void executeAsync_notClaimed_shortCircuits() {
        Transaction tx = interbankTx();
        when(settlementService.claimForSettlement(PAYMENT_ID)).thenReturn(false);

        service.executeAsync(PAYMENT_ID, tx);

        verify(transactionExecutorService, never()).execute(any());
        verify(settlementService, never()).applyOutcome(any(), any());
    }

    @Test
    @DisplayName("execute() THROWS the 2PC abort → swallowed (no propagation) and applyOutcome() STILL runs")
    void executeAsync_executeThrowsAbort_swallowedAndStillMirrors() {
        // 2PC atomicity contract: execute() THROWS on abort (NO vote / partner fail).
        // executeAsync's catch(Exception) must swallow it and still mirror the terminal
        // InterbankTransaction status to the Payment via applyOutcome — the thrown abort
        // must NOT propagate out of executeAsync.
        Transaction tx = interbankTx();
        when(settlementService.claimForSettlement(PAYMENT_ID)).thenReturn(true);
        org.mockito.Mockito.doThrow(
                        new rs.raf.banka2_bek.interbank.exception.InterbankExceptions
                                .InterbankTransactionAbortedException("Inter-bank 2PC aborted for transaction " + TX_ID))
                .when(transactionExecutorService).execute(any());

        service.executeAsync(PAYMENT_ID, tx);

        verify(settlementService).applyOutcome(eq(PAYMENT_ID), eq(tx));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Transaction interbankTx() {
        Asset monas = new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD));
        return new Transaction(List.of(
                new Posting(new TxAccount.Account(FROM_ACCOUNT), BigDecimal.valueOf(-100), monas),
                new Posting(new TxAccount.Account("111900001"), BigDecimal.valueOf(100), monas)
        ), new ForeignBankId(MY_RN, TX_ID), null, null, null, null);
    }
}
