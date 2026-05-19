package rs.raf.trading.internalapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2.contracts.internal.InternalErrorDto;
import rs.raf.banka2.contracts.internal.ReassignFundManagerRequest;
import rs.raf.trading.internalapi.service.InternalFundService;

/**
 * Interni REST API za fond seam ({@code /internal/funds/**}).
 * Sve rute zasticene X-Internal-Key-em ({@code InternalAuthFilter} + ROLE_INTERNAL).
 *
 * <p>Faza 2f: banka-core {@code employee} paket je do sada radio in-process JPA
 * bulk reassign menadzera fondova ({@code InvestmentFundService.reassignFundManager}).
 * Posle cutover-a {@code investment_funds} tabela zivi samo u trading_db, pa
 * {@code employee} paket zove ovaj endpoint preko {@code TradingServiceInternalClient}.
 *
 * <p>{@code reassign-manager} je idempotentan — zahteva {@code X-Idempotency-Key};
 * idempotency se handluje u {@code InternalFundService} (findCached + operacija +
 * store atomicno).
 */
@RestController
@RequestMapping("/internal/funds")
public class InternalFundController {

    private final InternalFundService fundService;

    public InternalFundController(InternalFundService fundService) {
        this.fundService = fundService;
    }

    /**
     * Bulk prebacivanje vlasnistva: svi fondovi kojima upravlja
     * {@code oldManagerEmployeeId} dobijaju {@code newManagerEmployeeId}.
     * Poziva banka-core {@code EmployeeServiceImpl} kada admin oduzme SUPERVISOR
     * permisiju supervizoru. Vraca broj fondova kojima je promenjen menadzer.
     */
    @PostMapping("/reassign-manager")
    public ResponseEntity<?> reassignManager(
            @RequestBody ReassignFundManagerRequest body,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new InternalErrorDto("MISSING_IDEMPOTENCY_KEY", "X-Idempotency-Key je obavezan"));
        }
        return ResponseEntity.ok(fundService.reassignFundManagerIdempotent(idempotencyKey, body));
    }
}
