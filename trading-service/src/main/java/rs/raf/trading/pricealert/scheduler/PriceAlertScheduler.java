package rs.raf.trading.pricealert.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.trading.pricealert.repository.PriceAlertRepository;
import rs.raf.trading.pricealert.service.PriceAlertService;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;

import java.util.List;

/**
 * [B5 - Cenovni alarmi] Periodicni scanner aktivnih alarma.
 *
 * <p>Svake minute (60s) cita sve aktivne alarme, povlaci svezu cenu njihovih
 * listinga lokalno (Listing entitet) i pita {@code PriceAlertService.checkAlerts}
 * da okine sve koji su ispunili uslov.
 *
 * <p>Aktivacija je gejtovana globalnim {@code trading.scheduling.enabled}
 * property-jem (preko {@code SchedulingConfig}); test profil ga gasi pa
 * scheduler ostaje inertan tokom unit/integration testova.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PriceAlertScheduler {

    private final PriceAlertRepository alertRepository;
    private final ListingRepository listingRepository;
    private final PriceAlertService priceAlertService;

    /**
     * Skenira sve listing-e sa aktivnim alarmima i prosledjuje service-u na
     * evaluaciju. Greske se logiraju ali ne propagiraju (scheduler mora da
     * preživi privremene padove).
     */
    @Scheduled(fixedRate = 60_000L)
    public void scanActiveAlerts() {
        List<Long> listingIds;
        try {
            listingIds = alertRepository.findDistinctListingIdsByActiveTrue();
        } catch (RuntimeException ex) {
            log.warn("PriceAlertScheduler: dohvat aktivnih listing-ova pukla: {}", ex.getMessage());
            return;
        }

        if (listingIds.isEmpty()) {
            log.debug("PriceAlertScheduler: nema aktivnih alarma");
            return;
        }

        try {
            List<Listing> listings = listingRepository.findAllById(listingIds);
            int triggered = priceAlertService.checkAlerts(listings);
            if (triggered > 0) {
                log.info("PriceAlertScheduler: scan zavrsen, {} alarm(a) okidano, {} listing-a provereno",
                        triggered, listings.size());
            } else {
                log.debug("PriceAlertScheduler: scan zavrsen, 0 alarma okidano, {} listing-a provereno",
                        listings.size());
            }
        } catch (RuntimeException ex) {
            log.warn("PriceAlertScheduler: evaluacija pukla: {}", ex.getMessage());
        }
    }
}
