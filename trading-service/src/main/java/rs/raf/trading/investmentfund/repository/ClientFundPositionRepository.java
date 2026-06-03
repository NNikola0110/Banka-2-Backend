package rs.raf.trading.investmentfund.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.trading.investmentfund.model.ClientFundPosition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ClientFundPositionRepository extends JpaRepository<ClientFundPosition, Long> {

    Optional<ClientFundPosition> findByFundIdAndUserIdAndUserRole(Long fundId, Long userId, String userRole);

    List<ClientFundPosition> findByFundId(Long fundId);

    List<ClientFundPosition> findByUserIdAndUserRole(Long userId, String userRole);

    /**
     * R1 793 — agregacija na DB nivou (COALESCE-SUM) umesto fetch-svih-pozicija +
     * in-memory reduce. Vraca 0 kad fond nema pozicija (ne null).
     */
    @Query("SELECT COALESCE(SUM(p.totalInvested), 0) FROM ClientFundPosition p WHERE p.fundId = :fundId")
    BigDecimal sumTotalInvestedByFundId(@Param("fundId") Long fundId);
}
