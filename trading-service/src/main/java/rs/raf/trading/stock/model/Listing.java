package rs.raf.trading.stock.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Osnovni entitet za sve hartije od vrednosti (akcije, futures, forex).
 * Cuva trenutne cene i osnovne podatke.
 *
 * Specifikacija: Celina 3 - Listings
 */
@Entity
@Table(name = "listings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String ticker;

    @Column(nullable = false)
    private String name;

    @Column(name = "exchange_acronym")
    private String exchangeAcronym;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ListingType listingType;

    @Column(precision = 18, scale = 4)
    private BigDecimal price;

    @Column(precision = 18, scale = 4)
    private BigDecimal ask;

    @Column(precision = 18, scale = 4)
    private BigDecimal bid;

    private Long volume;

    @Column(precision = 18, scale = 4)
    private BigDecimal priceChange;

    @Column(name = "last_refresh")
    private LocalDateTime lastRefresh;

    // ==========================================
    // POLJA SPECIFICNA ZA TIP HARTIJE
    // ==========================================

    // --- STOCKS (akcije) ---
    @Column(name = "outstanding_shares")
    private Long outstandingShares;

    @Column(name = "dividend_yield", precision = 10, scale = 4)
    private BigDecimal dividendYield;

    // --- FOREX ---
    @Column(name = "base_currency")
    private String baseCurrency;

    @Column(name = "quote_currency")
    private String quoteCurrency;

    private String liquidity;

    // --- FUTURES ---
    @Column(name = "contract_size")
    private Integer contractSize;

    @Column(name = "contract_unit")
    private String contractUnit;

    @Column(name = "settlement_date")
    private java.time.LocalDate settlementDate;

    // ==========================================
    // IZVEDENI PODACI (racunaju se, ne cuvaju se obavezno)
    // ==========================================
}
