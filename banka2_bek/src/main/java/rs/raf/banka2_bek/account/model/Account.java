package rs.raf.banka2_bek.account.model;

import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.company.model.Company;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.employee.model.Employee;

import jakarta.persistence.*;
import jakarta.validation.constraints.AssertTrue;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
// R4-1775: stack je PostgreSQL (podrzava CHECK constraint-e kroz Hibernate DDL),
// a invarijanta "racun ima TACNO jednog vlasnika — klijenta ili kompaniju" je do sada
// bila branjena samo aplikativnim @AssertTrue (zaobidjiva na native/bulk save/merge).
// Sada postoji i pravi DB-level CHECK koji garantuje invarijantu nezavisno od puta upisa.
@org.hibernate.annotations.Check(
        name = "chk_account_single_owner",
        constraints = "(client_id IS NULL) <> (company_id IS NULL)")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 18-cifreni broj računa (prvih 7 uvek iste: šifra banke + šifra filijale)
    @Column(nullable = false, unique = true, length = 18)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AccountSubtype accountSubtype;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "currency_id", nullable = false)
    private Currency currency;

    // Vlasnik: fizičko lice (null ako je kompanija vlasnik)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    // Vlasnik: pravno lice (null ako je klijent vlasnik)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    // Zaposleni koji je kreirao račun
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    // Stanja

    @Column(nullable = false, precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;           // Ukupno stanje

    @Column(nullable = false, precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal availableBalance = BigDecimal.ZERO;  // Raspoloživo stanje (balance - rezervisano)

    // R1-627: INTERNI reservation tracker — odrzavaju ga SAMO interbank/internalFunds
    // flow-ovi (2PC, OTC: setReservedAmount uz odgovarajuci setAvailableBalance).
    // NIJE izvor istine za prikazana "rezervisana sredstva" u AccountResponseDto —
    // DTO racuna (balance - availableBalance) kao prikazani gep (vidi
    // AccountServiceImplementation.toResponse). Dva razlicita pojma, ne dupli.
    @Column(nullable = false, precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal reservedAmount = BigDecimal.ZERO;    // Interno: rezervisano za 2PC/OTC reservation flow-ove

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @org.hibernate.annotations.ColumnDefault("'CLIENT'")
    @Builder.Default
    private AccountCategory accountCategory = AccountCategory.CLIENT;

    // Limiti

    @Column(nullable = false, precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal dailyLimit = BigDecimal.ZERO;        // Dnevni limit plaćanja

    @Column(nullable = false, precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal monthlyLimit = BigDecimal.ZERO;      // Mesečni limit plaćanja

    // Potrošnja (resetuje se schedulovanim jobom)

    @Column(nullable = false, precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal dailySpending = BigDecimal.ZERO;     // Resetuje se u 00:00

    @Column(nullable = false, precision = 19, scale = 4)
    @org.hibernate.annotations.ColumnDefault("0")
    @Builder.Default
    private BigDecimal monthlySpending = BigDecimal.ZERO;   // Resetuje se 1. u mesecu

    // Ostalo
    @Column(precision = 10, scale = 4)
    @Builder.Default
    private BigDecimal maintenanceFee = BigDecimal.ZERO;    // Mesečna naknada

    private LocalDate expirationDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @org.hibernate.annotations.ColumnDefault("'ACTIVE'")
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(length = 64)
    private String name;                                    // Prilagođeni naziv (korisnik može menjati)

    @Column(nullable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Validacija: tačno jedno od client/company mora biti postavljeno ───────
    // R4-1775: PostgreSQL podrzava CHECK constraint-e — invarijanta je sada i na
    // DB nivou (@Check chk_account_single_owner gore). @AssertTrue ostaje kao
    // brza aplikaciona provera (lepsa poruka, hvata gresku pre round-trip-a do baze).
    @AssertTrue(message = "Racun mora imati vlasnika: ili klijenta ili kompaniju, ali ne oba.")
    @Transient
    public boolean isOwnerValid() {
        return (client == null) != (company == null);
    }
}
