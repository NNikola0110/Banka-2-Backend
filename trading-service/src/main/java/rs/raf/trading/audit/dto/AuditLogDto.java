package rs.raf.trading.audit.dto;

import lombok.*;

import java.time.LocalDateTime;

/**
 * B7 — Audit log DTO u trading-service domenu
 * (port iz main PR #86; resolve actorName ide preko {@code BankaCoreClient.getUserById}).
 *
 * <p><b>R5 1879 (duplikat — DOCUMENT-ACCEPTED):</b> identican banka-core
 * {@code rs.raf.banka2_bek.audit.dto.AuditLogDto} (ista 11 polja). Konsolidacija u
 * {@code banka2-contracts} odlozena; polje-set se pin-uje karakterizacionim testom
 * ({@code AuditLogDtoContractPinTest}).
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
