package rs.raf.banka2_bek.interbank.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.banka2_bek.interbank.model.InterbankTransaction;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;
import rs.raf.banka2_bek.interbank.protocol.ForeignBankId;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;
import rs.raf.banka2_bek.interbank.service.TransactionExecutorService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * P1-3 — Reconciliation / timeout recovery za zaglavljene 2PC transakcije
 * (§Celina 5 §166-168 CHECK_STATUS).
 *
 * <p>Problem koji resava: {@code InterbankRetryScheduler} retry-uje samo PENDING
 * outbound PORUKE (NEW_TX/COMMIT_TX/ROLLBACK_TX), ne zaglavljena STANJA transakcija.
 * Ako primalac glasa YES + rezervise sredstva, pa nikad ne primi COMMIT_TX/ROLLBACK_TX
 * (koordinator crash / mrezna particija), red ostaje PREPARED sa sredstvima zakljucanim
 * zauvek. {@code findStaleInProgress} je postojao u repo-u ali nije imao nijednog
 * production caller-a — ovaj scheduler ga aktivira.
 *
 * <p>Strategija (N1 — ispravan 2PC; §2.8.7 + §2.9):
 * <ul>
 *   <li><b>RECIPIENT</b> red zaglavljen u PREPARED: <b>NE DIRAMO</b>. Recipient koji
 *       je glasao YES (PREPARED) se obavezao i ne sme unilateralno da abort-uje
 *       (§2.8.7). Koordinator ce — po §2.9 (sada neogranicena phase-2 retransmisija,
 *       vidi {@code InterbankMessageService}) — retransmitovati COMMIT_TX ili
 *       ROLLBACK_TX dok recipient ne potvrdi; tek tada se status menja na
 *       COMMITTED/ROLLED_BACK kroz inbound handler. Unilateralni presumed-abort
 *       ovde bi, ako koordinator COMMIT-uje, unistio novac (koordinator COMMITTED +
 *       recipient ROLLED_BACK). Zato cekamo, ne abort-ujemo.</li>
 *   <li><b>INITIATOR</b> red zaglavljen u PREPARING/PREPARED posle lokalne odluke:
 *       outbound COMMIT_TX/ROLLBACK_TX poruke se vec retry-uju kroz
 *       {@code InterbankRetryScheduler}. Ako je prosao dovoljno vremena bez resolucije,
 *       eskaliramo na STUCK za manuelnu intervenciju supervizora (§2.8 STUCK semantika).
 *       Ne diramo lokalnu rezervaciju INITIATOR-a ovde — njegova konacna odluka
 *       (commit vs rollback) je vec doneta u {@code execute()} flow-u; presumed-abort
 *       rezervacije bi mogao da kontradiktuje vec-poslat COMMIT.</li>
 * </ul>
 *
 * <p>Double-processing guard: INITIATOR red se eskalira preko {@code markStuck} sa
 * status-guard-om (samo PREPARING/PREPARED). Ako dva sweep-a istovremeno uhvate isti
 * red, drugi ce procitati vec-promenjeni status i ne uraditi nista — korektnost drzi
 * taj status-guard, NE optimistic lock ({@code InterbankTransaction} nema {@code @Version}).
 * (Globalni {@code @Version} je zaseban concurrency batch.) {@code reconcileStuckTransactions}
 * ipak hvata {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
 * defanzivno — bezopasno je i ostaje korektno ako se {@code @Version} kasnije doda.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterbankReconciliationScheduler {

    private final InterbankTransactionRepository txRepo;
    private final TransactionExecutorService transactionExecutorService;

    /**
     * Koliko minuta bez aktivnosti pre nego sto se transakcija smatra zaglavljenom.
     * Default 5min; konfigurabilno preko {@code interbank.reconciliation.stale-minutes}.
     */
    @Value("${interbank.reconciliation.stale-minutes:5}")
    private long staleMinutes;

    /**
     * Periodicna reconciliation. Default na svakih 2 minuta (isti red velicine kao
     * {@code InterbankRetryScheduler}). Test profil ne treba poseban disable jer
     * scheduled metoda samo cita {@code findStaleInProgress} koji vraca prazno na
     * cistoj test bazi — ali da bismo bili sigurni, fixedRate je velik dovoljno da
     * se ne pali tokom kratkih @SpringBootTest run-ova.
     */
    @Scheduled(fixedRateString = "${interbank.reconciliation.fixed-rate-ms:120000}")
    public void reconcileStuckTransactions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleMinutes);
        List<InterbankTransaction> stale = txRepo.findStaleInProgress(
                List.of(InterbankTransactionStatus.PREPARING, InterbankTransactionStatus.PREPARED),
                cutoff);

        if (!stale.isEmpty()) {
            log.info("InterbankReconciliation: {} stale in-progress transaction(s) older than {}min",
                    stale.size(), staleMinutes);

            for (InterbankTransaction ibTx : stale) {
                try {
                    reconcileOne(ibTx);
                } catch (org.springframework.orm.ObjectOptimisticLockingFailureException concurrent) {
                    // Drugi sweep / inbound handler je vec uzeo ovaj red — preskoci tiho.
                    log.debug("Reconciliation skipped (concurrent worker took tx id={}): {}",
                            ibTx.getId(), concurrent.getMessage());
                } catch (Exception e) {
                    log.error("Reconciliation error for tx id={} ({}/{}): {}",
                            ibTx.getId(), ibTx.getTransactionRoutingNumber(),
                            ibTx.getTransactionIdString(), e.getMessage(), e);
                }
            }
        }

        // R2 1432: STUCK vidljivost-sweep. PREPARING/PREPARED sweep (gore) eskalira
        // u STUCK, ali se STUCK redovi posle toga NIKAD vise ne pregledaju —
        // write-once-never-read. STUCK je po dizajnu stanje za MANUELNU supervizorsku
        // intervenciju (§2.8: COMMIT/ROLLBACK koordinatora se ne sme automatski
        // pogadjati jer bi unilateralna odluka mogla da unisti/kreira novac), pa ovde
        // NE menjamo status automatski. Umesto toga periodicno re-LOGUJEMO STUCK redove
        // (WARN heartbeat) da ne istrunu tiho — operativci/Prometheus alert ih vide i
        // mogu rucno da ih razrese kroz supervizor portal.
        surfaceStuckTransactions();
    }

    /**
     * R2 1432: periodicno re-objavljuje STUCK transakcije (WARN). Ne menja stanje —
     * STUCK zahteva manuelnu intervenciju (§2.8). Svrha je sprecavanje "silent rot":
     * pre fix-a, kad bi se red eskalirao na STUCK, vise se nikad ne bi spomenuo u
     * logu/metrici, pa bi zaglavljena 2PC transakcija (sa zakljucanim sredstvima)
     * ostala nevidljiva. Sad svaki ciklus surfejsuje koliko ih ima i koje.
     */
    void surfaceStuckTransactions() {
        List<InterbankTransaction> stuck;
        try {
            stuck = txRepo.findByStatusIn(List.of(InterbankTransactionStatus.STUCK));
        } catch (Exception e) {
            log.error("InterbankReconciliation: STUCK sweep lookup failed: {}", e.getMessage(), e);
            return;
        }
        if (stuck == null || stuck.isEmpty()) {
            return;
        }
        log.warn("InterbankReconciliation: {} STUCK transaction(s) awaiting MANUAL supervisor resolution.",
                stuck.size());
        for (InterbankTransaction ibTx : stuck) {
            log.warn("  STUCK tx id={} {}/{} role={} reason='{}' lastActivity={}",
                    ibTx.getId(), ibTx.getTransactionRoutingNumber(), ibTx.getTransactionIdString(),
                    ibTx.getRole(), ibTx.getFailureReason(), ibTx.getLastActivityAt());
        }
    }

    /**
     * Obradi jedan zaglavljen red.
     * <ul>
     *   <li>RECIPIENT (PREPARED, glasao YES) → <b>no-op</b>: ceka koordinatorov ishod
     *       (§2.8.7). NIKAD unilateralni presumed-abort.</li>
     *   <li>INITIATOR → eskalacija na STUCK za manuelnu intervenciju.</li>
     * </ul>
     */
    private void reconcileOne(InterbankTransaction ibTx) {
        ForeignBankId txId = new ForeignBankId(
                ibTx.getTransactionRoutingNumber(), ibTx.getTransactionIdString());

        if (ibTx.getRole() == InterbankTransaction.InterbankTransactionRole.RECIPIENT) {
            // N1 (§2.8.7): recipient koji je glasao YES (PREPARED) NE SME unilateralno
            // da abort-uje. Cekamo koordinatorov COMMIT_TX/ROLLBACK_TX (koji se po §2.9
            // retransmituje neograniceno). Status i rezervacija ostaju netaknuti.
            // (Eskalacija na STUCK takodje NE — to bi maskiralo legitimno cekanje;
            //  stale PREPARED recipient redovi su vidljivi supervizoru kroz
            //  findStaleInProgress upit ako bude potrebna ljudska provera.)
            log.debug("Reconciliation: recipient tx {}/{} stuck in PREPARED (voted YES) — "
                            + "waiting for coordinator outcome per §2.8.7, NOT abort-ing.",
                    ibTx.getTransactionRoutingNumber(), ibTx.getTransactionIdString());
            return;
        }

        // INITIATOR: outbound poruke se vec retry-uju kroz InterbankRetryScheduler.
        // Ako je transakcija ostala zaglavljena posle stale cutoff-a, eskaliramo na
        // STUCK za manuelnu supervizorsku intervenciju (§2.8 STUCK).
        markStuck(txId, "Reconciliation: initiator transaction unresolved after stale cutoff ("
                + staleMinutes + "min); escalated to STUCK for manual review.");
    }

    /**
     * Eskalacija na STUCK. Guard: samo ako je red i dalje PREPARING/PREPARED (ne
     * pregazi red koji je u medjuvremenu COMMITTED/ROLLED_BACK). {@code txRepo.save}
     * je sam transakcioni (SimpleJpaRepository), pa eksplicitan save persistuje
     * promenu bez oslanjanja na okruzujucu transakciju.
     */
    void markStuck(ForeignBankId txId, String reason) {
        txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                        txId.routingNumber(), txId.id())
                .ifPresent(ibt -> {
                    if (ibt.getStatus() == InterbankTransactionStatus.PREPARING
                            || ibt.getStatus() == InterbankTransactionStatus.PREPARED) {
                        ibt.setStatus(InterbankTransactionStatus.STUCK);
                        ibt.setFailureReason(reason);
                        ibt.setLastActivityAt(LocalDateTime.now());
                        txRepo.save(ibt);
                        log.warn("Reconciliation: tx {}/{} escalated to STUCK", txId.routingNumber(), txId.id());
                    }
                });
    }
}
