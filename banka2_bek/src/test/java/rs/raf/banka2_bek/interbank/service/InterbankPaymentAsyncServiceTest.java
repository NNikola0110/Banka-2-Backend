package rs.raf.banka2_bek.interbank.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.interbank.model.InterbankTransaction;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;
import rs.raf.banka2_bek.interbank.protocol.Asset;
import rs.raf.banka2_bek.interbank.protocol.CurrencyCode;
import rs.raf.banka2_bek.interbank.protocol.ForeignBankId;
import rs.raf.banka2_bek.interbank.protocol.MonetaryAsset;
import rs.raf.banka2_bek.interbank.protocol.Posting;
import rs.raf.banka2_bek.interbank.protocol.Transaction;
import rs.raf.banka2_bek.interbank.protocol.TxAccount;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.payment.repository.PaymentRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-4: InterbankPaymentAsyncService.executeAsync must increment daily/monthly
 * spending on the sender account EXACTLY ONCE when an interbank payment COMMITs,
 * and NOT increment when it is rolled back / aborted (REJECTED).
 */
@ExtendWith(MockitoExtension.class)
class InterbankPaymentAsyncServiceTest {

    @Mock private TransactionExecutorService transactionExecutorService;
    @Mock private InterbankTransactionRepository interbankTransactionRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private AccountRepository accountRepository;

    private InterbankPaymentAsyncService service;

    private static final int MY_RN = 222;
    private static final String TX_ID = "tx-async-1";
    private static final String FROM_ACCOUNT = "222100001";
    private static final Long PAYMENT_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new InterbankPaymentAsyncService(
                transactionExecutorService, interbankTransactionRepository,
                paymentRepository, accountRepository);
    }

    @Test
    @DisplayName("executeAsync COMMITTED → payment COMPLETED + incrementSpending called once with amount (P1-4)")
    void executeAsync_committed_incrementsSpendingOnce() {
        Transaction tx = interbankTx();
        Payment processing = payment(PaymentStatus.PROCESSING);

        // Guard pre-check (line 42): payment is still PROCESSING.
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(processing));
        when(interbankTransactionRepository.findByTransactionRoutingNumberAndTransactionIdString(
                eq(MY_RN), eq(TX_ID)))
                .thenReturn(Optional.of(ibTx(InterbankTransactionStatus.COMMITTED)));
        when(accountRepository.incrementSpending(eq(FROM_ACCOUNT), eq(BigDecimal.valueOf(100))))
                .thenReturn(1);

        service.executeAsync(PAYMENT_ID, tx);

        assertThat(processing.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        // Exactly once, with the payment amount.
        verify(accountRepository).incrementSpending(eq(FROM_ACCOUNT), eq(BigDecimal.valueOf(100)));
        verify(paymentRepository).save(processing);
    }

    @Test
    @DisplayName("executeAsync ROLLED_BACK (not COMMITTED) → payment REJECTED + incrementSpending NEVER called (P1-4)")
    void executeAsync_rolledBack_doesNotIncrementSpending() {
        Transaction tx = interbankTx();
        Payment processing = payment(PaymentStatus.PROCESSING);

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(processing));
        when(interbankTransactionRepository.findByTransactionRoutingNumberAndTransactionIdString(
                eq(MY_RN), eq(TX_ID)))
                .thenReturn(Optional.of(ibTx(InterbankTransactionStatus.ROLLED_BACK)));

        service.executeAsync(PAYMENT_ID, tx);

        assertThat(processing.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        verify(accountRepository, never()).incrementSpending(any(), any());
    }

    @Test
    @DisplayName("executeAsync no InterbankTransaction record → REJECTED + incrementSpending NEVER called (P1-4)")
    void executeAsync_noTxRecord_doesNotIncrementSpending() {
        Transaction tx = interbankTx();
        Payment processing = payment(PaymentStatus.PROCESSING);

        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(processing));
        when(interbankTransactionRepository.findByTransactionRoutingNumberAndTransactionIdString(
                eq(MY_RN), eq(TX_ID)))
                .thenReturn(Optional.empty());

        service.executeAsync(PAYMENT_ID, tx);

        assertThat(processing.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        verify(accountRepository, never()).incrementSpending(any(), any());
    }

    @Test
    @DisplayName("executeAsync replay (payment already COMPLETED) → idempotent no-op, incrementSpending NEVER called (P1-4)")
    void executeAsync_replayAlreadyTerminal_noDoubleIncrement() {
        Transaction tx = interbankTx();
        // The guard at the top: payment is no longer PROCESSING → method returns
        // before any settlement/increment runs. This is what makes the increment
        // idempotent: a second invocation (Spring @Async re-hydration after restart)
        // cannot double-count spending.
        Payment committed = payment(PaymentStatus.COMPLETED);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(committed));

        service.executeAsync(PAYMENT_ID, tx);

        verify(accountRepository, never()).incrementSpending(any(), any());
        verify(transactionExecutorService, never()).execute(any());
        verify(interbankTransactionRepository, never())
                .findByTransactionRoutingNumberAndTransactionIdString(anyInt(), any());
    }

    @Test
    @DisplayName("NIT1: reconciler settles payment in the execute() gap → executeAsync re-reads "
            + "status and does NOT flip again or double-increment spending")
    void executeAsync_reconcilerWonRace_noDoubleIncrement() {
        // NIT1 double-increment race: the entry guard sees PROCESSING and lets us run
        // execute() (ibTx → COMMITTED). But execute() stalled long enough that the
        // Payment-reconciler ran in the gap, already flipping Payment → COMPLETED AND
        // incrementing spending. When executeAsync resumes and re-reads the Payment it
        // must see the non-PROCESSING status and bail — otherwise it flips again and
        // increments a SECOND time (the bug). First findById (entry guard) returns
        // PROCESSING; the second (post-execute) returns the reconciler-COMPLETED row.
        Transaction tx = interbankTx();
        Payment atEntry = payment(PaymentStatus.PROCESSING);
        Payment afterReconciler = payment(PaymentStatus.COMPLETED); // reconciler already settled it

        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(atEntry))        // entry guard
                .thenReturn(Optional.of(afterReconciler)); // post-execute re-read
        when(interbankTransactionRepository.findByTransactionRoutingNumberAndTransactionIdString(
                eq(MY_RN), eq(TX_ID)))
                .thenReturn(Optional.of(ibTx(InterbankTransactionStatus.COMMITTED)));

        service.executeAsync(PAYMENT_ID, tx);

        // The async path must NOT increment spending again (reconciler already did it once),
        // and must NOT re-save the payment status.
        verify(accountRepository, never()).incrementSpending(any(), any());
        verify(paymentRepository, never()).save(any());
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

    private Payment payment(PaymentStatus status) {
        Account from = new Account();
        from.setAccountNumber(FROM_ACCOUNT);
        return Payment.builder()
                .id(PAYMENT_ID)
                .fromAccount(from)
                .toAccountNumber("111900001")
                .amount(BigDecimal.valueOf(100))
                .status(status)
                .interbankTxIdString(TX_ID)
                .interbankTxRoutingNumber(MY_RN)
                .build();
    }

    private InterbankTransaction ibTx(InterbankTransactionStatus status) {
        InterbankTransaction ibt = new InterbankTransaction();
        ibt.setTransactionRoutingNumber(MY_RN);
        ibt.setTransactionIdString(TX_ID);
        ibt.setStatus(status);
        return ibt;
    }
}
