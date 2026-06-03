package rs.raf.banka2_bek.otp.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * N2 — server-side single-use OTP store.
 *
 * <p>Stateless TOTP (windowSize=3, ~90s validnost) je po dizajnu replay-abilan:
 * isti 6-cifreni kod prolazi vise puta dok je u prozoru. Bez single-use-a, jedan
 * presretnut/procuren OTP omogucava replay placanja, transfera, stednje, kredita,
 * orderea i Arbitro write-akcija (6 flow-ova).</p>
 *
 * <p>Ovaj entitet belezi SVAKI uspesno potroseni OTP. UNIQUE constraint na
 * {@code (user_id, code_hash)} cini "potrosi kod" atomicnim na DB nivou: drugi
 * pokusaj istog koda za istog korisnika baca {@code DataIntegrityViolationException}
 * i odbija se. Zapisi su kratkotrajni (cisti ih {@code OtpCleanupScheduler}) jer
 * isti 6-cifreni kod posle dovoljno vremena predstavlja nov, legitiman TOTP prozor.</p>
 *
 * <p>{@code code_hash} je SHA-256 hex od {@code userId + ":" + code} — ne cuvamo
 * sirov OTP. {@code intent_hash} (opciono) vezuje kod za amount+recipient da
 * spreci cross-flow replay (npr. OTP za placanje A iskoriscen za placanje B).</p>
 *
 * <p>PG-DDL: sve kolone su BIGINT/VARCHAR/TIMESTAMP — bez boolean default zamke.</p>
 */
@Entity
@Table(
        name = "otp_consumed_codes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_otp_consumed_user_code",
                        columnNames = {"user_id", "code_hash"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtpConsumedCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    /**
     * Opcioni hash amount+recipient namere (bind-to-intent). NULL kad caller ne
     * prosledjuje intent (npr. mobilni getActiveOtp flow). Kad je prisutan, dva
     * razlicita placanja sa istim kodom dobijaju razlicit intentHash, ali isti
     * (user_id, code_hash) i dalje blokira replay — intentHash je dodatni audit.
     */
    @Column(name = "intent_hash", length = 64)
    private String intentHash;

    @Column(name = "consumed_at", nullable = false)
    @Builder.Default
    private LocalDateTime consumedAt = LocalDateTime.now();
}
