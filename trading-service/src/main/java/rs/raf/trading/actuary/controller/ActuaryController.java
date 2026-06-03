package rs.raf.trading.actuary.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import rs.raf.trading.actuary.dto.ActuaryInfoDto;
import rs.raf.trading.actuary.dto.UpdateActuaryLimitDto;
import rs.raf.trading.actuary.service.ActuaryService;

import java.util.List;

/**
 * Controller za upravljanje aktuarima.
 * Pristup: samo supervizori (i admini koji su automatski supervizori).
 *
 */
@RestController
@RequestMapping("/actuaries")
@RequiredArgsConstructor
public class ActuaryController {

    private final ActuaryService actuaryService;

    /**
     * GET /actuaries/agents - Lista svih agenata
     * Filtriranje po email, firstName, lastName
     */
    @GetMapping("/agents")
    public ResponseEntity<List<ActuaryInfoDto>> getAgents(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName,
            @RequestParam(required = false) String position) {
        return ResponseEntity.ok(actuaryService.getAgents(email, firstName, lastName, position));
    }

    /**
     * GET /actuaries/{employeeId} - Aktuarski podaci za zaposlenog
     */
    @GetMapping("/{employeeId}")
    public ResponseEntity<ActuaryInfoDto> getActuaryInfo(@PathVariable Long employeeId) {
        return ResponseEntity.ok(actuaryService.getActuaryInfo(employeeId));
    }

    /**
     * PATCH /actuaries/{employeeId}/limit - Promena limita i needApproval
     * Samo supervizor moze da menja, samo za agente.
     *
     * <p>P2-authz-method-1 (R1 442): method-level guard usaglasen sa
     * {@link #resetUsedLimit}. {@code updateAgentLimit} mutira stanje (dnevni
     * limit + needApproval) a ranije je imao SAMO HTTP-matcher zastitu
     * ({@code /actuaries/** → ADMIN/SUPERVISOR} u {@code TradingSecurityConfig});
     * sad nosi i {@code @PreAuthorize} (defense-in-depth) — identicno
     * reset-limit-u — pa state-mutirajuci aktuar endpointi imaju konzistentan
     * guard cak i ako bi neko bypass-ovao/promenio URL matcher.
     */
    @PatchMapping("/{employeeId}/limit")
    @PreAuthorize("hasAuthority('SUPERVISOR') or hasRole('ADMIN')")
    public ResponseEntity<ActuaryInfoDto> updateAgentLimit(
            @PathVariable Long employeeId,
            @Valid @RequestBody UpdateActuaryLimitDto dto) {
        return ResponseEntity.ok(actuaryService.updateAgentLimit(employeeId, dto));
    }

    /**
     * PATCH /actuaries/{employeeId}/reset-limit - Reset usedLimit na 0
     * Supervizor rucno resetuje dnevni limit agenta.
     */
    @PatchMapping("/{employeeId}/reset-limit")
    @PreAuthorize("hasAuthority('SUPERVISOR') or hasRole('ADMIN')")
    public ResponseEntity<ActuaryInfoDto> resetUsedLimit(@PathVariable Long employeeId) {
        return ResponseEntity.ok(actuaryService.resetUsedLimit(employeeId));
    }
}
