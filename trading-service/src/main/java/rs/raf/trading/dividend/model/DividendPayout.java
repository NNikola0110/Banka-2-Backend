package rs.raf.trading.dividend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "dividend_payouts",
        indexes = {
                @Index(name = "idx_dp_owner", columnList = "owner_id, owner_type"),
                @Index(name = "idx_dp_listing_date", columnList = "stock_listing_id, payment_date")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DividendPayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID vlasnika — clientId ili employeeId zavisno od ownerType. */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** Diskriminator vlasnika: "CLIENT" ili "EMPLOYEE". */
    @Column(name = "owner_type", nullable = false, length = 16)
    private String ownerType;

    /** ID listinga (Listing.id) za koji se dividenda placa. */
    @Column(name = "stock_listing_id", nullable = false)
    private Long stockListingId;

    /** Ticker hartije — denormalizovan radi citljivosti. */
    @Column(name = "stock_ticker", nullable = false, length = 32)
    private String stockTicker;

    /** Broj akcija u vlasnistvu na dan obracuna. */
    @Column(nullable = false)
    private Integer quantity;

    /** Cena akcije na dan obracuna (Listing.price). */
    @Column(name = "price_on_date", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceOnDate;

    /** Kvartalni prinos (dividendYield / 4). */
    @Column(name = "dividend_yield_rate", nullable = false, precision = 8, scale = 6)
    private BigDecimal dividendYieldRate;

    /** Bruto iznos: quantity * priceOnDate * dividendYieldRate. */
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount;

    /** Porez 15% na grossAmount; 0 za EMPLOYEE (oslobodjeni). */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal tax;

    /** Neto iznos koji je knjizen na racun (grossAmount - tax). */
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    /** ID racuna na koji je dividenda knjizena (banka-core accountId). */
    @Column(name = "credited_account_id", nullable = false)
    private Long creditedAccountId;

    /** Valuta isplate (valuta ciljnog racuna). */
    @Column(name = "currency_code", nullable = false, length = 8)
    private String currencyCode;

    /** Datum isplate (poslednji radni dan kvartala). */
    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    /**
     * true za EMPLOYEE — oslobodjeni poreza na kapitalnu dobit.
     *
     * <p>Default je {@code "0"} (NE {@code "false"}) jer je ova Boolean kolona
     * mapirana kao {@code INTEGER} ({@code hibernate.type.preferred_boolean_jdbc_type=INTEGER}).
     * Na pravom PostgreSQL-u {@code @ColumnDefault("false")} emituje
     * {@code tax_exempt integer not null default false} sto PG odbija
     * (boolean default na integer koloni) → tabela {@code dividend_payouts}
     * se NE kreira → svaki runtime save/find puca + idempotency-guard ostaje
     * prazan → dupla dividenda na cron retry-u. H2 (MODE=PostgreSQL) ovo guta
     * jer ima implicit boolean↔int cast, pa je bug nevidljiv pod H2 testovima.
     */
    @ColumnDefault("0")
    @Column(name = "tax_exempt", nullable = false)
    private Boolean taxExempt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
