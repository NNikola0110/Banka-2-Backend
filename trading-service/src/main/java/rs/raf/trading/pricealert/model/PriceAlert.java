package rs.raf.trading.pricealert.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [B5 - Cenovni alarmi] JPA entitet jednog cenovnog alarma.
 *
 * <p>Korisnik (klijent ili zaposleni) postavlja alarm na neku hartiju (Listing)
 * sa pragom i smerom (ABOVE/BELOW). Kad scheduler detektuje da trenutna cena
 * hartije zadovoljava uslov, alarm se okida (active=false, triggeredAt=now) i
 * notifikacija ide korisniku preko {@code NotificationService}.
 *
 * <p>One-shot semantika: jednom okidan alarm vise se NE okida (paritet sa
 * Watchlist/RecurringOrder + Zadaci_Backend.pdf B5 spec).
 */
@Entity
@Table(name = "price_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /**
     * "CLIENT" ili "EMPLOYEE" — paritet sa {@code Order.userRole} obrascem
     * (string umesto enum zbog interop-a sa banka-core internim API-jem).
     */
    @Column(name = "owner_type", nullable = false, length = 16)
    private String ownerType;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 8)
    private PriceAlertCondition condition;

    @Column(name = "threshold", nullable = false, precision = 19, scale = 4)
    private BigDecimal threshold;

    @Column(name = "active", nullable = false)
    @ColumnDefault("1")
    private Boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Vreme kad je alarm okidan (null dok alarm jos uvek aktivan). */
    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;
}
