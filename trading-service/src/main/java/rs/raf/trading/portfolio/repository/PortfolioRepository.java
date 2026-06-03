package rs.raf.trading.portfolio.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rs.raf.trading.portfolio.model.Portfolio;

import java.util.List;
import java.util.Optional;

@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {

    /**
     * Vraca sve portfolios za (userId, userRole). Posto clients.id i employees.id
     * imaju preklapajuce prostore, uloga je obavezna da se izbegne cross-owner curenje.
     */
    List<Portfolio> findByUserIdAndUserRole(Long userId, String userRole);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Portfolio p WHERE p.id = :id")
    Optional<Portfolio> findByIdForUpdate(@Param("id") Long id);

    Optional<Portfolio> findByUserIdAndUserRoleAndListingId(Long userId, String userRole, Long listingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Portfolio p WHERE p.userId = :userId AND p.userRole = :userRole AND p.listingId = :listingId")
    Optional<Portfolio> findByUserIdAndUserRoleAndListingIdForUpdate(@Param("userId") Long userId,
                                                                    @Param("userRole") String userRole,
                                                                    @Param("listingId") Long listingId);

    /**
     * Vraca sve Portfolio pozicije tipa STOCK sa quantity > 0.
     * Koristi ga DividendService za kvartalnu isplatu dividendi (B9).
     */
    @Query("SELECT p FROM Portfolio p WHERE p.quantity > 0 AND p.listingType = 'STOCK'")
    List<Portfolio> findAllStockPositionsWithQuantity();

    /**
     * P2-perf-nplus1-1 (R5 1898 / R5 1900): vraca SAMO pozicije sa
     * {@code publicQuantity > 0} (DB-side filter). Zamenjuje {@code findAll()} +
     * in-memory filter koji je bio pun-table-scan + GC pritisak nad CELOM
     * {@code portfolios} tabelom (OTC discovery + interbank public-stock seam).
     */
    @Query("SELECT p FROM Portfolio p WHERE p.publicQuantity > 0")
    List<Portfolio> findAllWithPublicQuantity();
}
