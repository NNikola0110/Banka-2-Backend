package rs.raf.banka2_bek.savings.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import rs.raf.banka2_bek.currency.model.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * TEST-savings-3 (✅ FIXED 02.06): uniqueness se enforce-uje SAMO medju AKTIVNIM
 * redovima preko PostgreSQL PARTIAL UNIQUE INDEX-a
 * {@code (currency_id, term_months) WHERE active = true} — kreira ga
 * {@link rs.raf.banka2_bek.persistence.SavingsInterestRateIndexInitializer}
 * na startu (isti obrazac kao {@link rs.raf.banka2_bek.persistence.CardSlotIndexInitializer}).
 *
 * <p>Ranije je tu stajao 3-kolonski {@code @UniqueConstraint}
 * {@code (currency_id, term_months, active)} u kojem je {@code active} bio PUNI
 * deo kljuca. Posledica: {@code upsertOnce} deaktivira tekuci red pa insert-uje nov;
 * 3. promena rate-a za isti {@code (currency,term)} pravi DRUGI {@code (..,false)}
 * red → DataIntegrityViolation → @Retryable 3× → 500. Net efekat: rate se mogao
 * promeniti TACNO JEDNOM po (currency,term). Partial-unique {@code WHERE active=true}
 * dozvoljava proizvoljno mnogo istorijskih {@code false} redova, a garantuje najvise
 * JEDAN aktivan red po (currency,term) — sto je i poslovno pravilo.
 */
@Entity
@Table(name = "savings_interest_rates")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavingsInterestRate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    private Currency currency;

    @Column(name = "term_months", nullable = false)
    private Integer termMonths;

    @Column(name = "annual_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal annualRate;

    @Column(nullable = false)
    @ColumnDefault("1")
    private Boolean active;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * BE-PAY-04: optimistic locking — sprecava race-condition u
     * {@link rs.raf.banka2_bek.savings.service.SavingsInterestRateService#upsert}
     * gde dva concurrent admin POST-a mogu oba da nadju isti aktivan record,
     * oba ga deaktiviraju i oba pokusaju insert novog -> unique constraint fail.
     */
    @Version
    @Column(nullable = false)
    @ColumnDefault("0")
    @Builder.Default
    private Long version = 0L;
}
