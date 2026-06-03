package rs.raf.trading.order.mapper;

import org.junit.jupiter.api.Test;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.model.*;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMapperTest {

    @Test
    void toDto_null_returnsNull() {
        assertThat(OrderMapper.toDto(null)).isNull();
    }

    @Test
    void toDto_withAllFields_mapsCorrectly() {
        Listing listing = new Listing();
        listing.setId(100L);
        listing.setTicker("AAPL");
        listing.setName("Apple Inc.");
        listing.setListingType(ListingType.STOCK);
        listing.setSettlementDate(LocalDate.of(2026, 6, 1));

        Order order = new Order();
        order.setId(1L);
        order.setListing(listing);
        order.setUserRole("EMPLOYEE");
        order.setOrderType(OrderType.MARKET);
        order.setQuantity(10);
        order.setContractSize(1);
        order.setPricePerUnit(BigDecimal.valueOf(150.0));
        order.setLimitValue(BigDecimal.valueOf(155.0));
        order.setStopValue(BigDecimal.valueOf(145.0));
        order.setDirection(OrderDirection.BUY);
        order.setStatus(OrderStatus.PENDING);
        order.setApprovedBy("admin@banka.rs");
        order.setDone(false);
        order.setRemainingPortions(10);
        order.setAfterHours(true);
        order.setAllOrNone(false);
        order.setMargin(true);
        order.setAccountId(5L);
        order.setCreatedAt(LocalDateTime.of(2026, 4, 1, 10, 0));
        order.setLastModification(LocalDateTime.of(2026, 4, 1, 11, 0));

        OrderDto dto = OrderMapper.toDto(order, "Marko Petrovic");

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getListingId()).isEqualTo(100L);
        assertThat(dto.getUserName()).isEqualTo("Marko Petrovic");
        assertThat(dto.getUserRole()).isEqualTo("EMPLOYEE");
        assertThat(dto.getListingTicker()).isEqualTo("AAPL");
        assertThat(dto.getListingName()).isEqualTo("Apple Inc.");
        assertThat(dto.getListingType()).isEqualTo("STOCK");
        assertThat(dto.getOrderType()).isEqualTo("MARKET");
        assertThat(dto.getQuantity()).isEqualTo(10);
        assertThat(dto.getContractSize()).isEqualTo(1);
        assertThat(dto.getDirection()).isEqualTo("BUY");
        assertThat(dto.getStatus()).isEqualTo("PENDING");
        assertThat(dto.getApprovedBy()).isEqualTo("admin@banka.rs");
        assertThat(dto.isDone()).isFalse();
        assertThat(dto.getRemainingPortions()).isEqualTo(10);
        assertThat(dto.isAfterHours()).isTrue();
        assertThat(dto.isAllOrNone()).isFalse();
        assertThat(dto.isMargin()).isTrue();
        assertThat(dto.getAccountId()).isEqualTo(5L);
        assertThat(dto.getListingSettlementDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        // Approximate price = 1 * 150 * 10 = 1500
        assertThat(dto.getApproximatePrice()).isEqualByComparingTo(BigDecimal.valueOf(1500));
    }

    @Test
    void toDto_withoutUserName_userNameIsNull() {
        Order order = new Order();
        order.setId(2L);
        order.setListing(null);
        order.setOrderType(null);
        order.setDirection(null);
        order.setStatus(null);

        OrderDto dto = OrderMapper.toDto(order);

        assertThat(dto.getUserName()).isNull();
        assertThat(dto.getListingId()).isNull();
        assertThat(dto.getListingTicker()).isNull();
        assertThat(dto.getListingName()).isNull();
        assertThat(dto.getListingType()).isNull();
        assertThat(dto.getOrderType()).isNull();
        assertThat(dto.getDirection()).isNull();
        assertThat(dto.getStatus()).isNull();
        assertThat(dto.getListingSettlementDate()).isNull();
    }

    @Test
    void toDto_nullPricePerUnit_approximatePriceIsNull() {
        Order order = new Order();
        order.setId(3L);
        order.setPricePerUnit(null);
        order.setQuantity(10);

        OrderDto dto = OrderMapper.toDto(order);

        assertThat(dto.getApproximatePrice()).isNull();
    }

    @Test
    void toDto_nullQuantity_approximatePriceIsNull() {
        Order order = new Order();
        order.setId(4L);
        order.setPricePerUnit(BigDecimal.TEN);
        order.setQuantity(null);

        OrderDto dto = OrderMapper.toDto(order);

        assertThat(dto.getApproximatePrice()).isNull();
    }

    @Test
    void toDto_nullContractSize_noListing_defaultsToOne() {
        // OT-1048: legacy order bez persistovanog contractSize-a i bez listing-a →
        // tip se ne moze razresiti → neutralni default 1.
        Order order = new Order();
        order.setId(5L);
        order.setPricePerUnit(BigDecimal.valueOf(100));
        order.setQuantity(5);
        order.setContractSize(null);

        OrderDto dto = OrderMapper.toDto(order);

        // 1 * 100 * 5 = 500
        assertThat(dto.getApproximatePrice()).isEqualByComparingTo(BigDecimal.valueOf(500));
    }

    @Test
    void toDto_nullContractSize_forexListing_defaultsTo1000_OT1048() {
        // OT-1048 [FIXED 02.06]: legacy FOREX order bez persistovanog contractSize-a →
        // default se razresava po tipu hartije (FOREX → 1000 per spec §162), NE 1.
        // Uskladjeno sa OrderServiceImpl rezervacijom i ListingMapper display-em.
        Listing forex = new Listing();
        forex.setListingType(ListingType.FOREX);
        Order order = new Order();
        order.setId(55L);
        order.setListing(forex);
        order.setPricePerUnit(BigDecimal.valueOf(1.10));
        order.setQuantity(2);
        order.setContractSize(null);

        OrderDto dto = OrderMapper.toDto(order);

        // 1000 * 1.10 * 2 = 2200
        assertThat(dto.getApproximatePrice()).isEqualByComparingTo(BigDecimal.valueOf(2200));
    }

    @Test
    void toDto_withContractSize_calculatesCorrectly() {
        Order order = new Order();
        order.setId(6L);
        order.setPricePerUnit(BigDecimal.valueOf(50));
        order.setQuantity(2);
        order.setContractSize(100);

        OrderDto dto = OrderMapper.toDto(order);

        // 100 * 50 * 2 = 10000
        assertThat(dto.getApproximatePrice()).isEqualByComparingTo(BigDecimal.valueOf(10000));
    }

    @Test
    void toDto_persistedApproximatePrice_returnedVerbatim_R1_720() {
        // R1-720: kad je approximatePrice persistovana (vrednost koja je stvarno
        // rezervisana u order-flow-u), DTO je vraca takvu kakva jeste — NE
        // rekalkulise iz pricePerUnit×qty×cs (sto bi dalo 999, divergira od
        // persistovane 4200 npr. ako je pricePerUnit naknadno azuriran fill-om).
        Order order = new Order();
        order.setId(7L);
        order.setPricePerUnit(BigDecimal.valueOf(333));
        order.setQuantity(3);
        order.setContractSize(1);
        order.setApproximatePrice(BigDecimal.valueOf(4200)); // != 333*3 = 999

        OrderDto dto = OrderMapper.toDto(order);

        assertThat(dto.getApproximatePrice()).isEqualByComparingTo(BigDecimal.valueOf(4200));
    }

    @Test
    void fromCreateDto_mapsCorrectly() {
        Listing listing = new Listing();
        listing.setId(100L);

        CreateOrderDto dto = new CreateOrderDto();
        dto.setListingId(100L);
        dto.setOrderType("LIMIT");
        dto.setQuantity(20);
        dto.setContractSize(1);
        dto.setDirection("SELL");
        dto.setLimitValue(BigDecimal.valueOf(200));
        dto.setStopValue(BigDecimal.valueOf(180));
        dto.setAllOrNone(true);
        dto.setMargin(false);
        dto.setAccountId(7L);

        Order order = OrderMapper.fromCreateDto(dto, listing);

        assertThat(order.getListing()).isEqualTo(listing);
        assertThat(order.getOrderType()).isEqualTo(OrderType.LIMIT);
        assertThat(order.getQuantity()).isEqualTo(20);
        assertThat(order.getContractSize()).isEqualTo(1);
        assertThat(order.getDirection()).isEqualTo(OrderDirection.SELL);
        assertThat(order.getLimitValue()).isEqualByComparingTo(BigDecimal.valueOf(200));
        assertThat(order.getStopValue()).isEqualByComparingTo(BigDecimal.valueOf(180));
        assertThat(order.isAllOrNone()).isTrue();
        assertThat(order.isMargin()).isFalse();
        assertThat(order.getAccountId()).isEqualTo(7L);
        assertThat(order.getRemainingPortions()).isEqualTo(20);
        assertThat(order.isDone()).isFalse();
        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getLastModification()).isNotNull();
    }
}
