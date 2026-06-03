package rs.raf.trading.otc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import rs.raf.trading.otc.dto.OtcNegotiationHistoryDto;
import rs.raf.trading.otc.model.OtcNegotiationHistory;
import rs.raf.trading.otc.model.OtcOffer;
import rs.raf.trading.otc.repository.OtcNegotiationHistoryRepository;
import rs.raf.trading.otc.repository.OtcOfferRepository;
import rs.raf.trading.otc.service.OtcAccessGuard;
import rs.raf.trading.otc.service.OtcNegotiationHistoryService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B10 — Unit testovi za {@link OtcNegotiationHistoryService} (port iz main PR #89,
 * Aja Timotic — package rename ka rs.raf.trading.otc.*).
 */
@ExtendWith(MockitoExtension.class)
class OtcNegotiationHistoryServiceTest {

    @Mock
    private OtcNegotiationHistoryRepository repository;

    @Mock
    private OtcOfferRepository offerRepository;

    @Mock
    private OtcAccessGuard accessGuard;

    @InjectMocks
    private OtcNegotiationHistoryService service;

    @BeforeEach
    void setUp() {
        // Default: SUPERVISOR rola — testovi za denial eksplicitno menjaju kontekst.
        authenticateAs("SUPERVISOR");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────── recordEntry ────────────────────

    @Test
    void recordEntry_savesEntityWithCorrectFields() {
        service.recordEntry(
                42L,
                100,
                new BigDecimal("12.5000"),
                new BigDecimal("5.0000"),
                LocalDate.of(2026, 6, 1),
                "ACTIVE",
                7L,
                "Marko Markovic");

        ArgumentCaptor<OtcNegotiationHistory> captor =
                ArgumentCaptor.forClass(OtcNegotiationHistory.class);
        verify(repository).save(captor.capture());

        OtcNegotiationHistory saved = captor.getValue();
        assertThat(saved.getNegotiationId()).isEqualTo(42L);
        assertThat(saved.getQuantity()).isEqualTo(100);
        assertThat(saved.getPricePerShare()).isEqualByComparingTo("12.5000");
        assertThat(saved.getPremium()).isEqualByComparingTo("5.0000");
        assertThat(saved.getSettlementDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getModifiedById()).isEqualTo(7L);
        assertThat(saved.getModifiedByName()).isEqualTo("Marko Markovic");
    }

    @Test
    void recordEntry_doesNotOpenNewTransaction() {
        for (int i = 0; i < 3; i++) {
            service.recordEntry(
                    (long) i,
                    10,
                    new BigDecimal("1.00"),
                    new BigDecimal("0.10"),
                    LocalDate.of(2026, 6, 1),
                    "ACTIVE",
                    1L,
                    "User");
        }
        verify(repository, times(3)).save(any(OtcNegotiationHistory.class));
    }

    // ──────────────────── getHistoryForNegotiation ────────────────────

    @Test
    void getHistoryForNegotiation_returnsChronologicalList() {
        OtcNegotiationHistory e1 = buildEntry(1L, 100, "ACTIVE", LocalDateTime.of(2026, 1, 1, 10, 0));
        OtcNegotiationHistory e2 = buildEntry(2L, 120, "ACTIVE", LocalDateTime.of(2026, 1, 2, 10, 0));
        OtcNegotiationHistory e3 = buildEntry(3L, 120, "ACCEPTED", LocalDateTime.of(2026, 1, 3, 10, 0));
        when(repository.findByNegotiationIdOrderByCreatedAtAsc(42L))
                .thenReturn(List.of(e1, e2, e3));
        when(offerRepository.findById(42L)).thenReturn(java.util.Optional.of(buildOffer(42L)));

        List<OtcNegotiationHistoryDto> result = service.getHistoryForNegotiation(42L);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(1).getId()).isEqualTo(2L);
        assertThat(result.get(2).getId()).isEqualTo(3L);
        assertThat(result.get(2).getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void getHistoryForNegotiation_throwsWhenNoEntries() {
        when(repository.findByNegotiationIdOrderByCreatedAtAsc(999L))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getHistoryForNegotiation(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Pregovor nije pronadjen");
    }

    @Test
    void getHistoryForNegotiation_mapsAllDtoFields() {
        LocalDateTime created = LocalDateTime.of(2026, 3, 15, 14, 30);
        OtcNegotiationHistory entry = OtcNegotiationHistory.builder()
                .id(11L)
                .negotiationId(42L)
                .quantity(250)
                .pricePerShare(new BigDecimal("99.9900"))
                .premium(new BigDecimal("7.5000"))
                .settlementDate(LocalDate.of(2026, 12, 31))
                .status("ACCEPTED")
                .modifiedById(33L)
                .modifiedByName("Ana Anic")
                .createdAt(created)
                .build();
        when(repository.findByNegotiationIdOrderByCreatedAtAsc(42L))
                .thenReturn(List.of(entry));
        when(offerRepository.findById(42L)).thenReturn(java.util.Optional.of(buildOffer(42L)));

        OtcNegotiationHistoryDto dto = service.getHistoryForNegotiation(42L).get(0);

        assertThat(dto.getId()).isEqualTo(11L);
        assertThat(dto.getNegotiationId()).isEqualTo(42L);
        assertThat(dto.getQuantity()).isEqualTo(250);
        assertThat(dto.getPricePerShare()).isEqualByComparingTo("99.9900");
        assertThat(dto.getPremium()).isEqualByComparingTo("7.5000");
        assertThat(dto.getSettlementDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(dto.getStatus()).isEqualTo("ACCEPTED");
        assertThat(dto.getModifiedById()).isEqualTo(33L);
        assertThat(dto.getModifiedByName()).isEqualTo("Ana Anic");
        assertThat(dto.getCreatedAt()).isEqualTo(created);
    }

    // ──────────────── getHistoryForNegotiation — IDOR guard (P1-authz-idor-1, R1 208) ────────────────

    @Test
    void getHistoryForNegotiation_deniedForNonParticipant() {
        // RED pre fix-a: single-ID istorija je bila samo authenticated() → ne-ucesnik
        // je enumeracijom citao tudje cene/premije.
        when(repository.findByNegotiationIdOrderByCreatedAtAsc(42L))
                .thenReturn(List.of(buildEntry(1L, 100, "ACTIVE", LocalDateTime.now())));
        when(offerRepository.findById(42L)).thenReturn(java.util.Optional.of(buildOffer(42L)));
        org.mockito.Mockito.doThrow(new AccessDeniedException("nije ucesnik"))
                .when(accessGuard).ensureParticipantOrOversight(
                        any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> service.getHistoryForNegotiation(42L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getHistoryForNegotiation_allowedForParticipant() {
        when(repository.findByNegotiationIdOrderByCreatedAtAsc(42L))
                .thenReturn(List.of(buildEntry(1L, 100, "ACTIVE", LocalDateTime.now())));
        when(offerRepository.findById(42L)).thenReturn(java.util.Optional.of(buildOffer(42L)));
        // guard no-op (ucesnik) → prolazi

        assertThat(service.getHistoryForNegotiation(42L)).hasSize(1);
        verify(accessGuard).ensureParticipantOrOversight(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void getHistoryForNegotiation_offerDeleted_deniedForNonOversight() {
        // Istorija prezivljava brisanje izvorne ponude — obican korisnik bez ponude
        // za proveru ucesca mora dobiti 403; oversight (admin/supervisor) prolazi.
        when(repository.findByNegotiationIdOrderByCreatedAtAsc(42L))
                .thenReturn(List.of(buildEntry(1L, 100, "ACTIVE", LocalDateTime.now())));
        when(offerRepository.findById(42L)).thenReturn(java.util.Optional.empty());
        when(accessGuard.isAdminOrSupervisor()).thenReturn(false);

        assertThatThrownBy(() -> service.getHistoryForNegotiation(42L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getHistoryForNegotiation_offerDeleted_allowedForOversight() {
        when(repository.findByNegotiationIdOrderByCreatedAtAsc(42L))
                .thenReturn(List.of(buildEntry(1L, 100, "ACTIVE", LocalDateTime.now())));
        when(offerRepository.findById(42L)).thenReturn(java.util.Optional.empty());
        when(accessGuard.isAdminOrSupervisor()).thenReturn(true);

        assertThat(service.getHistoryForNegotiation(42L)).hasSize(1);
    }

    // ──────────────────── findWithFilters — role check ────────────────────

    @Test
    void findWithFilters_allowedForSupervisor() {
        when(repository.findWithFilters(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<OtcNegotiationHistoryDto> result =
                service.findWithFilters(null, null, null, null, 0, 20);

        assertThat(result).isNotNull();
    }

    @Test
    void findWithFilters_allowedForAdmin() {
        authenticateAs("ROLE_ADMIN");
        when(repository.findWithFilters(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<OtcNegotiationHistoryDto> result =
                service.findWithFilters(null, null, null, null, 0, 20);

        assertThat(result).isNotNull();
    }

    @Test
    void findWithFilters_deniedForAgent() {
        authenticateAs("ROLE_AGENT");

        assertThatThrownBy(() ->
                service.findWithFilters(null, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void findWithFilters_deniedForClient() {
        authenticateAs("ROLE_CLIENT");

        assertThatThrownBy(() ->
                service.findWithFilters(null, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ──────────────────── findWithFilters — parameter passing ────────────────────

    @Test
    void findWithFilters_passesParametersToRepository() {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 5, 1, 0, 0);
        when(repository.findWithFilters(
                eq("ACCEPTED"), eq(42L), eq(from), eq(to), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        buildEntry(1L, 50, "ACCEPTED", LocalDateTime.now()))));

        Page<OtcNegotiationHistoryDto> result =
                service.findWithFilters("ACCEPTED", 42L, from, to, 0, 10);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(repository).findWithFilters(
                eq("ACCEPTED"), eq(42L), eq(from), eq(to), eq(PageRequest.of(0, 10)));
    }

    // ──────────────────── helpers ────────────────────

    private OtcNegotiationHistory buildEntry(Long id, int qty, String status, LocalDateTime createdAt) {
        return OtcNegotiationHistory.builder()
                .id(id)
                .negotiationId(42L)
                .quantity(qty)
                .pricePerShare(new BigDecimal("12.50"))
                .premium(new BigDecimal("5.00"))
                .settlementDate(LocalDate.of(2026, 6, 1))
                .status(status)
                .modifiedById(7L)
                .modifiedByName("Marko Markovic")
                .createdAt(createdAt)
                .build();
    }

    private OtcOffer buildOffer(Long id) {
        OtcOffer offer = new OtcOffer();
        offer.setId(id);
        offer.setBuyerId(7L);
        offer.setBuyerRole("CLIENT");
        offer.setSellerId(8L);
        offer.setSellerRole("CLIENT");
        return offer;
    }

    private void authenticateAs(String... authorities) {
        SecurityContext ctx = new SecurityContextImpl();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "user", "pwd",
                Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
        ctx.setAuthentication(token);
        SecurityContextHolder.setContext(ctx);
    }
}
