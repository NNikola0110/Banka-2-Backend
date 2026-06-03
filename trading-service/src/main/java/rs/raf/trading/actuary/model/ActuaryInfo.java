package rs.raf.trading.actuary.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Entitet koji cuva aktuarske podatke za zaposlene (agente i supervizore).
 * Svaki admin je ujedno i supervizor. Agent ima limit, supervizor nema.
 *
 * Specifikacija: Celina 3 - Aktuari
 *
 * NAPOMENA (copy-first ekstrakcija, faza 2c): u monolitu je {@code employee}
 * bila JPA {@code @OneToOne} veza ka {@code Employee} entitetu. U trading-service-u
 * je {@code Employee} entitet u banka-core domenu, pa je veza prevedena u soft id —
 * kolona {@code employee_id} je zadrzana imenom (1:1 migracija podataka kasnije),
 * uniqueness {@code unique = true} ocuvana (originalna veza je bila 1:1).
 * Podaci o zaposlenom se razresavaju preko {@code BankaCoreClient}.
 */
@Entity
@Table(name = "actuary_info")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActuaryInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false, unique = true)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActuaryType actuaryType;

    // R4-1772: novcana polja moraju imati eksplicitni precision/scale.
    // Bez njih Hibernate emituje PG default numeric(38,2) (scale-2), dok je
    // SVUDA drugde u codebase-u novac numeric(19,4) (scale-4) — npr. usedLimit
    // se poredi sa order-amount-om (scale-4) u OrderServiceImpl, pa scale-2
    // truncate moze pomeriti prelazak dailyLimit-a. Poravnato na scale-4.
    @Column(name = "daily_limit", precision = 19, scale = 4)
    private BigDecimal dailyLimit;

    @Column(name = "used_limit", precision = 19, scale = 4)
    private BigDecimal usedLimit;

    @Column(name = "need_approval", nullable = false)
    private boolean needApproval;

    /**
     * BE-ORD-07: optimistic locking za concurrent usedLimit increment-e iz
     * OrderServiceImpl.createOrder + approveOrder + cancelOrder. Bez @Version,
     * dva paralelna BUY ordera istog agenta nad istim ActuaryInfo redom mogu
     * citati isti usedLimit, oba upisati current + svoju delta, i lost-update
     * istisnuti jednu inkrementaciju (agent prelazi dailyLimit unprimetno).
     * Sa @Version, drugi commit puca sa OptimisticLockingFailureException —
     * OrderServiceImpl ima retry loop koji ucita svezi red i ponovi inkrement.
     */
    @jakarta.persistence.Version
    @Column(name = "version")
    private Long version;
}
