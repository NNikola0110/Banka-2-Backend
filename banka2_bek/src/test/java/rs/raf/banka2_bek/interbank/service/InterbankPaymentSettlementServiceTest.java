package rs.raf.banka2_bek.interbank.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Settlement logika medjubankarskog placanja: entry guard ({@code claimForSettlement}) i
 * mirror final status ({@code applyOutcome}). Pokriva P1-4 spending increment (tacno jednom na
 * COMMIT) i NIT1 double-increment guard.
 *
 * <p><b>Replica-lag regresioni guard:</b> {@link #settlementMethods_mustBeReadWrite_routedToPrimary()}
 * tvrdi da obe metode ostaju read-WRITE {@code @Transactional}. Ako bi neko stavio
 * {@code readOnly = true}, {@link rs.raf.banka2_bek.persistence.DataSourceConfig} bi ih rutirao na
 * read repliku → tek commit-ovan Payment (afterCommit dispatch) jos ne bi bio replikovan →
 * {@code findById} = empty → lazni "not found" → placanje bi tiho palo. Vidi
 * {@link InterbankPaymentSettlementService} javadoc.
 */
@ExtendWith(MockitoExtension.class)
class InterbankPaymentSettlementServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private InterbankTransactionRepository interbankTransactionRepository;
    @Mock private AccountRepository accountRepository;

    @InjectMocks private InterbankPaymentSettlementService service;

    private static final int MY_RN = 222;
    private static final String TX_ID = "tx-async-1";
    private static final String FROM_ACCOUNT = "222100001";
    private static final Long PAYMENT_ID = 1L;

    // ---- claimForSettlement (entry guard) ----------------------------------

    @Test
    @DisplayName("claimForSettlement: PROCESSING payment → true (proceed)")
    void claim_processing_true() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.PROCESSING)));
        assertThat(service.claimForSettlement(PAYMENT_ID)).isTrue();
    }

    @Test
    @DisplayName("claimForSettlement: already-terminal payment → false (idempotent skip)")
    void claim_terminal_false() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment(PaymentStatus.COMPLETED)));
        assertThat(service.claimForSettlement(PAYMENT_ID)).isFalse();
    }

    @Test
    @DisplayName("claimForSettlement: payment not found (replay/deleted) → false")
    void claim_missing_false() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());
        assertThat(service.claimForSettlement(PAYMENT_ID)).isFalse();
    }

    // ---- applyOutcome (mirror final status) --------------------------------

    @Test
    @DisplayName("applyOutcome COMMITTED → payment COMPLETED + incrementSpending called once with amount (P1-4)")
    void applyOutcome_committed_incrementsSpendingOnce() {
        Transaction tx = interbankTx();
        Payment processing = payment(PaymentStatus.PROCESSING);

        when(interbankTransactionRepository.findByTransactionRoutingNumberAndTransactionIdString(
                eq(MY_RN), eq(TX_ID)))
                .thenReturn(Optional.of(ibTx(InterbankTransactionStatus.COMMITTED)));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(processing));
        when(accountRepository.incrementSpending(eq(FROM_ACCOUNT), eq(BigDecimal.valueOf(100))))
                .thenReturn(1);

        service.applyOutcome(PAYMENT_ID, tx);

        assertThat(processing.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(accountRepository).incrementSpending(eq(FROM_ACCOUNT), eq(BigDecimal.valueOf(100)));
        verify(paymentRepository).save(processing);
    }

    @Test
    @DisplayName("applyOutcome ROLLED_BACK (not COMMITTED) → payment REJECTED + incrementSpending NEVER called (P1-4)")
    void applyOutcome_rolledBack_doesNotIncrementSpending() {
        Transaction tx = interbankTx();
        Payment processing = payment(PaymentStatus.PROCESSING);

        when(interbankTransactionRepository.findByTransactionRoutingNumberAndTransactionIdString(
                eq(MY_RN), eq(TX_ID)))
                .thenReturn(Optional.of(ibTx(InterbankTransactionStatus.ROLLED_BACK)));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(processing));

        service.applyOutcome(PAYMENT_ID, tx);

        assertThat(processing.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        verify(accountRepository, never()).incrementSpending(any(), any());
    }

    @Test
    @DisplayName("applyOutcome no InterbankTransaction record → REJECTED + incrementSpending NEVER called (P1-4)")
    void applyOutcome_noTxRecord_doesNotIncrementSpending() {
        Transaction tx = interbankTx();
        Payment processing = payment(PaymentStatus.PROCESSING);

        when(interbankTransactionRepository.findByTransactionRoutingNumberAndTransactionIdString(
                eq(MY_RN), eq(TX_ID)))
                .thenReturn(Optional.empty());
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(processing));

        service.applyOutcome(PAYMENT_ID, tx);

        assertThat(processing.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        verify(accountRepository, never()).incrementSpending(any(), any());
    }

    @Test
    @DisplayName("NIT1: reconciler settled the payment in the execute() gap → applyOutcome re-reads "
            + "status and does NOT flip again or double-increment spending")
    void applyOutcome_reconcilerWonRace_noDoubleIncrement() {
        // NIT1 double-increment race: ibTx is COMMITTED, but execute() stalled long enough that
        // the Payment-reconciler already flipped Payment → COMPLETED AND incremented spending.
        // applyOutcome must re-read the (non-PROCESSING) status and bail — otherwise it flips
        // again and increments a SECOND time (the bug).
        Transaction tx = interbankTx();
        Payment afterReconciler = payment(PaymentStatus.COMPLETED); // reconciler already settled it

        when(interbankTransactionRepository.findByTransactionRoutingNumberAndTransactionIdString(
                eq(MY_RN), eq(TX_ID)))
                .thenReturn(Optional.of(ibTx(InterbankTransactionStatus.COMMITTED)));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(afterReconciler));

        service.applyOutcome(PAYMENT_ID, tx);

        verify(accountRepository, never()).incrementSpending(any(), any());
        verify(paymentRepository, never()).save(any());
    }

    // ---- replica-lag regression guard --------------------------------------

    @Test
    @DisplayName("REGRESSION: claimForSettlement & applyOutcome MUST be read-WRITE @Transactional "
            + "(routed to PRIMARY, not the lagging read replica)")
    void settlementMethods_mustBeReadWrite_routedToPrimary() throws NoSuchMethodException {
        assertReadWriteTransactional(
                InterbankPaymentSettlementService.class.getDeclaredMethod("claimForSettlement", Long.class));
        assertReadWriteTransactional(
                InterbankPaymentSettlementService.class.getDeclaredMethod("applyOutcome", Long.class, Transaction.class));
    }

    private static void assertReadWriteTransactional(Method method) {
        Transactional tx = method.getAnnotation(Transactional.class);
        assertThat(tx)
                .as("%s must be @Transactional so the routing datasource pins it to PRIMARY", method.getName())
                .isNotNull();
        assertThat(tx.readOnly())
                .as("%s must be read-WRITE (readOnly=false) — readOnly=true would route to the "
                        + "lagging read replica and re-introduce the 'payment not found' race", method.getName())
                .isFalse();
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
