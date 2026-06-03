package rs.raf.trading.order.service;

import org.springframework.stereotype.Service;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.stock.model.Listing;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class ListingPriceService {

    public BigDecimal getPricePerUnit(CreateOrderDto dto, Listing listing, OrderType orderType, OrderDirection direction) {
        return switch (orderType) {
            case MARKET -> direction == OrderDirection.BUY ? listing.getAsk() : listing.getBid();
            // P1-dividends-order-1 (1544 / spec §387): Price Per Unit za STOP nalog je
            // Stop Value (NE ask/bid). Pre fix-a se koristio ask/bid pa je approximatePrice
            // bio potcenjen (stopValue je tipicno iznad ask-a za BUY) -> rezervacija premala
            // -> commit moze premasiti rezervaciju. Fallback na ask/bid samo ako stopValue
            // nije zadat (defanzivno, ne bi trebalo da se desi za STOP nalog).
            case STOP -> dto.getStopValue() != null
                    ? dto.getStopValue()
                    : (direction == OrderDirection.BUY ? listing.getAsk() : listing.getBid());
            case LIMIT, STOP_LIMIT -> dto.getLimitValue();
        };
    }

    public BigDecimal calculateApproximatePrice(int contractSize, BigDecimal pricePerUnit, int quantity) {
        return BigDecimal.valueOf(contractSize)
                .multiply(pricePerUnit)
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
