package rs.raf.banka2_bek.loan.model;

import jakarta.persistence.*;
import lombok.*;
import rs.raf.banka2_bek.currency.model.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "loan_installments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id", nullable = false)
    private Loan loan;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(precision = 19, scale = 4)
    private BigDecimal principalAmount;

    @Column(precision = 19, scale = 4)
    private BigDecimal interestAmount;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal interestRate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    private Currency currency;

    @Column(nullable = false)
    private LocalDate expectedDueDate;

    private LocalDate actualDueDate;

    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private Boolean paid = false;

    /**
     * R1 346 (§409-417): broj neuspelih pokusaja naplate (nedovoljno sredstava).
     * Prati se da bi se posle praga ({@code PENALTY_AFTER_FAILED_ATTEMPTS} u
     * {@code InstallmentProcessor}) primenila eskalacija kamate (+0.05% za
     * kasnjenje) UMESTO beskonacnog klizanja due-date-a +3 dana bez ikakve
     * posledice. Postojeca tabela: kolona dobija default 0.
     */
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0;

    /**
     * R1 346: da li je penal kamate (+0.05%) vec primenjen za ovu ratu —
     * sprecava da se isti penal naplati vise puta dok rata ostaje neplacena
     * (idempotentna eskalacija po rati).
     */
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    @Column(name = "penalty_applied", nullable = false)
    private Boolean penaltyApplied = false;

    /**
     * P2-concurrency-locks-1 (R3-1580): optimisticko zakljucavanje rate.
     *
     * <p>Glavna double-debit zastita je vec P0-B2 pessimistic re-read
     * ({@code findByIdForUpdate} + {@code paid} re-check u
     * {@link rs.raf.banka2_bek.loan.service.InstallmentProcessor#processOne}).
     * {@code @Version} je defense-in-depth (paritet sa {@code SavingsScheduler}-om
     * koji vec ima @Version): ako bi dva pisanja nekako prosla lock (npr. drugaciji
     * lock-mode posle refaktora ili in-memory put), drugi commit nad istim redom
     * baca {@code OptimisticLockException} → izolovani rollback umesto tihog
     * lost-update-a {@code paid}/{@code actualDueDate}. Postojeca tabela:
     * kolona dobija default 0.</p>
     */
    @Version
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "version")
    private Long version;
}
