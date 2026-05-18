package rs.raf.trading.stock.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Istorijski podaci o ceni hartije na dnevnom nivou.
 * Koristi se za prikaz grafika promene cene.
 *
 * Specifikacija: Celina 3 - ListingDailyPriceInfo
 */
@Entity
@Table(name = "listing_daily_prices")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListingDailyPriceInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @Column(nullable = false)
    private LocalDate date;

    @Column(precision = 18, scale = 4)
    private BigDecimal price;

    @Column(precision = 18, scale = 4)
    private BigDecimal high;

    @Column(precision = 18, scale = 4)
    private BigDecimal low;

    @Column(name = "price_change", precision = 18, scale = 4)
    private BigDecimal change;

    private Long volume;
}
