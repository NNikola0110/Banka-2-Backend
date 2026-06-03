package rs.raf.banka2_bek.interbank.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;
import rs.raf.banka2_bek.interbank.protocol.Transaction;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.payment.repository.PaymentRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterbankPaymentAsyncService {

    private final TransactionExecutorService transactionExecutorService;
    private final InterbankTransactionRepository interbankTransactionRepository;
    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;

    /**
     * Runs the full 2PC flow for an interbank payment on a dedicated thread pool.
     * The calling thread (HTTP request) has already committed the Payment record
     * with status=PROCESSING before this method is invoked.
     *
     * After execute() returns, reads the final InterbankTransaction status and
     * mirrors it back to the Payment row: COMMITTED → COMPLETED, anything else → REJECTED.
     */
    @Async("interbankTaskExecutor")
    @Transactional(propagation = Propagation.NEVER)
    public void executeAsync(Long paymentId, Transaction tx) {
        // BE-INT-04: idempotency guard.
        // Spring @Async ne garantuje at-most-once preko app restart-a; ako se
        // posle restart-a executor task queue rehydrate-uje, re-invoked async
        // job moze flipnuti vec-terminal Payment (npr. COMPLETED → REJECTED).
        // Guard: ako Payment vise nije u PROCESSING stanju, ovo je replay i
        // izlazimo bez modifikacije stanja.
        Payment current = paymentRepository.findById(paymentId).orElse(null);
        if (current == null) {
            log.warn("Skipping async settlement: payment {} not found (replay or deleted)", paymentId);
            return;
        }
        if (current.getStatus() != PaymentStatus.PROCESSING) {
            log.debug("Skipping async settlement for payment {} — already in terminal state {}",
                    paymentId, current.getStatus());
            return;
        }

        try {
            transactionExecutorService.execute(tx);
        } catch (Exception e) {
            log.error("Interbank 2PC execute failed for payment {}: {}", paymentId, e.getMessage(), e);
        }

        interbankTransactionRepository
                .findByTransactionRoutingNumberAndTransactionIdString(
                        tx.transactionId().routingNumber(), tx.transactionId().id())
                .ifPresentOrElse(ibTx -> {
                    PaymentStatus finalStatus = ibTx.getStatus() == InterbankTransactionStatus.COMMITTED
                            ? PaymentStatus.COMPLETED
                            : PaymentStatus.REJECTED;
                    paymentRepository.findById(paymentId).ifPresent(p -> {
                        // NIT1 — double-increment guard. execute() can stall (network I/O,
                        // pool saturation) for longer than the reconciler's stale cutoff.
                        // If InterbankPaymentReconciliationScheduler ran in that window it
                        // already flipped this Payment to a terminal state AND incremented
                        // spending. The entry guard (line ~49) only protects the START of
                        // this method, not the gap between execute() and this flip — so we
                        // MUST re-read the status here and bail if the reconciler beat us,
                        // otherwise we'd flip again and double-count the daily/monthly limit.
                        if (p.getStatus() != PaymentStatus.PROCESSING) {
                            log.debug("Skipping status flip for payment {} — reconciler already "
                                    + "settled it to {} (avoiding duplicate spending increment).",
                                    paymentId, p.getStatus());
                            return;
                        }
                        p.setStatus(finalStatus);
                        paymentRepository.save(p);
                        // P1-4 fix: dnevni/mesecni limit se proverava u
                        // PaymentServiceImpl.createInterbankPayment ali nikad nije
                        // inkrementiran — klijent je mogao da prelazi limit beskonacno
                        // kroz medjubankarska placanja (same-bank flow inkrementira u
                        // createPayment:247-248). Inkrementiramo SAMO na uspesan COMMIT
                        // (rolled-back/abortovano placanje se ne broji). Tacno jednom po
                        // placanju: ulazni guard blokira replay, a re-citanje statusa
                        // iznad blokira dupliranje sa Payment-reconciler-om (NIT1).
                        if (finalStatus == PaymentStatus.COMPLETED
                                && p.getFromAccount() != null
                                && p.getFromAccount().getAccountNumber() != null
                                && p.getAmount() != null) {
                            int updated = accountRepository.incrementSpending(
                                    p.getFromAccount().getAccountNumber(), p.getAmount());
                            if (updated == 0) {
                                log.warn("P1-4: incrementSpending matched 0 rows for account {} (payment {})",
                                        p.getFromAccount().getAccountNumber(), paymentId);
                            }
                        }
                    });
                }, () -> {
                    log.warn("InterbankTransaction not found after execute() for payment {}", paymentId);
                    paymentRepository.findById(paymentId).ifPresent(p -> {
                        // NIT1 — same re-read guard: don't overwrite a terminal status the
                        // reconciler may have already set (it would be a no-op here, but the
                        // explicit guard keeps the invariant "exactly one terminal flip").
                        if (p.getStatus() != PaymentStatus.PROCESSING) {
                            log.debug("Skipping REJECTED flip for payment {} — already terminal {}.",
                                    paymentId, p.getStatus());
                            return;
                        }
                        p.setStatus(PaymentStatus.REJECTED);
                        paymentRepository.save(p);
                    });
                });
    }
}
