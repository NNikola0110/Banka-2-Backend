package rs.raf.trading.pricealert.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// ============================================================
// TODO [B5 - Cenovni alarmi (Price Alert) | Nosilac: Aleksa Vucinic]
//
// Scheduled komponenta koja periodicno pokusava da obare alarme
// cija cena je vec presla prag, ali koji nisu okidani u realnom
// vremenu (npr. zbog pada servera, privremene nedostupnosti
// Alpha Vantage-a, ili alarma postavljenih retroaktivno).
//
// Napomena (mikroservisi): ovaj paket zivi u `trading-service`. Trzisne cene
// hartija (Listing) su LOKALNE — citaju se direktno iz ListingRepository, bez
// HTTP poziva. @Scheduled je aktivan preko SchedulingConfig (property
// trading.scheduling.enabled, default true; test profil ga gasi).
//
// IMPLEMENTIRATI:
//
// Zavisnosti (polja za injekciju):
//   - PriceAlertRepository alertRepository
//       Za dohvatanje aktivnih alarma koji jos nisu okidani.
//   - PriceAlertService priceAlertService
//       Za poziv checkAlerts(listingId, currentPrice) po listingu.
//   - ListingRepository listingRepository (paket rs.raf.trading.stock.repository)
//       Za dohvatanje trenutnih cena svih hartija na kojima postoje
//       aktivni alarmi — LOKALNO u trading-service-u.
//
// Metoda koja SE MORA implementirati:
//
//   @Scheduled(fixedRate = 60_000)   // svakih 60 sekundi
//   public void scanActiveAlerts()
//       Tok:
//         1. alertRepository.findDistinctListingIdsByActiveTrue()
//            (ovu metodu takodje dodati u PriceAlertRepository —
//            @Query("SELECT DISTINCT a.listingId FROM PriceAlert a
//                    WHERE a.active = true"))
//            -> List<Long> listingIds
//         2. Za svaki listingId:
//            a. Dohvatiti trenutnu cenu hartije iz listings tabele
//               (listing.getCurrentPrice() ili ekvivalentno polje
//               u projektu — proveriti sa koordinatorom koji paket
//               cuva trzisnu cenu).
//            b. Pozvati priceAlertService.checkAlerts(listingId, price).
//            c. Uhvatiti svaki Exception po listingu (try/catch),
//               logovati na WARN i nastaviti sa sledecim — greska
//               jednog listinga ne sme da zaustavi ceo scan.
//         3. log.info na kraju: "PriceAlertScheduler: scan zavrsen,
//            {} listinga provereno" sa brojacem.
//
// Napomena: @Scheduled metoda namerno NIJE deklarisana u skeleton-u
// (bila bi pokrenuta prazna i ne bi radila nista korisno). Koordinator
// ce je dodati nakon implementacije service-a. @EnableScheduling je vec
// aktivno u trading-service-u (rs.raf.trading.config.SchedulingConfig) —
// NE dodavati duplikat. Metoda ne sme biti @Transactional direktno —
// delegira transakcije na PriceAlertService.
//
// Konvencija: pratiti trgovinski scheduler `rs.raf.trading.order.scheduler.OrderScheduler` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B5.
// ============================================================
@Component
@RequiredArgsConstructor
@Slf4j
public class PriceAlertScheduler {
}
