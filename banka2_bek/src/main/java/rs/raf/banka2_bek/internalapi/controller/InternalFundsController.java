package rs.raf.banka2_bek.internalapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.InternalErrorDto;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2_bek.internalapi.service.InternalFundsService;
import rs.raf.banka2_bek.internalapi.service.InternalLookupService;

/**
 * Interni REST API za trading-service SAGA seam.
 * Sve rute su zasticene X-Internal-Key (InternalAuthFilter + ROLE_INTERNAL).
 * Mutirajuci endpoint-i zahtevaju X-Idempotency-Key; idempotency se handluje
 * direktno u InternalFundsService (reserveIdempotent / commitIdempotent /
 * releaseIdempotent / transferIdempotent) — store + operacija su atomicni u
 * jednoj @Transactional.
 */
@RestController
@RequestMapping("/internal")
public class InternalFundsController {

    private final InternalFundsService fundsService;
    private final InternalLookupService lookupService;

    public InternalFundsController(InternalFundsService fundsService,
                                   InternalLookupService lookupService) {
        this.fundsService = fundsService;
        this.lookupService = lookupService;
    }

    // ── Funds ────────────────────────────────────────────────────────────────

    /**
     * Rezervise sredstva na racunu za nadolazeci BUY order, OTC ili fond operaciju.
     */
    @PostMapping("/funds/reserve")
    public ResponseEntity<?> reserve(
            @RequestBody ReserveFundsRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(fundsService.reserveIdempotent(idempotencyKey, body));
    }

    /**
     * Naplacuje (deo) rezervacije — tipicno pri fill-u order-a.
     */
    @PostMapping("/funds/reservations/{reservationId}/commit")
    public ResponseEntity<?> commit(
            @PathVariable String reservationId,
            @RequestBody CommitFundsRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(fundsService.commitIdempotent(idempotencyKey, reservationId, body));
    }

    /**
     * Oslobadja preostali rezervisani iznos (decline / cancel / failed SAGA).
     */
    @PostMapping("/funds/reservations/{reservationId}/release")
    public ResponseEntity<?> release(
            @PathVariable String reservationId,
            @RequestBody ReleaseFundsRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(fundsService.releaseIdempotent(idempotencyKey, reservationId, body));
    }

    /**
     * Direktan prenos izmedju dva racuna (OTC premija, dividenda, porez, fond uplata).
     */
    @PostMapping("/funds/transfer")
    public ResponseEntity<?> transfer(
            @RequestBody TransferFundsRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(fundsService.transferIdempotent(idempotencyKey, body));
    }

    // ── Lookup ───────────────────────────────────────────────────────────────

    /**
     * Vraca metadata racuna (stanje, valuta, vlasnik) za dati ID.
     */
    @GetMapping("/accounts/{id}")
    public ResponseEntity<?> getAccount(@PathVariable Long id) {
        return ResponseEntity.ok(lookupService.getAccount(id));
    }

    /**
     * Vraca skup permisija zaposlenog identifikovanog email-om.
     * Vraca praznu listu ako zaposleni ne postoji.
     */
    @GetMapping("/users/{email}/permissions")
    public ResponseEntity<?> getUserPermissions(@PathVariable String email) {
        return ResponseEntity.ok(lookupService.getUserPermissions(email));
    }
}
