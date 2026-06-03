package rs.raf.trading.berza.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import rs.raf.trading.berza.dto.ExchangeDto;
import rs.raf.trading.berza.service.ExchangeManagementService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST kontroler za upravljanje berzama.
 *
 * Endpointovi:
 *   GET  /exchanges              - lista svih aktivnih berzi sa statusom
 *   GET  /exchanges/{acronym}    - detalji jedne berze
 *   PATCH /exchanges/{acronym}/test-mode - ukljuci/iskljuci test mode (ADMIN/SUPERVISOR)
 *   PUT/POST/DELETE /exchanges/{acronym}/holidays - praznici (ADMIN/SUPERVISOR)
 *
 * R4-444: mutacije globalne berza-config (test-mode + praznici) su admin/oversight
 * alat — gejtovane na ADMIN+SUPERVISOR (route layer u {@code TradingSecurityConfig}
 * + ovaj {@code @PreAuthorize}, defense-in-depth). Agenti i klijenti su iskljuceni.
 *
 * Specifikacija: Celina 3 - Berza
 */
@RestController
@RequestMapping("/exchanges")
@RequiredArgsConstructor
public class ExchangeManagementController {

    private final ExchangeManagementService exchangeManagementService;

    /**
     * GET /exchanges
     * Vraca listu svih aktivnih berzi sa computed statusom (isOpen, currentLocalTime, nextOpenTime).
     *
     */
    @GetMapping
    public ResponseEntity<List<ExchangeDto>> getAllExchanges() {
        return ResponseEntity.ok(exchangeManagementService.getAllExchanges());
    }

    @GetMapping("/{acronym}")
    public ResponseEntity<ExchangeDto> getByAcronym(@PathVariable String acronym) {
        return ResponseEntity.ok(exchangeManagementService.getByAcronym(acronym));
    }

    @PatchMapping("/{acronym}/test-mode")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN', 'SUPERVISOR')")
    public ResponseEntity<Map<String, String>> setTestMode(
            @PathVariable String acronym,
            @RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", false);
        exchangeManagementService.setTestMode(acronym, enabled);
        return ResponseEntity.ok(Map.of("message", "Test mode set to " + enabled + " for " + acronym));
    }

    /**
     * GET /exchanges/{acronym}/holidays
     * Vraca listu praznika za berzu.
     */
    @GetMapping("/{acronym}/holidays")
    public ResponseEntity<Set<LocalDate>> getHolidays(@PathVariable String acronym) {
        return ResponseEntity.ok(exchangeManagementService.getHolidays(acronym));
    }

    /**
     * PUT /exchanges/{acronym}/holidays
     * Postavlja kompletnu listu praznika za berzu (zamenjuje postojece).
     */
    @PutMapping("/{acronym}/holidays")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN', 'SUPERVISOR')")
    public ResponseEntity<Map<String, String>> setHolidays(
            @PathVariable String acronym,
            @RequestBody Set<LocalDate> holidays) {
        exchangeManagementService.setHolidays(acronym, holidays);
        return ResponseEntity.ok(Map.of("message", "Set " + holidays.size() + " holidays for " + acronym));
    }

    /**
     * POST /exchanges/{acronym}/holidays
     * Dodaje pojedinacni praznik za berzu.
     */
    @PostMapping("/{acronym}/holidays")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN', 'SUPERVISOR')")
    public ResponseEntity<Map<String, String>> addHoliday(
            @PathVariable String acronym,
            @RequestBody Map<String, String> body) {
        // R1-753: nedostajuci/prazan "date" je bacao NPE (LocalDate.parse(null)) ili
        // nejasan 400 bez poruke. Eksplicitno vrati 400 sa porukom; neispravan format
        // (DateTimeParseException) takodje hvatamo i vracamo razumljiv 400.
        LocalDate date = parseHolidayDate(body == null ? null : body.get("date"));
        exchangeManagementService.addHoliday(acronym, date);
        return ResponseEntity.ok(Map.of("message", "Added holiday " + date + " for " + acronym));
    }

    /**
     * R1-753: parsira ISO datum praznika iz tela; baca
     * {@link org.springframework.web.server.ResponseStatusException} (400) sa
     * razumljivom porukom kad je {@code date} prazan ili nevalidan, umesto
     * NPE/genericke greske.
     */
    private static LocalDate parseHolidayDate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Polje 'date' je obavezno (ISO format YYYY-MM-DD).");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Nevalidan datum '" + raw + "' (ocekivan ISO format YYYY-MM-DD).");
        }
    }

    /**
     * DELETE /exchanges/{acronym}/holidays/{date}
     * Uklanja praznik za berzu.
     */
    @DeleteMapping("/{acronym}/holidays/{date}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN', 'SUPERVISOR')")
    public ResponseEntity<Map<String, String>> removeHoliday(
            @PathVariable String acronym,
            @PathVariable LocalDate date) {
        exchangeManagementService.removeHoliday(acronym, date);
        return ResponseEntity.ok(Map.of("message", "Removed holiday " + date + " for " + acronym));
    }
}
