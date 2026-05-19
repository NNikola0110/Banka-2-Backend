package rs.raf.trading.internalapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.ReassignFundManagerRequest;
import rs.raf.banka2.contracts.internal.ReassignFundManagerResponse;
import rs.raf.trading.internalapi.model.InternalRequest;
import rs.raf.trading.investmentfund.service.InvestmentFundService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit testovi za {@link InternalFundService} — jezgro internog fond seam-a
 * ({@code /internal/funds/**}).
 *
 * <p>Verifikuje da bulk reassign delegira na monolitov
 * {@code InvestmentFundService.reassignFundManager}, plus idempotency replay.
 * Mirror {@code InternalPortfolioServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class InternalFundServiceTest {

    @Mock private InvestmentFundService investmentFundService;
    @Mock private InternalIdempotencyService idempotencyService;

    private InternalFundService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new InternalFundService(investmentFundService, idempotencyService);
    }

    // ─── bulk reassign ────────────────────────────────────────────────────────

    @Test
    @DisplayName("reassignFundManager: delegira na InvestmentFundService, vraca count")
    void reassignFundManager_delegatesAndReturnsCount() {
        when(investmentFundService.reassignFundManager(100L, 200L)).thenReturn(3);

        ReassignFundManagerResponse resp = service.reassignFundManager(
                new ReassignFundManagerRequest(100L, 200L));

        assertThat(resp.reassignedCount()).isEqualTo(3);
        verify(investmentFundService).reassignFundManager(100L, 200L);
    }

    @Test
    @DisplayName("reassignFundManager: stari menadzer bez fondova → count 0")
    void reassignFundManager_noFunds_returnsZero() {
        when(investmentFundService.reassignFundManager(999L, 200L)).thenReturn(0);

        ReassignFundManagerResponse resp = service.reassignFundManager(
                new ReassignFundManagerRequest(999L, 200L));

        assertThat(resp.reassignedCount()).isZero();
    }

    // ─── idempotency replay ───────────────────────────────────────────────────

    @Test
    @DisplayName("reassignFundManagerIdempotent: kesiran odgovor → operacija se ne izvrsava ponovo")
    void reassignFundManagerIdempotent_cachedReplay() throws Exception {
        ReassignFundManagerResponse cached = new ReassignFundManagerResponse(2);
        InternalRequest req = new InternalRequest();
        req.setResponseBody(objectMapper.writeValueAsString(cached));
        when(idempotencyService.findCached("reassign-mgr-100-200")).thenReturn(Optional.of(req));

        ReassignFundManagerResponse resp = service.reassignFundManagerIdempotent(
                "reassign-mgr-100-200", new ReassignFundManagerRequest(100L, 200L));

        assertThat(resp.reassignedCount()).isEqualTo(2);
        // operacija NIJE izvrsena — InvestmentFundService nije diran
        verify(investmentFundService, never()).reassignFundManager(any(), any());
    }

    @Test
    @DisplayName("reassignFundManagerIdempotent: prvi poziv → izvrsava operaciju + skladisti idempotency")
    void reassignFundManagerIdempotent_firstCall_storesIdempotency() {
        when(idempotencyService.findCached("reassign-mgr-100-200")).thenReturn(Optional.empty());
        when(investmentFundService.reassignFundManager(100L, 200L)).thenReturn(2);

        ReassignFundManagerResponse resp = service.reassignFundManagerIdempotent(
                "reassign-mgr-100-200", new ReassignFundManagerRequest(100L, 200L));

        assertThat(resp.reassignedCount()).isEqualTo(2);
        verify(investmentFundService).reassignFundManager(100L, 200L);
        verify(idempotencyService).store(eq("reassign-mgr-100-200"),
                eq("/internal/funds/reassign-manager"), eq(200), any());
    }
}
