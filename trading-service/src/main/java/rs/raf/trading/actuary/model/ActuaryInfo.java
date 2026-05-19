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

    @Column(name = "daily_limit")
    private BigDecimal dailyLimit;

    @Column(name = "used_limit")
    private BigDecimal usedLimit;

    @Column(name = "need_approval", nullable = false)
    private boolean needApproval;
}
