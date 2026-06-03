package rs.raf.trading.order.service;

import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Servis za izvrsavanje odobrenih naloga (APPROVED) — orkestrator tick-a.
 *
 * Specifikacija: Celina 3 - Order Execution Engine
 *
 * Simulira izvrsavanje naloga na berzi koristeci parcijalno punjenje (partial fills).
 * Podrzava: MARKET, LIMIT, AON (all-or-none), after-hours naloge.
 *
 * <p><b>P2-3 (per-order tx izolacija):</b> ranije je {@code executeOrders()} bila
 * jedna velika {@code @Transactional} metoda koja petlja preko SVIH izvrsivih
 * ordera. Ugnjezdeni {@code @Transactional(REQUIRED)} poziv koji baci oznaci
 * deljenu transakciju rollback-only → na outer commit-u Spring baca
 * {@code UnexpectedRollbackException} → cela mutacija tick-a (krediti portfolia,
 * {@code remainingPortions} dekrementi DRUGIH ordera) se rollback-uje, dok su
 * banka-core novcani pomeraji vec commit-ovani van procesa → torn state.
 * <p>Sad {@code executeOrders()} <b>NIJE transakciona</b> — samo petlja sa
 * eligibility guard-ima (citanje + time check, bez DB mutacije) — a obrada
 * SVAKOG ordera ide kroz {@link SingleOrderExecutor#execute(Order)} koji je
 * {@code @Transactional(REQUIRES_NEW)} (poziv kroz proxy, ne self-invocation).
 * Tako jedan order koji baci ne truje tick: njegova tx se rollback-uje izolovano,
 * uspesni orderi commit-uju.
 *
 * STOP i STOP_LIMIT nalozi se ovde NE izvrsavaju — oni se prvo aktiviraju
 * u StopOrderActivationService pa postaju MARKET/LIMIT.
 */
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private static final Logger log = LoggerFactory.getLogger(OrderExecutionService.class);

    private final OrderRepository orderRepository;

    /**
     * P2-3: per-order izvrsavanje u sopstvenoj ({@code REQUIRES_NEW}) transakciji.
     * Mora biti zaseban bean da bi Spring proxy otvorio novu tx (self-invocation
     * ne bi otvorila novu).
     */
    private final SingleOrderExecutor singleOrderExecutor;

    /**
     * W2-T1: histogramska distribucija trajanja izvrsavanja jednog ordera.
     */
    private final Timer orderExecutionTimer;

    /** Minimalan broj sekundi izmedju approval-a i prvog fill pokusaja (Phase 6). */
    @Value("${orders.execution.initial-delay-seconds:60}")
    private long initialDelaySeconds;

    /**
     * Dodatan delay za after-hours naloge (u sekundama). Spec trazi 30 min
     * po svakom fill-u; za demo se moze skratiti (npr. 60s u dev-u).
     */
    @Value("${orders.afterhours.delay-seconds:1800}")
    private long afterHoursDelaySeconds;

    /**
     * P2-3: petlja preko svih izvrsivih ordera. NIJE {@code @Transactional} —
     * svaki order se obradjuje u svojoj {@code REQUIRES_NEW} tx kroz
     * {@link SingleOrderExecutor}. Eligibility guard-i (settlement date,
     * fill interval) ostaju ovde; svaki order je u sopstvenom try/catch da
     * jedna greska ne srusi tick.
     */
    public void executeOrders() {
        // 1. Dohvatiti sve APPROVED naloge koji nisu zavrseni
        List<Order> activeOrders = orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED);

        // 2. Filtrirati samo MARKET i LIMIT naloge
        // STOP i STOP_LIMIT su vec pretvoreni u MARKET/LIMIT u proslom zadatku
        List<Order> executableOrders = activeOrders.stream()
                .filter(o -> o.getOrderType() == OrderType.MARKET || o.getOrderType() == OrderType.LIMIT)
                .toList();

        log.info("Starting execution cycle for {} orders.", executableOrders.size());

        LocalDateTime now = LocalDateTime.now();
        for (Order order : executableOrders) {
            try {
                // 3. Fill eligibility guard (samo citanje + time check, bez DB mutacije).
                // Ako nalog ima setovan nextFillAt (posle prvog uspesnog fill-a),
                // koristi ga direktno — spec zahteva random interval izmedju
                // fill-ova + 30 min bonus za after-hours naloge PO SVAKOM fill-u.
                // Ako jos nije bilo fill-a, primeni standardni initialDelay
                // guard koristeci approvedAt/createdAt.
                if (order.getNextFillAt() != null) {
                    if (now.isBefore(order.getNextFillAt())) {
                        log.debug("Order #{} not yet eligible — next fill at {}",
                                order.getId(), order.getNextFillAt());
                        continue;
                    }
                } else {
                    LocalDateTime referenceTime = order.getApprovedAt() != null
                            ? order.getApprovedAt()
                            : order.getCreatedAt();
                    if (referenceTime != null) {
                        long requiredDelay = order.isAfterHours()
                                ? initialDelaySeconds + afterHoursDelaySeconds
                                : initialDelaySeconds;
                        if (Duration.between(referenceTime, now).getSeconds() < requiredDelay) {
                            log.debug("Order #{} not yet eligible for execution (needs {}s delay)",
                                    order.getId(), requiredDelay);
                            continue;
                        }
                    }
                }

                // 4. Izvrsavanje pojedinacnog naloga u SOPSTVENOJ (REQUIRES_NEW) tx.
                // Settlement-date provera + auto-decline su sada deo SingleOrderExecutor-a
                // (per-order, da bi mutacija commit-ovala izolovano).
                executeSingleOrder(order);

            } catch (Exception e) {
                // 5. Wrap u try-catch da greska na jednom nalogu ne srusi celu petlju.
                // Posto je svaki order u svojoj REQUIRES_NEW tx, neuspeli order se
                // rollback-uje izolovano — ostali orderi u tick-u ostaju commit-ovani
                // (nema UnexpectedRollbackException jer outer petlja nije transakciona).
                log.error("Critical error executing order #{}: {}", order.getId(), e.getMessage());
            }
        }
    }

    /**
     * W2-T1: timer obuhvata ceo fill flow jednog ordera. Delegira na
     * {@link SingleOrderExecutor} kroz proxy (REQUIRES_NEW tx po orderu).
     */
    void executeSingleOrder(Order order) {
        Timer.Sample sample = Timer.start();
        try {
            singleOrderExecutor.execute(order);
        } finally {
            sample.stop(orderExecutionTimer);
        }
    }
}
