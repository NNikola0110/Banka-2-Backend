package rs.raf.banka2_bek.interbank.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.interbank.model.InterbankTransaction;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.payment.repository.PaymentRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N4 — Payment-level reconciler frees stuck PROCESSING inter-bank payments that the
 * {@code InterbankTransaction}-only reconciler cannot see (async dispatch task dropped
 * before {@code execute()} ever created an InterbankTransaction row).
 */
@ExtendWith(MockitoExtension.class)
class InterbankPaymentReconciliationSchedulerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private InterbankTransactionRepository txRepo;
    @Mock private AccountRepository accountRepository;

    private InterbankPaymentReconciliationScheduler scheduler;

    private static final int MY_RN = 222;

    @BeforeEach
    void setUp() {
        scheduler = new InterbankPaymentReconciliationScheduler(
                paymentRepository, txRepo, accountRepository);
        ReflectionTestUtils.setField(scheduler, "staleMinutes", 5L);
    }

    @Test
    @DisplayName("N4: stuck PROCESSING payment with NO InterbankTransaction row → REJECTED (presumed-abort)")
    void reconcile_noInterbankTxRow_rejectsPayment() {
        Payment p = payment(1L, "tx-orphan");
        when(paymentRepository.findStuckInterbankPayments(eq(PaymentStatus.PROCESSING), any()))
                .thenReturn(List.of(p));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(MY_RN, "tx-orphan"))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.reconcileStuckPayments();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        verify(paymentRepository).save(p);
        // Presumed-abort: spending se NE inkrementira (placanje nije proslo).
        verify(accountRepository, never()).incrementSpending(any(), any());
    }

    @Test
    @DisplayName("N4: stuck PROCESSING payment whose InterbankTransaction COMMITTED → COMPLETED + spending incremented")
    void reconcile_committedTx_completesPayment() {
        Payment p = payment(2L, "tx-committed");
        InterbankTransaction ibt = ibtx("tx-committed", InterbankTransactionStatus.COMMITTED);
        when(paymentRepository.findStuckInterbankPayments(eq(PaymentStatus.PROCESSING), any()))
                .thenReturn(List.of(p));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(MY_RN, "tx-committed"))
                .thenReturn(Optional.of(ibt));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.incrementSpending(any(), any())).thenReturn(1);

        scheduler.reconcileStuckPayments();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(accountRepository).incrementSpending(eq("222000111"), eq(p.getAmount()));
    }

    @Test
    @DisplayName("N4: stuck PROCESSING payment whose InterbankTransaction ROLLED_BACK → REJECTED, no spending")
    void reconcile_rolledBackTx_rejectsPayment() {
        Payment p = payment(3L, "tx-rolled");
        InterbankTransaction ibt = ibtx("tx-rolled", InterbankTransactionStatus.ROLLED_BACK);
        when(paymentRepository.findStuckInterbankPayments(eq(PaymentStatus.PROCESSING), any()))
                .thenReturn(List.of(p));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(MY_RN, "tx-rolled"))
                .thenReturn(Optional.of(ibt));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.reconcileStuckPayments();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        verify(accountRepository, never()).incrementSpending(any(), any());
    }

    @Test
    @DisplayName("N4: stuck PROCESSING payment whose InterbankTransaction is still PREPARED → left alone (owned by tx reconciler)")
    void reconcile_nonTerminalTx_leavesPaymentProcessing() {
        Payment p = payment(4L, "tx-inflight");
        InterbankTransaction ibt = ibtx("tx-inflight", InterbankTransactionStatus.PREPARED);
        when(paymentRepository.findStuckInterbankPayments(eq(PaymentStatus.PROCESSING), any()))
                .thenReturn(List.of(p));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(MY_RN, "tx-inflight"))
                .thenReturn(Optional.of(ibt));

        scheduler.reconcileStuckPayments();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("N4: one failing payment row does not block the rest of the batch")
    void reconcile_oneFailureDoesNotBlockOthers() {
        Payment failing = payment(5L, "tx-fail");
        Payment ok = payment(6L, "tx-ok");
        when(paymentRepository.findStuckInterbankPayments(eq(PaymentStatus.PROCESSING), any()))
                .thenReturn(List.of(failing, ok));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(MY_RN, "tx-fail"))
                .thenThrow(new RuntimeException("boom"));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(MY_RN, "tx-ok"))
                .thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.reconcileStuckPayments();

        // Drugi red obradjen uprkos padu prvog.
        assertThat(ok.getStatus()).isEqualTo(PaymentStatus.REJECTED);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Payment payment(Long id, String txIdString) {
        Account from = new Account();
        from.setAccountNumber("222000111");
        return Payment.builder()
                .id(id)
                .orderNumber("ord-" + id)
                .fromAccount(from)
                .toAccountNumber("999000222")
                .amount(new BigDecimal("100.00"))
                .status(PaymentStatus.PROCESSING)
                .interbankTxRoutingNumber(MY_RN)
                .interbankTxIdString(txIdString)
                .createdAt(LocalDateTime.now().minusMinutes(30))
                .build();
    }

    private InterbankTransaction ibtx(String idString, InterbankTransactionStatus status) {
        InterbankTransaction ibt = new InterbankTransaction();
        ibt.setTransactionRoutingNumber(MY_RN);
        ibt.setTransactionIdString(idString);
        ibt.setStatus(status);
        ibt.setRole(InterbankTransaction.InterbankTransactionRole.INITIATOR);
        return ibt;
    }
}
