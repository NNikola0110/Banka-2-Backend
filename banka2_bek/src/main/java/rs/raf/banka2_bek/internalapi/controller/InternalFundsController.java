package rs.raf.banka2_bek.internalapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.InternalErrorDto;
import rs.raf.banka2.contracts.internal.ProvisionFundAccountRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.TaxCollectRequest;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2_bek.internalapi.service.InternalAccountProvisioningService;
import rs.raf.banka2_bek.internalapi.service.InternalFundsService;
import rs.raf.banka2_bek.internalapi.service.InternalLookupService;

/**
 * Interni REST API za trading-service SAGA seam.
 * Sve rute su zasticene X-Internal-Key (InternalAuthFilter + ROLE_INTERNAL).
 * {@code /funds/**} mutirajuci endpoint-i zahtevaju X-Idempotency-Key;
 * idempotency se handluje direktno u InternalFundsService (reserveIdempotent /
 * commitIdempotent / releaseIdempotent / transferIdempotent) — store + operacija
 * su atomicni u jednoj @Transactional.
 * {@code POST /accounts/fund} provizionira FUND racun (bez idempotency kljuca —
 * fond se kreira jednom po pozivu trading-service-a).
 */
@RestController
@RequestMapping("/internal")
public class InternalFundsController {

    private final InternalFundsService fundsService;
    private final InternalLookupService lookupService;
    private final InternalAccountProvisioningService provisioningService;

    public InternalFundsController(InternalFundsService fundsService,
                                   InternalLookupService lookupService,
                                   InternalAccountProvisioningService provisioningService) {
        this.fundsService = fundsService;
        this.lookupService = lookupService;
        this.provisioningService = provisioningService;
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

    /**
     * Jednostrani kredit racuna bez debit kontra-strane — SELL prihod, dividende.
     * Trziste je apstraktan izvor novca (verno modelu monolita).
     */
    @PostMapping("/funds/credit")
    public ResponseEntity<?> credit(
            @RequestBody CreditFundsRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(fundsService.creditIdempotent(idempotencyKey, body));
    }

    /**
     * Jednostrani debit racuna bez credit kontra-strane — option exercise CALL,
     * pocetna uplata marginskog racuna. Trziste je apstraktan ponor novca
     * (simetricno {@code /funds/credit}).
     */
    @PostMapping("/funds/debit")
    public ResponseEntity<?> debit(
            @RequestBody DebitFundsRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(fundsService.debitIdempotent(idempotencyKey, body));
    }

    /**
     * Naplata poreza na kapitalnu dobit — debit RSD racuna klijenta, credit
     * drzavnog RSD racuna. Ako klijent nema sredstava, naplata se preskace
     * ({@code collected=false}).
     */
    @PostMapping("/funds/tax-collect")
    public ResponseEntity<?> taxCollect(
            @RequestBody TaxCollectRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(fundsService.collectTaxIdempotent(idempotencyKey, body));
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
     * Provizionira gotovinski (RSD) FUND racun za nov investicioni fond.
     * Literalni segment {@code fund} ne kolidira sa {@code /accounts/{id:Long}}
     * (drugi HTTP metod + Spring ne mapira ne-numericku vrednost na Long path var).
     */
    @PostMapping("/accounts/fund")
    public ResponseEntity<?> provisionFundAccount(@RequestBody ProvisionFundAccountRequest body) {
        return ResponseEntity.ok(
                provisioningService.provisionFundAccount(body.fundName(), body.managerEmployeeId()));
    }

    /**
     * Vraca bankin trading racun za datu valutu (kod).
     */
    @GetMapping("/accounts/bank-trading/{currencyCode}")
    public ResponseEntity<?> getBankTradingAccount(@PathVariable String currencyCode) {
        return ResponseEntity.ok(lookupService.getBankTradingAccount(currencyCode));
    }

    /**
     * Vraca podrazumevani racun OTC ucesnika u datoj valuti — za CLIENT klijentov
     * preferiran aktivan racun, za EMPLOYEE/ADMIN bankin trading racun (verno
     * monolitovom {@code OtcService.findDefaultAccount}). 4-segmentna putanja
     * ({@code /accounts/preferred/{userRole}/{userId}}) ne kolidira sa
     * {@code /accounts/{id}}, {@code /accounts/fund} ni {@code /accounts/bank-trading/{ccy}}.
     */
    @GetMapping("/accounts/preferred/{userRole}/{userId}")
    public ResponseEntity<?> getPreferredAccount(@PathVariable String userRole,
                                                 @PathVariable Long userId,
                                                 @RequestParam("currency") String currency) {
        return ResponseEntity.ok(lookupService.getPreferredAccount(userRole, userId, currency));
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
