package rs.raf.trading.investmentfund.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "client_fund_transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientFundTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fund_id", nullable = false)
    private Long fundId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_role", nullable = false, length = 16)
    private String userRole;

    @Column(name = "amount_rsd", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountRsd;

    @Column(name = "source_account_id", nullable = false)
    private Long sourceAccountId;

    @Column(name = "is_inflow", nullable = false)
    @org.hibernate.annotations.ColumnDefault("1")
    private boolean inflow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ClientFundTransactionStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * Primarno: poruka o gresci za FAILED transakcije.
     * <p><b>Tech-debt (R1 500 — DOCUMENT-ACCEPTED):</b> kod dividend flow-a (FundDividendService)
     * ovo polje je preopterećeno kao metadata-nosilac na USPESNIM transakcijama
     * ({@code "DIVIDEND_INFLOW listingId=…"}, {@code "DIVIDEND_REINVESTED orderId=…, listingId=…"},
     * {@code "DIVIDEND_DISTRIBUTED totalAmount=…"}) i {@code extractListingId} ga string-parsuje
     * nazad. Cisto resenje su zasebne strukturisane kolone (npr. {@code listing_id},
     * {@code source_order_id}), ali to je schema-izmena (Flyway + data-migracija postojecih
     * redova) — van mehanickog cleanup scope-a. NE menjati marker format bez migracije:
     * {@code extractListingId} zavisi od {@code "listingId="} prefiksa u postojecim redovima.
     */
    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    /**
     * P1-funds-1 (1343): cost-basis udeo ({@code totalInvested}) koji se povlaci
     * pri OVOJ withdrawal transakciji. Pozicija se smanjuje TEK kad isplata
     * stvarno zavrsi (immediate payout ili FIFO onFillCompleted), ne pri
     * kreiranju PENDING reda — tako klijent ne gubi udeo ako likvidacija nikad
     * ne uspe (stuck/ALARM). Null za invest/dividend redove (inflow).
     */
    @Column(name = "invested_delta", precision = 19, scale = 4)
    private BigDecimal investedDelta;

    /**
     * R3 1629: optimistic-lock verzija. Status tranzicije
     * (PENDING→COMPLETED/FAILED, DIVIDEND_INFLOW→REINVESTED/DISTRIBUTED) se
     * desavaju iz schedulera i sinhronih flow-ova; novcana strana je vec stitena
     * stabilnim banka-core idempotency kljucevima i FundDividendDistributionLedger
     * write-ahead marker-ima (P1-2), ali {@code @Version} dodaje detekciju
     * konkurentnog double-write-a (lost-update na status/failureReason) i sluzi
     * kao audit signal. {@code @ColumnDefault("0")} inicijalizuje postojece redove
     * na 0 (izbegava null-version drift na PG).
     */
    @Version
    @Column(name = "version", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private Long version;
}