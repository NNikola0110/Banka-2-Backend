package rs.raf.banka2_bek.loan.model;

import jakarta.persistence.*;
import lombok.*;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.currency.model.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "loans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String loanNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LoanType loanType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InterestType interestType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private Integer repaymentPeriod;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal nominalRate;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal effectiveRate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal monthlyPayment;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal remainingDebt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @org.hibernate.annotations.ColumnDefault("'ACTIVE'")
    @Builder.Default
    private LoanStatus status = LoanStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(length = 500)
    private String loanPurpose;

    /**
     * R1-659: optimisticko zakljucavanje kredita (komplement
     * {@code LoanInstallment.@Version} iz P2-concurrency-locks-1). Stiti od
     * lost-update prozora kad early-repay (klijent gasi ostatak duga) i installment
     * cron paralelno menjaju {@code remainingDebt}/{@code status}/{@code effectiveRate}
     * istog kredita — drugi commit nad istim redom baca {@code OptimisticLockException}
     * (→ 409 kroz GlobalExceptionHandler / izolovani scheduler skip) umesto tihog
     * gazenja stanja. Postojeca tabela: kolona dobija default 0.
     */
    @Version
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "version")
    private Long version;

    @Column(nullable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
