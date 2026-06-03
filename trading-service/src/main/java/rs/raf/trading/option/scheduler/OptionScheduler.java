package rs.raf.trading.option.scheduler;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.trading.option.service.OptionMaintenanceService;

/**
 * Scheduler za automatsko odrzavanje opcija.
 *
 * Pokrece se svakodnevno u 03:00 (cron: "0 0 3 * * *") i obavlja tri zadatka:
 *
 * 1. BRISANJE ISTEKLIH OPCIJA (brise opcije sa settlementDate &lt; danas)
 * 2. GENERISANJE NOVIH OPCIJA (za nove settlement datume)
 * 3. REKALKULACIJA CENA (Black-Scholes nad svim neisteklim opcijama)
 *
 * <p><b>P2-money-tx-1 (R3 1587):</b> stvarne transakcione jedinice su izdvojene u
 * {@link OptionMaintenanceService} (public {@code @Transactional} metode). Scheduler
 * ih zove KROZ PROXY (injektovan bean), pa {@code @Transactional} zaista vazi —
 * ranije su bile {@code protected} self-invocation metode iste klase i AOP ih je
 * potpuno zaobilazio (NO-OP tx → {@code recalculatePrices.saveAll} parcijalan bez
 * rollback-a). Per-fazni try/catch je zadrzan ovde za fault-isolation: pad jedne
 * faze (npr. cleanup) ne sme da spreci sledece (generate/recalc).
 */
@Component
@RequiredArgsConstructor
public class OptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(OptionScheduler.class);

    private final OptionMaintenanceService maintenanceService;

    /** Glavni scheduled metod -- pokrece se svakodnevno u 03:00. */
    @Scheduled(cron = "0 0 3 * * *")
    public void dailyOptionMaintenance() {
        log.info("Pocetak dnevnog odrzavanja opcija...");

        try {
            maintenanceService.cleanupExpiredOptions();
        } catch (Exception e) {
            log.error("Greska pri brisanju isteklih opcija: {}", e.getMessage(), e);
        }

        try {
            maintenanceService.generateNewOptions();
        } catch (Exception e) {
            log.error("Greska pri generisanju novih opcija: {}", e.getMessage(), e);
        }

        try {
            maintenanceService.recalculatePrices();
        } catch (Exception e) {
            log.error("Greska pri rekalkulaciji cena opcija: {}", e.getMessage(), e);
        }

        log.info("Dnevno odrzavanje opcija zavrseno.");
    }
}
