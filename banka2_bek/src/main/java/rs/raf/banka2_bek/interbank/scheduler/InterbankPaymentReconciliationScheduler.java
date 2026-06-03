package rs.raf.banka2_bek.interbank.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.interbank.model.InterbankTransaction;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.payment.repository.PaymentRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * N4 — Payment-level reconciler za "orphan PROCESSING" medjubankarska placanja.
 *
 * <p><b>Rupa koju zatvara:</b> inter-bank 2PC dispatch ({@code executeAsync}) se
 * pokrece iz {@code afterCommit} POSLE sto je Payment red commit-ovan kao PROCESSING.
 * Ako je async task ODBIJEN (queue saturacija / app shutdown) PRE nego sto
 * {@code TransactionExecutorService.execute()} kreira {@code InterbankTransaction} red,
 * Payment ostaje PROCESSING <i>zauvek</i> — a {@code InterbankReconciliationScheduler}
 * gleda iskljucivo {@code InterbankTransaction} redove, pa ovaj slucaj ne moze da uhvati.
 *
 * <p>{@code CallerRunsPolicy} na {@code interbankTaskExecutor}-u (vidi {@code AssistantConfig})
 * vec znatno smanjuje verovatnocu (odbijeni task se izvrsi sinhrono), ali ovaj reconciler
 * je <b>safety net</b> za sve preostale rupe (npr. JVM kill izmedju commit-a Payment-a i
 * dispatch-a, ili crash unutar {@code execute()} pre prvog save-a).
 *
 * <p><b>Strategija</b> (za svako stuck PROCESSING inter-bank placanje starije od cutoff-a):
 * <ul>
 *   <li><b>Nema {@code InterbankTransaction} reda</b> → task nikad nije pokrenuo 2PC →
 *       presumed-abort: Payment → REJECTED. Rezervacija izvora NIJE napravljena (ona se
 *       desava unutar {@code execute()}→{@code prepareTxPhase}), pa nema sta da se oslobodi.</li>
 *   <li><b>InterbankTransaction COMMITTED</b> → mirror: Payment → COMPLETED + inkrement
 *       dnevne/mesecne potrosnje (kao {@code InterbankPaymentAsyncService}).</li>
 *   <li><b>InterbankTransaction ROLLED_BACK / STUCK</b> → mirror: Payment → REJECTED.
 *       {@code rollbackLocal} je vec oslobodio rezervaciju.</li>
 *   <li><b>InterbankTransaction PREPARING / PREPARED (jos in-flight)</b> → ostavi na miru;
 *       vlasnik je {@code InterbankReconciliationScheduler} (initiator → STUCK eskalacija,
 *       recipient → ceka §2.8.7).</li>
 * </ul>
 *
 * <p>Idempotentno: svaka grana koja menja Payment prvo prelazi iz PROCESSING u terminal,
 * pa ponovni sweep vidi ne-PROCESSING status i preskace (a {@code findStuckInterbankPayments}
 * filter-uje na PROCESSING).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterbankPaymentReconciliationScheduler {

    private final PaymentRepository paymentRepository;
    private final InterbankTransactionRepository txRepo;
    private final AccountRepository accountRepository;

    @Value("${interbank.reconciliation.stale-minutes:5}")
    private long staleMinutes;

    /**
     * Periodicno (default 2 min, deli isti red velicine kao ostali reconciler-i).
     */
    @Scheduled(fixedRateString = "${interbank.payment-reconciliation.fixed-rate-ms:120000}")
    @Transactional
    public void reconcileStuckPayments() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleMinutes);
        List<Payment> stuck = paymentRepository.findStuckInterbankPayments(
                PaymentStatus.PROCESSING, cutoff);

        if (stuck.isEmpty()) return;

        log.info("InterbankPaymentReconciliation: {} stuck PROCESSING inter-bank payment(s) older than {}min",
                stuck.size(), staleMinutes);

        for (Payment p : stuck) {
            try {
                reconcileOne(p);
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException concurrent) {
                log.debug("Payment reconciliation skipped (concurrent worker took payment {}): {}",
                        p.getId(), concurrent.getMessage());
            } catch (Exception e) {
                log.error("Payment reconciliation error for payment {} (tx {}/{}): {}",
                        p.getId(), p.getInterbankTxRoutingNumber(), p.getInterbankTxIdString(),
                        e.getMessage(), e);
            }
        }
    }

    private void reconcileOne(Payment p) {
        Optional<InterbankTransaction> ibTxOpt =
                txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                        p.getInterbankTxRoutingNumber(), p.getInterbankTxIdString());

        if (ibTxOpt.isEmpty()) {
            // Task nikad nije kreirao 2PC red → presumed-abort, nema rezervacije za oslobodi.
            log.warn("Payment {} stuck PROCESSING with no InterbankTransaction row (tx {}/{}) — "
                            + "presumed-abort → REJECTED.",
                    p.getId(), p.getInterbankTxRoutingNumber(), p.getInterbankTxIdString());
            p.setStatus(PaymentStatus.REJECTED);
            paymentRepository.save(p);
            return;
        }

        InterbankTransaction ibTx = ibTxOpt.get();
        switch (ibTx.getStatus()) {
            case COMMITTED -> {
                p.setStatus(PaymentStatus.COMPLETED);
                paymentRepository.save(p);
                incrementSpending(p);
                log.info("Payment {} reconciled → COMPLETED (mirrored COMMITTED tx {}/{}).",
                        p.getId(), ibTx.getTransactionRoutingNumber(), ibTx.getTransactionIdString());
            }
            case ROLLED_BACK, STUCK -> {
                p.setStatus(PaymentStatus.REJECTED);
                paymentRepository.save(p);
                log.info("Payment {} reconciled → REJECTED (mirrored {} tx {}/{}).",
                        p.getId(), ibTx.getStatus(),
                        ibTx.getTransactionRoutingNumber(), ibTx.getTransactionIdString());
            }
            case PREPARING, PREPARED -> {
                // Jos in-flight — vlasnik je InterbankReconciliationScheduler. Ne diramo.
                log.debug("Payment {} still in-flight (tx {}/{} = {}) — leaving for tx reconciler.",
                        p.getId(), ibTx.getTransactionRoutingNumber(),
                        ibTx.getTransactionIdString(), ibTx.getStatus());
            }
            default -> log.warn("Payment {} tx {}/{} in unexpected status {} — no action.",
                    p.getId(), ibTx.getTransactionRoutingNumber(),
                    ibTx.getTransactionIdString(), ibTx.getStatus());
        }
    }

    /**
     * Mirror {@code InterbankPaymentAsyncService} dnevni/mesecni limit inkrement na uspeh.
     */
    private void incrementSpending(Payment p) {
        if (p.getFromAccount() != null
                && p.getFromAccount().getAccountNumber() != null
                && p.getAmount() != null) {
            int updated = accountRepository.incrementSpending(
                    p.getFromAccount().getAccountNumber(), p.getAmount());
            if (updated == 0) {
                log.warn("N4: incrementSpending matched 0 rows for account {} (payment {})",
                        p.getFromAccount().getAccountNumber(), p.getId());
            }
        }
    }
}
