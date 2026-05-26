package rs.raf.trading.analytics;

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
 * [W3-T2] Read-only JPA entitet za Spark "analytics_daily" output tabelu
 * (schema iz {@code trading-db-init/02-analytics-tables.sql}).
 *
 * <p>Tabela se popunjava noćnim PySpark job-om
 * ({@code spark/jobs/analytics_daily.py}); trading-service je samo CITAC.
 * Korisnik {@code analyticsuser} se postavlja u Spark-u sa write rolama, a
 * trading-service koristi svoj postojeci {@code tradinguser} role — read je
 * dovoljan.
 *
 * <p>Polje {@code dimensions} je u PostgreSQL-u {@code jsonb}; ovde se
 * cita kao raw {@link String} (JSON tekst) zbog dva razloga:
 * <ol>
 *   <li>H2 u {@code MODE=PostgreSQL} (test profil) ne podrzava {@code jsonb}
 *       kao kolonni tip, pa {@code columnDefinition="jsonb"} pucalo bi
 *       schema-create u {@code @SpringBootTest} kontekstu.</li>
 *   <li>Postojeci codebase ima paritet pattern (videti
 *       {@code interbank.InterbankTransaction.transactionBody}) — JSONB se
 *       drzi kao text plain string da Hibernate-Jackson conversion ne pravi
 *       skrivene zamke. Mapper (service layer) parsira string u
 *       {@code Map&lt;String,Object&gt;} pre vracanja DTO-a.</li>
 * </ol>
 *
 * <p>Production: kolona ostaje {@code jsonb} (W2-T3 init script), JDBC vraca
 * tekstualnu reprezentaciju koja je validan JSON. Write se ne radi iz JPA.
 */
@Entity
@Table(name = "analytics_daily")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyticsDailyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_date", nullable = false)
    private LocalDate metricDate;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    /** Raw JSON tekst — parse u service sloju. Default '{}' garantuje validan parse. */
    @Column(name = "dimensions", nullable = false, columnDefinition = "TEXT")
    private String dimensions;

    /**
     * Ime kolone je {@code value} u DB-u (Spark job ga upisuje pod tim
     * imenom — videti {@code spark/jobs/analytics_daily.py}), ali u H2 i
     * nekoliko drugih dijalekata {@code VALUE} je rezervisana rec. JPA
     * spec dozvoljava escape sa double-quote sintaksom; Hibernate to
     * dispatch-uje na ispravan dialect-specific quote pri DDL/DML.
     */
    @Column(name = "\"value\"", nullable = false)
    private BigDecimal value;

    @CreationTimestamp
    @Column(name = "computed_at", updatable = false)
    private LocalDateTime computedAt;
}
