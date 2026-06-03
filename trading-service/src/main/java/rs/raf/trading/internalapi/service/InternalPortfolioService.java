package rs.raf.trading.internalapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CommitStockRequest;
import rs.raf.banka2.contracts.internal.CommitStockResponse;
import rs.raf.banka2.contracts.internal.InternalListingDto;
import rs.raf.banka2.contracts.internal.InternalPortfolioHoldingDto;
import rs.raf.banka2.contracts.internal.InternalPublicStockSellerDto;
import rs.raf.banka2.contracts.internal.ReleaseStockRequest;
import rs.raf.banka2.contracts.internal.ReleaseStockResponse;
import rs.raf.banka2.contracts.internal.ReserveStockRequest;
import rs.raf.banka2.contracts.internal.ReserveStockResponse;
import rs.raf.trading.internalapi.model.InternalRequest;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Jezgro internog portfolio/stock seam-a ({@code /internal/portfolio/**}).
 *
 * <p>Faza 2f: posle cutover-a {@code portfolios}/{@code listings} tabele zive samo
 * u trading_db. banka-core {@code interbank} paket (inter-bank OTC + 2PC settlement)
 * vise ne sme da radi in-process JPA pristup tim tabelama — zove ovaj servis preko
 * HTTP-a. Metode {@code reserveStock}/{@code commitStock}/{@code releaseStock}
 * verno reprodukuju logiku monolitovog {@code InterbankReservationApplier}.
 *
 * <p>Reserve/commit/release su idempotentne — {@code X-Idempotency-Key} + kesiranje
 * (mirror banka-core {@code InternalFundsService}); store + operacija su atomicni u
 * jednoj {@code @Transactional}. Read-side metode (holding/listing/public-stock)
 * nisu idempotentne (cisto citanje).
 */
@Service
public class InternalPortfolioService {

    private static final Logger log = LoggerFactory.getLogger(InternalPortfolioService.class);

    private final PortfolioRepository portfolioRepository;
    private final ListingRepository listingRepository;
    private final InternalIdempotencyService idempotencyService;

    /**
     * Privatni {@code ObjectMapper} — koristi se SAMO za (de)serijalizaciju
     * idempotency response tela. DTO-i seam-a su jednostavni record-i bez
     * {@code java.time} polja, pa plain mapper dovoljava; ne zavisi od Spring
     * Jackson auto-konfiguracije (koja u trading-service kontekstu nije bean).
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InternalPortfolioService(PortfolioRepository portfolioRepository,
                                    ListingRepository listingRepository,
                                    InternalIdempotencyService idempotencyService) {
        this.portfolioRepository = portfolioRepository;
        this.listingRepository = listingRepository;
        this.idempotencyService = idempotencyService;
    }

    // ─── Idempotent facade metode (findCached + operacija + store atomicno) ────

    /** Idempotent wrapper: reserveStock + idempotency store u jednoj transakciji. */
    @Transactional
    public ReserveStockResponse reserveStockIdempotent(String idempotencyKey, ReserveStockRequest req) {
        Optional<InternalRequest> cached = idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), ReserveStockResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        ReserveStockResponse result = reserveStock(req);
        storeIdempotency(idempotencyKey, "/internal/portfolio/reserve-stock", result);
        return result;
    }

    /** Idempotent wrapper: commitStock + idempotency store u jednoj transakciji. */
    @Transactional
    public CommitStockResponse commitStockIdempotent(String idempotencyKey, CommitStockRequest req) {
        Optional<InternalRequest> cached = idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), CommitStockResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        CommitStockResponse result = commitStock(req);
        storeIdempotency(idempotencyKey, "/internal/portfolio/commit-stock", result);
        return result;
    }

    /** Idempotent wrapper: releaseStock + idempotency store u jednoj transakciji. */
    @Transactional
    public ReleaseStockResponse releaseStockIdempotent(String idempotencyKey, ReleaseStockRequest req) {
        Optional<InternalRequest> cached = idempotencyService.findCached(idempotencyKey);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getResponseBody(), ReleaseStockResponse.class);
            } catch (Exception e) {
                log.warn("Idempotency deserialization failed for key {}: {}", idempotencyKey, e.getMessage());
            }
        }
        ReleaseStockResponse result = releaseStock(req);
        storeIdempotency(idempotencyKey, "/internal/portfolio/release-stock", result);
        return result;
    }

    // ─── Core operacije (verno monolitovom InterbankReservationApplier) ────────

    /**
     * Rezervise hartije: povecava {@code Portfolio.reservedQuantity}.
     * Verno {@code InterbankReservationApplier.reserveStock}.
     */
    @Transactional
    public ReserveStockResponse reserveStock(ReserveStockRequest req) {
        Listing listing = requireListing(req.ticker());
        Portfolio portfolio = portfolioRepository
                .findByUserIdAndUserRoleAndListingIdForUpdate(req.userId(), req.userRole(), listing.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "NO_SUCH_ASSET: no portfolio exists for listing " + listing.getId()));

        if (portfolio.getAvailableQuantity() < req.quantity()) {
            throw new IllegalStateException(
                    "INSUFFICIENT_QUANTITY on listing " + listing.getId() + ". Only "
                            + portfolio.getAvailableQuantity() + " quantity available.");
        }

        portfolio.setReservedQuantity(portfolio.getReservedQuantity() + req.quantity());
        portfolioRepository.save(portfolio);

        return new ReserveStockResponse(portfolio.getId(), listing.getId(), listing.getTicker(),
                portfolio.getReservedQuantity(), portfolio.getAvailableQuantity());
    }

    /**
     * Oslobadja rezervisane hartije: smanjuje {@code Portfolio.reservedQuantity}
     * (clamp na 0). Verno {@code InterbankReservationApplier.releaseStock}.
     */
    @Transactional
    public ReleaseStockResponse releaseStock(ReleaseStockRequest req) {
        Listing listing = requireListing(req.ticker());
        Portfolio portfolio = portfolioRepository
                .findByUserIdAndUserRoleAndListingIdForUpdate(req.userId(), req.userRole(), listing.getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "NO_SUCH_ASSET: no portfolio exists for listing " + listing.getId()));

        portfolio.setReservedQuantity(Math.max(0, portfolio.getReservedQuantity() - req.quantity()));
        portfolioRepository.save(portfolio);

        return new ReleaseStockResponse(portfolio.getId(), listing.getTicker(),
                portfolio.getReservedQuantity());
    }

    /**
     * Commit kretanja hartija. Verno {@code InterbankReservationApplier.commitStock}:
     * <ul>
     *   <li>{@code debit=true} — vlasnik DOBIJA hartije: povecava {@code quantity}
     *       (ili kreira nov portfolio sa {@code averageBuyPrice} = cena listinga).</li>
     *   <li>{@code debit=false} — vlasnik PREDAJE hartije: smanjuje {@code quantity}
     *       i {@code reservedQuantity} (clamp na 0).</li>
     * </ul>
     */
    @Transactional
    public CommitStockResponse commitStock(CommitStockRequest req) {
        Listing listing = requireListing(req.ticker());

        if (req.debit()) {
            Optional<Portfolio> current = portfolioRepository
                    .findByUserIdAndUserRoleAndListingIdForUpdate(req.userId(), req.userRole(), listing.getId());
            Portfolio portfolio;
            if (current.isPresent()) {
                portfolio = current.get();
                portfolio.setQuantity(portfolio.getQuantity() + req.quantity());
            } else {
                portfolio = Portfolio.builder()
                        .userId(req.userId())
                        .userRole(req.userRole())
                        .listingId(listing.getId())
                        .listingTicker(listing.getTicker())
                        .listingName(listing.getName())
                        .listingType(listing.getListingType().name())
                        .averageBuyPrice(listing.getPrice() != null ? listing.getPrice() : BigDecimal.ZERO)
                        .quantity(req.quantity())
                        .reservedQuantity(0)
                        .publicQuantity(0)
                        .build();
            }
            portfolioRepository.save(portfolio);
            return new CommitStockResponse(portfolio.getId(), listing.getId(), listing.getTicker(),
                    portfolio.getQuantity());
        } else {
            Portfolio portfolio = portfolioRepository
                    .findByUserIdAndUserRoleAndListingIdForUpdate(req.userId(), req.userRole(), listing.getId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "NO_SUCH_ASSET: no portfolio exists for listing " + listing.getId()));
            portfolio.setQuantity(portfolio.getQuantity() - req.quantity());
            portfolio.setReservedQuantity(Math.max(0, portfolio.getReservedQuantity() - req.quantity()));
            portfolioRepository.save(portfolio);
            return new CommitStockResponse(portfolio.getId(), listing.getId(), listing.getTicker(),
                    portfolio.getQuantity());
        }
    }

    // ─── Read-side (validacija + obogacivanje) ────────────────────────────────

    /**
     * Vraca listing po ticker-u. {@code Optional.empty()} ako ne postoji —
     * banka-core {@code interbank} validacija mapira to u {@code NO_SUCH_ASSET}.
     */
    @Transactional(readOnly = true)
    public Optional<InternalListingDto> findListingByTicker(String ticker) {
        return listingRepository.findByTicker(ticker).map(this::toListingDto);
    }

    /**
     * Vraca portfolio poziciju vlasnika za listing odredjen ticker-om.
     * {@code exists=false} ako listing ne postoji ili vlasnik nema portfolio.
     */
    @Transactional(readOnly = true)
    public InternalPortfolioHoldingDto findHolding(Long userId, String userRole, String ticker) {
        Optional<Listing> listingOpt = listingRepository.findByTicker(ticker);
        if (listingOpt.isEmpty()) {
            return new InternalPortfolioHoldingDto(false, null, null, ticker, 0, 0, 0);
        }
        Listing listing = listingOpt.get();
        Optional<Portfolio> portfolioOpt = portfolioRepository
                .findByUserIdAndUserRoleAndListingId(userId, userRole, listing.getId());
        if (portfolioOpt.isEmpty()) {
            return new InternalPortfolioHoldingDto(false, null, listing.getId(), ticker, 0, 0, 0);
        }
        Portfolio p = portfolioOpt.get();
        return new InternalPortfolioHoldingDto(true, p.getId(), listing.getId(), ticker,
                p.getQuantity(),
                p.getReservedQuantity() == null ? 0 : p.getReservedQuantity(),
                p.getAvailableQuantity());
    }

    /**
     * Vraca sve javno-vidljive pozicije ({@code publicQuantity > 0}).
     * banka-core {@code OtcNegotiationService.serveLocalPublicStocks} grupise ih
     * po ticker-u i oduzima kolicinu rezervisanu u ACTIVE inter-bank pregovorima.
     */
    @Transactional(readOnly = true)
    public List<InternalPublicStockSellerDto> findAllPublicStock() {
        // P2-perf-nplus1-1 (R5 1900): DB-side filter (publicQuantity > 0) umesto
        // findAll() + in-memory filter — izbegava pun-table-scan nad celom
        // portfolios tabelom (samo javne pozicije se materijalizuju).
        List<InternalPublicStockSellerDto> result = new ArrayList<>();
        for (Portfolio p : portfolioRepository.findAllWithPublicQuantity()) {
            int publicQty = p.getPublicQuantity() == null ? 0 : p.getPublicQuantity();
            if (publicQty <= 0) continue;
            result.add(new InternalPublicStockSellerDto(p.getUserId(), p.getUserRole(),
                    p.getListingTicker(), publicQty));
        }
        return result;
    }

    /**
     * Vraca javno-vidljive pozicije konkretnog vlasnika za odredjeni ticker.
     * banka-core {@code OtcNegotiationService.acceptCreatedNegotiation} koristi
     * sumu {@code publicQuantity} za kvota proveru prodavca.
     */
    @Transactional(readOnly = true)
    public List<InternalPublicStockSellerDto> findPublicStockForSeller(Long userId, String userRole,
                                                                       String ticker) {
        List<InternalPublicStockSellerDto> result = new ArrayList<>();
        for (Portfolio p : portfolioRepository.findByUserIdAndUserRole(userId, userRole)) {
            if (!p.getListingTicker().equals(ticker)) continue;
            int publicQty = p.getPublicQuantity() == null ? 0 : p.getPublicQuantity();
            result.add(new InternalPublicStockSellerDto(p.getUserId(), p.getUserRole(),
                    p.getListingTicker(), publicQty));
        }
        return result;
    }

    // ─── Pomocne metode ───────────────────────────────────────────────────────

    private Listing requireListing(String ticker) {
        return listingRepository.findByTicker(ticker)
                .orElseThrow(() -> new IllegalArgumentException("Listing not found: " + ticker));
    }

    private InternalListingDto toListingDto(Listing l) {
        return new InternalListingDto(l.getId(), l.getTicker(), l.getName(),
                l.getListingType() != null ? l.getListingType().name() : null,
                l.getPrice(), l.getQuoteCurrency(), l.getBaseCurrency());
    }

    private void storeIdempotency(String key, String endpoint, Object result) {
        String body;
        try {
            body = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // Serijalizacija MORA uspeti: idempotency kes mora biti konzistentan sa
            // izvrsenom operacijom. Propagiramo (unchecked) da se cela @Transactional
            // operacija rollback-uje — bez divergencije commit-ovano stanje vs kes.
            throw new RuntimeException("Idempotency serijalizacija nije uspela za kljuc " + key, e);
        }
        idempotencyService.store(key, endpoint, 200, body);
    }
}
