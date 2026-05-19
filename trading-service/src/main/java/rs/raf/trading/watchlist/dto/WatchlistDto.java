package rs.raf.trading.watchlist.dto;

// ============================================================
// TODO [B6 - Watchlist | Nosilac: L. Draskovic]
//
// DTO koji predstavlja jednu listu pracenih hartija u API odgovoru.
//
// IMPLEMENTIRATI (polja):
//   - Long id                        -- primarni kljuc liste
//   - Long ownerId                   -- ID vlasnika
//   - String ownerType               -- "CLIENT" ili "EMPLOYEE"
//   - String name                    -- naziv liste (npr. "Moje akcije")
//   - int itemCount                  -- broj hartija u listi (izracunava service)
//   - LocalDateTime createdAt        -- datum kreiranja
//
// Lombok: @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
//
// Konvencija: pratiti trgovinski paket `investmentfund` kao sablon (npr. FundDto).
// Spec: Zadaci_Backend.pdf, zadatak B6.
// ============================================================

import lombok.*;

// Napomena: skeleton je prazan pa nosi samo @NoArgsConstructor. Pri dodavanju
// polja vratiti @AllArgsConstructor i @Builder (videti Lombok napomenu u TODO-u).
@Getter @Setter
@NoArgsConstructor
public class WatchlistDto {
}
