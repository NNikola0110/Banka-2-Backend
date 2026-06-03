package rs.raf.trading.order.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StopOrderActivationService {

    private static final Logger log = LoggerFactory.getLogger(StopOrderActivationService.class);

    private final OrderRepository orderRepository;
    private final ListingRepository listingRepository;

    @Transactional
    public void checkAndActivateStopOrders() {
        log.info("Starting stop order activation check...");

        // 1. Dohvatiti sve APPROVED naloge koji nisu zavrseni [cite: 58, 68]
        List<Order> activeOrders = orderRepository.findByStatusAndIsDoneFalse(OrderStatus.APPROVED);

        // Filtriramo samo STOP i STOP_LIMIT tipove [cite: 69]
        List<Order> stopOrders = activeOrders.stream()
                .filter(o -> o.getOrderType() == OrderType.STOP || o.getOrderType() == OrderType.STOP_LIMIT)
                .toList();

        if (stopOrders.isEmpty()) {
            return;
        }

        for (Order order : stopOrders) {
            try {
                // 2a. Dohvatiti azuriranu cenu listinga [cite: 70]
                Listing listing = listingRepository.findById(order.getListing().getId()).orElse(null);
                if (listing == null) {
                    log.warn("Listing not found for order #{}. Skipping.", order.getId());
                    continue;
                }

                // 2b. Dohvatiti trzisnu cenu za triger.
                // P1-dividends-order-1 (1321 / spec §329-331,358-360): STOP i STOP_LIMIT
                // se aktiviraju po ASK (BUY) / BID (SELL), NE po poslednjoj (last) ceni.
                //   - Buy [Stop|Stop-Limit]: okida kad ASK >= stopValue (po asku zapravo kupujemo)
                //   - Sell [Stop|Stop-Limit]: okida kad BID <= stopValue (po bidu zapravo prodajemo)
                // Fallback na last price ako ask/bid nije dostupan (seed listinzi bez kotacije).
                BigDecimal triggerPrice = (order.getDirection() == OrderDirection.BUY)
                        ? listing.getAsk()
                        : listing.getBid();
                if (triggerPrice == null) {
                    triggerPrice = listing.getPrice();
                }
                if (triggerPrice == null || triggerPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                if (order.getStopValue() == null) {
                    log.warn("Stop order #{} is missing stopValue. Skipping.", order.getId());
                    continue;
                }

                // 2c. Provera stop uslova po specifikaciji [cite: 72, 74]
                boolean shouldActivate = false;
                if (order.getDirection() == OrderDirection.BUY) {
                    // BUY stop/stop-limit: aktivira se kad ASK >= stopValue (§329/358)
                    if (triggerPrice.compareTo(order.getStopValue()) >= 0) {
                        shouldActivate = true;
                    }
                } else if (order.getDirection() == OrderDirection.SELL) {
                    // SELL stop/stop-limit: aktivira se kad BID <= stopValue (§331/360)
                    if (triggerPrice.compareTo(order.getStopValue()) <= 0) {
                        shouldActivate = true;
                    }
                }

                // 2d. Aktivacija naloga ako je uslov ispunjen
                if (shouldActivate) {
                    OrderType originalType = order.getOrderType();

                    if (originalType == OrderType.STOP) {
                        // STOP postaje MARKET [cite: 76]; pricePerUnit = trzisna cena
                        // po kojoj se izvrsava (ask za BUY, bid za SELL).
                        order.setOrderType(OrderType.MARKET);
                        order.setPricePerUnit(triggerPrice);
                    } else if (originalType == OrderType.STOP_LIMIT) {
                        // STOP_LIMIT postaje LIMIT
                        order.setOrderType(OrderType.LIMIT);
                        order.setPricePerUnit(order.getLimitValue());
                    }

                    // 2e. Azuriranje metapodataka i cuvanje
                    order.setLastModification(LocalDateTime.now());
                    orderRepository.save(order);

                    log.info("Stop order #{} activated: {} -> {}, trigger price: {}, stop value: {}",
                            order.getId(), originalType, order.getOrderType(),
                            triggerPrice, order.getStopValue());
                }

            } catch (Exception e) {
                log.error("Critical error processing stop order #{}: {}", order.getId(), e.getMessage());
            }
        }
    }
}
