package rs.raf.trading.portfolio.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioItemDto {
    private Long id;
    private Long listingId;
    /*
     * [OT-1218 — REKLASIFIKOVANO 02.06] Ranije je ovde stajalo {@code optionId}
     * (tobozhe pravi {@code Option.id} za opcionu poziciju u portfoliju). Domenski
     * model NE predstavlja opcione pozicije kao {@link rs.raf.trading.portfolio.model.Portfolio}
     * redove: {@link rs.raf.trading.stock.model.ListingType} = {STOCK, FUTURES, FOREX}
     * (nema OPTION), a jedini producer ({@code OptionService.updatePortfolioBuy})
     * upisuje ticker/tip OSNOVNE akcije ("STOCK"). Zato je {@code resolveOptionId}
     * bio NEDOSTIZAN u proizvodnji (uvek vracao null) — id-kontrakt je bio korektan
     * ali na mrtvoj grani. Exercise plain-opcije je aktuar/admin operacija koja se
     * pokrece iz lanca opcija (SecuritiesDetailsPage, {@code OptionItem.id} = pravi
     * {@code Option.id}), NE iz portfolija. Polje je uklonjeno kao dead-contract.
     */
    private String listingTicker;
    private String listingName;
    private String listingType;
    private Integer quantity;
    private BigDecimal averageBuyPrice;
    private BigDecimal currentPrice;
    private BigDecimal profit;
    private BigDecimal profitPercent;
    private Integer publicQuantity;
    private LocalDateTime lastModified;
    private LocalDate settlementDate;
    private Boolean inTheMoney;
}
