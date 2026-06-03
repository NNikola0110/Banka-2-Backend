package rs.raf.trading.recurringorder.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.trading.recurringorder.model.RecurringOrder;
import rs.raf.trading.recurringorder.repository.RecurringOrderRepository;
import rs.raf.trading.recurringorder.service.RecurringOrderService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

// ============================================================
// [B8 - Trajni nalozi (DCA / RecurringOrder) | Nosilac: Nikola Djurovic] - DONE
//
// Scheduler koji periodicno provera i izvrsava dospele trajne naloge.
//
// @EnableScheduling je gejtovano property-jem trading.scheduling.enabled
// u rs.raf.trading.config.SchedulingConfig (test profil ga gasi).
//
// Transakcione operacije se delegiraju na RecurringOrderService.executeOne()
// koji je @Transactional(REQUIRES_NEW) — Spring AOP proxy ispravno presreca
// poziv iz scheduler bean-a.
//
// Spec: Zadaci_Backend.pdf, zadatak B8.
// ============================================================
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringOrderScheduler {

    private final RecurringOrderRepository recurringOrderRepo;
    private final RecurringOrderService recurringOrderService;

    // R1 820: interval eksternalizovan (default 60s) — fixedRateString cita property
    // trading.recurring-order.cron-interval-ms (override bez rekompajliranja).
    @Scheduled(fixedRateString = "${trading.recurring-order.cron-interval-ms:60000}")
    // N3 FIX: dupli-DCA-buy guard na vise k8s replika — samo jedna replika obradi
    // dospele trajne naloge po ciklusu. lockAtMostFor 55s < fixedRate 60s (oslobodi
    // pre sledeceg tika / pri crash-u). @Version je in-process backup ako lock istekne
    // usred dugog ciklusa.
    @SchedulerLock(name = "RecurringOrderScheduler_processRecurringOrders",
            lockAtMostFor = "PT55S", lockAtLeastFor = "PT0S")
    public void processRecurringOrders() {
        runCycle();
    }

    public void runCycle() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<RecurringOrder> due = recurringOrderRepo.findDue(now);

        log.info("RecurringOrderScheduler: {} dospelih naloga za {}", due.size(), now);

        for (RecurringOrder order : due) {
            try {
                recurringOrderService.executeOne(order);
            } catch (Exception e) {
                log.error("Scheduler: greska pri izvrsavanju trajnog naloga id={}: {}",
                        order.getId(), e.getMessage(), e);
            }
        }
    }
}
