package rs.raf.trading.otc.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.otc.dto.OtcNegotiationHistoryDto;
import rs.raf.trading.otc.model.OtcNegotiationHistory;
import rs.raf.trading.otc.model.OtcOffer;
import rs.raf.trading.otc.repository.OtcNegotiationHistoryRepository;
import rs.raf.trading.otc.repository.OtcOfferRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * B10 — Servis za snimanje i preuzimanje istorije OTC pregovora.
 * (port iz main PR #89, Aja Timotic — package rename ka rs.raf.trading.otc.*)
 *
 * Pozivati ga iz {@link OtcService} pri svakom counter-offer, accept i
 * decline dogadjaju. {@code recordEntry} ne otvara novu transakciju —
 * oslanja se na pozivaocev {@code @Transactional} kontekst.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtcNegotiationHistoryService {

    private final OtcNegotiationHistoryRepository historyRepository;

    // P1-authz-idor-1 (R1 208): single-ID istorija je bila samo authenticated() →
    // svaki CLIENT je enumeracijom citao tudje cene/premije. Razresava ucesnika
    // preko izvorne ponude (negotiationId == OtcOffer.id) + admin/supervisor oversight.
    private final OtcOfferRepository offerRepository;
    private final OtcAccessGuard accessGuard;

    /**
     * Kreira i cuva novi {@link OtcNegotiationHistory} zapis. Pozivati unutar
     * {@code @Transactional} konteksta pozivaoca — metoda sama ne otvara
     * novu transakciju.
     */
    public void recordEntry(Long negotiationId,
                            Integer quantity,
                            BigDecimal pricePerShare,
                            BigDecimal premium,
                            LocalDate settlementDate,
                            String status,
                            Long modifiedById,
                            String modifiedByName) {
        OtcNegotiationHistory entry = OtcNegotiationHistory.builder()
                .negotiationId(negotiationId)
                .quantity(quantity)
                .pricePerShare(pricePerShare)
                .premium(premium)
                .settlementDate(settlementDate)
                .status(status)
                .modifiedById(modifiedById)
                .modifiedByName(modifiedByName)
                .build();
        historyRepository.save(entry);
        log.debug("OTC history recorded: negotiation={} status={}", negotiationId, status);
    }

    @Transactional(readOnly = true)
    public List<OtcNegotiationHistoryDto> getHistoryForNegotiation(Long negotiationId) {
        List<OtcNegotiationHistory> entries =
                historyRepository.findByNegotiationIdOrderByCreatedAtAsc(negotiationId);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Pregovor nije pronadjen");
        }
        // P1-authz-idor-1 (R1 208): IDOR guard — samo ucesnik pregovora (buyer/seller
        // izvorne ponude) ili admin/supervizor sme da cita istoriju cena/premija.
        // negotiationId je logicki FK ka OtcOffer.id. Ako je izvorna ponuda vec
        // obrisana (istorija prezivljava brisanje), oversight (admin/supervisor)
        // jos uvek prolazi; obican korisnik bez ponude za proveru → 403.
        OtcOffer offer = offerRepository.findById(negotiationId).orElse(null);
        if (offer == null) {
            if (!accessGuard.isAdminOrSupervisor()) {
                throw new AccessDeniedException(
                        "Niste ucesnik ovog OTC pregovora (istorije pregovora).");
            }
        } else {
            accessGuard.ensureParticipantOrOversight(
                    offer.getBuyerId(), offer.getBuyerRole(),
                    offer.getSellerId(), offer.getSellerRole(),
                    "istorije pregovora");
        }
        return entries.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Page<OtcNegotiationHistoryDto> findWithFilters(String status,
                                                          Long modifiedById,
                                                          LocalDateTime from,
                                                          LocalDateTime to,
                                                          int page,
                                                          int size) {
        ensureSupervisorOrAdmin();
        Page<OtcNegotiationHistory> entries = historyRepository.findWithFilters(
                status, modifiedById, from, to, PageRequest.of(page, size));
        return entries.map(this::toDto);
    }

    // ────────────────────────── helpers ──────────────────────────

    private void ensureSupervisorOrAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new AccessDeniedException("Niste autentifikovani.");
        }
        boolean allowed = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ADMIN".equals(a)
                        || "SUPERVISOR".equals(a)
                        || "ROLE_ADMIN".equals(a)
                        || "ROLE_SUPERVISOR".equals(a));
        if (!allowed) {
            throw new AccessDeniedException(
                    "Pregled cele istorije OTC pregovora dozvoljen je samo supervizorima i adminima.");
        }
    }

    private OtcNegotiationHistoryDto toDto(OtcNegotiationHistory entry) {
        return OtcNegotiationHistoryDto.builder()
                .id(entry.getId())
                .negotiationId(entry.getNegotiationId())
                .quantity(entry.getQuantity())
                .pricePerShare(entry.getPricePerShare())
                .premium(entry.getPremium())
                .settlementDate(entry.getSettlementDate())
                .status(entry.getStatus())
                .modifiedById(entry.getModifiedById())
                .modifiedByName(entry.getModifiedByName())
                .createdAt(entry.getCreatedAt())
                .build();
    }
}
