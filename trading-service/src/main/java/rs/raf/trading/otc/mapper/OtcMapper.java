package rs.raf.trading.otc.mapper;

import rs.raf.trading.otc.dto.OtcContractDto;
import rs.raf.trading.otc.dto.OtcOfferDto;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcOffer;
import rs.raf.trading.stock.model.Listing;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class OtcMapper {
    private OtcMapper() {}

    public static OtcOfferDto toDto(OtcOffer offer, String buyerName, String sellerName,
                                    String listingCurrency, Long viewerUserId) {
        OtcOfferDto dto = new OtcOfferDto();
        dto.setId(offer.getId());
        Listing listing = offer.getListing();
        dto.setListingId(listing != null ? listing.getId() : null);
        dto.setListingTicker(listing != null ? listing.getTicker() : null);
        dto.setListingName(listing != null ? listing.getName() : null);
        dto.setListingCurrency(listingCurrency);
        dto.setBuyerId(offer.getBuyerId());
        dto.setBuyerName(buyerName);
        dto.setSellerId(offer.getSellerId());
        dto.setSellerName(sellerName);
        dto.setQuantity(offer.getQuantity());
        dto.setPricePerStock(offer.getPricePerStock());
        dto.setPremium(offer.getPremium());
        dto.setCurrentPrice(listing != null ? listing.getPrice() : null);
        dto.setSettlementDate(offer.getSettlementDate());
        dto.setLastModifiedById(offer.getLastModifiedById());
        dto.setLastModifiedByName(offer.getLastModifiedByName());
        dto.setWaitingOnUserId(offer.getWaitingOnUserId());
        dto.setMyTurn(viewerUserId != null && viewerUserId.equals(offer.getWaitingOnUserId()));
        dto.setStatus(offer.getStatus() != null ? offer.getStatus().name() : null);
        dto.setCreatedAt(offer.getCreatedAt());
        dto.setLastModifiedAt(offer.getLastModifiedAt());
        return dto;
    }

    public static OtcContractDto toDto(OtcContract contract, String buyerName, String sellerName,
                                       String listingCurrency, BigDecimal currentPrice) {
        OtcContractDto dto = new OtcContractDto();
        dto.setId(contract.getId());
        Listing listing = contract.getListing();
        dto.setListingId(listing != null ? listing.getId() : null);
        dto.setListingTicker(listing != null ? listing.getTicker() : null);
        dto.setListingName(listing != null ? listing.getName() : null);
        dto.setListingCurrency(listingCurrency);
        dto.setBuyerId(contract.getBuyerId());
        dto.setBuyerName(buyerName);
        dto.setSellerId(contract.getSellerId());
        dto.setSellerName(sellerName);
        dto.setQuantity(contract.getQuantity());
        dto.setStrikePrice(contract.getStrikePrice());
        dto.setPremium(contract.getPremium());
        dto.setCurrentPrice(currentPrice);
        dto.setProfit(computeProfit(currentPrice, contract.getStrikePrice(), contract.getQuantity(),
                contract.getPremium()));
        dto.setSettlementDate(contract.getSettlementDate());
        dto.setStatus(contract.getStatus() != null ? contract.getStatus().name() : null);
        dto.setCreatedAt(contract.getCreatedAt());
        dto.setExercisedAt(contract.getExercisedAt());
        return dto;
    }

    /**
     * Izvedeni profit za prikaz u tabeli "Sklopljeni ugovori" (Celina 4 §149):
     * neto dobit ako se call opcija odmah iskoristi =
     * {@code (currentPrice − strikePrice) × quantity − premium}.
     * Spec primeri: Celina 4 "Sklopljeni ugovori" (AAPL current 250, strike 200, qty 50,
     * premium 1150 → 1350) i Celina 5 Primer 1 (12500 − 10000 − 1150 = 1350).
     * Null kad trenutna cena/strike/quantity nisu poznati (verno {@code currentPrice}).
     * Null premium se tretira kao 0. Scale 4, HALF_UP — ista konvencija kao strike/premium kolone.
     */
    private static BigDecimal computeProfit(BigDecimal currentPrice, BigDecimal strikePrice,
                                            Integer quantity, BigDecimal premium) {
        if (currentPrice == null || strikePrice == null || quantity == null) {
            return null;
        }
        BigDecimal premiumValue = premium != null ? premium : BigDecimal.ZERO;
        return currentPrice.subtract(strikePrice)
                .multiply(BigDecimal.valueOf(quantity))
                .subtract(premiumValue)
                .setScale(4, RoundingMode.HALF_UP);
    }
}
