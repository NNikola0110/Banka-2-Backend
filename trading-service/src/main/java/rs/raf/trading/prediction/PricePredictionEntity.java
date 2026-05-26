package rs.raf.trading.prediction;

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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * [W3-T2] Read-only JPA entitet za Spark "price_predictions" output tabelu
 * (schema iz {@code trading-db-init/02-analytics-tables.sql}).
 *
 * <p>Spark ML job ({@code spark/jobs/price_prediction.py}) noćno upisuje
 * N+1 day predikciju za svaki simbol (close price + lower/upper bound). FE
 * "PredictionWidget" cita najnoviju predikciju za simbol koji korisnik gleda
 * na {@code ListingDetailPage}-u.
 */
@Entity
@Table(name = "price_predictions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricePredictionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    /** Datum ZA KOJI je predikcija (npr. sutra). */
    @Column(name = "prediction_date", nullable = false)
    private LocalDate predictionDate;

    @Column(name = "predicted_close", nullable = false)
    private BigDecimal predictedClose;

    @Column(name = "lower_bound", nullable = false)
    private BigDecimal lowerBound;

    @Column(name = "upper_bound", nullable = false)
    private BigDecimal upperBound;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @CreationTimestamp
    @Column(name = "computed_at", updatable = false)
    private LocalDateTime computedAt;
}
