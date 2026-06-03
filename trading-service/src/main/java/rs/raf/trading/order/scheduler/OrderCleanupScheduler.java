package rs.raf.trading.order.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.FundReservationService;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduler za ciscenje ordera kojima je prosao settlement datum.
 *
 * Pokrece se svaki dan u 01:00 ujutru. Pronalazi PENDING ili APPROVED
 * ordere za hartije ciji je settlementDate prosao i postavlja ih na
 * DECLINED.
 *
 * Specifikacija: Celina 3 - "Kod hartija koje imaju settlement date,
 * i gde je taj datum prosao, postoji samo Decline opcija."
 *
 * NAPOMENA (post-cutover 2f): {@code @Scheduled} je AKTIVAN — {@link rs.raf.trading.config.SchedulingConfig}
 * nosi {@code @EnableScheduling} (gejtovano {@code trading.scheduling.enabled},
 * uspavan samo u test profilu). Monolitna kopija ovog cleanup posla je ugasena
 * cutover-om, pa trading-service jedini izvrsava settlement-date cleanup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCleanupScheduler {

    private final OrderRepository orderRepository;
    private final NotificationService notificationService;
    private final FundReservationService fundReservationService;
    private final PortfolioRepository portfolioRepository;

    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void cleanupExpiredOrders() {
        log.info("Pokrecem ciscenje ordera sa isteklim settlement datumom...");

        List<Order> candidates = orderRepository.findActiveNonDone();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        int declinedCount = 0;

        for (Order order : candidates) {
            LocalDate settlement = order.getListing() != null
                    ? order.getListing().getSettlementDate()
                    : null;
            if (settlement == null || !settlement.isBefore(today)) {
                continue;
            }
            // P1-dividends-order-1 (164): pre DECLINED-a oslobodi rezervaciju, inace
            // sredstva (BUY) / rezervisane hartije (SELL) ostaju zauvek zakljucani (leak).
            // Samo APPROVED orderi imaju rezervaciju (PENDING jos nema). Best-effort:
            // pad release-a ne sme da spreci declime.
            if (order.getStatus() == OrderStatus.APPROVED && !order.isReservationReleased()) {
                releaseReservationSafe(order);
            }
            order.setStatus(OrderStatus.DECLINED);
            order.setApprovedBy("SYSTEM - Settlement date expired");
            order.setLastModification(now);
            orderRepository.save(order);
            log.info("Order {} (user={}, listing={}) declined - settlement {} passed",
                    order.getId(), order.getUserId(),
                    order.getListing().getTicker(), settlement);
            try {
                notificationService.notify(
                        order.getUserId(),
                        order.getUserRole(),
                        NotificationType.ORDER_CANCELLED,
                        "Nalog automatski otkazan",
                        "Vaš nalog za " + order.getListing().getTicker() + " je automatski otkazan jer je datum dospeća prošao.",
                        "ORDER",
                        order.getId()
                );
            } catch (Exception ex) {
                log.warn("Failed to send order cancelled notification for order #{}: {}", order.getId(), ex.getMessage());
            }
            declinedCount++;
        }

        log.info("Ciscenje zavrseno. Ukupno odbijeno: {}", declinedCount);
    }

    /**
     * P1-dividends-order-1 (164): oslobadja rezervaciju isteklog APPROVED ordera.
     * BUY -> {@link FundReservationService#releaseForBuy} (idempotentno, sam rukuje
     * margin granom); SELL -> oslobodi rezervisanu kolicinu hartija. Best-effort:
     * pad release-a se loguje i guta da jedan fail ne srusi ciscenje.
     */
    private void releaseReservationSafe(Order order) {
        try {
            if (order.getDirection() == OrderDirection.BUY) {
                fundReservationService.releaseForBuy(order);
            } else {
                Portfolio portfolio = portfolioRepository
                        .findByUserIdAndUserRole(order.getUserId(), order.getUserRole()).stream()
                        .filter(p -> order.getListing() != null
                                && p.getListingId().equals(order.getListing().getId()))
                        .findFirst()
                        .orElse(null);
                if (portfolio != null) {
                    fundReservationService.releaseForSell(order, portfolio);
                } else {
                    order.setReservationReleased(true);
                }
            }
        } catch (Exception ex) {
            log.warn("Order #{} settlement-cleanup release rezervacije nije uspeo: {}",
                    order.getId(), ex.getMessage());
        }
    }
}
