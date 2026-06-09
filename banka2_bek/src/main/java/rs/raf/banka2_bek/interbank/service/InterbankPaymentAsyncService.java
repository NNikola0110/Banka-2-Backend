package rs.raf.banka2_bek.interbank.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.interbank.protocol.Transaction;

/**
 * Orkestrira pun 2PC flow medjubankarskog placanja na zasebnom thread pool-u
 * ({@code interbankTaskExecutor}). Pozvana je iz {@code TransactionSynchronization.afterCommit()}
 * u {@code PaymentServiceImpl}, pa je Payment red vec commit-ovan kao PROCESSING.
 *
 * <p>Metoda je {@code @Transactional(propagation = NEVER)} namerno: {@code execute()} radi mrezni
 * 2PC (round-trip-ovi ka drugoj banci) i NE sme da drzi otvorenu DB tx/konekciju to vreme. Zato
 * sav Payment read/modify/write delegira na {@link InterbankPaymentSettlementService}, cije su
 * metode {@code @Transactional} (read-WRITE) → rutiraju se na PRIMARY. Bez te delegacije, direktan
 * {@code paymentRepository.findById} iz NEVER metode bi se izvrsio u {@code SimpleJpaRepository}
 * readOnly tx → READ REPLIKA → svez (tek commit-ovan) red jos nije replikovan → lazni "not found"
 * (replica-lag race). Vidi {@link InterbankPaymentSettlementService} za detalje.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterbankPaymentAsyncService {

    private final TransactionExecutorService transactionExecutorService;
    private final InterbankPaymentSettlementService settlementService;

    @Async("interbankTaskExecutor")
    @Transactional(propagation = Propagation.NEVER)
    public void executeAsync(Long paymentId, Transaction tx) {
        // Entry guard (PRIMARY, read-write tx). Preskace replay/terminal Payment.
        if (!settlementService.claimForSettlement(paymentId)) {
            return;
        }

        try {
            transactionExecutorService.execute(tx);
        } catch (Exception e) {
            log.error("Interbank 2PC execute failed for payment {}: {}", paymentId, e.getMessage(), e);
        }

        // Mirror final InterbankTransaction status u Payment (PRIMARY, read-write tx).
        settlementService.applyOutcome(paymentId, tx);
    }
}
