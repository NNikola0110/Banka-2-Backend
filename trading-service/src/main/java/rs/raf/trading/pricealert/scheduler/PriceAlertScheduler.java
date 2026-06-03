package rs.raf.trading.pricealert.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.trading.pricealert.repository.PriceAlertRepository;
import rs.raf.trading.pricealert.service.PriceAlertService;

import java.util.List;

/**
 * [B5 - Cenovni alarmi] Periodicni scanner aktivnih alarma.
 *
 * <p>Svake minute (60s) cita {@code listingId}-eve sa aktivnim alarmima i pita
 * {@code PriceAlertService.checkAlertsForListings} da okine sve koji su ispunili
 * uslov.
 *
 * <p>R2-1384 (stale-price) FIX: scheduler vise NE cita {@code Listing} entitete
 * sam (u zasebnoj tx) pa ih prosledjuje servisu — to je davalo stale cenu izmedju
 * scheduler reada i evaluacije. Sada prosledjuje samo {@code listingId}-eve, a
 * {@code checkAlertsForListings} cita SVEZU cenu UNUTAR svoje transakcije.
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
    private final PriceAlertService priceAlertService;

    /**
     * Skenira sve listing-e sa aktivnim alarmima i prosledjuje service-u
     * {@code listingId}-eve na evaluaciju (sveza cena se cita u servis-tx). Greske
     * se logiraju ali ne propagiraju (scheduler mora da preživi privremene padove).
     *
     * <p>P2-state-machine-1 (R1 512): {@code fixedDelay} (NE {@code fixedRate}).
     * {@code fixedRate} pokrece sledeci ciklus tacno na 60s bez obzira da li je
     * prethodni jos u toku — spor scan (mnogo listing-a × banka-core lookup) bi
     * doveo do PREKLAPAJUCIH instanci koje se utrkuju nad istim alarmima
     * (dvostruko okidanje / dvostruka notifikacija). {@code fixedDelay} broji 60s
     * od KRAJA prethodnog ciklusa → nikad preklapanje u istom JVM-u. Vrednost je
     * konfigurabilna preko {@code trading.pricealert.fixed-delay-ms}.
     *
     * <p>Multi-replica jednokratnost (vise instanci servisa) ostaje van scope-a
     * ovog state-machine fixa — to je cross-replica ShedLock tema (zaseban
     * concurrency batch); {@code fixedDelay} resava intra-JVM preklapanje.
     */
    @Scheduled(fixedDelayString = "${trading.pricealert.fixed-delay-ms:60000}")
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
            int triggered = priceAlertService.checkAlertsForListings(listingIds);
            if (triggered > 0) {
                log.info("PriceAlertScheduler: scan zavrsen, {} alarm(a) okidano, {} listing-a provereno",
                        triggered, listingIds.size());
            } else {
                log.debug("PriceAlertScheduler: scan zavrsen, 0 alarma okidano, {} listing-a provereno",
                        listingIds.size());
            }
        } catch (RuntimeException ex) {
            log.warn("PriceAlertScheduler: evaluacija pukla: {}", ex.getMessage());
        }
    }
}
