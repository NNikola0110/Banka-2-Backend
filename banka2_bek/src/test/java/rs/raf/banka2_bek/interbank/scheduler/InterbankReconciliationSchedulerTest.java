package rs.raf.banka2_bek.interbank.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2_bek.interbank.model.InterbankTransaction;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;
import rs.raf.banka2_bek.interbank.service.TransactionExecutorService;

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
 * P1-3 — InterbankReconciliationScheduler frees stuck PREPARED recipient rows
 * (presumed-abort → rollbackLocal) and escalates stuck initiator rows to STUCK.
 */
@ExtendWith(MockitoExtension.class)
class InterbankReconciliationSchedulerTest {

    @Mock private InterbankTransactionRepository txRepo;
    @Mock private TransactionExecutorService transactionExecutorService;

    private InterbankReconciliationScheduler scheduler;

    private static final int MY_RN = 222;
    private static final long STALE_MINUTES = 5;

    @BeforeEach
    void setUp() {
        scheduler = new InterbankReconciliationScheduler(txRepo, transactionExecutorService);
        ReflectionTestUtils.setField(scheduler, "staleMinutes", STALE_MINUTES);
    }

    @Test
    @DisplayName("reconcile: queries findStaleInProgress with PREPARING+PREPARED and a cutoff in the past (P1-3)")
    void reconcile_queriesStaleInProgress() {
        when(txRepo.findStaleInProgress(anyList(), any())).thenReturn(List.of());

        scheduler.reconcileStuckTransactions();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InterbankTransactionStatus>> statusCaptor =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(txRepo).findStaleInProgress(statusCaptor.capture(), cutoffCaptor.capture());

        assertThat(statusCaptor.getValue())
                .containsExactlyInAnyOrder(
                        InterbankTransactionStatus.PREPARING, InterbankTransactionStatus.PREPARED);
        assertThat(cutoffCaptor.getValue()).isBefore(LocalDateTime.now());
    }

    @Test
    @DisplayName("N1: stale RECIPIENT PREPARED (voted YES) → MUST NOT presumed-abort; waits for coordinator outcome (§2.8.7)")
    void reconcile_staleRecipientPrepared_doesNotRollback() {
        // N1 — ISPRAVKA BUGA koji je stara asercija cementirala:
        //
        // Stara verzija je tvrdila da stale RECIPIENT u PREPARED treba da uradi
        // unilateralni presumed-abort (rollbackLocal → ROLLED_BACK). To je KRSILO
        // §2.8.7 i osnovni 2PC ugovor: kad je recipient glasao YES (PREPARED), on
        // se OBAVEZAO i NE SME unilateralno da abort-uje. Ako koordinator u
        // medjuvremenu COMMIT-uje (i §2.9 garantuje da ce COMMIT_TX biti
        // retransmitovan dok ga recipient ne potvrdi), a recipient se sam
        // ROLLED_BACK → koordinator COMMITTED + recipient ROLLED_BACK = NOVAC
        // UNISTEN. (Presumed-abort vazi samo PRE YES vote-a / na inicijatorskoj
        // strani gde odluka jos nije doneta.)
        //
        // Ispravna invarijanta: recipient u PREPARED CEKA ishod — reconciler NE
        // dira njegovu rezervaciju i NE menja status. COMMIT_TX/ROLLBACK_TX ce
        // stici kroz koordinatorovu (sada neogranicenu, N2) retransmisiju.
        InterbankTransaction recipient = ibTx("tx-recip", InterbankTransactionStatus.PREPARED,
                InterbankTransaction.InterbankTransactionRole.RECIPIENT);
        when(txRepo.findStaleInProgress(anyList(), any())).thenReturn(List.of(recipient));

        scheduler.reconcileStuckTransactions();

        // Kljucna invarijanta N1: NIKAKAV rollbackLocal na recipient-u koji je glasao YES.
        verify(transactionExecutorService, never()).rollbackLocal(any());
        // Status ostaje PREPARED — ne menjamo ga (cekamo koordinatorov ishod).
        assertThat(recipient.getStatus()).isEqualTo(InterbankTransactionStatus.PREPARED);
    }

    @Test
    @DisplayName("reconcile: stale INITIATOR PREPARED → escalated to STUCK, rollbackLocal NOT called (P1-3)")
    void reconcile_staleInitiatorPrepared_escalatesToStuck() {
        InterbankTransaction initiator = ibTx("tx-init", InterbankTransactionStatus.PREPARED,
                InterbankTransaction.InterbankTransactionRole.INITIATOR);
        when(txRepo.findStaleInProgress(anyList(), any())).thenReturn(List.of(initiator));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(eq(MY_RN), eq("tx-init")))
                .thenReturn(Optional.of(initiator));
        when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        scheduler.reconcileStuckTransactions();

