package rs.raf.trading.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.stock.model.Listing;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cisto-logicki test {@link ListingPriceService} — porten verbatim iz monolita
 * (faza 2c, samo package rename).
 */
@DisplayName("ListingPriceService")
class ListingPriceServiceTest {

    private final ListingPriceService service = new ListingPriceService();

    private Listing listing(BigDecimal price, BigDecimal ask, BigDecimal bid) {
        Listing l = new Listing();
        l.setPrice(price);
        l.setAsk(ask);
        l.setBid(bid);
        return l;
    }

    private CreateOrderDto dtoWithLimit(BigDecimal limitValue) {
        CreateOrderDto dto = new CreateOrderDto();
        dto.setLimitValue(limitValue);
        return dto;
    }

    @Nested
    @DisplayName("getPricePerUnit")
    class GetPricePerUnit {

        @Test
        @DisplayName("MARKET BUY → listing.ask")
        void marketBuyUsesAsk() {
            Listing l = listing(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"));
            BigDecimal result = service.getPricePerUnit(new CreateOrderDto(), l, OrderType.MARKET, OrderDirection.BUY);
            assertEquals(new BigDecimal("101"), result);
        }

        @Test
        @DisplayName("MARKET SELL → listing.bid")
        void marketSellUsesBid() {
            Listing l = listing(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"));
            BigDecimal result = service.getPricePerUnit(new CreateOrderDto(), l, OrderType.MARKET, OrderDirection.SELL);
            assertEquals(new BigDecimal("99"), result);
        }

        @Test
        @DisplayName("LIMIT → limitValue")
        void limitUsesLimitValue() {
            Listing l = listing(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"));
            BigDecimal result = service.getPricePerUnit(dtoWithLimit(new BigDecimal("105")), l, OrderType.LIMIT, OrderDirection.BUY);
            assertEquals(new BigDecimal("105"), result);
        }

        @Test
        @DisplayName("STOP BUY → listing.ask")
        void stopBuyUsesAsk() {
            Listing l = listing(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"));
            BigDecimal result = service.getPricePerUnit(new CreateOrderDto(), l, OrderType.STOP, OrderDirection.BUY);
            assertEquals(new BigDecimal("101"), result);
        }

        @Test
        @DisplayName("STOP SELL → listing.bid")
        void stopSellUsesBid() {
            Listing l = listing(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"));
            BigDecimal result = service.getPricePerUnit(new CreateOrderDto(), l, OrderType.STOP, OrderDirection.SELL);
            assertEquals(new BigDecimal("99"), result);
        }

        @Test
        @DisplayName("STOP_LIMIT → limitValue")
        void stopLimitUsesLimitValue() {
            Listing l = listing(new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"));
            BigDecimal result = service.getPricePerUnit(dtoWithLimit(new BigDecimal("98")), l, OrderType.STOP_LIMIT, OrderDirection.SELL);
            assertEquals(new BigDecimal("98"), result);
        }
    }

    @Nested
    @DisplayName("calculateApproximatePrice")
    class CalculateApproximatePrice {

        @Test
        @DisplayName("contractSize × pricePerUnit × quantity")
        void basicCalculation() {
            BigDecimal result = service.calculateApproximatePrice(2, new BigDecimal("150.00"), 5);
            assertEquals(new BigDecimal("1500.0000"), result);
        }

        @Test
        @DisplayName("contractSize=1 ekvivalentno je pricePerUnit × quantity")
        void contractSizeOne() {
            BigDecimal result = service.calculateApproximatePrice(1, new BigDecimal("200.00"), 3);
            assertEquals(new BigDecimal("600.0000"), result);
        }

        @Test
        @DisplayName("decimalni pricePerUnit — zaokruzuje se na 4 decimale")
        void decimalPricePerUnit() {
            BigDecimal result = service.calculateApproximatePrice(1, new BigDecimal("10.12345"), 3);
            assertEquals(0, new BigDecimal("30.3704").compareTo(result));
        }
    }
}
