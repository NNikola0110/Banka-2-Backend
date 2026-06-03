package rs.raf.trading.order.mapper;

import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.model.*;
import rs.raf.trading.stock.model.Listing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Centralizovano mapiranje Order <-> OrderDto.
 */
public final class OrderMapper {

    private OrderMapper() {}

    public static OrderDto toDto(Order order) {
        return toDto(order, null);
    }

    public static OrderDto toDto(Order order, String userName) {
        if (order == null) return null;

        OrderDto dto = new OrderDto();
        dto.setId(order.getId());
        dto.setListingId(order.getListing() != null ? order.getListing().getId() : null);
        dto.setUserName(userName);
        dto.setUserRole(order.getUserRole());
        dto.setListingTicker(order.getListing() != null ? order.getListing().getTicker() : null);
        dto.setListingName(order.getListing() != null ? order.getListing().getName() : null);
        dto.setListingType(order.getListing() != null && order.getListing().getListingType() != null
                ? order.getListing().getListingType().name() : null);
        dto.setOrderType(order.getOrderType() != null ? order.getOrderType().name() : null);
        dto.setQuantity(order.getQuantity());
        dto.setContractSize(order.getContractSize());
        dto.setPricePerUnit(order.getPricePerUnit());
        dto.setLimitValue(order.getLimitValue());
        dto.setStopValue(order.getStopValue());
        dto.setDirection(order.getDirection() != null ? order.getDirection().name() : null);
        dto.setStatus(order.getStatus() != null ? order.getStatus().name() : null);
        dto.setApprovedBy(order.getApprovedBy());
        dto.setDone(order.isDone());
        dto.setLastModification(order.getLastModification());
        dto.setRemainingPortions(order.getRemainingPortions());
        dto.setAfterHours(order.isAfterHours());
        dto.setAllOrNone(order.isAllOrNone());
        dto.setMargin(order.isMargin());
        dto.setCreatedAt(order.getCreatedAt());
        dto.setAccountId(order.getAccountId());
        dto.setApproximatePrice(resolveApproximatePrice(order));
        dto.setListingSettlementDate(order.getListing() != null ? order.getListing().getSettlementDate() : null);
        dto.setFxCommission(order.getFxCommission());
        dto.setExchangeRate(order.getExchangeRate());
        dto.setFundId(order.getFundId());

        return dto;
    }

    public static Order fromCreateDto(CreateOrderDto dto, Listing listing) {
        Order order = new Order();
        order.setListing(listing);
        order.setOrderType(OrderType.valueOf(dto.getOrderType()));
        order.setQuantity(dto.getQuantity());
        order.setContractSize(dto.getContractSize());
        order.setLimitValue(dto.getLimitValue());
        order.setStopValue(dto.getStopValue());
        order.setDirection(OrderDirection.valueOf(dto.getDirection()));
        order.setAllOrNone(dto.isAllOrNone());
        order.setMargin(dto.isMargin());
        order.setAccountId(dto.getAccountId());
        order.setRemainingPortions(dto.getQuantity());
        order.setDone(false);
        order.setCreatedAt(LocalDateTime.now());
        order.setLastModification(LocalDateTime.now());
        return order;
    }

    /**
     * R1-720: vraca PERSISTOVANU {@code approximatePrice} (vrednost koja je
     * stvarno rezervisana/koriscena u order-flow-u — {@code OrderServiceImpl}
     * je upisuje pri kreiranju). Rekalkulacija iz {@code pricePerUnit×qty×cs}
     * moze da DIVERGIRA od persistovane vrednosti (npr. ako se {@code pricePerUnit}
     * naknadno azurira fill-om), pa je DTO prikazivao drugaciji iznos od onog
     * koji je rezervisan. Fallback na rekalkulaciju samo za legacy ordere bez
     * persistovane vrednosti (stari seed pre uvodjenja kolone).
     */
    private static BigDecimal resolveApproximatePrice(Order order) {
        if (order.getApproximatePrice() != null) {
            return order.getApproximatePrice();
        }
        return calculateApproximatePrice(order);
    }

    private static BigDecimal calculateApproximatePrice(Order order) {
        if (order.getPricePerUnit() == null || order.getQuantity() == null) return null;
        // OT-1048: default contractSize po tipu hartije (FOREX → 1000 per spec §162),
        // ne slepo 1 — uskladjeno sa OrderServiceImpl rezervacijom i ListingMapper
        // display-em. Produkcioni order uvek nosi persistovani contractSize (postavlja
        // ga OrderServiceImpl iz listinga), pa je ovo fallback samo za legacy order-e
        // bez te kolone; tip se uzima sa order.listing kad postoji.
        rs.raf.trading.stock.model.ListingType type =
                order.getListing() != null ? order.getListing().getListingType() : null;
        int cs = rs.raf.trading.stock.model.ContractSize.resolve(order.getContractSize(), type);
        return BigDecimal.valueOf(cs)
                .multiply(order.getPricePerUnit())
                .multiply(BigDecimal.valueOf(order.getQuantity()))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
