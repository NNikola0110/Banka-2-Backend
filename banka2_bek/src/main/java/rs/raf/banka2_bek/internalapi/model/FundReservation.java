package rs.raf.banka2_bek.internalapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// R4-1774: ranije je `reservationId` imao DVA redundantna unique constrainta —
// `@Column(unique=true)` (linija dole) I `@Table(indexes=@Index(unique=true))`.
// Hibernate je emitovao dva odvojena UNIQUE constrainta/indexa nad istom kolonom.
// Zadrzano je SAMO `@Column(unique=true)` (konvencija ostatka codebase-a za
// jednokolonske unique-eve); tabelarni unique-index uklonjen.
@Entity
@Table(name = "fund_reservations")
@Getter
@Setter
@NoArgsConstructor
public class FundReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String reservationId;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal committedAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 8)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FundReservationStatus status = FundReservationStatus.RESERVED;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
