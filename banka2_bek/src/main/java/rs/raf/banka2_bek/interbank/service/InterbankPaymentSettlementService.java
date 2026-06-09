package rs.raf.banka2_bek.interbank.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;
import rs.raf.banka2_bek.interbank.protocol.Transaction;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.payment.repository.PaymentRepository;

/**
 * PRIMARY-pinned (read-WRITE) DB deo medjubankarskog payment settlement-a.
 *
 * <p><b>Bug fix (08.06.2026 — replica-lag race):</b> {@link InterbankPaymentAsyncService#executeAsync}
 * je {@code @Transactional(propagation = NEVER)} (namerno bez transakcije — da ne drzi DB
 * konekciju tokom mreznog 2PC-a u {@code execute()}). Posledica: svaki
 * {@code paymentRepository.findById(...)} pozvan direktno iz te metode otvara SVOJU
 * {@code @Transactional(readOnly = true)} transakciju (default {@code SimpleJpaRepository}).
 * Uz aktivan read/write splitting ({@link rs.raf.banka2_bek.persistence.DataSourceConfig
 * banka2.datasource.replica.enabled=true} na k8s-u) ta readOnly tx se rutira na
 * <b>READ REPLIKU</b>. Posto je Payment red tek commit-ovan na PRIMARY-ju (dispatch ide iz
 * {@code TransactionSynchronization.afterCommit()} u {@code PaymentServiceImpl}), replika jos
 * nije stigla da ga replikuje (streaming lag reda velicine ms) → {@code findById} vraca
 * {@code Optional.empty()} → settlement se preskace kao lazni "replay/deleted" → 2PC nikad ne
 * krene → {@link rs.raf.banka2_bek.interbank.scheduler.InterbankPaymentReconciliationScheduler}
 * kasnije markira placanje kao REJECTED. Medjubankarsko placanje tako <i>intermitentno tiho pada</i>
 * (zavisno od trenutnog replication lag-a).
 *
 * <p><b>Fix:</b> sva Payment read/modify/write logika ide kroz ove
 * {@code @Transactional} (read-WRITE, {@code readOnly=false}) metode → routing key = PRIMARY →
 * svez red je vidljiv odmah. {@code execute(tx)} (mrezni 2PC) i dalje tece van svake DB tx.
 * Identican pattern kao {@link rs.raf.banka2_bek.interbank.scheduler.InterbankPaymentReconciliationScheduler}
 * (koji radi ispravno bas zato sto je njegova metoda {@code @Transactional} → primary).
 *
 * <p><b>Regresioni guard:</b> {@code InterbankPaymentSettlementServiceTest} reflektivno tvrdi da
 * obe metode ostaju read-WRITE — promena u {@code readOnly=true} bi vratila bug.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterbankPaymentSettlementService {

    private final PaymentRepository paymentRepository;
    private final InterbankTransactionRepository interbankTransactionRepository;
    private final AccountRepository accountRepository;

    /**
     * Entry guard — PRIMARY (read-WRITE tx). Vraca {@code true} ako settlement treba da krene.
     *
     * <p>BE-INT-04 idempotency: Spring {@code @Async} ne garantuje at-most-once preko app
     * restart-a; ako se posle restart-a executor task queue rehydrate-uje, re-invoked async job
     * moze flipnuti vec-terminal Payment. Guard: ako Payment ne postoji (replay/deleted) ili vise
     * nije {@code PROCESSING}, izlazimo bez ulaska u 2PC.
     */
    @Transactional
    public boolean claimForSettlement(Long paymentId) {
        Payment current = paymentRepository.findById(paymentId).orElse(null);
        if (current == null) {
            log.warn("Skipping async settlement: payment {} not found (replay or deleted)", paymentId);
            return false;
        }
        if (current.getStatus() != PaymentStatus.PROCESSING) {
            log.debug("Skipping async settlement for payment {} — already in terminal state {}",
                    paymentId, current.getStatus());
            return false;
        }
        return true;
    }

    /**
     * Posle {@code execute()} — mirror final {@code InterbankTransaction} status u Payment.
     * PRIMARY (read-WRITE tx): COMMITTED → COMPLETED (+ dnevni/mesecni spending increment),
     * bilo sta drugo / nedostajuci tx red → REJECTED.
     */
    @Transactional
    public void applyOutcome(Long paymentId, Transaction tx) {
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
                        // spending. The entry guard (claimForSettlement) only protects the START,
                        // not the gap between execute() and this flip — so we MUST re-read the
                        // status here and bail if the reconciler beat us, otherwise we'd flip
                        // again and double-count the daily/monthly limit.
                        if (p.getStatus() != PaymentStatus.PROCESSING) {
                            log.debug("Skipping status flip for payment {} — reconciler already "
                                    + "settled it to {} (avoiding duplicate spending increment).",
                                    paymentId, p.getStatus());
                            return;
                        }
                        p.setStatus(finalStatus);
                        paymentRepository.save(p);
                        // P1-4: dnevni/mesecni limit se proverava u
                        // PaymentServiceImpl.createInterbankPayment ali se inkrementira SAMO
                        // ovde — tacno jednom po placanju na uspesan COMMIT (ulazni guard blokira
                        // replay, a re-citanje statusa iznad blokira dupliranje sa reconciler-om).
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
                        // reconciler may have already set (no-op here, but keeps the invariant
                        // "exactly one terminal flip").
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
