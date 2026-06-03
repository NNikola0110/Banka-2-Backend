package rs.raf.trading.stock.service;

import org.springframework.data.domain.Page;
import rs.raf.trading.stock.dto.ListingDailyPriceDto;
import rs.raf.trading.stock.dto.ListingDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ListingService {

    /**
     * Vraca stranicu hartija od vrednosti filtrirano po tipu.
     * Tipovi: STOCK, FUTURES, FOREX
     * Klijenti vide samo STOCK i FUTURES.
     * Aktuari vide sve.
     */
    Page<ListingDto> getListings(String type, String search, int page, int size);

    /**
     * Vraca stranicu hartija sa naprednim filterima.
     */
    Page<ListingDto> getListings(String type, String search,
                                 String exchangePrefix,
                                 BigDecimal priceMin, BigDecimal priceMax,
                                 LocalDate settlementDateFrom, LocalDate settlementDateTo,
                                 int page, int size);

    /**
     * Vraca detalje za jednu hartiju po ID-ju.
     * Ukljucuje izvedene podatke (marketCap, maintenanceMargin, initialMarginCost).
     */
    ListingDto getListingById(Long id);

    /**
     * P2-perf-nplus1-1 (R1 515): batch-resolve vise hartija po ID-ju jednim
     * {@code findAllById} (+ batch testMode lookup) umesto N pojedinacnih
     * {@code getListingById} poziva. Vraca mapu {@code listingId → ListingDto};
     * nepostojeci ID-evi se izostavljaju. Koristi ga {@code WatchlistService.listItems}.
     */
    java.util.Map<Long, ListingDto> getListingsByIds(java.util.Collection<Long> ids);

    /**
     * Vraca istorijske cene za hartiju za dati period.
     * Period: DAY, WEEK, MONTH, YEAR, FIVE_YEARS, ALL
     */
    List<ListingDailyPriceDto> getListingHistory(Long listingId, String period);

    /**
     * Osvezava cene hartija iz eksternog API-ja.
     * Poziva se:
     * 1. Automatski svakih 15 minuta (@Scheduled)
     * 2. Rucno od strane korisnika (dugme "Osvezi")
     * 3. Nakon izvrsavanja operacije
     */
    void refreshPrices();
}
