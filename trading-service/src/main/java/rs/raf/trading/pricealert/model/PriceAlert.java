package rs.raf.trading.pricealert.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// ============================================================
// TODO [B5 - Cenovni alarmi (Price Alert) | Nosilac: Aleksa Vucinic]
//
// JPA entitet koji predstavlja jedan cenovni alarm koji korisnik
// (klijent ili aktuar/supervizor) postavlja na odredjenu hartiju.
//
// IMPLEMENTIRATI (dodati polja uz odgovarajuce JPA anotacije):
//   - ownerId    (Long, @Column nullable=false)
//       ID korisnika koji je postavio alarm; FK na users.id.
//   - ownerType  (String, @Column length=16, nullable=false)
//       "CLIENT" ili "EMPLOYEE"; cuvati kao String (ne enum) kako bi
//       se izbegla zavisnost od internog modela rola.
//   - listingId  (Long, @Column nullable=false)
//       ID hartije (FK na listings.id) za koju se prati cena.
//   - condition  (PriceAlertCondition, @Enumerated(EnumType.STRING),
//                 @Column nullable=false, length=8)
//       Smer okidanja: ABOVE ili BELOW (vidi PriceAlertCondition).
//   - threshold  (BigDecimal, @Column nullable=false, precision=19, scale=4)
//       Vrednost praga u valuti hartije.
//   - active     (Boolean, @Column nullable=false, @ColumnDefault("1"))
//       true dok alarm nije okidan; postavljeno na false cim se okine
//       (one-shot semantika — alarm se ne ponavlja).
//   - createdAt  (LocalDateTime, @CreationTimestamp,
//                 @Column nullable=false, updatable=false)
//       Automatski popunjen pri INSERT-u (ne unosi rucno).
//
// Dodati Lombok anotacije: @Getter @Setter @NoArgsConstructor
//   @AllArgsConstructor @Builder (po uzoru na trgovinski entitet, npr. Option).
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B5.
// ============================================================
@Entity
@Table(name = "price_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
