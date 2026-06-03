package rs.raf.trading.margin.model;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entitet koji predstavlja margin racun.
 *
 * Specifikacija: Marzni_Racuni.txt §1-159, Celina 3 - Margin racuni
 *
 * Kljucni koncepti:
 *   - initialMargin: stanje na racunu (kolicina novca koju imamo)
 *   - loanValue: koliko smo duzni banci (na pocetku nula)
 *   - maintenanceMargin: kolicina novca koju moramo imati na racunu
 *   - bankParticipation: deo koji dobijamo od banke, izrazen u procentima
 *
 * <p><b>BE-STK-06 (25.05.2026):</b> ova klasa je natklasa sa
 * {@code @Inheritance(SINGLE_TABLE)} + {@code @DiscriminatorColumn}.
 * Konkretne potklase:
 * <ul>
 *   <li>{@link UserMarginAccount} (dtype="USER") — userId, klijent ima racun</li>
 *   <li>{@link CompanyMarginAccount} (dtype="COMPANY") — companyId, kompanija ima racun</li>
 * </ul>
 * Postojeci {@code user_id} kolonu Cuva {@code UserMarginAccount}, novi
 * {@code company_id} nullable u {@code CompanyMarginAccount}.
 * <p>Sama {@code MarginAccount} ima {@code dtype="MARGIN"} discriminator za
 * legacy seed redove (backwards-compat).
 */
@Entity
// R1-469: Marzni_Racuni.txt §57 — najvise JEDAN marzni racun po baznom racunu.
// Do sada je invarijanta bila branjena samo aplikativno (findByAccountId().isEmpty()
// pre save-a), pa su dva paralelna createForUser/createForCompany sa istim account_id
// oba prosla TOCTOU proveru i kreirala duplikat. DB unique constraint na account_id
// to eliminise (account_id je distinktan i u seed-u: 1/4/7/10).
@Table(name = "margin_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_margin_account_base", columnNames = "account_id"))
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "dtype", discriminatorType = DiscriminatorType.STRING, length = 31)
@DiscriminatorValue("MARGIN")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MarginAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID povezanog obicnog racuna (banka-core) sa kog se skidaju/uplacuju sredstva. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Denormalizovan broj baznog racuna — popunjava se pri kreiranju margin racuna
     * iz {@code InternalAccountDto}.
     */
    @Column(name = "account_number")
    private String accountNumber;

    /**
     * ID korisnika (klijenta) koji je vlasnik margin racuna.
     * Set kada je vlasnik USER (UserMarginAccount). Ako je vlasnik kompanija,
     * koristi se polje {@code companyId} u {@link CompanyMarginAccount}.
     */
    @Column(name = "user_id", nullable = true)
    private Long userId;

    /** Currency — uvek RSD po Marzni_Racuni.txt §17 (BE-STK-04). */
    @Column(name = "currency", length = 3)
    @org.hibernate.annotations.ColumnDefault("'RSD'")
    @Builder.Default
    private String currency = "RSD";

    /**
     * Pocetna margina — stanje na racunu (kolicina novca koju imamo).
     * <p>Po Marzni_Racuni.txt §75-101: BUY skida userPart sa initialMargin.
     */
    @Column(name = "initial_margin", nullable = false, precision = 19, scale = 4)
    private BigDecimal initialMargin;

    /**
     * Vrednost kredita — koliko smo duzni banci (na pocetku nula po Marzni_Racuni.txt §9).
     */
    @Column(name = "loan_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal loanValue;

    /**
     * Margina odrzavanja — minimalna vrednost ispod koje se racun blokira.
     * <p>BE-STK-07: vrednost zadaje zaposleni pri kreiranju.
     */
    @Column(name = "maintenance_margin", nullable = false, precision = 19, scale = 4)
    private BigDecimal maintenanceMargin;

    /**
     * BankParticipation — deo koji banka pokriva (Marzni_Racuni.txt §13).
     * <p>BE-STK-07: vrednost zadaje zaposleni pri kreiranju.
     */
    @Column(name = "bank_participation", nullable = false, precision = 5, scale = 4)
    private BigDecimal bankParticipation;

    /** Status margin racuna (ACTIVE ili BLOCKED) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @org.hibernate.annotations.ColumnDefault("'ACTIVE'")
    @Builder.Default
    private MarginAccountStatus status = MarginAccountStatus.ACTIVE;

    /**
     * Rezervisan iznos sa initialMargin za in-flight BUY ordere (BE-STK-05).
     * Pri createOrder za margin BUY: reservedMargin += userPart;
     * pri fill: reservedMargin -= settledUserPart, initialMargin -= settledUserPart;
     * pri cancel/decline: reservedMargin -= rolledBackUserPart.
     */
    @Column(name = "reserved_margin", precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal reservedMargin = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Optimistic locking version (BE-STK-05) — sprecava concurrent mutation race.
     * Default {@code null} dozvoljava postojece test fixture-e bez bumpa.
     */
    @Version
    @Column(name = "version")
    private Long version;

    /**
     * Raspoloziva initialMargin za nove BUY ordere = initialMargin - reservedMargin.
     */
    @Transient
    public BigDecimal getAvailableInitialMargin() {
        BigDecimal im = initialMargin != null ? initialMargin : BigDecimal.ZERO;
        BigDecimal rm = reservedMargin != null ? reservedMargin : BigDecimal.ZERO;
        return im.subtract(rm);
    }

    /**
     * BE-STK-06: returns owner id (userId za UserMarginAccount, companyId za CompanyMarginAccount).
     * Default impl pada na userId radi backwards-compat sa legacy redovima.
     */
    @Transient
    public Long getOwnerId() {
        return userId;
    }

    /**
     * BE-STK-06: returns "USER" or "COMPANY" discriminator-style.
     * Subklase overrides.
     */
    @Transient
    public String getOwnerType() {
        return "USER";
    }
}
