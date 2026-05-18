package rs.raf.banka2_bek.internalapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "internal_requests",
       indexes = @Index(name = "idx_internal_req_key", columnList = "idempotencyKey", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class InternalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 80)
    private String endpoint;

    @Column(nullable = false)
    private int httpStatus;

    @Lob
    @Column(nullable = false)
    private String responseBody;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
