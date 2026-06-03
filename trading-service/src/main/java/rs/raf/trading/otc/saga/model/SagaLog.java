package rs.raf.trading.otc.saga.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "saga_logs", indexes = {
    @Index(name = "idx_saga_logs_saga_id", columnList = "saga_id", unique = true),
    @Index(name = "idx_saga_logs_status", columnList = "status")
})
@Data
public class SagaLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_id", nullable = false, unique = true, length = 64)
    private String sagaId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SagaStatus status;

    /** Ordinal (1..5) poslednje pokusane forward faze. */
    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Convert(converter = SagaLogEntryListConverter.class)
    @Column(name = "log_entries", columnDefinition = "text")
    private List<SagaLogEntry> entries = new ArrayList<>();

    /** F1 rezervacioni handle ako je SAGA sama rezervisala (ensure-reserve). */
    @Column(name = "banka_core_reservation_id")
    private String bankaCoreReservationId;

    /**
     * <b>N3 (P0-T2):</b> EFEKTIVNI buyer-ov racun sa koga je F1 rezervisao/REUSE-ovao
     * sredstva — perzistovan write-ahead U F1 (pre/atomicno sa out-of-process rezervacijom).
     *
     * <p>Bez ovoga, real-crash izmedju F1 banka-core rezervacije i outer-tx commit-a bi izgubio
     * {@code contract.buyerReservedAccountId} (postavlja se tek u outer tx-u, reserve-at-exercise
     * grana), pa bi {@link rs.raf.trading.otc.saga.service.OtcExerciseSagaOrchestrator}{@code
     * .rebuildContext} pao na DEFAULT racun → C3 refund na pogresan racun kod multi-account kupca.
     * recovery sad cita ovaj write-ahead datum (vezan za out-of-process rezervaciju).
     */
    @Column(name = "buyer_account_id")
    private Long buyerAccountId;

    /** Snapshot kupcevog portfolija PRE F4 — za tacan C4 restore (avg buy price je lossy). */
    @Column(name = "pre_f4_buyer_existed") private Boolean preF4BuyerExisted;
    @Column(name = "pre_f4_buyer_quantity") private Integer preF4BuyerQuantity;
    @Column(name = "pre_f4_buyer_avg_price", precision = 18, scale = 4) private BigDecimal preF4BuyerAvgPrice;

    /**
     * <b>N1 (P0-T2):</b> snapshot prodavceve {@code quantity} PRE F4 dekrementa — perzistovan
     * write-ahead PRE samog dekrementa, radi audit/log konteksta. <b>VISE NIJE</b> izvor istine za
     * recovery C4 restore-odluku: qty-snapshot heuristika ({@code currentQty >= snapshot → no-op})
     * je bila lossy — lazno restore kad prodavac proda hartije izmedju crash-a i recovery-ja, i
     * bezuslovni restore za legacy null-snapshot sage. Zamenjena je OUTER-TX markerom
     * {@link #f4Committed} (deli sudbinu sa F4 lokalnim efektom), vidi
     * {@code OtcExerciseSagaOrchestrator.c4ReturnShares}.
     */
    @Column(name = "pre_f4_seller_quantity") private Integer preF4SellerQuantity;

    /**
     * <b>BUG-W2-01:</b> da li je F1 OVE saga-e zaista kreirao novu buyer-ovu
     * rezervaciju (reserve-at-exercise) — perzistovano da crash-recovery zna sme li
     * C1 da oslobodi rezervaciju ili je u pitanju accept-time hold koji se cuva.
     */
    @Column(name = "buyer_reservation_created_here") private Boolean buyerReservationCreatedHere;
    /** <b>BUG-W2-01:</b> da li je F2 OVE saga-e zaista rezervisala akcije prodavca. */
    @Column(name = "seller_shares_reserved_here") private Boolean sellerSharesReservedHere;
    /** <b>BUG-W2-01:</b> koliko je akcija F2 OVE saga-e rezervisala — C2 oslobadja tacno toliko. */
    @Column(name = "seller_shares_reserved_amount") private Integer sellerSharesReservedAmount;

    /**
     * <b>P0-1 (Bug A):</b> da li je F3 {@code commitFunds} (debit kupca + zatvaranje
     * rezervacije) uspeo. F3 nije atoman (commit pa credit = 2 poziva), pa kad
     * credit padne POSLE commit-a, C3 mora znati da je commit obavljen da bi vratio
     * novac kupcu. Perzistovano radi crash-recovery (rebuildContext cita ovaj flag).
     */
    @Column(name = "f3_commit_done") private Boolean f3CommitDone;
    /**
     * <b>N4 (P0-T2):</b> write-ahead NAMERA da F3 {@code creditFunds} (isplata prodavcu)
     * upravo krece — perzistuje se PRE poziva. Real-crash izmedju {@code creditFunds}
     * (priliv prodavcu je out-of-process i durable) i {@code persist(f3CreditDone)} bi
     * ostavio {@code f3CommitDone=true, f3CreditDone=false} → naivni C3 commit-only refund
     * SAMO kupca, a prodavceva isplata ostaje → STVARANJE novca (I1 prekrsen). Sa intent
     * flag-om recovery zna da je credit MOZDA izvrsen ({@code intent && !done}) pa radi pun
     * reverzni transfer (idempotentni banka-core kljucevi cine ga bezbednim cak i ako
     * credit nikad nije stigao do prodavca — debit prodavca je tada protiv njegovog salda,
     * ali je iznos isti koji bi mu credit dao; konzervacija drzi).
     */
    @Column(name = "f3_credit_intent") private Boolean f3CreditIntent;
    /**
     * <b>P0-1 (Bug A):</b> da li je F3 {@code creditFunds} (isplata prodavcu) uspeo.
     * Ako je true → obe noge F3 obavljene (C3 radi pun reverzni transfer); ako je
     * commit=true a credit=false → prodavac nikad kreditiran (C3 kreditira SAMO kupca).
     */
    @Column(name = "f3_credit_done") private Boolean f3CreditDone;

    /**
     * <b>P0-1 (Bug B):</b> da li je F4 dekrement seller pozicije (save/delete)
     * komitovan PRE buyer kreditiranja. F4 nije atoman (seller-leg pa buyer-leg), pa
     * kad buyer-leg padne, C4 mora znati da je seller umanjen da bi mu vratio akcije
     * — i da NE dira buyer ako njegov blok nije ni izvrsen. Perzistovano radi recovery.
     */
    @Column(name = "f4_seller_applied") private Boolean f4SellerApplied;

    /**
     * <b>N1 (P0-T2):</b> OUTER-TX-COMMITTED marker za F4 — postavlja se na MANAGED SagaLog
     * entitet UNUTAR outer (exercise) transakcije i flush-uje se prirodno tek na njenom commit-u.
     * Time deli sudbinu sa F4 lokalnim JPA efektom (seller dekrement + buyer credit): real-crash
     * pre outer commit-a ponisti I efekat I marker (oba ostaju false/null), pa recovery C4 ne radi
     * slepi {@code += qty} (phantom akcije) niti pogadja po lossy qty-snapshot heuristici (koja je
     * pucala ako prodavac proda hartije izmedju crash-a i recovery-ja, i radila bezuslovni restore
     * za legacy null-snapshot sage). recovery C4 restore-uje SAMO ako je marker {@code true}.
     * Nullable bez DEFAULT (PG-DDL): stare sage → null → tretira se kao "nije committed" (bezbedno).
     */
    @Column(name = "f4_committed") private Boolean f4Committed;

    /**
     * <b>N1 (P0-T2):</b> OUTER-TX-COMMITTED marker za F5 (status ACTIVE→EXERCISED) — postavljen na
     * managed entitet u outer tx-u, flush na commit-u. recovery C5 restore-uje status SAMO ako je
     * {@code true} (deli sudbinu sa F5 lokalnim efektom). Nullable bez DEFAULT (PG-DDL); null=legacy
     * → ne restore (bezbedno). Simetricno {@link #f4Committed}.
     */
    @Column(name = "f5_committed") private Boolean f5Committed;

    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    @Version private Long version;

    @PrePersist void onCreate() { LocalDateTime n = LocalDateTime.now(); createdAt = n; updatedAt = n; }
    @PreUpdate  void onUpdate() { updatedAt = LocalDateTime.now(); }

    public void append(SagaLogEntry e) { entries.add(e); }
}
