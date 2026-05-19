package rs.raf.trading.dividend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// ============================================================
// TODO [B9 - Isplata dividendi na akcije | Nosilac: Djordje Zlatanovic]
//
// Entitet koji biljezi jednu isplacenu dividendu po vlasniku i po hartiji.
//
// IMPLEMENTIRATI:
//   - Long ownerId (NOT NULL) — ID vlasnika (clientId ili employeeId zavisno od ownerType)
//   - String ownerType (NOT NULL, length=16) — diskriminator: "CLIENT" ili "EMPLOYEE"
//     (konzistentno sa Order.userRole konvencijom u paketu `order`)
//   - Long stockListingId (NOT NULL) — ID hartije (Listing.id) za koju se dividenda placa
//   - String stockTicker (NOT NULL, length=32) — denormalizovani ticker radi citljivosti
//   - Integer quantity (NOT NULL) — broj akcija u vlasnistvu na dan obracuna
//   - BigDecimal priceOnDate (NOT NULL, precision=19, scale=4) — cena akcije na dan obracuna
//   - BigDecimal dividendYieldRate (NOT NULL, precision=8, scale=6) — dividendYield/4
//     (kvartalna stopa, kopiran iz Listing.dividendYield na dan obracuna)
//   - BigDecimal grossAmount (NOT NULL, precision=19, scale=4) — bruto iznos: quantity * priceOnDate * dividendYieldRate
//   - BigDecimal tax (NOT NULL, precision=19, scale=4) — porez 15% na grossAmount;
//     0 za EMPLOYEE (aktuar koji drzi akcije u ime banke)
//   - BigDecimal netAmount (NOT NULL, precision=19, scale=4) — grossAmount - tax
//   - Long creditedAccountId (NOT NULL) — ID racuna na koji je dividenda knjizena
//   - String currencyCode (NOT NULL, length=8) — valuta isplate (valuta listinga)
//   - LocalDate paymentDate (NOT NULL) — datum isplate (poslednji radni dan kvartala)
//   - @ColumnDefault("false") Boolean taxExempt (NOT NULL) — true za EMPLOYEE/bankine aktivnosti
//   - @CreationTimestamp LocalDateTime createdAt
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B9.
// ============================================================

@Entity
@Table(name = "dividend_payouts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DividendPayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
