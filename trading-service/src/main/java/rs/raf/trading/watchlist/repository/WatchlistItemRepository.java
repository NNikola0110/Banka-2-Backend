package rs.raf.trading.watchlist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.watchlist.model.WatchlistItem;

import java.util.List;
import java.util.Optional;

public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

    List<WatchlistItem> findByWatchlistIdOrderByAddedAtAsc(Long watchlistId);

    Optional<WatchlistItem> findByWatchlistIdAndListingId(Long watchlistId, Long listingId);

    @Modifying
    @Transactional
    void deleteByWatchlistIdAndListingId(Long watchlistId, Long listingId);

    @Modifying
    @Transactional
    void deleteAllByWatchlistId(Long watchlistId);

    boolean existsByWatchlistIdAndListingId(Long watchlistId, Long listingId);

    /** [P2-input-validation-1 / R1 517] broj stavki u listi (DoS limit guard). */
    long countByWatchlistId(Long watchlistId);
}