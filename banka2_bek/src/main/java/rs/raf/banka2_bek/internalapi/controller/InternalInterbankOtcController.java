package rs.raf.banka2_bek.internalapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2.contracts.internal.InterbankOtcExercisedDto;
import rs.raf.banka2_bek.internalapi.service.InternalInterbankOtcQueryService;

import java.util.List;

/**
 * P2-tax-interbank-otc-1 — interni endpoint koji izlaze EXERCISED inter-bank
 * OTC ugovore trading-service tax engine-u:
 *   GET /internal/interbank-otc/exercised — svi EXERCISED ugovori sa lokalnom stranom
 *
 * <p>banka-core nema tax modul; inter-bank OTC ugovori zive ovde
 * ({@code interbank_otc_contracts}). Bez ovog seam-a, lokalni CLIENT koji
 * exercise-uje inter-bank opciju realizuje kapitalnu dobit koju trading-service
 * tax engine ne vidi → under-taxation. Trading-service ih ukljucuje u 15%
 * obracun istom logikom kao intra-OTC (seller proceeds = strike×qty + premium,
 * buyer cost-basis = strike×qty, EMPLOYEE izuzet).
 *
 * <p>Zasticen istim X-Internal-Key seam-om kao ostali {@code /internal/**}
 * ({@code InternalAuthFilter} → ROLE_INTERNAL). READ-ONLY; ne dira 2PC
 * wire-protokol.
 */
@RestController
@RequestMapping("/internal/interbank-otc")
public class InternalInterbankOtcController {

    private final InternalInterbankOtcQueryService queryService;

    public InternalInterbankOtcController(InternalInterbankOtcQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Vraca sve EXERCISED inter-bank OTC ugovore sa lokalnom stranom.
     */
    @GetMapping("/exercised")
    public ResponseEntity<List<InterbankOtcExercisedDto>> getExercised() {
        return ResponseEntity.ok(queryService.findExercised());
    }
}
