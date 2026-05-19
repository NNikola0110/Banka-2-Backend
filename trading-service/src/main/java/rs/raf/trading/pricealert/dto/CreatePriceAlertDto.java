package rs.raf.trading.pricealert.dto;

import lombok.*;

// ============================================================
// TODO [B5 - Cenovni alarmi (Price Alert) | Nosilac: Aleksa Vucinic]
//
// DTO za kreiranje novog cenovnog alarma (request body za POST
// /price-alerts). Sva polja su obavezna; validirati Jakarta Bean
// Validation anotacijama (@NotNull, @NotBlank, @Positive...).
//
// IMPLEMENTIRATI (dodati polja):
//   - Long listingId
//       @NotNull — ID hartije na kojoj se postavlja alarm.
//       Servis proverava da hartija postoji u listings tabeli.
//   - rs.raf.trading.pricealert.model.PriceAlertCondition condition
//       @NotNull — smer okidanja (ABOVE ili BELOW).
//       Deserializuje se direktno iz JSON stringa ("ABOVE"/"BELOW").
//   - java.math.BigDecimal threshold
//       @NotNull @Positive — prag u valuti hartije.
//       Mora biti > 0; negativne i nulte vrednosti odbiti sa 400.
//
// Lombok anotacije: @Getter @Setter @NoArgsConstructor @AllArgsConstructor
//   (Builder opciono — input DTO, ne treba fluent kreiranje u service-u).
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B5.
// ============================================================
// Napomena: skeleton je prazan pa nosi samo @NoArgsConstructor. Pri dodavanju
// polja vratiti @AllArgsConstructor (videti Lombok napomenu u TODO-u).
@Getter
@Setter
@NoArgsConstructor
public class CreatePriceAlertDto {
}
