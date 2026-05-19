package rs.raf.trading.pricealert.dto;

import lombok.*;

// ============================================================
// TODO [B5 - Cenovni alarmi (Price Alert) | Nosilac: Aleksa Vucinic]
//
// DTO za izlazni prikaz jednog cenovnog alarma (response body).
//
// IMPLEMENTIRATI (dodati polja):
//   - Long id
//       Primarni kljuc alarma.
//   - Long ownerId
//       ID vlasnika (klijent ili zaposleni).
//   - String ownerType
//       "CLIENT" ili "EMPLOYEE".
//   - Long listingId
//       ID hartije na kojoj je postavljen alarm.
//   - String listingTicker
//       Ticker simbol hartije (npr. "AAPL") — denormalizovano polje
//       popunjava mapper iz Listing entiteta radi bolje citljivosti
//       bez potrebe za dodatnim FE pozivom.
//   - String condition
//       Naziv enum vrednosti: "ABOVE" ili "BELOW".
//   - java.math.BigDecimal threshold
//       Prag u valuti hartije.
//   - Boolean active
//       Da li je alarm jos uvek aktivan (nije okidan).
//   - java.time.LocalDateTime createdAt
//       Vreme kreiranja alarma.
//
// Lombok anotacije: @Getter @Setter @NoArgsConstructor
//   @AllArgsConstructor @Builder (po uzoru na trgovinski FundDto).
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B5.
// ============================================================
// Napomena: skeleton je prazan pa nosi samo @NoArgsConstructor. Pri dodavanju
// polja vratiti @AllArgsConstructor i @Builder (videti Lombok napomenu u TODO-u).
@Getter
@Setter
@NoArgsConstructor
public class PriceAlertDto {
}
