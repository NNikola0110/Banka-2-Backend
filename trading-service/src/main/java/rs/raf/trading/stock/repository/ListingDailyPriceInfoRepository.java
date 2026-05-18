package rs.raf.trading.stock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rs.raf.trading.stock.model.ListingDailyPriceInfo;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ListingDailyPriceInfoRepository extends JpaRepository<ListingDailyPriceInfo, Long> {

    List<ListingDailyPriceInfo> findByListingIdOrderByDateDesc(Long listingId);

    List<ListingDailyPriceInfo> findByListingIdAndDate(Long listingId, LocalDate date);

    List<ListingDailyPriceInfo> findByListingIdAndDateAfterOrderByDateDesc(Long listingId, LocalDate date);
}
