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
 * OTC ponuda u pregovorima izmedju kupca i prodavca unutar iste banke.
 *
 * Spec: Celina 4 - Aktivne ponude / OTC Trgovina.
 * Pregovara se o: kolicini akcija, ceni po akciji (strike), premiji,
 * i settlementDate. Svaka kontraponuda moze da promeni bilo koji od ovih
 * polja i setuje {@code waitingOnUserId} na drugu stranu.
 */
@Entity
@Table(name = "otc_offers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtcOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kupac opcije — korisnik koji inicira ponudu. */
    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "buyer_role", nullable = false, length = 16)
    private String buyerRole;

    /** Prodavac akcija (vlasnik javne kolicine). */
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "seller_role", nullable = false, length = 16)
    private String sellerRole;

    @ManyToOne
    @JoinColumn(name = "listing_id", nullable = false)
    private Listing listing;

    /** Broj akcija o kojima se pregovara. */
    @Column(nullable = false)
    private Integer quantity;

    /** Cena po akciji u listing valuti. */
    @Column(name = "price_per_stock", precision = 18, scale = 4, nullable = false)
    private BigDecimal pricePerStock;

    /** Premija za opcioni ugovor u listing valuti. */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal premium;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    /** Ko je napravio poslednju izmenu (radi UI prikaza i obavestavanja). */
    @Column(name = "last_modified_by_id", nullable = false)
    private Long lastModifiedById;

    @Column(name = "last_modified_by_name", nullable = false)
    private String lastModifiedByName;

    /** Ciji je red da odgovori (buyerId ili sellerId). */
    @Column(name = "waiting_on_user_id", nullable = false)
    private Long waitingOnUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OtcOfferStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_modified_at", nullable = false)
    private LocalDateTime lastModifiedAt;

    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (lastModifiedAt == null) lastModifiedAt = now;
        if (status == null) status = OtcOfferStatus.ACTIVE;
    }

    @PreUpdate
    void onUpdate() {
        lastModifiedAt = LocalDateTime.now();
    }
}
