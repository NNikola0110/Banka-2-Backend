package rs.raf.trading.dividend.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// ============================================================
// TODO [B9 - Isplata dividendi na akcije | Nosilac: Djordje Zlatanovic]
//
// Kvartalani scheduler koji pokree isplatu dividendi na poslednji radni dan
// svakog kvartala (mart, juni, septembar, decembar).
//
// Napomena (mikroservisi): ovaj paket zivi u `trading-service`. @EnableScheduling
// je vec aktivno (rs.raf.trading.config.SchedulingConfig — gejtovano property-jem
// trading.scheduling.enabled, default true; test profil ga gasi). NE dodavati
// duplikat @EnableScheduling.
//
// ZAVISNOSTI ZA INJEKTOVANJE:
//   - DividendService dividendService
//
// IMPLEMENTIRATI:
//
//   @Scheduled(cron = "0 0 6 L MAR,JUN,SEP,DEC ?")
//   public void runQuarterlyDividendPayout()
//       — Pokrece dividendService.processQuarterlyDividends(LocalDate.now(ZoneOffset.UTC)).
//         Napomena: Spring @Scheduled cron ne podrzava direktno "poslednji radni dan" —
//         koristiti "L" specifikator za poslednji dan meseca (`0 0 6 L MAR,JUN,SEP,DEC ?`),
//         a u DividendService.processQuarterlyDividends dodati logiku da ako je poslednji dan
//         meseca subota/nedelja, preskociti na prethodni petak:
//           while (paymentDate.getDayOfWeek() == SATURDAY || paymentDate.getDayOfWeek() == SUNDAY)
//               paymentDate = paymentDate.minusDays(1);
//         Scheduler loguje pocetak i kraj + koliko isplata je procesirano.
//         Svaka greska na nivou jedne isplate NE sme da zaustavi ceo ciklus
//         (uhvati Exception per-payout i logovati, nastaviti sa ostalima).
//
//   Razlog za @Component (ne @Service): ovo je infrastrukturna klasa bez poslovne
//   logike. @Component jasnije oznacava ulogu — moze i @Service ako zelite.
//
//   VAZNO: NE stavljati @Scheduled unutar DividendService direktno.
//   @Transactional pozivi moraju prolaziti kroz Spring AOP proxy, sto znaci da scheduler
//   mora biti zaseban bean koji poziva service metode (transakciona granica je
//   na DividendService.payDividendForOwner).
//
// Konvencija: pratiti postojece schedulere u trading-service-u
//   (npr. rs.raf.trading.order.scheduler) kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B9.
// ============================================================

@Component
@RequiredArgsConstructor
@Slf4j
public class DividendScheduler {
}
