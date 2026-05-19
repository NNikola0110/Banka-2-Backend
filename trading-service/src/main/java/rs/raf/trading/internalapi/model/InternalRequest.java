package rs.raf.trading.internalapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Idempotency zapis za trading-service interni API ({@code /internal/portfolio/**}).
 * Mirror banka-core {@code internalapi.model.InternalRequest} — kesira (httpStatus,
 * responseBody) po {@code idempotencyKey}-u tako da ponovljen poziv (banka-core
 * {@code InterbankRetryScheduler}) ne primeni kretanje hartija dvaput.
 */
@Entity
@Table(name = "internal_requests",
       indexes = @Index(name = "idx_trading_internal_req_key", columnList = "idempotencyKey", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class InternalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Idempotency kljuc. Duzina 200 — inter-bank hartijski kljuc je
     * {@code ib-{rn}-{64-hex-txid}:stock-{phase}:{userId}:{role}:{ticker}}
     * (banka-core {@code TransactionExecutorService.stockIdempotencyKey}),
     * sto premasuje 100 znakova.
     */
    @Column(nullable = false, unique = true, length = 200)
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
