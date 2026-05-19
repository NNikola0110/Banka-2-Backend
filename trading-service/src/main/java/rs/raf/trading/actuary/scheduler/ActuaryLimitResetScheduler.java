package rs.raf.trading.actuary.scheduler;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;

/**
 * Scheduler koji svakodnevno resetuje iskorisceni dnevni limit svih aktuara.
 *
 * Specifikacija: Celina 3 - Aktuari
 *
 * Pokrece se svaki dan u 23:59 i postavlja usedLimit na 0 za sve aktuare,
 * cime se priprema novi dnevni limit za sledeci dan.
 *
 * NAPOMENA (copy-first ekstrakcija, faza 2c): @Scheduled je USPAVAN do
 * cutover-a (2f) — TradingServiceApplication namerno NEMA @EnableScheduling,
 * pa se ovaj cron ne aktivira. Monolit zadrzava svoju kopiju i radi posao
 * dok KT3 ide sa monolitom.
 */
@Component
@RequiredArgsConstructor
public class ActuaryLimitResetScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActuaryLimitResetScheduler.class);

    private final ActuaryInfoRepository actuaryInfoRepository;

    /**
     * Resetuje usedLimit na 0 za sve aktuare.
     * Cron: svaki dan u 23:59:00.
     */
    @Scheduled(cron = "0 59 23 * * *")
    @Transactional
    public void resetDailyLimits() {
        log.info("Starting daily actuary limit reset");
        try {
            int updatedCount = actuaryInfoRepository.resetAllUsedLimits();
            log.info("Daily actuary limit reset completed — {} actuaries reset", updatedCount);
        } catch (Exception e) {
            log.error("Error during daily actuary limit reset: {}", e.getMessage(), e);
        }
    }
}
