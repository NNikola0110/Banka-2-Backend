package rs.raf.trading.investmentfund.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <b>P1-2 — trajni per-klijent ledger isplate fondovske dividende.</b>
 *
 * <p>Jedan red = jedan ulazni dividendni priliv ({@code sourceDividendInflowTxId})
 * isplacen tacno jednom konkretnom klijentu ({@code clientUserId}) u okviru jedne
 * distribucione runde ({@code cycleKey}). Red se upisuje <b>write-ahead</b>
 * (preko {@link rs.raf.trading.investmentfund.service.FundDividendLedgerWriter} u
 * {@code REQUIRES_NEW} transakciji) TEK POSLE uspesnog banka-core transfera, pa
 * prezivi rollback orkestratorovog {@code @Transactional} bloka:
 *
 * <ul>
 *   <li>banka-core transfer je commit-ovan out-of-process (novac je vec presao),</li>
 *   <li>ako outer trading_db tx kasnije padne/rollback-uje, ledger marker OSTAJE,</li>
 *   <li>sledeci run (cron retry) vidi marker → preskace tog klijenta → NEMA double-pay.</li>
 * </ul>
 *
 * <p>{@code idempotencyKey} je <b>stabilan kroz run-ove</b> (NE izvodi se iz
 * IDENTITY id-a {@link ClientFundTransaction} koji nije postojan kroz rollback) i
 * predstavlja primarnu odbranu (banka-core dedup po istom kljucu). Unique
 * constraint-i garantuju da ni baza ne dozvoli dupli marker za isti
 * (priliv, klijent) odn. (fond, klijent, ciklus).
 */
@Entity
@Table(name = "fund_dividend_distribution_ledger",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_fddl_idempotency_key",
                        columnNames = {"idempotency_key"}),
                @UniqueConstraint(name = "uk_fddl_inflow_client",
                        columnNames = {"source_dividend_inflow_tx_id", "client_user_id"})
        },
        indexes = {
                @Index(name = "idx_fddl_fund_cycle", columnList = "fund_id, cycle_key")
        })
@Data
public class FundDividendDistributionLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fund_id", nullable = false)
    private Long fundId;

    @Column(name = "client_user_id", nullable = false)
    private Long clientUserId;

    /** Id ulaznog DIVIDEND_INFLOW reda — stabilna kotva (vec commit-ovan). */
    @Column(name = "source_dividend_inflow_tx_id", nullable = false)
    private Long sourceDividendInflowTxId;

    /** Deterministicki diskriminator distribucione runde (isti pri re-run-u). */
    @Column(name = "cycle_key", nullable = false, length = 128)
    private String cycleKey;

    /** Stabilan idempotency kljuc poslat banka-core transferu. */
    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "amount_rsd", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountRsd;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
