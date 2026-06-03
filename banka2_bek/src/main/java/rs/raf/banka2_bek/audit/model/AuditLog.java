package rs.raf.banka2_bek.audit.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * B7 — Audit log entitet (port iz main PR #86, Stasa Dragovic).
 *
 * Svaka administrativna akcija cuva ko je izvrsio (actor), sta je uradjeno
 * (actionType, description), na kom resursu (targetType, targetId) i koje su
 * bile stare i nove vrednosti (oldValue, newValue). Append-only — nema
 * @Version niti @UpdateTimestamp jer su zapisi immutable.
 */
@Entity
// R5-1899: findFiltered filtrira po action_type + actor_id, findByTargetTypeAndTargetId
// po (target_type, target_id). Particionisanje je samo po created_at (pomaze time-range),
// pa su unutar particije svi ostali filteri seq-scan. Entitet ranije nije definisao
// nijedan @Index → (LIKE ... INCLUDING ALL) particionisanje nije imalo sta da kopira.
// Hibernate kreira indexe na parent particionisanoj tabeli → propagiraju se na nove particije.
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
