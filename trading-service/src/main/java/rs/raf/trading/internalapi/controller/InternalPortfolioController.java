package rs.raf.trading.internalapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2.contracts.internal.CommitStockRequest;
import rs.raf.banka2.contracts.internal.InternalErrorDto;
import rs.raf.banka2.contracts.internal.InternalListingDto;
import rs.raf.banka2.contracts.internal.ReleaseStockRequest;
import rs.raf.banka2.contracts.internal.ReserveStockRequest;
import rs.raf.trading.internalapi.service.InternalPortfolioService;

/**
 * Interni REST API za inter-bank portfolio/stock seam ({@code /internal/portfolio/**}).
 * Sve rute zasticene X-Internal-Key-em ({@code InternalAuthFilter} + ROLE_INTERNAL).
 *
 * <p>Faza 2f: banka-core {@code interbank} paket (inter-bank OTC + 2PC settlement)
 * je do sada SAMO slao interne pozive ({@code BankaCoreClient} → trading-service);
 * sada trading-service I PRIMA interne pozive jer posle cutover-a {@code portfolios}/
 * {@code listings} tabele zive samo u trading_db.
 *
 * <p>{@code reserve/commit/release-stock} mutirajuci endpoint-i zahtevaju
 * {@code X-Idempotency-Key} — idempotency se handluje direktno u
 * {@code InternalPortfolioService} (findCached + operacija + store atomicno).
 * Read-side endpoint-i (listing/holding/public-stock) su cisto citanje.
 */
@RestController
@RequestMapping("/internal/portfolio")
public class InternalPortfolioController {

    private final InternalPortfolioService portfolioService;

    public InternalPortfolioController(InternalPortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    // ── Mutirajuci endpoint-i (idempotentni) ─────────────────────────────────

    /**
     * Rezervise hartije u portfoliju vlasnika (inter-bank OTC 2PC prepare faza).
     */
    @PostMapping("/reserve-stock")
    public ResponseEntity<?> reserveStock(
            @RequestBody ReserveStockRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(portfolioService.reserveStockIdempotent(idempotencyKey, body));
    }

    /**
     * Commit kretanja hartija (inter-bank OTC 2PC commit faza).
     */
    @PostMapping("/commit-stock")
    public ResponseEntity<?> commitStock(
            @RequestBody CommitStockRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(portfolioService.commitStockIdempotent(idempotencyKey, body));
    }

    /**
     * Oslobadja rezervisane hartije (inter-bank OTC 2PC rollback kompenzacija).
     */
    @PostMapping("/release-stock")
    public ResponseEntity<?> releaseStock(
            @RequestBody ReleaseStockRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(portfolioService.releaseStockIdempotent(idempotencyKey, body));
    }

    // ── Read-side endpoint-i ─────────────────────────────────────────────────

    /**
     * Vraca listing po ticker-u (inter-bank validacija postojanja hartije +
     * obogacivanje OTC DTO-a). 404 ako ticker ne postoji.
     */
    @GetMapping("/listing")
    public ResponseEntity<?> getListing(@RequestParam("ticker") String ticker) {
        InternalListingDto dto = portfolioService.findListingByTicker(ticker)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + ticker));
        return ResponseEntity.ok(dto);
    }

    /**
     * Vraca portfolio poziciju vlasnika za listing odredjen ticker-om
     * ({@code exists=false} ako listing/portfolio ne postoji).
     */
    @GetMapping("/holding")
    public ResponseEntity<?> getHolding(@RequestParam("userId") Long userId,
                                        @RequestParam("userRole") String userRole,
                                        @RequestParam("ticker") String ticker) {
        return ResponseEntity.ok(portfolioService.findHolding(userId, userRole, ticker));
    }

    /**
     * Vraca javno-vidljive pozicije ({@code publicQuantity > 0}).
     * Bez query parametara — sve pozicije (protokol §3.1 public-stock).
     * Sa {@code userId}+{@code userRole}+{@code ticker} — samo pozicije tog
     * prodavca za taj ticker (kvota provera pri inbound §3.2 createNegotiation).
     */
    @GetMapping("/public-stock")
    public ResponseEntity<?> getPublicStock(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "userRole", required = false) String userRole,
            @RequestParam(value = "ticker", required = false) String ticker) {
        if (userId != null && userRole != null && ticker != null) {
            return ResponseEntity.ok(portfolioService.findPublicStockForSeller(userId, userRole, ticker));
        }
        return ResponseEntity.ok(portfolioService.findAllPublicStock());
    }
}
