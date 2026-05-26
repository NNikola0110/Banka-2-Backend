package rs.raf.banka2_bek.fraud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [W3-T2] JPA entitet za Spark "transaction_anomalies" output tabelu
 * (schema iz {@code db-init/02-fraud-tables.sql}).
 *
 * <p>Spark fraud detection job ({@code spark/jobs/fraud_detection.py})
 * popunjava ovu tabelu sa {@code risk_score} po transakciji i {@code features}
 * JSONB feature vektorom; supervizori (SUPERVISOR + ADMIN) revizuju
 * "pending" redove preko {@code POST /admin/fraud-alerts/{id}/review}.
 *
 * <p>{@code features} se cita kao raw {@link String} (videti
 * {@code AnalyticsDailyEntity#dimensions} za isto obrazlozenje — H2 compat +
 * paritet sa {@code interbank.InterbankTransaction.transactionBody}).
 */
@Entity
@Table(name = "transaction_anomalies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionAnomalyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "risk_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal riskScore;

    /** Raw JSON tekst — service sloj ga prosledjuje kao-je u DTO. */
    @Column(name = "features", nullable = false, columnDefinition = "TEXT")
    private String features;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @CreationTimestamp
    @Column(name = "computed_at", updatable = false)
    private LocalDateTime computedAt;

    /** Email supervizora koji je revizovao (null dok pending). */
    @Column(name = "reviewed_by")
    private String reviewedBy;

    /** "pending" / "confirmed" / "false_positive" / "closed" (null = pending). */
    @Column(name = "review_status")
    private String reviewStatus;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;
}
