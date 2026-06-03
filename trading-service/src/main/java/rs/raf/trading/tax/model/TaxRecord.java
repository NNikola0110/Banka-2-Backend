package rs.raf.trading.tax.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * NAPOMENA (copy-first ekstrakcija, faza 2c): kopija monolitnog
 * {@code rs.raf.banka2_bek.tax.model.TaxRecord}. Vlasnik je referenciran
 * soft id-em ({@code userId} + {@code userType}) — nema JPA veze ka
 * banka-core entitetima, pa je kopija doslovna.
 */
@Entity
@Table(name = "tax_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "user_type", nullable = false, length = 20)
    private String userType; // CLIENT or EMPLOYEE

    @Column(name = "total_profit", nullable = false, precision = 18, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal totalProfit = BigDecimal.ZERO;

    @Column(name = "tax_owed", nullable = false, precision = 18, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal taxOwed = BigDecimal.ZERO;

    /**
     * P0-B3 (B-1 fix): <b>godisnji kumulativ</b> placenog poreza za tekucu
     * kalendarsku godinu (spec Celina 3 §488: "otplacen porez za tekucu
     * kalendarsku godinu"). Resetuje se na 0 kad se {@link #taxPaidYear}
     * promeni. Ovo je iznos koji se PRIKAZUJE kao "otplacen porez".
     */
    @Column(name = "tax_paid", nullable = false, precision = 18, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal taxPaid = BigDecimal.ZERO;

    /**
     * P0-B3 (B-1 fix): kalendarska godina za koju {@link #taxPaid} akumulira.
     * Kad se godina promeni, {@code taxPaid} se resetuje (godisnji kumulativ).
     * {@code null} dok porez nikad nije naplacen.
     */
    @Column(name = "tax_paid_year")
    private Integer taxPaidYear;

    /**
     * P0-B3 (B-1 fix): settlement mesec ({@code YYYY-MM}) za koji
     * {@link #taxPaidInPeriod} akumulira. {@code unpaidTax} se racuna kao
     * {@code taxOwed(mesecni) − taxPaidInPeriod(isti mesec)} — mesecni owed i
     * mesecni paid su u ISTOJ dimenziji (B-1 fix: pre se mesecni owed mesao sa
     * lifetime-kumulativnim paid → cross-month under-taxation). {@code null} dok
     * porez nikad nije naplacen.
     */
    @Column(name = "tax_paid_period", length = 7)
    private String taxPaidPeriod;

    /**
     * P0-B3 (B-1 fix): <b>mesecni</b> kumulativ placenog poreza za
     * {@link #taxPaidPeriod}. Resetuje se na 0 kad se settlement mesec promeni.
     * Drzi mesecni paid u istoj dimenziji kao mesecni {@code taxOwed}.
     */
    @Column(name = "tax_paid_in_period", nullable = false, precision = 18, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal taxPaidInPeriod = BigDecimal.ZERO;

    @Column(nullable = false, length = 10)
    @org.hibernate.annotations.ColumnDefault("'RSD'")
    @Builder.Default
    private String currency = "RSD";

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;

    /**
     * P2-tax-cost-basis-1 (R1 434): optimisticko zakljucavanje. Mesecni cron
     * ({@code TaxScheduler.calculateMonthlyTax}) i rucni okidac
     * ({@code POST /tax/calculate}) mogu raditi nad ISTIM {@code TaxRecord}-om
     * istovremeno — bez {@code @Version}-a drugi pisac tiho prepise prvog
     * (lost-update {@code taxPaid}/{@code taxPaidInPeriod} → dvostruka ili
     * izgubljena naplata). Sa {@code @Version}-om drugi commit baca
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}.
     * Per-user obracun je vec u {@code REQUIRES_NEW} (BE-PAY-04), pa pad jednog
     * user-record-a ne rusi batch ostalih korisnika. {@code @ColumnDefault("0")}
     * da postojeci redovi (pre-migracije) dobiju validnu pocetnu verziju.
     */
    @Version
    @Column(name = "version")
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private Long version = 0L;
}
