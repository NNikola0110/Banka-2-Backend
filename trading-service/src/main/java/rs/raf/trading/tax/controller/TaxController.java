package rs.raf.trading.tax.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.service.AuditLogService;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.dto.MessageResponseDto;
import rs.raf.trading.tax.dto.TaxBreakdownItemDto;
import rs.raf.trading.tax.dto.TaxRecordDto;
import rs.raf.trading.tax.service.TaxCalculationException;
import rs.raf.trading.tax.service.TaxService;

import java.util.List;

@RestController
@RequestMapping("/tax")
@RequiredArgsConstructor
@Slf4j
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
     * Zahteva ADMIN ili EMPLOYEE ulogu.
     *
     * <p><b>R1 429 (parcijalni FX pad — fix):</b> per-user obracun je u
     * {@code REQUIRES_NEW} (BE-PAY-04) pa svi obracunljivi korisnici budu
     * PERSISTOVANI cak i kad jednom (ili vise njih) nedostaje FX kurs. Pre fix-a
     * je {@code calculateTaxForAllUsers} agregatni {@link TaxCalculationException}
     * propagirao do {@link rs.raf.trading.common.TradingGlobalExceptionHandler}
     * (→ HTTP 400), pa je (a) klijent dobijao tvrdu gresku iako je vecina obracunata
     * i (b) {@code TAX_RUN_TRIGGERED} audit (koji je IZA poziva) bio preskocen —
     * gubitak revizorskog traga da je run uopste pokrenut. Sad: hvatamo parcijalni
     * pad, audit se UVEK emituje (run JESTE pokrenut), i vracamo 200 sa
     * parcijalnim-rezultatom umesto 400. Tvrdi (ne-FX) pad i dalje propagira.
     */
    @PostMapping("/calculate")
    @PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
    public ResponseEntity<MessageResponseDto> triggerCalculation(Authentication authentication) {
        String email = authentication != null ? authentication.getName() : "UNKNOWN";

        TaxCalculationException partialFailure = null;
        try {
            taxService.calculateTaxForAllUsers();
        } catch (TaxCalculationException ex) {
            // Parcijalni pad: bar jedan korisnik preskocen (najcesce FX nedostupan),
            // ali ostali su uspesno obracunati i persistovani (REQUIRES_NEW po korisniku).
            partialFailure = ex;
            log.warn("Manual tax run by {} completed with partial failure (first skipped user {} {}): {}",
                    email, ex.getUserId(), ex.getUserType(), ex.getMessage());
        }

        // B7 audit hook (port iz main PR #86, Stasa Dragovic) — UVEK se emituje:
        // run je pokrenut bez obzira na parcijalni pad (R1 429). U mikroservisnoj
        // arhitekturi banka-core ima Employee tabelu pa actorId resolvujemo preko
        // BankaCoreClient.getUserByEmail (umesto monolitovog EmployeeRepository).
        Long actorId = 0L;
        try {
            InternalUserDto user = bankaCoreClient.getUserByEmail(email);
            if (user != null) {
                actorId = user.userId();
            }
        } catch (RuntimeException ignored) {
            // banka-core lookup failed — koristimo fallback actorId=0
        }
        String auditDescription = partialFailure == null
                ? "Manual tax calculation triggered by " + email
                : "Manual tax calculation triggered by " + email
                        + " (PARTIAL: skipped user " + partialFailure.getUserId()
                        + " " + partialFailure.getUserType() + " — " + partialFailure.getMessage() + ")";
        // R1 394 (P2-audit-coverage-1): pad audit-a NE sme da obori vec-zavrsen tax-run
        // (200 se vraca ispod). Kontroler nije @Transactional pa nema phantom-rizika
        // (R4 1780 N/A) — calculateTaxForAllUsers je vec persistovao per-user REQUIRES_NEW.
        try {
            auditLogService.record(
                    actorId, "EMPLOYEE",
                    AuditActionType.TAX_RUN_TRIGGERED,
                    auditDescription,
                    null, null
            );
        } catch (Exception e) {
            log.warn("Audit log fail (best-effort) action=TAX_RUN_TRIGGERED by {}: {}", email, e.getMessage());
        }

        if (partialFailure != null) {
            // 200 + parcijalni-rezultat: vecina korisnika obracunata, neki preskoceni
            // (FX). Klijent (FE/Mobile supervizor portal) dobija poruku umesto 400.
            return ResponseEntity.ok(new MessageResponseDto(
                    "Obracun poreza zavrsen sa delimicnim preskakanjem (FX nedostupan za bar jednog korisnika: "
                            + partialFailure.getMessage() + "). Preostali korisnici su obracunati; pokrenuti "
                            + "ponovni obracun kad FX kursevi budu dostupni."));
        }
        return ResponseEntity.ok(new MessageResponseDto("Obracun poreza uspesno zavrsen."));
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
        // R2-1448: jedan identitet-lookup + jedan record-lookup (pre: 3 lookupa preko
        // getMyTaxRecord + getTaxBreakdownForUser). Prazna lista ako nema recorda.
        return ResponseEntity.ok(taxService.getMyTaxBreakdown(email));
    }
}
