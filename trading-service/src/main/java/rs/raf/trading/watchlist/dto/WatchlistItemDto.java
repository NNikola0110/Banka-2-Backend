package rs.raf.trading.watchlist.dto;

// ============================================================
// TODO [B6 - Watchlist | Nosilac: L. Draskovic]
//
// DTO koji predstavlja jednu stavku liste pracenja u API odgovoru.
// Sadrzi i zive trzisnee podatke koji se dohvataju iz listing/security servisa.
//
// IMPLEMENTIRATI (polja):
//   - Long id              -- primarni kljuc WatchlistItem reda
//   - Long watchlistId     -- ID liste kojoj pripada
//   - Long listingId       -- ID listinga (hartije) sa berze
//   - String ticker        -- simbol, npr. "AAPL"
//   - String listingName   -- puni naziv, npr. "Apple Inc."
//   - String securityType  -- "STOCK", "FOREX", "FUTURE", "OPTION"
//   - String exchangeName  -- naziv berze, npr. "NASDAQ"
//   - BigDecimal currentPrice  -- trenutna cena (dohvata se iz listing servisa)
//   - BigDecimal dailyChange   -- promena od otvaranja u % (moze biti negativna)
//   - Long volume              -- obim trgovanja za danasnji dan
//   - LocalDateTime addedAt    -- kada je korisnik dodao hartiju na listu
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
public class WatchlistItemDto {
}
