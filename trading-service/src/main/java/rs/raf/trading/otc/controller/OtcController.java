package rs.raf.trading.otc.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rs.raf.trading.otc.dto.CounterOtcOfferDto;
import rs.raf.trading.otc.dto.CreateOtcOfferDto;
import rs.raf.trading.otc.dto.OtcContractDto;
import rs.raf.trading.otc.dto.OtcListingDto;
import rs.raf.trading.otc.dto.OtcOfferDto;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.otc.saga.model.SagaLog;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;
import rs.raf.trading.otc.saga.service.OtcExerciseSagaOrchestrator;
import rs.raf.trading.otc.saga.service.SagaResult;
import rs.raf.trading.otc.saga.web.OtcExerciseResultDto;
import rs.raf.trading.otc.saga.web.SagaStatusDto;
import rs.raf.trading.otc.service.OtcAccessGuard;
import rs.raf.trading.otc.service.OtcService;

import java.util.List;

/**
 * Endpoint-i za OTC trgovinu (Celina 4 - intra-bank).
 *
 * Svi endpointi zahtevaju autentifikaciju. Pristup pojedinacnoj
 * ponudi/ugovoru proverava da li je trenutni korisnik ucesnik.
 */
@RestController
@RequestMapping("/otc")
@RequiredArgsConstructor
public class OtcController {

    private final OtcService otcService;
    private final OtcExerciseSagaOrchestrator exerciseSagaOrchestrator;
    private final OtcContractRepository contractRepository;
    private final SagaLogRepository sagaLogRepository;
    private final OtcAccessGuard accessGuard;

    @GetMapping("/listings")
    public ResponseEntity<List<OtcListingDto>> listDiscoveryListings() {
        return ResponseEntity.ok(otcService.listDiscoveryListings());
    }

    /**
     * Moje sopstvene javne akcije — portfolio item-i koje sam stavio u
     * javni rezim (publicQuantity > 0). User ne vidi svoje akcije u
     * Discovery-ju (linije 106-107 listDiscoveryListings filtriraju
     * `me.userId()`), pa ovaj endpoint daje vidljivost tom rezimu —
     * sta sam ja objavio za druge.
     */
    @GetMapping("/listings/my")
    public ResponseEntity<List<OtcListingDto>> listMyPublicListings() {
        return ResponseEntity.ok(otcService.listMyPublicListings());
    }

    @GetMapping("/offers/active")
    public ResponseEntity<List<OtcOfferDto>> listMyActiveOffers() {
        return ResponseEntity.ok(otcService.listMyActiveOffers());
    }

    @PostMapping("/offers")
    public ResponseEntity<OtcOfferDto> createOffer(@Valid @RequestBody CreateOtcOfferDto dto) {
        return ResponseEntity.ok(otcService.createOffer(dto));
    }

    @PostMapping("/offers/{offerId}/counter")
    public ResponseEntity<OtcOfferDto> counterOffer(@PathVariable Long offerId,
                                                    @Valid @RequestBody CounterOtcOfferDto dto) {
        return ResponseEntity.ok(otcService.counterOffer(offerId, dto));
    }

    @PostMapping("/offers/{offerId}/decline")
    public ResponseEntity<OtcOfferDto> declineOffer(@PathVariable Long offerId) {
        return ResponseEntity.ok(otcService.declineOffer(offerId));
    }

    @PostMapping("/offers/{offerId}/accept")
    public ResponseEntity<OtcOfferDto> acceptOffer(@PathVariable Long offerId,
                                                   @RequestParam(required = false) Long buyerAccountId) {
        return ResponseEntity.ok(otcService.acceptOffer(offerId, buyerAccountId));
    }

    @GetMapping("/contracts")
    public ResponseEntity<List<OtcContractDto>> listMyContracts(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(otcService.listMyContracts(status));
    }

    /**
     * Iskoriscavanje OTC opcionog ugovora preko Model-B SAGA orkestratora
     * ({@link OtcExerciseSagaOrchestrator}). SAGA se izvrsava sinhrono i UVEK
     * dolazi do terminalnog stanja (COMPLETED na uspeh ili COMPENSATED na
     * rollback); oba se vracaju kao HTTP 200 — pozivalac cita {@code sagaStatus}/
     * {@code status} (ili poll-uje {@code GET /otc/saga/{sagaId}}) da sazna ishod.
     *
     * <p>Pre-saga validacija baca {@code EntityNotFoundException} (404),
     * {@code AccessDeniedException} (403) i {@code IllegalStateException} (409) —
     * mapira {@link rs.raf.trading.otc.controller.exception_handler.OtcExceptionHandler}.
     */
    @PostMapping("/contracts/{contractId}/exercise")
    public ResponseEntity<OtcExerciseResultDto> exerciseContract(@PathVariable Long contractId,
                                                                 @RequestParam(required = false) Long buyerAccountId) {
        SagaResult result = exerciseSagaOrchestrator.exercise(contractId, buyerAccountId);
        OtcContract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new EntityNotFoundException("OTC ugovor ne postoji: " + contractId));
        return ResponseEntity.ok(new OtcExerciseResultDto(
                result.sagaId(), result.status().name(), result.currentStep(),
                contractId, contract.getStatus().name()));
    }

    /**
     * Polling stanja SAGA instance (SAGA_test.pdf Model-B). 404 ako saga sa datim
     * id-em ne postoji.
     */
    @GetMapping("/saga/{sagaId}")
    public ResponseEntity<SagaStatusDto> getSagaStatus(@PathVariable String sagaId) {
        SagaLog saga = sagaLogRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new EntityNotFoundException("SAGA ne postoji: " + sagaId));
        // P1-authz-idor-1 (R1 217): IDOR guard — saga log nosi tok exercise-a tudjeg
        // ugovora (cena, kolicina, koraci). Bez ove provere svaki CLIENT/SUPERVISOR je
        // enumeracijom sagaId-a citao tudju saga instancu. Razresavamo ucesnike preko
        // ugovora (saga.contractId) i propustamo samo buyer/seller ili admin/supervizora.
        OtcContract contract = contractRepository.findById(saga.getContractId()).orElse(null);
        if (contract == null) {
            if (!accessGuard.isAdminOrSupervisor()) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Niste ucesnik ovog OTC resursa (saga).");
            }
        } else {
            accessGuard.ensureParticipantOrOversight(
                    contract.getBuyerId(), contract.getBuyerRole(),
                    contract.getSellerId(), contract.getSellerRole(),
                    "saga");
        }
        return ResponseEntity.ok(new SagaStatusDto(
                saga.getSagaId(), saga.getStatus().name(), saga.getCurrentStep(), saga.getEntries()));
    }

    /** Rucno odustajanje od ugovora — kupac ne dobija nazad placenu premiju. */
    @PostMapping("/contracts/{contractId}/abandon")
    public ResponseEntity<OtcContractDto> abandonContract(@PathVariable Long contractId) {
        return ResponseEntity.ok(otcService.abandonContract(contractId));
    }
}
