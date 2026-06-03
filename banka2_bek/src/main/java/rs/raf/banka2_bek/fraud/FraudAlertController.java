package rs.raf.banka2_bek.fraud;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [W3-T2] REST endpoint za Spark fraud detection alerte (banka-core).
 *
 * <p>Pristup ogranicen na ADMIN i SUPERVISOR — autorizacija centralno u
 * {@code GlobalSecurityConfig} (paritet sa {@code /audit/**} pattern-om i
 * postojecim AuditLogController-om). Bez {@code @PreAuthorize} na metodama.
 */
@RestController
@RequestMapping("/admin/fraud-alerts")
@RequiredArgsConstructor
public class FraudAlertController {

    /** [P2-input-validation-1 / R1 539] gornja granica page size-a (DoS guard). */
    private static final int MAX_PAGE_SIZE = 200;

    private final FraudAlertService service;

    /**
     * Lista alerta sa opcionim filterima.
     *
     * @param since ISO datetime — npr. {@code 2026-05-26T00:00:00}; null = bez filtera
     * @param minRisk min risk score (0..1); null = bez filtera
     * @param onlyPending defaultno {@code true} — FE prikazuje samo nerevizovane
     * @param page paginacija
     * @param size paginacija (default 50)
     */
    @GetMapping
    public ResponseEntity<FraudAlertPageDto> list(
            @RequestParam(value = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(value = "min_risk", required = false) BigDecimal minRisk,
            @RequestParam(value = "only_pending", defaultValue = "true") boolean onlyPending,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        // [P2-input-validation-1 / R1 539] validacija paginacije — negativan page
        // (i nevalidan size) je inace bacao PageRequest.of IllegalArgumentException
        // duboko u stack-u; size bez gornje granice (?size=2147483647) bi pokusao
        // ogroman fetch. Odbij negativan page/size sa 400 i clamp-uj size na MAX.
        if (page < 0) {
            throw new IllegalArgumentException("page mora biti >= 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size mora biti >= 1");
        }
        int cappedSize = Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(page, cappedSize);
        return ResponseEntity.ok(service.findAlerts(since, minRisk, onlyPending, pageable));
    }

    /**
     * Supervizor revizuje alert.
     * Body: {@code { "status": "confirmed|false_positive|closed", "note": "..." }}.
     */
    @PostMapping("/{id}/review")
    public ResponseEntity<FraudAlertDto> review(
            @PathVariable("id") Long id,
            @Valid @RequestBody ReviewFraudAlertDto request) {
        return ResponseEntity.ok(service.reviewAlert(id, request));
    }
}
