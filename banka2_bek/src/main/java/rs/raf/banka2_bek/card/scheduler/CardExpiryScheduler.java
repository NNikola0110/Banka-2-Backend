package rs.raf.banka2_bek.card.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.banka2_bek.card.service.CardService;

import java.time.LocalDate;

/**
 * R1 317 — dnevni scheduler koji deaktivira istekle kartice.
 *
 * <p>Problem koji resava: kartica ima {@code expirationDate} (postavljen na
 * created + 4 godine pri izdavanju), ali NIJEDAN proces nije menjao status kad
 * datum prodje — pa je istekla kartica ostajala ACTIVE <em>zauvek</em>. Usage
 * gate za prepaid/placanja je {@code status == ACTIVE} (vidi
 * {@code CardServiceImpl}), pa bi istekla kartica i dalje bila upotrebljiva.
 *
 * <p>Strategija: jednom dnevno (03:30) pozovemo
 * {@link CardService#expireDueCards(LocalDate)} koji prebaci sve kartice sa
 * {@code expirationDate < danas} (a koje jos nisu DEACTIVATED) u status
 * DEACTIVATED. Operacija je idempotentna — vec deaktivirane kartice se ne
 * pokupe, pa ponovno pokretanje istog dana ne radi nista.
 *
 * <p>Aktivacija je gejtovana globalnim {@code banka2.scheduling.enabled}
 * property-jem (preko {@code SchedulingConfig}); test profil ga gasi pa
 * scheduler ostaje inertan tokom unit/integration testova (metoda se poziva
 * eksplicitno u unit testu).
 */
@Slf4j
@Component
public class CardExpiryScheduler {

    private final CardService cardService;

    public CardExpiryScheduler(CardService cardService) {
        this.cardService = cardService;
    }

    /**
     * Pokrece se svakodnevno u 03:30 — deaktivira sve istekle kartice.
     * Greska se logira ali ne propagira (scheduler mora da prezivi).
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void deactivateExpiredCards() {
        LocalDate today = LocalDate.now();
        try {
            int count = cardService.expireDueCards(today);
            if (count > 0) {
                log.info("CardExpiryScheduler: deaktivirano {} isteklih kartica (datum={})", count, today);
            } else {
                log.debug("CardExpiryScheduler: nema isteklih kartica za deaktivaciju (datum={})", today);
            }
        } catch (RuntimeException ex) {
            log.error("CardExpiryScheduler: greska pri deaktivaciji isteklih kartica: {}", ex.getMessage(), ex);
        }
    }
}
