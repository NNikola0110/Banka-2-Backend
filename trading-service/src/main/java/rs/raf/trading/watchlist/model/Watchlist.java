package rs.raf.trading.watchlist.model;

// ============================================================
// TODO [B6 - Watchlist | Nosilac: L. Draskovic]
//
// Entitet koji predstavlja jednu korisnicku listu pracenih hartija.
// Korisnik moze imati vise lista (npr. "Akcije", "Forex parovi").
//
// IMPLEMENTIRATI:
//   - ownerId (Long, NOT NULL) -- ID vlasnika (klijenta ili zaposlenog)
//   - ownerType (enum WatchlistOwnerType, NOT NULL, EnumType.STRING, length=16)
//       vrednosti: CLIENT, EMPLOYEE
//       enum definisati u istom paketu: watchlist/model/WatchlistOwnerType.java
//   - name (String, NOT NULL, length=120) -- naziv liste, npr. "Moje akcije"
//   - @CreationTimestamp createdAt (LocalDateTime, NOT NULL, updatable=false)
//   - @UpdateTimestamp updatedAt (LocalDateTime, NOT NULL)
//   - Lombok: @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
//   - @Table(name = "watchlists") sa UNIQUE constraint na (owner_id, owner_type, name)
//     tako da jedan vlasnik ne moze imati dve liste istog naziva:
//     @Table(name="watchlists", uniqueConstraints =
//         @UniqueConstraint(columnNames={"owner_id","owner_type","name"}))
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B6.
// ============================================================

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "watchlists")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Watchlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
