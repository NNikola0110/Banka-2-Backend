package rs.raf.trading.otc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import rs.raf.trading.stock.model.Listing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Sklopljeni OTC opcioni ugovor. Kupac ima pravo (ali ne obavezu) da
 * do settlementDate-a iskoristi pravo i kupi dogovorene akcije po
 * dogovorenoj ceni. Prodavceva javna kolicina je zakljucana (rezervisana)
 * dok ugovor ne istekne ili ne bude iskoriscen.
 *
 * Spec: Celina 4 - Sklopljeni ugovori.
 */
@Entity
@Table(name = "otc_contracts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtcContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Iz kog pregovora je ugovor nastao (link ka arhivskoj ponudi). */
    @Column(name = "source_offer_id", nullable = false)
    private Long sourceOfferId;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "buyer_role", nullable = false, length = 16)
    private String buyerRole;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "seller_role", nullable = false, length = 16)
    private String sellerRole;

    @ManyToOne
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    @Column(nullable = false)
    private Integer quantity;

    /** Cena po akciji dogovorena u pregovorima (strike). */
    @Column(name = "strike_price", precision = 18, scale = 4, nullable = false)
    private BigDecimal strikePrice;

    /** Premija placena prodavcu pri prihvatanju ponude. */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal premium;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OtcContractStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "exercised_at")
    private LocalDateTime exercisedAt;

    /**
     * Buyer-ov racun na kome je rezervisan strike × qty iznos pri sklapanju
     * ugovora. Pri exercise-u trosimo sa istog racuna; pri abandon-u oslobadja.
     * (Spec Celina 4 vece-5: rezervacija sredstava kupcu + akcija prodavcu.)
     */
    @Column(name = "buyer_reserved_account_id")
    private Long buyerReservedAccountId;

    /** Iznos rezervisan u valuti buyer-ovog racuna (NE listing currency). */
    @Column(name = "buyer_reserved_amount", precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    private BigDecimal buyerReservedAmount;

    /**
     * Handle rezervacije koji vrati banka-core {@code POST /internal/funds/reserve}
     * pri sklapanju ugovora ({@code OtcService.acceptOffer}). trading-service ga
     * koristi pri exercise-u ({@code commitFunds}) i abandon/expire
     * ({@code releaseFunds}). Nullable — legacy (pre-mikroservisni) ugovori i
     * ugovori bez novcane rezervacije ga nemaju.
     *
     * NAPOMENA (copy-first ekstrakcija, faza 2d-B): kolona je aditivna; u
     * monolitu novcanu rezervaciju drze {@code Account.reservedAmount} +
     * {@code buyerReservedAccountId}/{@code buyerReservedAmount}, a u
     * trading-service-u racuni zive u banka-core domenu, pa rezervacija postaje
     * banka-core {@code FundReservation} kome je ovo identifikator.
     */
    @Column(name = "banka_core_reservation_id")
    private String bankaCoreReservationId;

    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = OtcContractStatus.ACTIVE;
    }
}
