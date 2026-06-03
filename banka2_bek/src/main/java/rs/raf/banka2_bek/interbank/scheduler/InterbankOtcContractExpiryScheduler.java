package rs.raf.banka2_bek.interbank.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.banka2_bek.interbank.service.OtcNegotiationService;

/**
 * P0-B6 (Nalaz 1) — dnevno markiranje isteklih inter-bank OTC opcionih ugovora
 * kao EXPIRED + oslobadjanje sellerove rezervacije hartija.
 *
 * <p>Bug koji resava: inter-bank OTC ugovor se kreira ACTIVE pri §3.6 accept-u sa
 * {@code settlementDate}-om; sellerove hartije su rezervisane. Jedini prelaz iz
 * ACTIVE je →EXERCISED — {@code EXPIRED} se NIGDE nije upisivao, pa kad
 * settlementDate prodje bez exercise-a ugovor je ostajao TRAJNO ACTIVE a
 * sellerova rezervacija hartija stranded zauvek. Ovaj scheduler aktivira
 * {@link OtcNegotiationService#expireSettledContracts()} koji to ispravlja
 * (§2.7.2: "if that option was not used, the resources stuck in an option shall
 * be un-reserved").
 *
 * <p>Analogno intra-bank {@code OtcContractExpiryScheduler} (trading-service).
 * Cist lokalni bookkeeping — BEZ wire-promene (ne salje protokolnu poruku
 * partner banci; svaka strana lokalno oslobadja svoju rezervaciju po §2.7.2).
 *
 * <p>Cron 01:15 dnevno — namerno pomeren od 2PC reconciliation scheduler-a
 * (fixedRate) i intra-bank OTC expiry-ja (01:05/01:10) da se sweep-ovi ne
 * preklapaju.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterbankOtcContractExpiryScheduler {

    private final OtcNegotiationService otcNegotiationService;

    @Scheduled(cron = "0 15 1 * * *")
    public void expireContracts() {
        try {
            int count = otcNegotiationService.expireSettledContracts();
            if (count > 0) {
                log.info("Inter-bank OTC: {} ugovora isteklo i markirano EXPIRED", count);
            }
        } catch (RuntimeException e) {
            log.error("Inter-bank OTC expiry sweep nije uspeo: {}", e.getMessage(), e);
        }
    }
}
