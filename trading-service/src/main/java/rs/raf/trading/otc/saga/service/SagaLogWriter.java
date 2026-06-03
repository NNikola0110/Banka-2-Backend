package rs.raf.trading.otc.saga.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.otc.saga.model.SagaLog;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;

/**
 * <b>P1-1 — write-ahead durabilnost SAGA loga.</b>
 *
 * <p>Perzistuje {@link SagaLog} lifecycle zapise (RUNNING create, per-faza append,
 * status flip-ove, kompenzator append) u <b>SVOJOJ</b> {@link Propagation#REQUIRES_NEW}
 * transakciji, NEZAVISNO od pozivaocevog (orchestratorovog) {@code @Transactional}
 * konteksta. Tako se SAGA log komituje (write-ahead) i prezivi outer-tx rollback /
 * pad koordinatora: posle pada {@code saga_logs} red ostaje u bazi pa ga
 * {@link rs.raf.trading.otc.saga.scheduler.SagaRecoveryService#recoverOnce()}
 * (preko {@code findByStatusIn([RUNNING,COMPENSATING])}) nalazi i gura ka terminalu.
 *
 * <p><b>Bitno — forward bocni efekti OSTAJU u outer tx.</b> Ovaj writer dira SAMO
 * {@code saga_logs} red; portfolio/contract mutacije (F2/F4/F5) ostaju u
 * orchestratorovoj outer transakciji (njih kompenzacija logicki ponistava u istom
 * in-process run-u). Zato write-ahead semantika: forward bocni efekat koji se kasnije
 * rollback-uje NE ponistava vec-komitovane log zapise — log belezi NAMERU/PROGRES, a
 * recovery rekonciliuje.
 *
 * <p><b>Detached-entity bezbednost.</b> Posto je {@code SagaLog} deljen in-memory
 * objekat kroz vise REQUIRES_NEW save-ova (svaki commit zatvara svoj persistence
 * context → objekat postaje detached), {@link #persist(SagaLog)} sinhronizuje
 * {@code id} i {@code @Version} nazad iz vracene (merge-ovane) managed instance, tako
 * da sledeci {@code save} (merge) ne pada na stale-version optimistic-lock proveri.
 */
@Service
public class SagaLogWriter {

    private final SagaLogRepository sagaLogRepository;

    public SagaLogWriter(SagaLogRepository sagaLogRepository) {
        this.sagaLogRepository = sagaLogRepository;
    }

    /**
     * Komituje trenutno stanje prosledjenog {@link SagaLog} u zasebnoj
     * {@code REQUIRES_NEW} transakciji i sinhronizuje {@code id}/{@code version}
     * nazad u in-memory instancu (merge vraca managed kopiju).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(SagaLog saga) {
        SagaLog saved = sagaLogRepository.saveAndFlush(saga);
        // saveAndFlush detached entiteta radi merge → vraca managed kopiju sa azuriranim
        // id/version. Sinhronizuj nazad da sledeci REQUIRES_NEW merge ne padne na @Version.
        saga.setId(saved.getId());
        saga.setVersion(saved.getVersion());
    }
}
