package rs.raf.trading.watchlist.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import rs.raf.trading.watchlist.model.Watchlist;
import rs.raf.trading.watchlist.model.WatchlistOwnerType;

import java.util.List;
import java.util.Optional;

public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    List<Watchlist> findByOwnerIdAndOwnerTypeOrderByCreatedAtAsc(Long ownerId, WatchlistOwnerType ownerType);

    Optional<Watchlist> findByIdAndOwnerIdAndOwnerType(Long id, Long ownerId, WatchlistOwnerType ownerType);

    boolean existsByOwnerIdAndOwnerTypeAndName(Long ownerId, WatchlistOwnerType ownerType, String name);
}