package rs.raf.trading.investmentfund.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rs.raf.trading.investmentfund.model.FundDividendDistributionLedger;

import java.util.List;
import java.util.Optional;

/**
 * <b>P1-2.</b> Trajni per-klijent guard isplate fondovske dividende.
 * Pre transfera proveravamo {@link #existsByIdempotencyKey(String)} /
 * {@link #findBySourceDividendInflowTxIdAndClientUserId} da bismo preskocili
 * klijente koji su vec placeni za isti priliv (idempotentno kroz cron re-run-ove).
 */
@Repository
public interface FundDividendDistributionLedgerRepository
        extends JpaRepository<FundDividendDistributionLedger, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<FundDividendDistributionLedger> findBySourceDividendInflowTxIdAndClientUserId(
            Long sourceDividendInflowTxId, Long clientUserId);

    List<FundDividendDistributionLedger> findByFundIdAndCycleKey(Long fundId, String cycleKey);
}
