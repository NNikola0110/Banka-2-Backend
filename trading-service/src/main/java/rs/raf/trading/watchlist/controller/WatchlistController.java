package rs.raf.trading.watchlist.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import rs.raf.trading.watchlist.dto.CreateWatchlistDto;
import rs.raf.trading.watchlist.dto.WatchlistDto;
import rs.raf.trading.watchlist.dto.WatchlistItemDto;
import rs.raf.trading.watchlist.service.WatchlistService;

import java.util.List;

@RestController
@RequestMapping("/watchlists")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @PostMapping
    public ResponseEntity<WatchlistDto> create(@Valid @RequestBody CreateWatchlistDto dto) {
        WatchlistDto created = watchlistService.createWatchlist(dto);
        return ResponseEntity.ok(created);
    }

    @GetMapping
    public ResponseEntity<List<WatchlistDto>> list() {
        return ResponseEntity.ok(watchlistService.listMyWatchlists());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<WatchlistDto> rename(@PathVariable("id") Long id, @Valid @RequestBody CreateWatchlistDto dto) {
        return ResponseEntity.ok(watchlistService.renameWatchlist(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        watchlistService.deleteWatchlist(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<WatchlistItemDto> addItem(@PathVariable("id") Long id, @RequestParam("listingId") Long listingId) {
        return ResponseEntity.ok(watchlistService.addItem(id, listingId));
    }

    @DeleteMapping("/{id}/items/{listingId}")
    public ResponseEntity<Void> removeItem(@PathVariable("id") Long id, @PathVariable("listingId") Long listingId) {
        watchlistService.removeItem(id, listingId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/items")
    public ResponseEntity<List<WatchlistItemDto>> listItems(@PathVariable("id") Long id, @RequestParam(value = "type", required = false) String type) {
        return ResponseEntity.ok(watchlistService.listItems(id, type));
    }
}