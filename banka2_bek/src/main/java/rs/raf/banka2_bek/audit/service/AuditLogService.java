package rs.raf.banka2_bek.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.audit.dto.AuditLogDto;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.model.AuditLog;
import rs.raf.banka2_bek.audit.repository.AuditLogRepository;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * B7 — Audit log servis (port iz main PR #86, Stasa Dragovic).
 *
 * {@code record()} koristi {@link Propagation#REQUIRES_NEW} da audit upis
 * ostane cak i ako pozivajuca transakcija bude rollback-ovana — bitno za
 * logovanje neuspelih akcija. Audit log je append-only — nema delete metoda.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    /** Maks duzina {@code description} kolone (AuditLog.java:40). */
    static final int MAX_DESCRIPTION_LENGTH = 512;

    private final AuditLogRepository auditLogRepository;
    private final EmployeeRepository employeeRepository;
    private final ClientRepository clientRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String actorType, AuditActionType action,
                       String description, String targetType, Long targetId,
                       String oldValue, String newValue) {
        AuditLog log = AuditLog.builder()
                .actorId(actorId)
                .actorType(actorType)
                .actionType(action)
                .description(truncate(description))
                .targetType(targetType)
                .targetId(targetId)
                .oldValue(oldValue)
                .newValue(newValue)
                .build();
        auditLogRepository.save(log);
    }

    /**
     * R1 395: {@code description} je {@code length=512} NOT NULL — ne-skraceni
     * tekst duzi od 512 znakova baca {@code DataIntegrityViolationException} (PG)
     * i obara ceo audit upis. Skratimo na 512 (sa "..." sufiksom kad je odsecak)
     * umesto da izgubimo ceo trag.
     */
    private static String truncate(String description) {
        if (description == null || description.length() <= MAX_DESCRIPTION_LENGTH) {
            return description;
        }
        return description.substring(0, MAX_DESCRIPTION_LENGTH - 3) + "...";
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long actorId, String actorType, AuditActionType action,
                       String description, String targetType, Long targetId) {
        record(actorId, actorType, action, description, targetType, targetId, null, null);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogDto> query(AuditActionType actionType, Long actorId,
                                   LocalDateTime from, LocalDateTime to,
                                   Pageable pageable) {
        return auditLogRepository
                .findFiltered(actionType, actorId, from, to, pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<AuditLogDto> findById(Long id) {
        return auditLogRepository.findById(id).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<AuditLogDto> findByResource(String targetType, Long targetId) {
        return auditLogRepository.findByTargetTypeAndTargetId(targetType, targetId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private AuditLogDto toDto(AuditLog log) {
        String actorName = resolveActorName(log.getActorId(), log.getActorType());
        return AuditLogDto.builder()
                .id(log.getId())
                .actorId(log.getActorId())
                .actorType(log.getActorType())
                .actorName(actorName)
                .actionType(log.getActionType().name())
                .description(log.getDescription())
                .targetType(log.getTargetType())
                .targetId(log.getTargetId())
                .oldValue(log.getOldValue())
                .newValue(log.getNewValue())
                .createdAt(log.getCreatedAt())
                .build();
    }

    private String resolveActorName(Long actorId, String actorType) {
        if (actorId == null) {
            return "ID:null";
        }
        if ("EMPLOYEE".equals(actorType)) {
            return employeeRepository.findById(actorId)
                    .map(e -> e.getFirstName() + " " + e.getLastName())
                    .orElse("ID:" + actorId);
        }
        // R1 392: CLIENT aktor je ranije uvek davao "ID:42" jer resolveActorName
        // gleda samo EMPLOYEE tabelu. Klijent ime/prezime razresavamo iz CLIENT tabele.
        if ("CLIENT".equals(actorType)) {
            return clientRepository.findById(actorId)
                    .map(c -> c.getFirstName() + " " + c.getLastName())
                    .orElse("ID:" + actorId);
        }
        return "ID:" + actorId;
    }
}