        assertThat(initiator.getStatus()).isEqualTo(InterbankTransactionStatus.STUCK);
        assertThat(initiator.getFailureReason()).contains("STUCK");
        verify(txRepo).save(initiator);
        verify(transactionExecutorService, never()).rollbackLocal(any());
    }

    @Test
    @DisplayName("reconcile: markStuck guard — initiator already COMMITTED is NOT overwritten with STUCK (P1-3)")
    void reconcile_initiatorAlreadyCommitted_notOverwritten() {
        // findStaleInProgress vratio red dok je bio PREPARED, ali je u medjuvremenu
        // (race) COMMITTED. markStuck guard ne sme da ga pregazi.
        InterbankTransaction initiator = ibTx("tx-race", InterbankTransactionStatus.PREPARED,
                InterbankTransaction.InterbankTransactionRole.INITIATOR);
        InterbankTransaction committedNow = ibTx("tx-race", InterbankTransactionStatus.COMMITTED,
                InterbankTransaction.InterbankTransactionRole.INITIATOR);
        when(txRepo.findStaleInProgress(anyList(), any())).thenReturn(List.of(initiator));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(eq(MY_RN), eq("tx-race")))
                .thenReturn(Optional.of(committedNow));

        scheduler.reconcileStuckTransactions();

        assertThat(committedNow.getStatus()).isEqualTo(InterbankTransactionStatus.COMMITTED);
        verify(txRepo, never()).save(any());
    }

    @Test
    @DisplayName("R2 1432: STUCK redovi se periodicno SURFEJSUJU (findByStatusIn(STUCK)) — vise nisu write-once-never-read")
    void reconcile_surfacesStuckTransactions() {
        // Glavni stale sweep prazan; STUCK sweep nalazi 1 STUCK red.
        when(txRepo.findStaleInProgress(anyList(), any())).thenReturn(List.of());
        InterbankTransaction stuck = ibTx("tx-stuck", InterbankTransactionStatus.STUCK,
                InterbankTransaction.InterbankTransactionRole.INITIATOR);
        when(txRepo.findByStatusIn(List.of(InterbankTransactionStatus.STUCK)))
                .thenReturn(List.of(stuck));

        scheduler.reconcileStuckTransactions();

        // STUCK sweep je izvrsen (re-examine), ali status NIJE automatski promenjen
        // (STUCK zahteva manuelnu intervenciju — §2.8). Nema save, nema rollbackLocal.
        verify(txRepo).findByStatusIn(List.of(InterbankTransactionStatus.STUCK));
        verify(txRepo, never()).save(any());
        verify(transactionExecutorService, never()).rollbackLocal(any());
        assertThat(stuck.getStatus()).isEqualTo(InterbankTransactionStatus.STUCK);
    }

    @Test
    @DisplayName("reconcile: one failing row does not block the rest of the batch (P1-3)")
    void reconcile_oneFailureDoesNotBlockOthers() {
        // Posle N1 fix-a RECIPIENT vise ne radi rollbackLocal (cekamo koordinatora);
        // INITIATOR red i dalje eskalira na STUCK (save). Koristimo dva INITIATOR
        // reda — prvi save baci, drugi prodje — da dokazemo da jedan pad ne blokira
        // ostatak batch-a.
        InterbankTransaction failing = ibTx("tx-fail", InterbankTransactionStatus.PREPARED,
                InterbankTransaction.InterbankTransactionRole.INITIATOR);
        InterbankTransaction ok = ibTx("tx-ok", InterbankTransactionStatus.PREPARED,
                InterbankTransaction.InterbankTransactionRole.INITIATOR);
        when(txRepo.findStaleInProgress(anyList(), any())).thenReturn(List.of(failing, ok));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(eq(MY_RN), eq("tx-fail")))
                .thenReturn(Optional.of(failing));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(eq(MY_RN), eq("tx-ok")))
                .thenReturn(Optional.of(ok));
        // First save throws, second succeeds.
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .doAnswer(inv -> inv.getArgument(0))
                .when(txRepo).save(any());

        scheduler.reconcileStuckTransactions();

        // Both rows attempted despite the first throwing.
        verify(txRepo, org.mockito.Mockito.times(2)).save(any());
        // Recipient nikad ne rollback-uje (N1).
        verify(transactionExecutorService, never()).rollbackLocal(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private InterbankTransaction ibTx(String idString, InterbankTransactionStatus status,
                                      InterbankTransaction.InterbankTransactionRole role) {
        InterbankTransaction ibt = new InterbankTransaction();
        ibt.setId((long) idString.hashCode());
        ibt.setTransactionRoutingNumber(MY_RN);
        ibt.setTransactionIdString(idString);
        ibt.setStatus(status);
        ibt.setRole(role);
        ibt.setRetryCount(0);
        ibt.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        ibt.setLastActivityAt(LocalDateTime.now().minusMinutes(30));
        return ibt;
    }
}
