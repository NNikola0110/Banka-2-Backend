package rs.raf.banka2_bek.interbank.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/*
================================================================================
 INTERBANK TRANSACTION — STANJE 2PC TRANSAKCIJE (PROTOKOL §2.8)
 Spec ref: protokol §2.8 Transactions, §2.8.4 Local execution,
           §2.8.5 Remote execution
--------------------------------------------------------------------------------
 SVRHA:
   Persistentni trag svake distribuirane transakcije (placanja, OTC exercise,
   bilo cega). Iz perspektive nase banke, ovaj entitet drzi:
    - transactionId u protokol formi (foreignBankRouting + foreignBankIdString)
    - ulogu (INITIATOR vs RECIPIENT)
    - trenutnu fazu (PREPARING / PREPARED / COMMITTED / ROLLED_BACK / STUCK)
    - blob sa originalnim Transaction objektom (JSON) — koristi se za retry,
      verifikaciju i audit
    - listu primljenih TransactionVote-ova (kao IB)

 BLOB FORMAT:
   `transaction_body` cuva ceo Transaction objekat iz protocol/ paketa
   serijalizovan u JSON. Postingi se ne raspakuju u zasebne tabele jer:
    a) protokol ih tretira kao opaque (no domain knowledge)
    b) primenjivanje postinga (commitLocal) se radi kroz PostingApplier servis
       koji parsira blob i poziva domenske operacije
    c) audit cuva originalni format koji smo dobili / poslali

 INDEX:
   - (transaction_routing_number, transaction_id_string) UNIQUE — fast lookup
   - (status, last_activity_at) — za retry scheduler

 NAPOMENA:
   InterbankMessage je AUDIT za pojedinacne poruke (NEW_TX/COMMIT_TX/ROLLBACK_TX);
   InterbankTransaction je STANJE distribuirane transakcije. Veza preko
   transaction_id_string + transaction_routing_number.
================================================================================
*/
@Entity
@Table(name = "interbank_transactions", indexes = {
        @Index(
                name = "idx_ibt_transaction",
                columnList = "transaction_routing_number, transaction_id_string",
                unique = true
        ),
        @Index(name = "idx_ibt_status_activity", columnList = "status, last_activity_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InterbankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * §2.8.2 Transaction.transactionId.routingNumber — banka koja je formirala
     * transakciju (Initiating Bank). Ako == nas, mi smo IB; inace smo RB.
     */
    @Column(name = "transaction_routing_number", nullable = false)
    private Integer transactionRoutingNumber;

    /**
     * §2.8.2 Transaction.transactionId.id — opaque string max 64 bajta.
     */
    @Column(name = "transaction_id_string", nullable = false, length = 64)
    private String transactionIdString;

    /** Da li smo mi inicijator ili primalac. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InterbankTransactionRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InterbankTransactionStatus status;

    /**
     * Originalni Transaction body (JSON, §2.8.2). Cuva se da bismo mogli da
     * verifikujemo, retry-ujemo, ili audit-ujemo bez gubitka informacija.
     * Razmotri @JdbcTypeCode(SqlTypes.JSON) za PG jsonb mapiranje.
     */
    @Column(name = "transaction_body", columnDefinition = "text", nullable = false)
    private String transactionBody;

    /** Lista glasova kao IB (JSON niz TransactionVote-ova; null ako smo RB). */
    @Column(name = "votes", columnDefinition = "text")
    private String votes;

    /** Razlog ROLLED_BACK ili STUCK statusa. */
    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    /**
     * N5 — FX rate-pinning za cross-currency inbound settlement (§Celina 5 §40-66).
     *
     * <p>Kad smo RECIPIENT (Banka B) i primimo cross-currency NEW_TX, izracunamo i
     * <b>zakljucamo (pin)</b> mid-rate source→target u VOTE fazi. Commit faza MORA
     * koristiti ovaj pinned kurs umesto da re-racuna po live rate-u — inace FX drift
     * izmedju vote-a i commit-a moze da pomeri isplatu izvan provere stanja
     * obavljene pri glasanju (overdraft / leak). Null za same-currency ili
     * non-recipient transakcije.
     */
    @Column(name = "pinned_fx_rate", precision = 38, scale = 18)
    private BigDecimal pinnedFxRate;

    @Column(name = "retry_count", nullable = false)
    @ColumnDefault("0")
    private Integer retryCount = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "prepared_at")
    private LocalDateTime preparedAt;

    @Column(name = "committed_at")
    private LocalDateTime committedAt;

    @Column(name = "rolled_back_at")
    private LocalDateTime rolledBackAt;

    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    /**
     * Diskriminator uloge: jesmo li mi koordinator (poslali NEW_TX svima i
     * sakupljamo glasove) ili samo participant (primili NEW_TX, glasamo).
     */
    public enum InterbankTransactionRole {
        INITIATOR,
        RECIPIENT
    }
}
