package rs.raf.trading.audit.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * B7 — Audit log entitet u trading-service domenu
 * (port iz main PR #86 — duplicirano u trading-service jer je audit cross-cutting
 * i baza-po-servisu; banka-core i trading-service svaki ima svoju kopiju).
 */
@Entity
// R5 1971: findFiltered filtrira po action_type + actor_id, findByTargetTypeAndTargetId
// po (target_type, target_id). Bez indeksa su to seq-scan-ovi nad rastucom audit
// tabelom. Mirror banka-core AuditLog indeksa (oba servisa imaju svoju audit_logs).
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_actor_id", columnList = "actor_id"),
        @Index(name = "idx_audit_action_type", columnList = "action_type"),
        @Index(name = "idx_audit_target", columnList = "target_type, target_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32)
    private AuditActionType actionType;

    @Column(name = "description", nullable = false, length = 512)
    private String description;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
