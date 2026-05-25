package rs.raf.trading.investmentfund.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rs.raf.trading.investmentfund.model.FundValueSnapshot;

import java.time.LocalDate;
import java.util.List;

public interface FundValueSnapshotRepository extends JpaRepository<FundValueSnapshot, Long> {

    List<FundValueSnapshot> findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            Long fundId, LocalDate from, LocalDate to);

    /**
     * Sve dnevne snapshot-ove za zadati fond u hronoloskom redu (od najstarijeg).
     * Koristi {@link rs.raf.trading.investmentfund.service.FundStatisticsService}
     * za racunanje annualized return / volatility / max drawdown / Sharpe-like ratio.
     */
    List<FundValueSnapshot> findByFundIdOrderBySnapshotDateAsc(Long fundId);

    boolean existsByFundIdAndSnapshotDate(Long fundId, LocalDate snapshotDate);
}
