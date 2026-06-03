package rs.raf.trading.order.scheduler;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.raf.trading.order.service.OrderExecutionService;
import rs.raf.trading.order.service.StopOrderActivationService;

/**
 * Scheduler komponenta koja periodicno pokrece proveru i izvrsavanje naloga.
 *
 * Specifikacija: Celina 3 - Order Execution Scheduler
 *
 * Dva schedulovana zadatka:
 * 1. processStopOrders — svakih 30 sekundi proverava STOP/STOP_LIMIT naloge
 * 2. executeApprovedOrders — svakih 10 sekundi izvrsava APPROVED MARKET/LIMIT naloge
 *
 * NAPOMENA (post-cutover 2f): {@code @Scheduled} je AKTIVAN. {@link rs.raf.trading.config.SchedulingConfig}
 * nosi {@code @EnableScheduling} (gejtovano property-jem {@code trading.scheduling.enabled},
 * default true; uspavan samo u test profilu). Monolitna kopija order-execution
 * schedulera je ugasena cutover-om, pa trading-service jedini okida ove poslove
 * (nema vise rizika od duplog izvrsavanja / duplog gadjanja banka-core API-ja).
 */
@Component
@RequiredArgsConstructor
public class OrderScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderScheduler.class);

    private final StopOrderActivationService stopOrderActivationService;
    private final OrderExecutionService orderExecutionService;

    /**
     * Proverava STOP i STOP_LIMIT naloge svakih 30 sekundi.
     * Ako trzisna cena dostigne stop vrednost, nalog se aktivira
     * (STOP -> MARKET, STOP_LIMIT -> LIMIT).
     */
    @Scheduled(fixedRate = 30000)
    // N3 FIX: na vise k8s replika samo jedna aktivira stop-ordere po ciklusu.
    // lockAtMostFor 25s < fixedRate 30s da se lock oslobodi pre sledeceg tika
    // i pri crash-u; lockAtLeastFor 0 (kratak posao moze cesto da se vrti).
    @SchedulerLock(name = "OrderScheduler_processStopOrders",
            lockAtMostFor = "PT25S", lockAtLeastFor = "PT0S")
    public void processStopOrders() {

        log.debug("Stop order check cycle started");

            try {
                stopOrderActivationService.checkAndActivateStopOrders();
            } catch (Exception e) {
                log.error("Error processing stop orders: {}", e.getMessage(), e);
            }

        log.debug("Stop order check cycle completed");
    }

    /**
     * Izvrsava APPROVED naloge (MARKET i LIMIT) svakih 10 sekundi.
     * Za svaki nalog pokusava da izvrsi jedan parcijalni fill.
     */
    @Scheduled(fixedRate = 10000)
    // N3 FIX: kljucni double-fill guard na vise replika — samo jedna replika izvrsava
    // APPROVED ordere po ciklusu. lockAtMostFor 9s < fixedRate 10s.
    @SchedulerLock(name = "OrderScheduler_executeApprovedOrders",
            lockAtMostFor = "PT9S", lockAtLeastFor = "PT0S")
    public void executeApprovedOrders() {

        log.debug("Execute approved orders cycle started");

             try {
                 orderExecutionService.executeOrders();
             } catch (Exception e) {
                 log.error("Error executing approved orders: {}", e.getMessage(), e);
             }

         // R1-723 (NAMERNO, ne TODO): ciklus se NE preskace za zatvorenu berzu —
         // demo simulacija radi 24/7 (kontinuirano fill-ovanje + osvezavanje cena),
         // a after-hours nalozi se ionako izvrsavaju van radnog vremena berze. Gejt
         // po radnom vremenu bi blokirao demo van burzanskih sati i ne donosi nista
         // u simulaciji bez pravog orderbook-a. Per-fill after-hours kasnjenje
         // (SingleOrderExecutor.computeNextFillDelay) je dovoljna aproksimacija.

        log.debug("Execute approved orders cycle completed");
    }
}
