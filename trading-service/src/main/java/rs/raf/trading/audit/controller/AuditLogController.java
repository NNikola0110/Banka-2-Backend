package rs.raf.trading.audit.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import rs.raf.trading.audit.dto.AuditLogDto;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.service.AuditLogService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * B7 — REST kontroler za trading-service audit log (port iz main PR #86).
 *
 * Bazna putanja: /audit (isto kao u banka-core — gateway rutira /audit/**
 * ka pravom servisu na osnovu konteksta).
 */
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<Page<AuditLogDto>> getAuditLogs(
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String actorName,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        AuditActionType parsedActionType = null;
        if (actionType != null && !actionType.isBlank()) {
            try {
                parsedActionType = AuditActionType.valueOf(actionType);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown actionType: " + actionType);
            }
        }

        LocalDateTime parsedFrom = from != null ? LocalDateTime.parse(from) : null;
        LocalDateTime parsedTo = to != null ? LocalDateTime.parse(to) : null;

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Sc45 (TODO_testovi): filter po IMENU aktera (supervizora). Numericki actorId
        // ima prednost (specificniji, postojeci FE/Mobile filter); actorName je
        // alternativni put (scenario "When unese ime supervizora u filter") — servis
        // razresi ime u actorId-eve preko banka-core i filtrira po njima.
        if (actorId == null && actorName != null && !actorName.isBlank()) {
            return ResponseEntity.ok(auditLogService.queryByActorName(
                    parsedActionType, actorName, parsedFrom, parsedTo, pageable));
        }
        return ResponseEntity.ok(auditLogService.query(parsedActionType, actorId, parsedFrom, parsedTo, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AuditLogDto> getById(@PathVariable Long id) {
        return ResponseEntity.ok(auditLogService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Audit log entry not found: " + id)));
    }

    @GetMapping("/resource/{targetType}/{targetId}")
    public ResponseEntity<List<AuditLogDto>> getByResource(
            @PathVariable String targetType,
            @PathVariable Long targetId) {
        return ResponseEntity.ok(auditLogService.findByResource(targetType, targetId));
    }
}
