package rs.raf.trading.tax.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.service.AuditLogService;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.tax.dto.TaxBreakdownItemDto;
import rs.raf.trading.tax.dto.TaxRecordDto;
import rs.raf.trading.tax.service.TaxService;

import java.util.List;

@RestController
@RequestMapping("/tax")
@RequiredArgsConstructor
public class TaxController {

    private final TaxService taxService;
    private final AuditLogService auditLogService;
    private final BankaCoreClient bankaCoreClient;

    /**
     * GET /tax - Lista korisnika sa dugovanjima (supervizor portal).
     * Filtriranje po userType i name.
     * Zahteva ADMIN ili EMPLOYEE ulogu.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public ResponseEntity<List<TaxRecordDto>> getTaxRecords(
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) String name) {
        List<TaxRecordDto> records = taxService.getTaxRecords(name, userType);
        return ResponseEntity.ok(records);
    }

    /**
     * GET /tax/my - Vraca poreski zapis za autentifikovanog korisnika.
     * Dostupno svim autentifikovanim korisnicima.
     */
    @GetMapping("/my")
    public ResponseEntity<TaxRecordDto> getMyTaxRecord(Authentication authentication) {
        String email = authentication.getName();
        TaxRecordDto record = taxService.getMyTaxRecord(email);
        return ResponseEntity.ok(record);
    }

    /**
     * POST /tax/calculate - Pokreni obracun poreza za sve korisnike.
     * Zahteva ADMIN ulogu.
     */
    @PostMapping("/calculate")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public ResponseEntity<Void> triggerCalculation(Authentication authentication) {
        taxService.calculateTaxForAllUsers();

        // B7 audit hook (port iz main PR #86, Stasa Dragovic)
        // U mikroservisnoj arhitekturi banka-core ima Employee tabelu — pa actorId
        // resolvujemo preko BankaCoreClient.getUserByEmail (umesto monolitovog
        // EmployeeRepository).
        String email = authentication != null ? authentication.getName() : "UNKNOWN";
        Long actorId = 0L;
        try {
            InternalUserDto user = bankaCoreClient.getUserByEmail(email);
            if (user != null) {
                actorId = user.userId();
            }
        } catch (RuntimeException ignored) {
            // banka-core lookup failed — koristimo fallback actorId=0
        }
        auditLogService.record(
                actorId, "EMPLOYEE",
                AuditActionType.TAX_RUN_TRIGGERED,
                "Manual tax calculation triggered by " + email,
                null, null
        );

        return ResponseEntity.ok().build();
    }

    /**
     * P2.4 — GET /tax/{userId}/{userType}/breakdown - per-listing
     * granularni breakdown poreza za korisnika. Supervizor only.
     */
    @GetMapping("/{userId}/{userType}/breakdown")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public ResponseEntity<List<TaxBreakdownItemDto>> getBreakdown(
            @PathVariable Long userId,
            @PathVariable String userType) {
        return ResponseEntity.ok(taxService.getTaxBreakdownForUser(userId, userType));
    }

    /**
     * P2.4 — GET /tax/my/breakdown - per-listing breakdown poreza za
     * autentifikovanog korisnika.
     */
    @GetMapping("/my/breakdown")
    public ResponseEntity<List<TaxBreakdownItemDto>> getMyBreakdown(Authentication authentication) {
        String email = authentication.getName();
        // Resolva email -> (userId, userType) preko getMyTaxRecord
        TaxRecordDto myRecord = taxService.getMyTaxRecord(email);
        if (myRecord.getId() == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(
                taxService.getTaxBreakdownForUser(myRecord.getUserId(), myRecord.getUserType()));
    }
}
