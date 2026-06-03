package rs.raf.banka2_bek.interbank.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.interbank.model.InterbankTransaction;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/*
================================================================================
 REPOSITORY: InterbankTransaction (PROTOKOL §2.8)
 Spec ref: protokol §2.8.5 Remote transaction execution
--------------------------------------------------------------------------------
 SVRHA:
  Lookup po protokol-formi transactionId-a (routingNumber + idString) i
  pretrage za retry scheduler / supervisor portala.
================================================================================
*/
public interface InterbankTransactionRepository extends JpaRepository<InterbankTransaction, Long> {

    /**
     * §2.8.2 — lookup po (routingNumber, idString) paru.
     * Koristi se kad primamo COMMIT_TX ili ROLLBACK_TX za vec poznatu
     * transakciju, ili pri retry-u.
     */
    Optional<InterbankTransaction> findByTransactionRoutingNumberAndTransactionIdString(
            int transactionRoutingNumber, String transactionIdString);

    /**
     * Pessimistic-write lookup po (routingNumber, idString) paru — koristi se u
     * {@code commitLocal}/{@code rollbackLocal} da serijalizuje konkurentne
     * duplicate-delivery COMMIT_TX/ROLLBACK_TX poruke (§2.9 dozvoljava duplikat
     * dostavu). Bez locka, dve paralelne COMMIT_TX bi obe procitale PREPARED
     * stanje (COMMITTED-guard se evaluira pre bilo kakve serijalizacije) i obe
     * usle u petlju primene postinga → Monas leg (commitMonas/commitRecipientCredit)
     * koji NEMA idempotency kljuc bi se primenio dvaput (dupli debit/credit, novac
     * se kreira/unistava). Lock + re-citanje statusa pod lock-om cini drugu
     * isporuku no-op.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from InterbankTransaction t "
            + "where t.transactionRoutingNumber = :routingNumber "
            + "and t.transactionIdString = :idString")
    Optional<InterbankTransaction> findForUpdateByTransactionRoutingNumberAndTransactionIdString(
            @Param("routingNumber") int transactionRoutingNumber,
            @Param("idString") String transactionIdString);

    List<InterbankTransaction> findByStatusIn(List<InterbankTransactionStatus> statuses);

    /**
     * Za supervisor portal — sve "stuck" transakcije koje treba pregledati.
     * Stuck = PREPARING/PREPARED bez aktivnosti duze od cutoff-a, ili
     * eksplicitno STUCK status.
     */
    @Query("select t from InterbankTransaction t " +
            "where t.status in :statuses " +
            "and (t.lastActivityAt is null or t.lastActivityAt < :cutoff)")
    List<InterbankTransaction> findStaleInProgress(@Param("statuses") List<InterbankTransactionStatus> statuses,
                                                    @Param("cutoff") LocalDateTime cutoff);
}
