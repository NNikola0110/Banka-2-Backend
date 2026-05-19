package rs.raf.trading.internalapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit testovi za {@link InternalPortfolioService} — jezgro internog
 * portfolio/stock seam-a ({@code /internal/portfolio/**}).
 *
 * <p>Verifikuje da reserve/commit/release verno reprodukuju logiku monolitovog
 * {@code InterbankReservationApplier}, plus idempotency replay i read-side
 * (listing/holding/public-stock).
 */
@ExtendWith(MockitoExtension.class)
class InternalPortfolioServiceTest {

    @Mock private PortfolioRepository portfolioRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private InternalIdempotencyService idempotencyService;

    private InternalPortfolioService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new InternalPortfolioService(portfolioRepository, listingRepository,
                idempotencyService);
    }

    // ─── reserveStock ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("reserveStock: happy path → reservedQuantity povecan")
    void reserveStock_happyPath() {
        Listing listing = listing(7L, "AAPL");
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing));
        Portfolio portfolio = portfolio(7L, "AAPL", 20, 0);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 7L))
                .thenReturn(Optional.of(portfolio));

        ReserveStockResponse resp = service.reserveStock(
                new ReserveStockRequest(42L, "CLIENT", "AAPL", 10));

        assertThat(portfolio.getReservedQuantity()).isEqualTo(10);
        assertThat(resp.reservedQuantity()).isEqualTo(10);
        assertThat(resp.availableQuantity()).isEqualTo(10); // 20 - 10
        assertThat(resp.listingId()).isEqualTo(7L);
        verify(portfolioRepository).save(portfolio);
    }

    @Test
    @DisplayName("reserveStock: listing ne postoji → IllegalArgumentException (NO_SUCH_ASSET / 404)")
    void reserveStock_listingMissing_throws() {
        when(listingRepository.findByTicker("ZZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserveStock(
                new ReserveStockRequest(42L, "CLIENT", "ZZZZ", 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Listing not found");
    }

    @Test
    @DisplayName("reserveStock: portfolio ne postoji → IllegalArgumentException")
    void reserveStock_portfolioMissing_throws() {
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing(7L, "AAPL")));
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 7L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserveStock(
                new ReserveStockRequest(42L, "CLIENT", "AAPL", 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_SUCH_ASSET");
    }

    @Test
    @DisplayName("reserveStock: nedovoljna kolicina → IllegalStateException (INSUFFICIENT / 409)")
    void reserveStock_insufficient_throws() {
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing(7L, "AAPL")));
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 7L))
                .thenReturn(Optional.of(portfolio(7L, "AAPL", 5, 0))); // available 5, treba 10

        assertThatThrownBy(() -> service.reserveStock(
                new ReserveStockRequest(42L, "CLIENT", "AAPL", 10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSUFFICIENT_QUANTITY");
    }

    // ─── releaseStock ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("releaseStock: reservedQuantity smanjen, clamp na 0")
    void releaseStock_clampsToZero() {
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing(7L, "AAPL")));
        Portfolio portfolio = portfolio(7L, "AAPL", 20, 5);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 7L))
                .thenReturn(Optional.of(portfolio));

        // oslobadjamo 10 ali rezervisano je samo 5 → clamp na 0
        ReleaseStockResponse resp = service.releaseStock(
                new ReleaseStockRequest(42L, "CLIENT", "AAPL", 10));

        assertThat(portfolio.getReservedQuantity()).isZero();
        assertThat(resp.reservedQuantity()).isZero();
        verify(portfolioRepository).save(portfolio);
    }

    // ─── commitStock ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("commitStock debit=true, portfolio postoji → quantity povecan")
    void commitStock_debit_existingPortfolio() {
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing(7L, "AAPL")));
        Portfolio portfolio = portfolio(7L, "AAPL", 20, 0);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 7L))
                .thenReturn(Optional.of(portfolio));

        CommitStockResponse resp = service.commitStock(
                new CommitStockRequest(42L, "CLIENT", "AAPL", 10, true));

        assertThat(portfolio.getQuantity()).isEqualTo(30);
        assertThat(resp.quantity()).isEqualTo(30);
        verify(portfolioRepository).save(portfolio);
    }

    @Test
    @DisplayName("commitStock debit=true, portfolio ne postoji → kreira nov sa averageBuyPrice = cena listinga")
    void commitStock_debit_createsNewPortfolio() {
        Listing listing = listing(7L, "AAPL");
        listing.setPrice(new BigDecimal("185.50"));
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing));
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(99L, "CLIENT", 7L))
                .thenReturn(Optional.empty());

        service.commitStock(new CommitStockRequest(99L, "CLIENT", "AAPL", 15, true));

        ArgumentCaptor<Portfolio> captor = ArgumentCaptor.forClass(Portfolio.class);
        verify(portfolioRepository).save(captor.capture());
        Portfolio created = captor.getValue();
        assertThat(created.getUserId()).isEqualTo(99L);
        assertThat(created.getUserRole()).isEqualTo("CLIENT");
        assertThat(created.getListingId()).isEqualTo(7L);
        assertThat(created.getQuantity()).isEqualTo(15);
        assertThat(created.getReservedQuantity()).isZero();
        assertThat(created.getAverageBuyPrice()).isEqualByComparingTo("185.50");
    }

    @Test
    @DisplayName("commitStock debit=false → quantity i reservedQuantity smanjeni")
    void commitStock_credit_decrementsQuantityAndReserved() {
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing(7L, "AAPL")));
        Portfolio portfolio = portfolio(7L, "AAPL", 20, 10);
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 7L))
                .thenReturn(Optional.of(portfolio));

        CommitStockResponse resp = service.commitStock(
                new CommitStockRequest(42L, "CLIENT", "AAPL", 10, false));

        assertThat(portfolio.getQuantity()).isEqualTo(10);
        assertThat(portfolio.getReservedQuantity()).isZero(); // 10 - 10
        assertThat(resp.quantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("commitStock debit=false, portfolio ne postoji → IllegalArgumentException")
    void commitStock_credit_portfolioMissing_throws() {
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing(7L, "AAPL")));
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 7L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.commitStock(
                new CommitStockRequest(42L, "CLIENT", "AAPL", 10, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_SUCH_ASSET");
    }

    // ─── idempotency replay ───────────────────────────────────────────────────

    @Test
    @DisplayName("reserveStockIdempotent: kesiran odgovor → operacija se ne izvrsava ponovo")
    void reserveStockIdempotent_cachedReplay() throws Exception {
        ReserveStockResponse cached = new ReserveStockResponse(1L, 7L, "AAPL", 10, 10);
        InternalRequest req = new InternalRequest();
        req.setResponseBody(objectMapper.writeValueAsString(cached));
        when(idempotencyService.findCached("idem-1")).thenReturn(Optional.of(req));

        ReserveStockResponse resp = service.reserveStockIdempotent("idem-1",
                new ReserveStockRequest(42L, "CLIENT", "AAPL", 10));

        assertThat(resp.reservedQuantity()).isEqualTo(10);
        // operacija NIJE izvrsena — listing repo ni portfolio repo nisu dirani
        verify(listingRepository, never()).findByTicker(any());
        verify(portfolioRepository, never()).save(any());
    }

    @Test
    @DisplayName("reserveStockIdempotent: prvi poziv → izvrsava operaciju + skladisti idempotency")
    void reserveStockIdempotent_firstCall_storesIdempotency() {
        when(idempotencyService.findCached("idem-2")).thenReturn(Optional.empty());
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing(7L, "AAPL")));
        when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(42L, "CLIENT", 7L))
                .thenReturn(Optional.of(portfolio(7L, "AAPL", 20, 0)));

        service.reserveStockIdempotent("idem-2", new ReserveStockRequest(42L, "CLIENT", "AAPL", 10));

        verify(idempotencyService).store(eq("idem-2"), eq("/internal/portfolio/reserve-stock"),
                eq(200), any());
    }

    // ─── read-side ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findListingByTicker: postoji → mapiran InternalListingDto")
    void findListingByTicker_present() {
        Listing listing = listing(7L, "AAPL");
        listing.setPrice(new BigDecimal("180"));
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing));

        Optional<InternalListingDto> dto = service.findListingByTicker("AAPL");

        assertThat(dto).isPresent();
        assertThat(dto.get().id()).isEqualTo(7L);
        assertThat(dto.get().ticker()).isEqualTo("AAPL");
        assertThat(dto.get().listingType()).isEqualTo("STOCK");
        assertThat(dto.get().price()).isEqualByComparingTo("180");
    }

    @Test
    @DisplayName("findListingByTicker: ne postoji → Optional.empty")
    void findListingByTicker_missing() {
        when(listingRepository.findByTicker("ZZZZ")).thenReturn(Optional.empty());
        assertThat(service.findListingByTicker("ZZZZ")).isEmpty();
    }

    @Test
    @DisplayName("findHolding: listing ne postoji → exists=false")
    void findHolding_listingMissing() {
        when(listingRepository.findByTicker("ZZZZ")).thenReturn(Optional.empty());

        InternalPortfolioHoldingDto holding = service.findHolding(42L, "CLIENT", "ZZZZ");

        assertThat(holding.exists()).isFalse();
    }

    @Test
    @DisplayName("findHolding: portfolio postoji → exists=true sa availableQuantity")
    void findHolding_present() {
        when(listingRepository.findByTicker("AAPL")).thenReturn(Optional.of(listing(7L, "AAPL")));
        when(portfolioRepository.findByUserIdAndUserRoleAndListingId(42L, "CLIENT", 7L))
                .thenReturn(Optional.of(portfolio(7L, "AAPL", 20, 5)));

        InternalPortfolioHoldingDto holding = service.findHolding(42L, "CLIENT", "AAPL");

        assertThat(holding.exists()).isTrue();
        assertThat(holding.quantity()).isEqualTo(20);
        assertThat(holding.reservedQuantity()).isEqualTo(5);
        assertThat(holding.availableQuantity()).isEqualTo(15);
    }

    @Test
    @DisplayName("findAllPublicStock: vraca samo pozicije sa publicQuantity > 0")
    void findAllPublicStock_filtersZero() {
        when(portfolioRepository.findAll()).thenReturn(List.of(
                portfolioWithPublic(42L, "CLIENT", "AAPL", 7),
                portfolioWithPublic(43L, "CLIENT", "MSFT", 0))); // publicQty 0 → preskocen

        List<InternalPublicStockSellerDto> result = service.findAllPublicStock();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ticker()).isEqualTo("AAPL");
        assertThat(result.get(0).publicQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("findPublicStockForSeller: filtrira po ticker-u")
    void findPublicStockForSeller_filtersByTicker() {
        when(portfolioRepository.findByUserIdAndUserRole(42L, "CLIENT")).thenReturn(List.of(
                portfolioWithPublic(42L, "CLIENT", "AAPL", 7),
                portfolioWithPublic(42L, "CLIENT", "MSFT", 3)));

        List<InternalPublicStockSellerDto> result =
                service.findPublicStockForSeller(42L, "CLIENT", "AAPL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ticker()).isEqualTo("AAPL");
        assertThat(result.get(0).publicQuantity()).isEqualTo(7);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private Listing listing(Long id, String ticker) {
        Listing l = new Listing();
        l.setId(id);
        l.setTicker(ticker);
        l.setName(ticker + " Inc.");
        l.setListingType(ListingType.STOCK);
        return l;
    }

    private Portfolio portfolio(Long listingId, String ticker, int quantity, int reserved) {
        return Portfolio.builder()
                .id(1L)
                .userId(42L)
                .userRole("CLIENT")
                .listingId(listingId)
                .listingTicker(ticker)
                .listingName(ticker + " Inc.")
                .listingType("STOCK")
                .averageBuyPrice(new BigDecimal("100"))
                .quantity(quantity)
                .reservedQuantity(reserved)
                .publicQuantity(0)
                .lastModified(LocalDateTime.now())
                .build();
    }

    private Portfolio portfolioWithPublic(Long userId, String role, String ticker, int publicQty) {
        Portfolio p = portfolio(1L, ticker, 50, 0);
        p.setUserId(userId);
        p.setUserRole(role);
        p.setPublicQuantity(publicQty);
        return p;
    }
}
