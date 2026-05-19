package rs.raf.trading.recurringorder.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import rs.raf.trading.recurringorder.repository.RecurringOrderRepository;
import rs.raf.trading.recurringorder.service.RecurringOrderService;

// ============================================================
// TODO [B8 - Trajni nalozi (DCA / RecurringOrder) | Nosilac: Nikola Djurovic]
//
// Scheduler koji periodicno provera i izvrsava dospele trajne naloge.
// Prati obrazac trgovinskih schedulera (npr. OrderScheduler) — transakcione operacije delegira na
// RecurringOrderService da bi @Transactional(REQUIRES_NEW) Spring AOP proxy
// ispravno presreo pozive (direktni `this.method()` pozivi bi bili ignorisani).
//
// IMPLEMENTIRATI — dodati @Scheduled metodu i helper:
//
//   @Scheduled(fixedRate = 60_000)     // svakih 60 sekundi
//   public void processRecurringOrders()
//       -> Poziva runCycle()
//       -> Raspon 60s je dovoljan jer je najfrekventnija kadenca DAILY;
//          za demonstraciju/test okruzenje moze se smanjiti na 10_000
//
//   public void runCycle()
//       -> LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC)
//       -> List<RecurringOrder> due = recurringOrderRepo.findDue(now)
//          (ili findByActiveTrue() pa filter na nextRun <= now)
//       -> log.info("RecurringOrderScheduler: {} dospelih naloga za {}", due.size(), now)
//       -> Za svaki RecurringOrder r u due:
//            try {
//                recurringOrderService.executeOne(r);
//            } catch (Exception e) {
//                log.error("Scheduler: greska pri izvrsavanju trajnog naloga id={}: {}",
//                          r.getId(), e.getMessage(), e);
//                // nastaviti sa sledecim — jedna greska ne sme blokirati ostatak
//            }
//
// Injectovati kao final polja:
//   - RecurringOrderRepository recurringOrderRepo
//   - RecurringOrderService recurringOrderService
//
// NAPOMENA: @EnableScheduling je vec aktivno u trading-service-u
// (rs.raf.trading.config.SchedulingConfig — gejtovano property-jem
// trading.scheduling.enabled, default true; test profil ga gasi). NE dodavati
// duplikat @EnableScheduling.
//
// Konvencija: pratiti trgovinski scheduler `rs.raf.trading.order.scheduler.OrderScheduler` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B8.
// ============================================================
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringOrderScheduler {

    private final RecurringOrderRepository recurringOrderRepo;
    private final RecurringOrderService recurringOrderService;
}
