package rs.raf.banka2_bek.audit.dto;

import lombok.*;

import java.time.LocalDateTime;

/**
 * B7 — Audit log DTO (port iz main PR #86, Stasa Dragovic).
 *
 * Vraca se ADMIN/SUPERVISOR korisnicima pri pregledu audit log-a.
 * {@code actorName} popunjava {@code AuditLogService.toDto()} lookup-om
 * ka {@link rs.raf.banka2_bek.employee.repository.EmployeeRepository}-u.
 *
 * <p><b>R5 1879 (duplikat — DOCUMENT-ACCEPTED):</b> trading-service ima identican
 * {@code rs.raf.trading.audit.dto.AuditLogDto} (ista 11 polja). Konsolidacija u
 * {@code banka2-contracts} je odlozena (serijalizovani odgovor + Lombok @Builder
 * kroz vise modula); polje-ekvivalencija se pin-uje karakterizacionim testom
 * ({@code AuditLogDtoContractPinTest}) tako da drift odmah pukne build.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogDto {
    private Long id;
    private Long actorId;
    private String actorType;
    private String actorName;
    private String actionType;
    private String description;
    private String targetType;
    private Long targetId;
    private String oldValue;
    private String newValue;
    private LocalDateTime createdAt;
}
