package rs.raf.trading.watchlist.controller;

// ============================================================
// TODO [B6 - Watchlist | Nosilac: L. Draskovic]
//
// REST kontroler za Watchlist funkcionalnost.
// Koristiti @RequiredArgsConstructor za injekciju WatchlistService-a,
// (kao InvestmentFundController u trgovinskom domenu).
//
// Base path: /watchlists
//
// IMPLEMENTIRATI (endpoint metode):
//
//   POST   /watchlists
//     -- kreira novu listu; request body: @Valid CreateWatchlistDto
//        vraca: ResponseEntity<WatchlistDto> sa status 200
//        poziva: watchlistService.createWatchlist(dto)
//
//   GET    /watchlists
//     -- vraca sve liste tekuceg korisnika
//        vraca: ResponseEntity<List<WatchlistDto>>
//        poziva: watchlistService.listMyWatchlists()
//
//   PATCH  /watchlists/{id}
//     -- preimenuje listu; @PathVariable Long id, @Valid @RequestBody CreateWatchlistDto
//        vraca: ResponseEntity<WatchlistDto>
//        poziva: watchlistService.renameWatchlist(id, dto)
//
//   DELETE /watchlists/{id}
//     -- brise listu i sve njene stavke; @PathVariable Long id
//        vraca: ResponseEntity<Void> sa status 204 No Content
//        poziva: watchlistService.deleteWatchlist(id)
//
//   POST   /watchlists/{id}/items
//     -- dodaje hartiju na listu; @PathVariable Long id, @RequestParam Long listingId
//        vraca: ResponseEntity<WatchlistItemDto> sa status 200
//        poziva: watchlistService.addItem(id, listingId)
//
//   DELETE /watchlists/{id}/items/{listingId}
//     -- uklanja hartiju sa liste; @PathVariable Long id, @PathVariable Long listingId
//        vraca: ResponseEntity<Void> sa status 204 No Content
//        poziva: watchlistService.removeItem(id, listingId)
//
//   GET    /watchlists/{id}/items
//     -- vraca stavke liste sa live podacima; @PathVariable Long id,
//        @RequestParam(required=false) String type  -- filter po tipu hartije (STOCK/FOREX/FUTURE/OPTION)
//        vraca: ResponseEntity<List<WatchlistItemDto>>
//        poziva: watchlistService.listItems(id, type)
//
// SECURITY: pravilo `/watchlists/**` je vec dodato u TradingSecurityConfig
//   (paket rs.raf.trading.security) — authenticated(). Gateway (api-gateway/
//   nginx.conf) ima `location /watchlists` koji rutira na trading-service:8082.
//
// Konvencija: pratiti trgovinski paket `investmentfund` kao sablon (InvestmentFundController).
// Spec: Zadaci_Backend.pdf, zadatak B6.
// ============================================================

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/watchlists")
@RequiredArgsConstructor
public class WatchlistController {
}
