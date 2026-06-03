package rs.raf.trading.otc.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtcContractDto {
    private Long id;

    private Long listingId;
    private String listingTicker;
    private String listingName;
    private String listingCurrency;

    private Long buyerId;
    private String buyerName;
    private Long sellerId;
    private String sellerName;

    private Integer quantity;
    private BigDecimal strikePrice;
    private BigDecimal premium;
    /** Trenutna cena listinga — koristi se za prikaz ITM/OTM na UI. */
    private BigDecimal currentPrice;
    /**
     * Izvedeni NETO profit (Celina 4 §149 "Sklopljeni ugovori"): dobit ako se call
     * opcija odmah iskoristi, umanjena za vec placenu premiju =
     * {@code (currentPrice − strikePrice) × quantity − premium}. Spec primer
     * (Celina 5 Primer 1): {@code 12500 − 10000 − 1150 = 1350}. Null kad trenutna
     * cena nije poznata (isto kao {@link #currentPrice}). Racuna ga
     * {@code OtcMapper.computeProfit}.
     */
    private BigDecimal profit;

    private LocalDate settlementDate;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime exercisedAt;
}
