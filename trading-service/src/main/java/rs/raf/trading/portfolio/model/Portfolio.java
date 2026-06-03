package rs.raf.trading.portfolio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entitet za portfolio — predstavlja kolicinu hartija od vrednosti
 * koje korisnik poseduje.
 *
 * NAPOMENA (copy-first ekstrakcija, faza 2c): kopija monolitnog
 * {@code rs.raf.banka2_bek.portfolio.model.Portfolio}. Entitet je vec bio
 * potpuno soft-id ({@code userId}, {@code listingId}) — nema JPA veza ka
 * banka-core entitetima, pa je kopija doslovna.
 */
@Entity
@Table(name = "portfolios")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ID korisnika koji poseduje hartije.
     * - Za CLIENT: {@code clients.id}
     * - Za EMPLOYEE: {@code employees.id}
     * Kombinacija (userId, userRole) je jedinstvena oznaka vlasnika, posto
     * clients i employees imaju nezavisne ID prostore koji se preklapaju.
     */
    @Column(nullable = false)
    private Long userId;

    /** Uloga vlasnika — "CLIENT" ili "EMPLOYEE". Odvaja preklapajuce ID namespaces. */
    @Column(name = "user_role", nullable = false, length = 16)
    private String userRole;

    /** ID listinga (listings tabela) — hartija od vrednosti. */
    @Column(nullable = false)
    private Long listingId;

    /** Ticker hartije — cache za brzi prikaz. */
    @Column(nullable = false, length = 20)
    private String listingTicker;

    /** Naziv hartije — cache za brzi prikaz. */
    @Column(nullable = false)
    private String listingName;

    /** Tip hartije (STOCK, FUTURES, FOREX). */
    @Column(nullable = false, length = 20)
    private String listingType;

    /** Kolicina hartija u posedu. */
    @Column(nullable = false)
    private Integer quantity;

    /** Prosecna cena kupovine po jedinici. */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal averageBuyPrice;

    /** Broj hartija koje su javno vidljive za OTC trgovinu. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private Integer publicQuantity = 0;

    /** Rezervisana kolicina (za pending SELL ordere). Default 0. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private Integer reservedQuantity = 0;

    /** Datum poslednje izmene. */
    @Column(nullable = false)
    private LocalDateTime lastModified;

    /**
     * Optimistic-locking verzija.
     *
     * <p>Bug 4 (REAL money/execution): kolona je istorijski bila nullable bez
     * default-a, a {@code trading-seed.sql} je INSERT-ovao portfolije BEZ ove
     * kolone → svi seed-ovani redovi su imali {@code version = NULL}. Hibernate
     * optimistic locking NE moze da poredi {@code NULL} verziju pa svaki UPDATE
     * seed-ovanog portfolija (SELL rezervacija, {@code updatePortfolio} pri
     * izvrsenju ordera) puca na commit-u ("Could not commit JPA transaction" →
     * 400 "No active transaction"); BUY orderi ostaju trajno zaglavljeni iako je
     * banka-core settlement vec commit-ovao (conservation break — hartije se ne
     * pripisu).
     *
     * <p>Fix (3 sloja, mirror CardSlot/SavingsInterestRate + {@code @ColumnDefault}):
     * (1) field-init {@code = 0L} → nove/loadovane instance nikad nisu null;
     * (2) {@code @ColumnDefault("0")} → sveza schema kolona ima DB default;
     * (3) startup migration {@link rs.raf.trading.persistence.PortfolioVersionColumnMigration}
     * + seed sa {@code version} kolonom → postojeci null redovi backfill-ovani na 0.
     */
    @Version
    @org.hibernate.annotations.ColumnDefault("0")
    private Long version = 0L;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.lastModified = LocalDateTime.now();
    }

    /**
     * Kolicina koja je slobodna za novu prodaju (quantity minus reservedQuantity).
     * Nije persistirano — izracunava se na osnovu postojecih polja.
     */
    @Transient
    public Integer getAvailableQuantity() {
        return quantity - (reservedQuantity == null ? 0 : reservedQuantity);
    }
}
