package rs.raf.banka2_bek.interbank.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import rs.raf.banka2.contracts.internal.InternalListingDto;
import rs.raf.banka2.contracts.internal.InternalPortfolioHoldingDto;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.interbank.model.InterbankMessage;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContract;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContractStatus;
import rs.raf.banka2_bek.interbank.model.InterbankOtcNegotiation;
import rs.raf.banka2_bek.interbank.model.InterbankPartyType;
import rs.raf.banka2_bek.interbank.model.InterbankTransaction;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;
import rs.raf.banka2_bek.interbank.protocol.*;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcContractRepository;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcNegotiationRepository;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionExecutorService {

    private final InterbankMessageService messageService;
    private final InterbankClient client;
    private final BankRoutingService routing;
    private final InterbankTransactionRepository txRepo;
    private final ObjectMapper objectMapper;
    private final AccountRepository accountRepository;
    private final InterbankReservationApplier reservationApplier;
    private final TradingServiceInternalClient tradingServiceClient;
    private final InterbankOtcNegotiationRepository otcNegotiationRepository;
    private final InterbankOtcContractRepository otcContractRepository;
    private final InterbankFxService interbankFxService;
    private final InterbankInboundPaymentRecorder inboundPaymentRecorder;

    /**
     * §Celina 5 §40-66: registarski broj nase banke — koristi se za pronalazenje
     * bankinog pool/commission racuna pri cross-currency inbound settlement-u.
     */
    @org.springframework.beans.factory.annotation.Value("${bank.registration-number}")
    private String bankRegistrationNumber;

    /**
     * §2.8.5: self-proxy so that @Transactional on phase methods is respected when called
     * from execute() (Spring AOP does not intercept self-invocation through `this`).
     */
    @Lazy
    @Autowired
    TransactionExecutorService self;

    // -------------------------------------------------------------------------
    // Nested record for phase-1 result
    // -------------------------------------------------------------------------

    record Phase1Result(
            TransactionVote vote,
            Map<Integer, IdempotenceKey> keys,
            Map<Integer, Message<Transaction>> envelopes
    ) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public Transaction formTransaction(
            List<Posting> postings, String message,
            String callNumber, String paymentCode, String paymentPurpose) {

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) sb.append(String.format("%02x", b));

        ForeignBankId txId = new ForeignBankId(routing.myRoutingNumber(), sb.toString());
        return new Transaction(postings, txId, message, callNumber, paymentCode, paymentPurpose);
    }

    /**
     * §2.8.5 Coordinator — orchestrates the two-phase commit across all involved banks.
     * Not @Transactional itself: each phase runs in its own local transaction so DB locks
     * are released before network I/O begins.
     */
    public void execute(Transaction tx) {
        Set<Integer> remoteRns = collectRemoteRoutingNumbers(tx);

        if (remoteRns.isEmpty()) {
            // §2.8.4 last paragraph: fully local — two sequential local transactions.
            // Coordinator record must be persisted first so commitLocal/rollbackLocal
            // can find the transactionBody.
            saveCoordinatorState(tx, InterbankTransactionStatus.PREPARING);
            TransactionVote vote = self.prepareLocal(tx);
            if (vote.vote() == TransactionVote.Vote.YES) {
                self.commitLocal(tx.transactionId());
            } else {
                // 2PC ATOMICITY: local NO is an ABORT. Roll back locally FIRST (sets
                // InterbankTransaction → ROLLED_BACK), THEN signal the caller by throwing
                // so its compensation runs (see InterbankTransactionAbortedException).
                self.rollbackLocal(tx.transactionId());
                throw abort(tx, "local prepare vote=NO", vote);
            }
            return;
        }

        // §2.8.5: promote to coordinator — prepare + log outbound atomically
        Phase1Result phase1 = self.prepareTxPhase(tx, remoteRns);
        if (phase1.vote().vote() == TransactionVote.Vote.NO) {
            // 2PC ATOMICITY: local-prepare NO is an ABORT. prepareTxPhase already set the
            // status to ROLLED_BACK (two-pass validate-and-reserve found violations in
            // pass 1, so no local reservation was made). Signal the caller by throwing.
            throw abort(tx, "coordinator local-prepare vote=NO", phase1.vote());
        }

        // Network I/O outside any @Transactional
        Map<Integer, TransactionVote> votes = sendPhase1Network(phase1);
        boolean allYes = votes.values().stream().allMatch(v -> v.vote() == TransactionVote.Vote.YES);

        if (allYes) {
            Map<Integer, IdempotenceKey> commitKeys = self.commitTxPhase(tx.transactionId(), remoteRns);
            sendPhase2Network(commitKeys, MessageType.COMMIT_TX, new CommitTransaction(tx.transactionId()));
        } else {
            // 2PC ATOMICITY: a partner NO (or treated-as-NO network/auth failure) is an
            // ABORT. Roll back locally (→ ROLLED_BACK, releases reservations) and dispatch
            // ROLLBACK_TX to partners FIRST; sendPhase2Network is fire-and-forget so a
            // phase-2 send error never propagates here. THEN signal the caller by throwing.
            Map<Integer, IdempotenceKey> rollbackKeys = self.rollbackTxPhase(tx.transactionId(), remoteRns);
            sendPhase2Network(rollbackKeys, MessageType.ROLLBACK_TX, new RollbackTransaction(tx.transactionId()));
            // exercise-400 fix: prosledi PRVU partner NO vote (sa reasons) umesto null. Bez
            // ovoga konkretan protokol-razlog (npr. OPTION_USED_OR_EXPIRED) se gubi i poruka
            // ostaje obmanjujuca "(or network/auth failure treated as NO)" iako je partner dao
            // eksplicitan, dobro-formiran protokol NO (http 200). Kad je vote prisutan, abort()
            // dodaje " reasons=[...]" pa razlog stigne do korisnika kroz wrapper handler (409).
            // Ako je NO bio treated-as-NO (network/auth → prazna reasons lista), vote ostaje
            // null i poruka pada nazad na goli "partner vote=NO".
            TransactionVote firstNo = votes.values().stream()
                    .filter(v -> v.vote() == TransactionVote.Vote.NO)
                    .filter(v -> v.reasons() != null && !v.reasons().isEmpty())
                    .findFirst().orElse(null);
            throw abort(tx, "partner vote=NO", firstNo);
        }
    }

    /**
     * Builds the abort exception thrown on every {@code execute()} abort branch. Kept as
     * a helper so the message is consistent and includes the transactionId (which is what
     * callers log / surface). Caller-side compensation is keyed off the THROWN type
     * ({@link InterbankExceptions.InterbankTransactionAbortedException}), not the message.
     */
    private InterbankExceptions.InterbankTransactionAbortedException abort(
            Transaction tx, String reason, TransactionVote vote) {
        String voteDetail = (vote != null && vote.reasons() != null && !vote.reasons().isEmpty())
                ? " reasons=" + vote.reasons() : "";
        return new InterbankExceptions.InterbankTransactionAbortedException(
                "Inter-bank 2PC aborted for transaction " + tx.transactionId().id()
                        + " — " + reason + voteDetail);
    }

    /**
     * §2.8.5: atomically saves coordinator state, validates/reserves locally, and logs
     * outbound NEW_TX messages — all in one DB transaction.
     */
    @Transactional
    Phase1Result prepareTxPhase(Transaction tx, Set<Integer> remoteRns) {
        saveCoordinatorState(tx, InterbankTransactionStatus.PREPARING);

        // Coordinator-side preparation — trusted (this bank initiated the tx).
        List<NoVoteReason> violations = doValidateAndReserve(tx, false);
        if (!violations.isEmpty()) {
            updateTransactionStatus(tx.transactionId(), InterbankTransactionStatus.ROLLED_BACK,
                    "Local validation failed: " + violations);
            return new Phase1Result(
                    new TransactionVote(TransactionVote.Vote.NO, violations),
                    Map.of(), Map.of());
        }

        Map<Integer, IdempotenceKey> keys = new LinkedHashMap<>();
        Map<Integer, Message<Transaction>> envelopes = new LinkedHashMap<>();

        for (int rn : remoteRns) {
            IdempotenceKey key = messageService.generateKey();
            Message<Transaction> env = new Message<>(key, MessageType.NEW_TX, tx);
            try {
                messageService.recordOutbound(key, rn, MessageType.NEW_TX,
                        objectMapper.writeValueAsString(env), tx.transactionId().id());
            } catch (JsonProcessingException e) {
                throw new InterbankExceptions.InterbankProtocolException(
                        "Failed to serialize NEW_TX for routing " + rn + ": " + e.getMessage());
            }
            keys.put(rn, key);
            envelopes.put(rn, env);
        }

        return new Phase1Result(new TransactionVote(TransactionVote.Vote.YES, List.of()), keys, envelopes);
    }

    /**
     * §2.8.5: atomically commits locally and logs outbound COMMIT_TX messages.
     */
    @Transactional
    Map<Integer, IdempotenceKey> commitTxPhase(ForeignBankId txId, Set<Integer> remoteRns) {
        commitLocal(txId); // direct call — joins this @Transactional

        Map<Integer, IdempotenceKey> keys = new LinkedHashMap<>();
        CommitTransaction body = new CommitTransaction(txId);
        for (int rn : remoteRns) {
            IdempotenceKey key = messageService.generateKey();
            Message<CommitTransaction> env = new Message<>(key, MessageType.COMMIT_TX, body);
            try {
                messageService.recordOutbound(key, rn, MessageType.COMMIT_TX,
                        objectMapper.writeValueAsString(env), txId.id());
            } catch (JsonProcessingException e) {
                throw new InterbankExceptions.InterbankProtocolException(
                        "Failed to serialize COMMIT_TX for routing " + rn + ": " + e.getMessage());
            }
            keys.put(rn, key);
        }
        return keys;
    }

    /**
     * §2.8.8: atomically rolls back locally and logs outbound ROLLBACK_TX messages.
     */
    @Transactional
    Map<Integer, IdempotenceKey> rollbackTxPhase(ForeignBankId txId, Set<Integer> remoteRns) {
        rollbackLocal(txId); // direct call — joins this @Transactional

        Map<Integer, IdempotenceKey> keys = new LinkedHashMap<>();
        RollbackTransaction body = new RollbackTransaction(txId);
        for (int rn : remoteRns) {
            IdempotenceKey key = messageService.generateKey();
            Message<RollbackTransaction> env = new Message<>(key, MessageType.ROLLBACK_TX, body);
            try {
                messageService.recordOutbound(key, rn, MessageType.ROLLBACK_TX,
                        objectMapper.writeValueAsString(env), txId.id());
            } catch (JsonProcessingException e) {
                throw new InterbankExceptions.InterbankProtocolException(
                        "Failed to serialize ROLLBACK_TX for routing " + rn + ": " + e.getMessage());
            }
            keys.put(rn, key);
        }
        return keys;
    }

    @Transactional
    public TransactionVote prepareLocal(Transaction tx) {
        // Coordinator-side (local) preparation — trusted, charging our own sender
        // is the whole point. Inbound authz gate does NOT apply here.
        List<NoVoteReason> violations = doValidateAndReserve(tx, false);
        if (violations.isEmpty())
            return new TransactionVote(TransactionVote.Vote.YES, List.of());
        return new TransactionVote(TransactionVote.Vote.NO, violations);
    }

    @Transactional
    public void commitLocal(ForeignBankId transactionId) {
        // 1536 — duplicate-delivery COMMIT_TX double Monas commit fix.
        // §2.9 dozvoljava duplikat dostavu COMMIT_TX; dve paralelne poruke bi obe
        // procitale PREPARED stanje (COMMITTED-guard se evaluira pre bilo kakve
        // serijalizacije) i obe usle u petlju primene postinga. Stock leg je
        // zasticen determinisitckim idempotency kljucem (trading-service dedup),
        // ali Monas leg (commitMonas/commitRecipientCredit) NEMA idempotency kljuc —
        // pa bi se dvostruko primenio (dupli debit/credit, novac se kreira/unistava).
        // PESSIMISTIC_WRITE lock serijalizuje konkurentne pozive: prvi commit-uje i
        // postavlja COMMITTED, drugi (cekao na lock) procita COMMITTED pod lock-om i
        // izadje kao no-op. COMMITTED/ROLLED_BACK guard MORA biti POSLE locka.
        //
        // P2-money-tx-1 (R3 1578) LOCK-BUDGET (svesna odluka, NE skidamo lock):
        // ovaj red-lock se DRZI dok traje out-of-process commit petlja (sinhroni REST
        // ka trading-service-u kroz reservationApplier.commitStock + Monas commits).
        // Lock se NE sme skinuti i re-uzeti oko poziva — duplicate-delivery COMMIT_TX
        // (§2.9) bi mogao da prodje izmedju release i re-lock i dvostruko primeni
        // Monas leg (koji NEMA idempotency kljuc) → kreiranje/unistavanje novca (vidi
        // 1536). Lock-hold je OGRANICEN: TradingServiceClient ima connect=5s/read=30s
        // timeout (TradingServiceClientConfig), pa jedan zaglavljeni HTTP poziv ne moze
        // da drzi lock neograniceno (ima konacan lock-budget umesto contention lavine).
        InterbankTransaction ibTx = txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(
                        transactionId.routingNumber(), transactionId.id())
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "No record for transaction " + transactionId));

        if (ibTx.getStatus() == InterbankTransactionStatus.COMMITTED) return;
        if (ibTx.getStatus() == InterbankTransactionStatus.ROLLED_BACK)
            throw new InterbankExceptions.InterbankProtocolException(
                    "Cannot commit rolled-back transaction " + transactionId);

        Transaction tx;
        try {
            tx = objectMapper.readValue(ibTx.getTransactionBody(), Transaction.class);
        } catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Failed to parse transaction body: " + e.getMessage());
        }

        List<Posting> postings = tx.postings();
        // C-1 compounding bug fix po Celini 5 audit-u:
        //
        // Ranija heuristika je obelezavala ugovor kao EXERCISED kad god je commit
        // sadrzao (OptionAsset, Option) posting — sto je gadjalo i accept (koji
        // je krsio §3.6 i imao OptionAsset+Option posting). Posle C-1 fix-a accept
        // tx ima ISKLJUCIVO PERSON↔PERSON OptionAsset postings, pa ovaj uslov
        // tamo nikad ne pali (sto je tacno).
        //
        // Spec §2.7.2 exercise tx ima Stock-asset posting NA Option pseudo-acc-u
        // ("Credit option pseudo-account for k stocks" + "Debit relevant
        // receiving accounts for k assets"). Detekcija EXERCISED se sad veze za
        // taj signature: (Stock, Option) — jer to NIGDE drugde u protokolu se
        // ne pojavljuje (§3.6 accept ima OptionAsset, ne Stock, na Option-u).
        boolean isExercise = postings.stream().anyMatch(
                pp -> pp.asset() instanceof Asset.Stock && pp.account() instanceof TxAccount.Option);

        // BE-INT-06 fix po Celini 5 audit-u: commitLocal racunski cikl je out-of-process
        // (HTTP poziv ka trading-service-u). Ako commitStock uspe za jedan posting a
        // sledeci pukne, prvi je vec ulazio u trading_db sa side-effectom (smanjenje
        // portfolio quantity ili kreiranje novog portfolio reda). @Transactional rollback
        // nije pravi rollback (trading-service ima sopstvenu Tx granicu). Pratimo svaki
        // uspeh i reverzno-kompenzujemo immediate na mid-failure. Mirror Pass-2 obrasca.
        //
        // Monas commits se ne kompenzuju ovde — oni rade unutar nase @Transactional pa ce
        // Spring rollback-ovati DB state ako exception bubbles up iz ove metode. Track-uju
        // se samo Stock commits jer su out-of-process.
        // INCOMING payment-history zapis (best-effort, vidi recordInboundReceipts ispod).
        // Belezimo SAMO kad smo RECIPIENT (druga banka je inicirala NEW_TX, mi primamo
        // novac) — NE za nase OUTBOUND placanje (gde smo INITIATOR i Payment red vec
        // postoji iz createInterbankPayment). Sakupljamo ovde, upisujemo POSLE COMMITTED
        // flip-a (van money-leg petlje), u zasebnoj REQUIRES_NEW tx.
        boolean weAreRecipient =
                ibTx.getRole() == InterbankTransaction.InterbankTransactionRole.RECIPIENT;
        List<InboundReceipt> inboundReceipts = new ArrayList<>();

        // FINDING 2 (FIX B — buyer-credit simetrija): per-CONTRACT gate za exercise-shape tx.
        // Seller-settle grana (ispod petlje) je vec per-contract gated (lock + ACTIVE/EXERCISING
        // + atomican flip). ALI buyer-credit leg (Stock+Person, kad smo MI BUYER) se primenjuje
        // UNUTAR petlje i bio je gated SAMO per-txId (stockIdempotencyKey) — retry/restart sa
        // NOVIM txId za vec-EXERCISED ugovor bi dvostruko kreditirao kupcu hartije (asset
        // kreiran). Ovde, pod ISTIM per-contract PESSIMISTIC_WRITE lock-om kao seller-settle,
        // proveravamo da li je exercise jos settle-abilan; ako je ugovor vec EXERCISED,
        // exerciseSettlementGateOpen=false → buyer Stock commit se preskace (no-op). Kad nema
        // lokalnog ugovora za negId (npr. seller-host gde je ugovor nadjen u seller bloku, ili
        // strana hartija), gate ostaje OTVOREN (true) — postojece ponasanje netaknuto.
        boolean exerciseSettlementGateOpen = isExercise
                && isExerciseSettlementClaimable(postings);

        List<StockCommitRecord> stockCommits = new ArrayList<>();
        try {
            for (int i = 0; i < postings.size(); i++) {
                Posting p = postings.get(i);
                if (isPostingRemote(p)) continue;
                boolean isDebit = p.amount().compareTo(BigDecimal.ZERO) > 0;
                BigDecimal abs = p.amount().abs();

                if (p.asset() instanceof Asset.Monas m && p.account() instanceof TxAccount.Account a) {
                    if (isDebit) {
                        // Recipient (Banka B) credit — FX-aware: konvertuje wire valutu
                        // (m.asset().currency()) u valutu primaocevog racuna i naplacuje
                        // Banka-B proviziju (§Celina 5 §40-66). Same-currency je no-op
                        // (rate=1, fee=0) — regression-safe.
                        // N5: prosledjujemo pinned mid-rate iz vote-a (ibTx.pinnedFxRate)
                        // da commit ne re-racuna po live rate-u (FX drift eliminisan).
                        reservationApplier.commitRecipientCredit(
                                a.num(), abs, m.asset().currency().name(), ibTx.getPinnedFxRate());
                        // Plain inter-bank uplata na nas racun (pozitivan MONAS kredit na
                        // lokalnom Account-u) → kandidat za INCOMING istorijski zapis.
                        if (weAreRecipient) {
                            inboundReceipts.add(new InboundReceipt(
                                    a.num(), abs, m.asset().currency().name(),
                                    inboundSenderLabel(tx)));
                        }
                    } else {
                        // Sender (Banka A) debit — troši rezervaciju u izvornoj valuti,
                        // bez konverzije (R1-681: commitMonas radi samo sender-debit).
                        reservationApplier.commitMonas(a.num(), abs);
                    }

                } else if (p.asset() instanceof Asset.Stock s && p.account() instanceof TxAccount.Person pe) {
                    // FIX B (buyer-credit simetrija): kad je ovo buyer-stock leg exercise-shape
                    // tx-a a ugovor je VEC EXERCISED (gate zatvoren), preskoci — inace bi retry
                    // sa novim txId dvostruko kreditirao kupcu hartije (asset kreiran). Za
                    // ne-exercise tx-ove (plain stock transfer) gate je uvek otvoren (isExercise
                    // je false → exerciseSettlementGateOpen=false samo kad je ugovor EXERCISED;
                    // za ne-exercise je i isExercise=false pa gate ostaje "true" preko ranog
                    // izlaza ispod). NB: čuvamo ne-exercise tokove netaknutim.
                    if (isExercise && !exerciseSettlementGateOpen) {
                        // Vec settle-ovan po ovom ugovoru — buyer stock kredit je no-op.
                        continue;
                    }
                    String ticker = s.asset().ticker();
                    // commitStock razresava listing po ticker-u u trading-service-u i
                    // mapira odsustvo u "Listing not found: <ticker>" (→ InterbankProtocolException),
                    // pa zaseban findListingByTicker pre-check ne treba — bio je redundantan
                    // HTTP round-trip unutar @Transactional.
                    // BUG-2: strip "C-"/"E-" prefix pre parse (vidi parsePersonOwnerId).
                    Long userId = parsePersonOwnerId(pe.id().id());
                    int quantity = abs.intValueExact();
                    reservationApplier.commitStock(
                            stockIdempotencyKey(transactionId, "commit", userId, "CLIENT", ticker, i),
                            userId, "CLIENT", ticker, quantity, isDebit);
                    stockCommits.add(new StockCommitRecord(userId, "CLIENT", ticker, quantity, isDebit, i));

                } else if (p.asset() instanceof Asset.Monas && p.account() instanceof TxAccount.Person
                        && isInboundExerciseSellerCashCredit(p, tx)) {
                    // BUG-1: inbound exercise seller-cash credit leg — commit no-op.
                    // Prodavac je kreditiran kroz settleSellerOnInboundExercise (OPTION→
                    // prodavac sweep, izvrsen u exercise-shape grani ispod). commitMonas
                    // OVDE bi DEBITOVAO prodavca (pogresan smer) i dvostruko knjizio —
                    // zato exactly-once: ovaj leg se ne konzumira (nema ni rezervacije).

                } else if (p.asset() instanceof Asset.Monas m && p.account() instanceof TxAccount.Person pe) {
                    // T1/T2 — buyer-premium Monas+Person commit grana. CREDIT (kupac se
                    // tereti, amount<0) je §3.6 accept premium: prepare faza ga je
                    // rezervisala na stabilnom settlement racunu (reserveMonas), pa ga
                    // ovde konzumiramo (commitMonas, sender-debit) na ISTOM racunu. Bez
                    // ove grane rezervacija je ostajala zauvek (novac zaglavljen,
                    // narusena konzervacija). Resolucija mora biti identicna prepare-u.
                    if (!isDebit) {
                        // BUG-2: role-aware resolucija (E- zaposleni NE razresava se na klijentski racun).
                        PersonOwner owner = resolvePersonOwner(pe.id().id());
                        String postingCcy = m.asset().currency().name();
                        String chosenAccount = resolveBuyerSettlementAccount(tx);
                        String commitOn = (chosenAccount != null)
                                ? chosenAccount
                                : resolveLocalAccountDeterministic(owner.role(), owner.id(), postingCcy)
                                        .map(Account::getAccountNumber)
                                        .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                                                "Person+Monas commit: missing account for ownerId=" + pe.id().id()
                                                        + " currency=" + postingCcy));
                        reservationApplier.commitMonas(commitOn, abs);
                    }
                    // DEBIT Monas+Person (kupac prima novac na exercise) — kasniji unit;
                    // ovde no-op (taj tok jos nije wired).

                }
                // OptionAsset+Person posting (accept-shape) i Monas/Stock+Option posting
                // (exercise-shape) ne barataju nikakvim portfolio/account state-om u nasoj
                // banci ako smo seller (option pseudo-account je apstraktan); contract
                // status flip se desava ispod, posle petlje.
            }
        } catch (RuntimeException commitFail) {
            // Kompenzacija: za svaki uspesan stock commit, izvrsi reverzni commit
            // (isDebit ↔ !isDebit) sa razlicitim idempotency kljucem ("compensate" fazom)
            // da trading-service ne kesira kao replay. commitStock je idempotentan u
            // okviru istog kljuca, a "compensate" kljuc je determinisitcki po txId+
            // posting-u → retry-safe.
            for (StockCommitRecord c : stockCommits) {
                try {
                    reservationApplier.commitStock(
                            stockIdempotencyKey(transactionId, "compensate", c.userId(), c.role(), c.ticker(), c.postingIndex()),
                            c.userId(), c.role(), c.ticker(), c.quantity(), !c.isDebit());
                } catch (RuntimeException compEx) {
                    log.error("Compensation commitStock failed for tx={} posting={}: {}",
                            transactionId, c.postingIndex(), compEx.getMessage(), compEx);
                    // Best-effort — nastavi sa ostalim kompenzacijama. Idempotency-driven
                    // retry scheduler ili manualni intervent saniraju ostatak.
                }
            }
            throw commitFail;
        }

        // Posle iteracije: ako je exercise-shape tx, oznaci kontaminirani contract
        // kao EXERCISED. Trazimo prvu (Stock, Option) posting i razresimo negotiationId
        // iz nje (TxAccount.Option.id() je negotiation id po §2.7.2).
        if (isExercise) {
            // Trazimo prvi (Stock, Option) leg → negotiationId → contract. Resolucija
            // je ista za BUYER i SELLER stranu (oba Option leg-a nose isti kompozitni
            // negotiationId po §2.7.2, sa routing-om prodavceve banke). Razlika je u
            // contract.localPartyType: ako smo SELLER, dodatno settlujemo (T5(A)/S9).
            postings.stream()
                    .filter(pp -> pp.account() instanceof TxAccount.Option)
                    .filter(pp -> pp.asset() instanceof Asset.Stock)
                    .findFirst()
                    .ifPresent(pp -> {
                        TxAccount.Option opt = (TxAccount.Option) pp.account();
                        ForeignBankId negId = opt.id();
                        otcNegotiationRepository
                                .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                                        negId.routingNumber(), negId.id())
                                // FINDING 2 — PESSIMISTIC_WRITE lock na ugovoru: serijalizuje
                                // konkurentne razlicite-txId exercise-e ISTOG ugovora. Per-tx
                                // red-lock ne pomaze (razliciti txId-ovi), a non-locking
                                // findBySourceNegotiationId bi pustio oba da settle-uju (dvostruka
                                // isporuka hartija + dvostruki kredit). Drugi commit ceka na ovom
                                // lock-u, pa pod lock-om vidi EXERCISED i preskace settle.
                                .ifPresent(neg -> otcContractRepository
                                        .findBySourceNegotiationIdForUpdate(neg.getId())
                                        .ifPresent(contract -> {
                                            // Status-gate (FINDING 2): settle + flip se rade SAMO ako je
                                            // ugovor jos ACTIVE ili EXERCISING (claimed). Ako je vec
                                            // EXERCISED (prethodni/konkurentni commit ga je settle-ovao),
                                            // ovo je no-op — bez ponovne isporuke/kredita (idempotentno).
                                            boolean exercisable =
                                                    contract.getStatus() == InterbankOtcContractStatus.ACTIVE
                                                            || contract.getStatus() == InterbankOtcContractStatus.EXERCISING;
                                            if (!exercisable) {
                                                return;
                                            }
                                            // T5(A) — S9: prodavceva settlement (MI smo SELLER/host).
                                            // Kupceva banka exercise-uje: nasem prodavcu MORAMO
                                            // (1) osloboditi+predati k rezervisanih hartija
                                            //     (commitStock isDebit=false → quantity i
                                            //      reservedQuantity se umanjuju), i
                                            // (2) kreditirati strike (k*pi) na njegov racun u
                                            //     strike valuti (FX-aware commitRecipientCredit).
                                            // Pre fix-a ovo je bilo NO-OP: rezervacija hartija je
                                            // ostajala zaglavljena zauvek, prodavac nikad nije primio
                                            // novac → narusena konzervacija novca/asseta. Settlement
                                            // se izvrsava SAMO ako smo SELLER; status flip ide TEK
                                            // posle uspesnog settlement-a (transakciono).
                                            if (contract.getLocalPartyType() == InterbankPartyType.SELLER) {
                                                settleSellerOnInboundExercise(transactionId, contract);
                                            }
                                            // P1-interbank-otc-2 (1336): exercise claim flip-uje
                                            // ACTIVE→EXERCISING pre 2PC; uspesan COMMIT_TX finalizuje
                                            // i EXERCISING (claimed) i ACTIVE (legacy, inbound seller
                                            // strana koja ne prolazi kroz wrapper claim). Flip na ISTOJ
                                            // lock-ovanoj instanci, TEK posle uspesnog settle-a.
                                            contract.setStatus(InterbankOtcContractStatus.EXERCISED);
                                            contract.setExercisedAt(LocalDateTime.now());
                                            otcContractRepository.save(contract);
                                        }));
                    });
        }

        // S4 — buyer-side contract storage. Accept-shape tx (OptionAsset legovi, ne
        // exercise): kad smo MI BUYER, nas lokalni leg je Buyer-DEBIT optionContract
        // (+1 OptionAsset na nasem Person-u). Perzistujemo InterbankOtcContract
        // (localPartyType=BUYER) da kupac moze da ga vidi ("Sklopljeni ugovori") i
        // kasnije iskoristi. Ranije se ugovor NIKAD nije kreirao kad smo kupac
        // (commitLocal je OptionAsset+Person tretirao kao no-op), pa je lista bila
        // prazna i exercise je 404-ovao. Idempotentno: preskoci ako ugovor za ovaj
        // pregovor vec postoji (seller put ga je vec kreirao, ili commit replay).
        if (!isExercise) {
            postings.stream()
                    .filter(pp -> !isPostingRemote(pp))
                    .filter(pp -> pp.asset() instanceof Asset.OptionAsset
                            && pp.account() instanceof TxAccount.Person
                            && pp.amount().compareTo(BigDecimal.ZERO) > 0) // buyer DEBIT (+1)
                    .findFirst()
                    .ifPresent(pp -> {
                        ForeignBankId negId = ((Asset.OptionAsset) pp.asset()).asset().negotiationId();
                        Optional<InterbankOtcNegotiation> negOpt = otcNegotiationRepository
                                .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                                        negId.routingNumber(), negId.id());
                        // DIJAGNOSTIKA (FIX 3): kad nemamo lokalnu BUYER kopiju pregovora za ovaj
                        // negId, buyer-side InterbankOtcContract se NE kreira → kasniji exercise
                        // 404-uje. Najcesci uzrok je ID-namespace mismatch izmedju routing/id koji
                        // partner salje u OptionAsset.negotiationId i onoga sto smo persistovali pri
                        // createOffer-u. Samo LOG (bez menjanja money/contract logike); success put
                        // (.ifPresent ispod) ostaje netaknut.
                        if (negOpt.isEmpty()) {
                            log.warn("Buyer-side OTC contract NIJE kreiran: nema lokalne kopije pregovora "
                                    + "za negId={} (COMMIT_TX OptionAsset legovi). ID-namespace mismatch?", negId);
                        }
                        negOpt
                                .filter(neg -> neg.getLocalPartyType() == InterbankPartyType.BUYER)
                                .filter(neg -> otcContractRepository
                                        .findBySourceNegotiationId(neg.getId()).isEmpty())
                                .ifPresent(neg -> {
                                    InterbankOtcContract contract = new InterbankOtcContract();
                                    contract.setSourceNegotiationId(neg.getId());
                                    contract.setLocalPartyType(InterbankPartyType.BUYER);
                                    contract.setLocalPartyId(neg.getLocalPartyId());
                                    contract.setLocalPartyRole(neg.getLocalPartyRole());
                                    contract.setForeignPartyRoutingNumber(neg.getForeignPartyRoutingNumber());
                                    contract.setForeignPartyIdString(neg.getForeignPartyIdString());
                                    contract.setTicker(neg.getTicker());
                                    contract.setQuantity(neg.getAmount());
                                    contract.setStrikePrice(neg.getPricePerUnit());
                                    contract.setStrikeCurrency(neg.getPriceCurrency());
                                    contract.setPremium(neg.getPremium());
                                    contract.setPremiumCurrency(neg.getPremiumCurrency());
                                    contract.setSettlementDate(neg.getSettlementDate());
                                    contract.setStatus(InterbankOtcContractStatus.ACTIVE);
                                    contract.setCreatedAt(LocalDateTime.now());
                                    otcContractRepository.save(contract);
                                });
                    });
        }

        ibTx.setStatus(InterbankTransactionStatus.COMMITTED);
        ibTx.setCommittedAt(LocalDateTime.now());
        ibTx.setLastActivityAt(LocalDateTime.now());
        txRepo.save(ibTx);

        // POSLE money-leg-a: zabelezi INCOMING Payment(s) za primljene uplate (samo kad
        // smo RECIPIENT). Best-effort i AFTER-COMMIT: upis ide TEK kad se commitLocal
        // transakcija (kredit primaocu) durabilno commit-uje, pa nema orphan Payment reda
        // ako se outer tx rollback-uje. Sam upis je REQUIRES_NEW + idempotentan
        // (InterbankInboundPaymentRecorder); greska istorijskog upisa nikad ne dira novac.
        scheduleInboundReceiptRecording(transactionId, inboundReceipts);
    }

    /**
     * Best-effort upis INCOMING {@link rs.raf.banka2_bek.payment.model.Payment} redova za
     * uplate koje je nasa banka PRIMILA preko inter-bank-a (inbound COMMIT_TX koji je
     * kreditirao lokalni racun). Zakazuje se kroz {@link TransactionSynchronization#afterCommit()}
     * (mirror {@code PaymentServiceImpl.createInterbankPayment}) tako da Payment red nastaje
     * TEK po durabilnom commit-u kredita; ako nema aktivne tx sinhronizacije (npr. test bez
     * tx-a), upisuje odmah. Svaki red ide kroz {@link InterbankInboundPaymentRecorder} u
     * zasebnoj REQUIRES_NEW tx i idempotentan je po (tx-par, racun). Izuzeci se gutaju.
     */
    private void scheduleInboundReceiptRecording(ForeignBankId transactionId, List<InboundReceipt> receipts) {
        if (receipts.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    recordInboundReceipts(transactionId, receipts);
                }
            });
        } else {
            recordInboundReceipts(transactionId, receipts);
        }
    }

    private void recordInboundReceipts(ForeignBankId transactionId, List<InboundReceipt> receipts) {
        for (InboundReceipt r : receipts) {
            try {
                inboundPaymentRecorder.recordIncoming(
                        transactionId.routingNumber(), transactionId.id(),
                        r.accountNumber(), r.amount(), r.currencyCode(), r.senderLabel());
            } catch (RuntimeException e) {
                log.warn("Best-effort INCOMING payment record nije uspeo za tx {} -> {} ({}): {}",
                        transactionId, r.accountNumber(), r.amount(), e.getMessage());
            }
        }
    }

    /**
     * Best-effort, display-only naziv posiljaoca za INCOMING inter-bank uplatu: prvi
     * REMOTE (druga banka) MONAS sender-debit leg (negativan iznos na Account/Person u
     * drugoj banci). Vraca konkretan broj racuna / foreign-id ako je dostupan, inace
     * genericku oznaku. Nikad ne baca — koristi se samo kao {@code recipientName} labela.
     */
    private String inboundSenderLabel(Transaction tx) {
        for (Posting p : tx.postings()) {
            if (!(p.asset() instanceof Asset.Monas)) continue;
            if (p.amount().compareTo(BigDecimal.ZERO) >= 0) continue; // sender debit = negativan
            if (!isPostingRemote(p)) continue;
            if (p.account() instanceof TxAccount.Account a) return a.num();
            if (p.account() instanceof TxAccount.Person pe) return pe.id().id();
        }
        return "Inter-bank uplata";
    }

    /** Zabelezeni inbound kredit (primljena uplata) — kandidat za INCOMING Payment zapis. */
    private record InboundReceipt(String accountNumber, BigDecimal amount,
                                  String currencyCode, String senderLabel) {}

    @Transactional
    public void rollbackLocal(ForeignBankId transactionId) {
        // 1536 — symmetry sa commitLocal: lock pre statusne odluke da konkurentni
        // duplicate ROLLBACK_TX (i commit↔rollback trka) ne primene release dvaput.
        InterbankTransaction ibTx = txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(
                        transactionId.routingNumber(), transactionId.id())
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "No record for transaction " + transactionId));

        if (ibTx.getStatus() == InterbankTransactionStatus.ROLLED_BACK
                || ibTx.getStatus() == InterbankTransactionStatus.COMMITTED) return;

        Transaction tx;
        try {
            tx = objectMapper.readValue(ibTx.getTransactionBody(), Transaction.class);
        } catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Failed to parse transaction body: " + e.getMessage());
        }

        List<Posting> postings = tx.postings();
        for (int i = 0; i < postings.size(); i++) {
            Posting p = postings.get(i);
            if (isPostingRemote(p)) continue;
            if (p.amount().compareTo(BigDecimal.ZERO) >= 0) continue; // only credits were reserved

            BigDecimal abs = p.amount().abs();

            if (p.asset() instanceof Asset.Monas && p.account() instanceof TxAccount.Account a) {
                reservationApplier.releaseMonas(a.num(), abs);

            } else if (p.asset() instanceof Asset.Monas && p.account() instanceof TxAccount.Person
                    && isInboundExerciseSellerCashCredit(p, tx)) {
                // BUG-1: inbound exercise seller-cash credit leg — rollback no-op.
                // Ovaj leg nikad nije rezervisan (recognized no-op u Pass-2), pa
                // releaseMonas OVDE bi oslobodio nepostojecu rezervaciju (knjizni rascep).

            } else if (p.asset() instanceof Asset.Monas m && p.account() instanceof TxAccount.Person pe) {
                // T1/T2 — buyer-premium Monas+Person rollback grana (simetrija sa prepare
                // reserveMonas / commitLocal commitMonas). CREDIT leg (kupac se tereti,
                // amount<0) je §3.6 accept premium: prepare faza ga je rezervisala na
                // stabilnom settlement racunu; ovde MORAMO osloboditi BAS tu rezervaciju
                // (releaseMonas) na ISTOM racunu. Bez ove grane, abortiran/rollback-ovan
                // accept (seller NO / 2PC abort / presumed-abort) ostavlja premiju zauvek
                // zaglavljenu u rezervaciji. Resolucija mora biti identicna prepare/commit-u.
                // BUG-2: role-aware resolucija (E- zaposleni NE razresava se na klijentski racun).
                PersonOwner owner = resolvePersonOwner(pe.id().id());
                String postingCcy = m.asset().currency().name();
                String chosenAccount = resolveBuyerSettlementAccount(tx);
                String releaseOn = (chosenAccount != null)
                        ? chosenAccount
                        : resolveLocalAccountDeterministic(owner.role(), owner.id(), postingCcy)
                                .map(Account::getAccountNumber)
                                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                                        "Person+Monas rollback: missing account for ownerId=" + pe.id().id()
                                                + " currency=" + postingCcy));
                reservationApplier.releaseMonas(releaseOn, abs);

            } else if (p.asset() instanceof Asset.Stock s && p.account() instanceof TxAccount.Person pe) {
                String ticker = s.asset().ticker();
                // releaseStock razresava listing po ticker-u u trading-service-u i
                // mapira odsustvo u "Listing not found: <ticker>" (→ InterbankProtocolException),
                // pa zaseban findListingByTicker pre-check ne treba — bio je redundantan
                // HTTP round-trip unutar @Transactional.
                // BUG-2: strip "C-"/"E-" prefix pre parse (vidi parsePersonOwnerId).
                Long userId = parsePersonOwnerId(pe.id().id());
                reservationApplier.releaseStock(
                        stockIdempotencyKey(transactionId, "release", userId, "CLIENT", ticker, i),
                        userId, "CLIENT", ticker, abs.intValueExact());

            } else if (p.asset() instanceof Asset.OptionAsset) {
                // Option rollback is a no-op: stocks remain reserved under the contract;
                // the contract stays ACTIVE so the buyer can retry.
            }
        }

        ibTx.setStatus(InterbankTransactionStatus.ROLLED_BACK);
        ibTx.setRolledBackAt(LocalDateTime.now());
        ibTx.setLastActivityAt(LocalDateTime.now());
        txRepo.save(ibTx);
    }

    /**
     * §2.12.1: inbound NEW_TX handler. Atomically persists recipient state,
     * validates/reserves, caches the response for idempotency.
     */
    @Transactional
    public TransactionVote handleNewTx(Transaction tx, IdempotenceKey key) {
        // T2-A fix (Tim 1 cross-bank Stage A, 2026-05-20): pre-check za null
        // postings i transactionId pre nego sto udjemo u validation/reservation
        // logiku. Bez ovog NPE bubbles up kao 400 sa NPE-derived porukom umesto
        // razumnog "transaction.postings is required" odgovora. Po Tim 2 spec
        // §6.1 malformed/incomplete NEW_TX body mora biti 400 + jasan razlog.
        if (tx == null) {
            throw new IllegalArgumentException("transaction message is required");
        }
        if (tx.transactionId() == null) {
            throw new IllegalArgumentException("transaction.transactionId is required");
        }
        if (tx.postings() == null || tx.postings().isEmpty()) {
            throw new IllegalArgumentException(
                    "transaction.postings is required and must contain at least one balanced double-entry pair");
        }

        // BE-INT-01 fix: idempotency replay je obavljen na dispatch nivou
        // (InterbankInboundController.receiveMessage:100-114) gde se cached
        // response parsira kao Object (Map/List/String/Number) — sto preserve-uje
        // originalni JSON shape bez obzira na messageType. Redundantan lookup
        // u service handler-u je bio bug: parsirao je response u TransactionVote.class
        // bez obzira da li je messageType bio NEW_TX, COMMIT_TX ili ROLLBACK_TX,
        // sto bi za COMMIT_TX/ROLLBACK_TX replay (cached response je prazan string "")
        // bacalo JsonProcessingException. Sad se oslanjamo na dispatch-level cache —
        // ako dosegnemo handleNewTx, znaci cache je miss i moramo da obradimo
        // transakciju od pocetka.

        // P2-concurrency-locks-1 (R3-1582) DOUBLE-RESERVE GUARD: dispatch-level cache
        // (InterbankMessage) NE moze da iznudi idempotenciju jer je tabela
        // particionisana po created_at → UNIQUE indeks MORA da sadrzi created_at, pa
        // dva konkurentna NEW_TX sa istim kljucem ali razlicitim created_at oba
        // promase cache i oba rezervisu (double-reserve). Dedup se zato sidri na
        // NEPARTICIONISANU `interbank_transactions` tabelu (real UNIQUE na
        // (transaction_routing_number, transaction_id_string)): `saveRecipientState`
        // pesimisticki re-cita/insert-uje recipient red PRE rezervacije i vraca
        // `false` ako je transakcija vec poznata (duplicate-delivery ili racing
        // drugi NEW_TX). U tom slucaju NE rezervisemo ponovo — vracamo vec
        // izracunatu vote izvedenu iz AUTORITATIVNOG recipient reda (njegovog statusa),
        // deterministicki isti input→output.
        //
        // R3-1582 phantom-vote sub-fix (reviewer-found): replay se MORA izvesti iz
        // statusa recipient `interbank_transactions` reda (sidra dedup-a), a NE iz
        // dispatch idempotency cache-a po per-message kljucu. §2.2 posiljalac generise
        // SVEZ kljuc po poruci, pa same-transaction redelivery stize sa DRUGIM kljucem
        // K2 dok je vlasnik glas kesirao pod K1 → findCachedMessage(K2) je prazno i
        // raniji orElseGet je vracao bezuslovni "phantom YES" cak i kad je originalni
        // glas bio NO (red ROLLED_BACK, npr. nedovoljno sredstava). To bi rekло
        // koordinatoru da su sredstva rezervisana kad NISU → cross-bank leak na
        // COMMIT_TX. Sad replay-ujemo TACAN ishod iz statusa reda.
        boolean freshlyAcquired = saveRecipientState(tx);
        if (!freshlyAcquired) {
            log.debug("handleNewTx duplicate NEW_TX za transactionId={} — preskacem rezervaciju, "
                    + "vracam vote izvedenu iz autoritativnog recipient reda (double-reserve guard, R3-1582)",
                    tx.transactionId().id());
            return replayRecordedVote(tx, key);
        }

        // N3 — inbound authz gate (IDOR/drain): this transaction was initiated by a
        // remote bank (we are the RECIPIENT). A remote sender may only cause our local
        // accounts to RECEIVE value (debit-into); it must NEVER be able to CHARGE
        // (credit/reserve) one of our local currency accounts. doValidateAndReserve
        // with inbound=true rejects any local currency-account charge posting.
        // NIT2 — the cross-currency mid-rate must be pinned from the SAME live quote
        // used for the recipient balance check, not a second independent quote (the
        // two could drift between calls, letting a pinned rate diverge from the rate
        // we actually voted YES against). doValidateAndReserve captures that one quote
        // into pinHolder during the balance check; we persist it here.
        BigDecimal[] pinHolder = new BigDecimal[1];
        List<NoVoteReason> violations = doValidateAndReserve(tx, true, pinHolder);
        TransactionVote vote;
        if (violations.isEmpty()) {
            // N5 — pin the cross-currency mid-rate at VOTE time so commit cannot drift.
            pinRecipientFxRate(tx, pinHolder[0]);
            vote = new TransactionVote(TransactionVote.Vote.YES, List.of());
        } else {
            vote = new TransactionVote(TransactionVote.Vote.NO, violations);
            updateTransactionStatus(tx.transactionId(), InterbankTransactionStatus.ROLLED_BACK, null);
        }

        try {
            messageService.recordInboundResponse(key, MessageType.NEW_TX,
                    objectMapper.writeValueAsString(tx), 200,
                    objectMapper.writeValueAsString(vote),
                    tx.transactionId().id());
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Race condition: drugi paralelni request je vec snimio cache zapis sa
            // istim idempotency key-em. Vratimo vote koji smo izracunali — handleNewTx
            // je deterministicki (isti input → isti output), pa nasa kalkulacija
            // matchuje sa onom koja je vec u cache-u (modulo race window).
            log.debug("handleNewTx race: cache vec popunjen za key={}, vracamo izracunatu vote", key);
        } catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Failed to serialize handleNewTx response: " + e.getMessage());
        }

        return vote;
    }

    /**
     * §2.12.2: inbound COMMIT_TX handler. Atomically commits and caches the response.
     *
     * BE-INT-01 fix: redundantan cache lookup uklonjen — idempotency je obavljen na
     * dispatch nivou (InterbankInboundController.receiveMessage). Ako dosegnemo
     * ovaj handler, znaci da je cache miss. Ako se kasnije ispostavi da neko drugi
     * vec ima zapis (race), DataIntegrityViolationException u recordInboundResponse
     * (zbog UNIQUE constraint-a na (sender_routing_number, locally_generated_key))
     * bice mapiran u 409 ili tretiran kao no-op (vidi catch ispod).
     */
    @Transactional
    public void handleCommitTx(CommitTransaction body, IdempotenceKey key) {
        commitLocal(body.transactionId()); // direct call — joins this @Transactional

        try {
            messageService.recordInboundResponse(key, MessageType.COMMIT_TX,
                    objectMapper.writeValueAsString(body), 204, "",
                    body.transactionId().id());
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Race condition: drugi paralelni request je vec snimio cache zapis za
            // isti idempotency key. Ovo je no-op — odgovor je vec memorisan, mi
            // smo radili commit redundantno (commitLocal je idempotent — vraca
            // ranije ako je vec COMMITTED).
            log.debug("handleCommitTx race: cache vec popunjen za key={}", key);
        } catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Failed to serialize handleCommitTx response: " + e.getMessage());
        }
    }

    /**
     * §2.12.3: inbound ROLLBACK_TX handler. Atomically rolls back and caches the response.
     *
     * BE-INT-01 fix: redundantan cache lookup uklonjen — vidi handleCommitTx.
     */
    @Transactional
    public void handleRollbackTx(RollbackTransaction body, IdempotenceKey key) {
        rollbackLocal(body.transactionId()); // direct call — joins this @Transactional

        try {
            messageService.recordInboundResponse(key, MessageType.ROLLBACK_TX,
                    objectMapper.writeValueAsString(body), 204, "",
                    body.transactionId().id());
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Race condition: drugi paralelni request je vec snimio cache zapis.
            // rollbackLocal je idempotent — no-op u tom slucaju.
            log.debug("handleRollbackTx race: cache vec popunjen za key={}", key);
        } catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Failed to serialize handleRollbackTx response: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Set<Integer> collectRemoteRoutingNumbers(Transaction tx) {
        int myRn = routing.myRoutingNumber();
        Set<Integer> result = new LinkedHashSet<>();
        for (Posting posting : tx.postings()) {
            int rn;
            if (posting.account() instanceof TxAccount.Person p) {
                rn = p.id().routingNumber();
            } else if (posting.account() instanceof TxAccount.Account a) {
                // routingForAccount (NE parseRoutingNumber): za banke kojima se
                // account-prefix razlikuje od routing-a (EXBanka 2: 666 -> 265)
                // mora se prevesti, inace bi koordinacija ciljala nepostojeci
                // routing 666 i transakcija bi pala.
                rn = routing.routingForAccount(a.num());
            } else if (posting.account() instanceof TxAccount.Option o) {
                rn = o.id().routingNumber();
            } else {
                continue;
            }
            if (rn != myRn) result.add(rn);
        }
        return result;
    }

    /** Sends pre-logged NEW_TX envelopes, collects votes. Network-only; no @Transactional. */
    private Map<Integer, TransactionVote> sendPhase1Network(Phase1Result phase1) {
        Map<Integer, TransactionVote> votes = new LinkedHashMap<>();
        for (Map.Entry<Integer, Message<Transaction>> entry : phase1.envelopes().entrySet()) {
            int remoteRn = entry.getKey();
            IdempotenceKey key = phase1.keys().get(remoteRn);
            Message<Transaction> envelope = entry.getValue();
            TransactionVote vote;
            try {
                vote = client.sendMessage(remoteRn, MessageType.NEW_TX, envelope, TransactionVote.class);
                if (vote == null) {
                    messageService.markOutboundSent(key, 202, null);
                    vote = new TransactionVote(TransactionVote.Vote.NO, List.of());
                } else {
                    try {
                        messageService.markOutboundSent(key, 200, objectMapper.writeValueAsString(vote));
                    } catch (JsonProcessingException ignored) {
                        messageService.markOutboundSent(key, 200, null);
                    }
                }
            } catch (InterbankExceptions.InterbankException e) {
                // P1-5 fix: hvatamo siroku bazu InterbankException umesto samo
                // InterbankCommunicationException. InterbankClient.sendMessage baca
                // InterbankAuthException (extends InterbankException, NE
                // InterbankCommunicationException) na 401 — bez ovog catch-a, 401 bi
                // propagirao neuhvacen iz execute() i rollbackLocal() se nikad ne bi
                // pozvao → senderova rezervacija (vec commit-ovana u prepareTxPhase)
                // ostaje zauvek zakljucana. Tretiramo SVAKU inter-bank gresku (mreza,
                // auth, protokol) kao NO/abort glas → execute() ide u rollbackTxPhase
                // → rollbackLocal → releaseMonas/releaseStock. Identicna abort putanja
                // kao za komunikacione greske; log isto kao postojeci handler.
                log.warn("Phase-1 NEW_TX to routing {} failed ({}): {} — treating as NO vote (abort)",
                        remoteRn, e.getClass().getSimpleName(), e.getMessage());
                // Facet 1: koordinator ABORTUJE ovu transakciju (NO glas → rollbackTxPhase
                // → rollbackLocal). Outbound NEW_TX red se TERMINALIZUJE (markOutboundAborted),
                // a NE ostavlja PENDING preko markOutboundFailed — inace bi ga
                // InterbankRetryScheduler re-slao za transakciju koja vise ne postoji
                // (beskonacni/visekratni re-send + ERROR spam). Re-send NEW_TX-a za
                // abortovanu tx je i logicki pogresan.
                messageService.markOutboundAborted(key, e.getMessage());
                vote = new TransactionVote(TransactionVote.Vote.NO, List.of());
            }
            votes.put(remoteRn, vote);
        }
        return votes;
    }

    /** Fires pre-logged phase-2 messages (COMMIT_TX or ROLLBACK_TX). Network-only; no @Transactional. */
    private <T> void sendPhase2Network(Map<Integer, IdempotenceKey> keys, MessageType type, T body) {
        for (Map.Entry<Integer, IdempotenceKey> entry : keys.entrySet()) {
            int remoteRn = entry.getKey();
            IdempotenceKey key = entry.getValue();
            Message<T> envelope = new Message<>(key, type, body);
            try {
                client.sendMessage(remoteRn, type, envelope, Void.class);
                messageService.markOutboundSent(key, 204, null);
            } catch (InterbankExceptions.InterbankException e) {
                // 1537 — phase-2 MORA biti fire-and-forget: NIKAD ne propagiramo
                // gresku iz petlje. Pre fix-a hvatali smo SAMO InterbankCommunicationException;
                // InterbankAuthException (partner 401, extends InterbankException ali NE
                // InterbankCommunicationException) i InterbankProtocolException su
                // propagirali iz execute() POSLE lokalnog commit-a. Pozivalac (npr.
                // acceptReceivedNegotiation / OTC saga) bi tada usao u catch→compensate
                // i rollback-ovao pregovor iako je novac vec lokalno pomeren — knjige se
                // razilaze. Tretiramo SVAKU inter-bank gresku (mreza/auth/protokol)
                // identicno: oznaci poruku failed (ostaje PENDING za retransmisiju po
                // §2.9 — phase-2 se nikad ne napusta, vidi markOutboundFailed) i NASTAVI
                // sa ostalim partnerima. Lokalna odluka (commit/rollback) je vec doneta
                // i mora ostati — recipient ce ishod primiti kroz retransmit.
                messageService.markOutboundFailed(key, e.getMessage());
            } catch (RuntimeException unexpected) {
                // Defanzivno: ni neocekivani runtime izuzetak ne sme da probije i
                // ponisti vec-izvrsen lokalni commit/rollback. Loguj + markiraj failed
                // (retransmit pokupi) i nastavi — phase-2 ostaje fire-and-forget.
                log.error("Phase-2 {} to routing {} threw unexpected {} — marking failed (retransmit), "
                                + "NOT propagating (local outcome already applied): {}",
                        type, remoteRn, unexpected.getClass().getSimpleName(), unexpected.getMessage(), unexpected);
                try {
                    messageService.markOutboundFailed(key, unexpected.getMessage());
                } catch (RuntimeException markFail) {
                    log.error("Phase-2 markOutboundFailed also threw for key={}: {}", key, markFail.getMessage());
                }
            }
        }
    }

    private void saveCoordinatorState(Transaction tx, InterbankTransactionStatus status) {
        if (txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                tx.transactionId().routingNumber(), tx.transactionId().id()).isPresent()) {
            return;
        }
        try {
            InterbankTransaction ibt = new InterbankTransaction();
            ibt.setTransactionRoutingNumber(tx.transactionId().routingNumber());
            ibt.setTransactionIdString(tx.transactionId().id());
            ibt.setRole(InterbankTransaction.InterbankTransactionRole.INITIATOR);
            ibt.setStatus(status);
            ibt.setTransactionBody(objectMapper.writeValueAsString(tx));
            ibt.setRetryCount(0);
            LocalDateTime now = LocalDateTime.now();
            ibt.setCreatedAt(now);
            ibt.setLastActivityAt(now);
            txRepo.save(ibt);
        } catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Failed to serialize transaction body: " + e.getMessage());
        }
    }

    /**
     * Persistuje (ili re-detektuje) recipient stanje inbound transakcije i sluzi kao
     * idempotency/double-reserve barijera za NEW_TX.
     *
     * <p>P2-concurrency-locks-1 (R3-1582): pre fix-a je radio ne-zakljucani
     * check-then-act ({@code findBy...isPresent() ? return : save}) → dva
     * konkurentna NEW_TX sa istim {@code transactionId} oba vide "not present", oba
     * insert-uju i oba (u {@code handleNewTx}) rezervisu (double-reserve). Dispatch
     * cache (InterbankMessage) ih ne hvata jer je ta tabela particionisana po
     * created_at (UNIQUE mora sadrzati created_at).</p>
     *
     * <p>Fix: pesimisticki re-cita recipient red ({@code findForUpdate...}). Ako vec
     * postoji (duplicate-delivery ili racing drugi koji je vec commit-ovao), vraca
     * {@code false} → pozivalac NE rezervise ponovo. Za genuino-konkurentni prvi
     * insert (red jos ne postoji), oslanjamo se na REAL UNIQUE constraint
     * neparticionisane {@code interbank_transactions} tabele na
     * (transaction_routing_number, transaction_id_string): drugi {@code save} baca
     * {@link org.springframework.dao.DataIntegrityViolationException} → hvatamo ga i
     * vracamo {@code false} (drugi je vec kreirao red i rezervisace). FOR-UPDATE +
     * UNIQUE constraint zajedno serijalizuju oba slucaja, i to PRE money-leg-a.</p>
     *
     * @return {@code true} ako smo MI sveze kreirali recipient red (vlasnik obrade →
     *         nastavi sa rezervacijom); {@code false} ako je transakcija vec poznata
     *         (preskoci rezervaciju, vrati cached vote).
     */
    private boolean saveRecipientState(Transaction tx) {
        if (txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(
                tx.transactionId().routingNumber(), tx.transactionId().id()).isPresent()) {
            return false;
        }
        try {
            InterbankTransaction ibt = new InterbankTransaction();
            ibt.setTransactionRoutingNumber(tx.transactionId().routingNumber());
            ibt.setTransactionIdString(tx.transactionId().id());
            ibt.setRole(InterbankTransaction.InterbankTransactionRole.RECIPIENT);
            ibt.setStatus(InterbankTransactionStatus.PREPARED);
            ibt.setTransactionBody(objectMapper.writeValueAsString(tx));
            ibt.setRetryCount(0);
            LocalDateTime now = LocalDateTime.now();
            ibt.setCreatedAt(now);
            ibt.setLastActivityAt(now);
            txRepo.saveAndFlush(ibt);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Racing prvi insert: drugi NEW_TX je u medjuvremenu kreirao isti red
            // (UNIQUE constraint na neparticionisanoj interbank_transactions tabeli).
            // On je vlasnik obrade i rezervisace — mi preskacemo rezervaciju.
            log.debug("saveRecipientState: konkurentni duplicate insert za transactionId={} "
                    + "(UNIQUE constraint) — drugi NEW_TX je vlasnik obrade", tx.transactionId().id());
            return false;
        } catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Failed to serialize transaction body: " + e.getMessage());
        }
    }

    /**
     * P2-concurrency-locks-1 (R3-1582): za duplicate NEW_TX (vec poznata transakcija)
     * vraca prethodno izracunatu vote BEZ ponovne rezervacije.
     *
     * <p><b>R3-1582 phantom-vote sub-fix (reviewer-found, 01.06):</b> raniji
     * {@code replayCachedVote} je vote izvlacio ISKLJUCIVO iz dispatch idempotency
     * cache-a ({@link InterbankMessage}) po per-message kljucu. Ali §2.2 posiljalac
     * generise SVEZ kljuc po poruci, pa same-transaction redelivery stize sa DRUGIM
     * kljucem K2 dok je vlasnik glas kesirao pod K1 → {@code findCachedMessage(K2)}
     * promasi i {@code orElseGet} je vracao bezuslovni <b>phantom YES</b> — cak i kad
     * je originalni glas bio NO i recipient red {@code ROLLED_BACK} (npr. nedovoljno
     * sredstava). To bi prijavilo koordinatoru rezervaciju koja NE postoji →
     * cross-bank conservation leak / stranded state na COMMIT_TX.
     *
     * <p>Fix: replay se sidri na AUTORITATIVNI recipient {@code interbank_transactions}
     * red (isto sidro koje dedup koristi), preko {@code transactionId}, NE per-message
     * kljuca. Status reda je merodavan ishod originalnog glasa:
     * <ul>
     *   <li>{@code ROLLED_BACK} → originalni glas je bio NO/abort → replay <b>NO</b>
     *       (nikad ne tvrdimo rezervaciju koja ne postoji).</li>
     *   <li>{@code PREPARED}/{@code COMMITTED} → originalni NEW_TX je glasao YES i
     *       rezervacija/commit postoje → replay <b>YES</b>.</li>
     * </ul>
     * Dispatch cache se koristi SAMO kao obogacenje (precizni {@code reasons} za NO
     * glas) kad je <em>konzistentan</em> sa statusom reda; nikad ne nadjacava red.
     * Wire-format odgovora je nepromenjen (isti {@link TransactionVote} shape koji
     * partner ocekuje).
     *
     * <p>Recipient red ovde po pravilu postoji ({@code saveRecipientState} je upravo
     * vratio {@code false} jer ga je nasao pod lock-om). Ako bi ipak nedostajao
     * (krajnje degenerativan slucaj), fallback-ujemo na cache; a ako ni cache nema —
     * vracamo NO (presumed-abort: bezbedan default, NE tvrdimo nepostojecu
     * rezervaciju), bez diranja novca u ovoj grani.
     */
    private TransactionVote replayRecordedVote(Transaction tx, IdempotenceKey key) {
        Optional<InterbankTransaction> rowOpt = txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                tx.transactionId().routingNumber(), tx.transactionId().id());

        Optional<TransactionVote> cachedVote = messageService.findCachedMessage(key)
                .map(InterbankMessage::getResponseBody)
                .filter(body -> body != null && !body.isBlank())
                .map(body -> {
                    try {
                        return objectMapper.readValue(body, TransactionVote.class);
                    } catch (JsonProcessingException e) {
                        log.warn("replayRecordedVote: ne mogu da parsiram cached vote za key={}: {}",
                                key, e.getMessage());
                        return null;
                    }
                });

        if (rowOpt.isEmpty()) {
            // Krajnje degenerativan slucaj: dedup je rekao "vec poznata" ali red nije
            // citljiv. Oslonimo se na cache ako ga ima; inace presumed-abort (NO).
            return cachedVote.orElseGet(() -> {
                log.warn("replayRecordedVote: ni recipient red ni cached vote nisu citljivi za "
                        + "transactionId={} (key={}) — vracam NO (presumed-abort, NE tvrdimo rezervaciju)",
                        tx.transactionId().id(), key);
                return new TransactionVote(TransactionVote.Vote.NO,
                        List.of(new NoVoteReason(NoVoteReason.Reason.UNBALANCED_TX, null)));
            });
        }

        InterbankTransactionStatus status = rowOpt.get().getStatus();
        boolean originalVotedNo = status == InterbankTransactionStatus.ROLLED_BACK;

        if (originalVotedNo) {
            // Originalni glas je bio NO — red je oslobodjen/odbacen. Replay NO. Ako
            // cache ima konzistentan NO sa preciznim reasons, vrati ga (precizniji
            // odgovor partneru); inace generican NO.
            if (cachedVote.isPresent() && cachedVote.get().vote() == TransactionVote.Vote.NO) {
                return cachedVote.get();
            }
            log.debug("replayRecordedVote: recipient red ROLLED_BACK za transactionId={} — replay NO",
                    tx.transactionId().id());
            return new TransactionVote(TransactionVote.Vote.NO,
                    List.of(new NoVoteReason(NoVoteReason.Reason.INSUFFICIENT_ASSET, null)));
        }

        // Status PREPARED/COMMITTED (i defanzivno PREPARING) → originalni glas je YES,
        // rezervacija/commit postoje → replay YES. Cache (ako ima konzistentan YES) je
        // ekvivalentan; vracamo kanonski YES.
        return new TransactionVote(TransactionVote.Vote.YES, List.of());
    }

    /**
     * N5 — pin the cross-currency mid-rate for a recipient (Banka B) inbound
     * transaction at VOTE time, persisting it on the recipient's
     * {@link InterbankTransaction#getPinnedFxRate()}. Commit re-uses this exact rate
     * (see {@code commitRecipientCredit}) so a live FX drift between vote and commit
     * cannot push the payout outside the balance check made when voting YES.
     *
     * <p>NIT2 — the {@code midRate} passed here is the one captured DURING the balance
     * check in {@code doValidateAndReserve} (single live quote), so the pinned rate is
     * guaranteed identical to the rate the YES vote was decided against. No second live
     * quote is issued here.
     *
     * <p>No-op for same-currency settlements ({@code midRate} stays null) and for
     * transactions with no recipient cross-currency credit leg.
     *
     * @param midRate the mid-rate captured from the balance-check quote, or {@code null}
     *        when there was no cross-currency recipient leg (then nothing to pin).
     */
    private void pinRecipientFxRate(Transaction tx, BigDecimal midRate) {
        if (midRate == null) return; // same-currency or no recipient cross-currency leg
        txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                        tx.transactionId().routingNumber(), tx.transactionId().id())
                .ifPresent(ibt -> {
                    ibt.setPinnedFxRate(midRate);
                    txRepo.save(ibt);
                });
    }

    /**
     * T5(A) — S9: prodavceva settlement na INBOUND exercise (MI smo prodavceva/option
     * host banka; kupceva banka je inicirala exercise §2.7.2). Pri commit-u exercise
     * COMMIT_TX-a, nas Option pseudo-account je autoritativan, pa MORAMO:
     * <ol>
     *   <li><b>Predati hartije:</b> {@code commitStock(isDebit=false)} — prodavcevih
     *       k rezervisanih hartija (rezervisanih na accept-u u
     *       {@code OtcNegotiationService.acceptReceivedNegotiation}) se oslobadja i
     *       predaje (trading-service umanjuje {@code quantity} i {@code reservedQuantity}).
     *       Bez ovoga rezervacija ostaje zaglavljena zauvek (asset se ne predaje kupcu).</li>
     *   <li><b>Naplatiti strike:</b> {@code commitRecipientCredit} — prodavac prima
     *       strike (k*pi) u strike valuti na svoj racun (FX-aware). Bez ovoga prodavac
     *       isporuci hartije ali nikad ne primi novac → narusena konzervacija.</li>
     * </ol>
     * Settlement se izvrsava unutar {@code commitLocal} {@code @Transactional} granice
     * PRE statusnog flip-a na EXERCISED (pozivalac flip-uje TEK po uspesnom povratku);
     * ako bilo koji korak baci, transakcija se ne commit-uje (Monas leg roll-back-uje
     * Spring; idempotency-keyed retransmit §2.9 ponavlja COMMIT_TX).
     *
     * <p>Strike racun prodavca se rezolvise deterministicki po (localPartyId, strikeCcy);
     * commitStock idempotency kljuc je vezan za inter-bank transakciju (retransmit-safe).
     */
    /**
     * FIX B (buyer-credit simetrija) — per-CONTRACT gate odluka za exercise-shape tx.
     * Razresava (Stock, Option) leg → negotiationId → lokalni ugovor pod ISTIM
     * {@code findBySourceNegotiationIdForUpdate} PESSIMISTIC_WRITE lock-om koji koristi
     * seller-settle grana, pa je gate-odluka serijalizovana sa razlicitim-txId exercise-ima
     * istog ugovora (lock se drzi do kraja {@code @Transactional}).
     *
     * @return {@code true} kad settlement TREBA da se izvrsi: ugovor je jos ACTIVE ili
     *         EXERCISING (claimed), ILI ne postoji lokalni ugovor za ovaj negId (ungated —
     *         postojece ponasanje za strane/seller-host slucajeve). {@code false} kad je
     *         ugovor vec EXERCISED (ili terminal) → buyer stock kredit se preskace
     *         (exactly-once po ugovoru; sprecava double-delivery na retry sa novim txId).
     */
    private boolean isExerciseSettlementClaimable(List<Posting> postings) {
        return postings.stream()
                .filter(pp -> pp.account() instanceof TxAccount.Option)
                .filter(pp -> pp.asset() instanceof Asset.Stock)
                .findFirst()
                .map(pp -> {
                    ForeignBankId negId = ((TxAccount.Option) pp.account()).id();
                    return otcNegotiationRepository
                            .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                                    negId.routingNumber(), negId.id())
                            .flatMap(neg -> otcContractRepository.findBySourceNegotiationIdForUpdate(neg.getId()))
                            .map(contract ->
                                    contract.getStatus() == InterbankOtcContractStatus.ACTIVE
                                            || contract.getStatus() == InterbankOtcContractStatus.EXERCISING)
                            // Nema lokalnog ugovora za ovaj negId → gate OTVOREN (ungated).
                            .orElse(true);
                })
                // Nema (Stock, Option) leg-a (nije exercise) → gate OTVOREN.
                .orElse(true);
    }

    private void settleSellerOnInboundExercise(ForeignBankId transactionId, InterbankOtcContract contract) {
        Long sellerUserId = contract.getLocalPartyId();
        String sellerRole = contract.getLocalPartyRole();
        String ticker = contract.getTicker();
        int qty = contract.getQuantity().intValueExact();
        BigDecimal strike = contract.getStrikePrice().multiply(contract.getQuantity());
        String strikeCcy = contract.getStrikeCurrency();

        // FINDING 1 — resolve-first: prodavcev strike racun (JEDINI korak koji moze
        // da baci) razresavamo PRE bilo kakvog out-of-process side effect-a. Pre
        // fix-a je commitStock(isDebit=false) (REALNA isporuka k hartija trading-
        // service-u — zaseban tx boundary) isao PRE bacajuce rezolucije racuna: ako
        // prodavac nema racun u strike valuti, orElseThrow je pucao TEK posle isporuke
        // → @Transactional roll-back banka-core, ali NE i out-of-process isporuku
        // hartija → hartije zaglavljene, prodavac nikad kreditiran, contract nikad
        // flip-uje → §2.9 re-delivery petlja zauvek. Resolve-first cini settle
        // all-or-nothing: ako racuna nema, NISTA out-of-process se nije desilo.
        String sellerAccount = resolveLocalAccountDeterministic(sellerRole, sellerUserId, strikeCcy)
                .map(Account::getAccountNumber)
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Seller settlement: prodavac " + sellerUserId + " nema racun u valuti "
                                + strikeCcy + " za naplatu strike-a (exercise)"));

        // (1) Predaja prodavcevih k rezervisanih hartija (isDebit=false → quantity i
        //     reservedQuantity se umanjuju). Idempotency kljuc po inter-bank tx-u.
        //     Sad se izvrsava TEK posle uspesne rezolucije racuna (zero stranded delivery).
        reservationApplier.commitStock(
                stockIdempotencyKey(transactionId, "seller-deliver", sellerUserId, sellerRole, ticker, 0),
                sellerUserId, sellerRole, ticker, qty, false);

        // (2) Naplata strike-a (k*pi) na vec razresen prodavcev racun u strike valuti.
        //     Kredit je uvek iste valute (racun razresen u strikeCcy, kredit u strikeCcy)
        //     pa commitRecipientCredit ne moze da baci posle resolve-a — settle ostaje
        //     all-or-nothing. pinnedFxRate iz vote faze (null za same-currency);
        //     commitRecipientCredit je FX-svestan i za same-ccy radi obican kredit.
        reservationApplier.commitRecipientCredit(sellerAccount, strike, strikeCcy, null);
    }

    private void updateTransactionStatus(ForeignBankId txId,
            InterbankTransactionStatus status, String failureReason) {
        txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                        txId.routingNumber(), txId.id())
                .ifPresent(ibt -> {
                    ibt.setStatus(status);
                    ibt.setLastActivityAt(LocalDateTime.now());
                    if (failureReason != null) ibt.setFailureReason(failureReason);
                    txRepo.save(ibt);
                });
    }

    private static String assetKey(Asset asset) {
        if (asset instanceof Asset.Monas m)      return "MONAS:" + m.asset().currency().name();
        if (asset instanceof Asset.Stock s)       return "STOCK:" + s.asset().ticker();
        if (asset instanceof Asset.OptionAsset o) return "OPTION:" + o.asset().negotiationId().id();
        return "UNKNOWN:" + asset.getClass().getSimpleName();
    }

    /**
     * §2.6 — TxAccount.Person foreign id resolve: nasi Person id-evi su prefiksirani
     * sa {@code "C-"} (klijent) ili {@code "E-"} (zaposleni) da bi se razlikovala dva
     * id-prostora (clients.id vs employees.id) koji se preklapaju. Numericki id se
     * dobija odbacivanjem prefiksa.
     *
     * <p>BUG-2 fix: Stock+Person leg-ovi (prepare/commit/rollback) su ranije zvali
     * {@code Long.parseLong(pe.id().id())} DIREKTNO na prefiksiranom id-u ({@code "C-1"})
     * → {@link NumberFormatException} → prepare glasao NO sa {@code NO_SUCH_ACCOUNT},
     * pa je buyer stock-delivery exercise pucao. Monas+Person grane su prefiks vec
     * strip-ovale inline; ovaj helper centralizuje istu logiku za sve grane.
     *
     * @return numericki owner id; baca {@link NumberFormatException} ako string nije broj
     *         ni posle skidanja prefiksa (caller ga mapira na {@code NO_SUCH_ACCOUNT})
     */
    private static long parsePersonOwnerId(String personId) {
        return resolvePersonOwner(personId).id();
    }

    /**
     * §2.6 — role-aware Person foreign-id resolve. Nasi Person id-evi su prefiksirani
     * sa {@code "C-"} (klijent) ili {@code "E-"} (zaposleni). Vraca PAR (role, numericki id)
     * da pozivaoci mogu da razresavaju racune ROLE-AWARE.
     *
     * <p><b>BUG-2 [WRONG PARTY CHARGED / IDOR] fix:</b> ranije se prefiks samo odbacivao
     * pa je {@code "E-3"} (zaposleni) i {@code "C-3"} (klijent) davalo ISTI numericki id 3,
     * a {@link #resolveLocalAccountDeterministic} je slepo zvao {@code findByClientId(3)} →
     * zaposleni {@code E-3} bi se razresio na racun TUDJEG klijenta {@code C-3} i naplata
     * (premija/strike) bi pala na pogresno lice. Sad caller dobija i ULOGU, pa
     * {@code resolveLocalAccountDeterministic} klijentske racune trazi SAMO za {@code "C-"};
     * za {@code "E-"} (zaposleni nemaju klijentske settlement racune u ovom modelu —
     * {@code Account.client} je {@code null}, {@code Account.employee} je samo kreator)
     * vraca {@link Optional#empty()} → accept-guard cisto odbija (400), a inbound debit
     * pukne glasno umesto da tereti stranca.
     *
     * @return {@link PersonOwner} (role + numericki id); {@code role} je {@code "CLIENT"} za
     *         {@code "C-"}, {@code "EMPLOYEE"} za {@code "E-"}, {@code null} ako nema poznatog
     *         prefiksa (legacy/strani opaque id). Baca {@link NumberFormatException} ako
     *         ostatak nije broj (caller ga mapira na {@code NO_SUCH_ACCOUNT}).
     */
    private static PersonOwner resolvePersonOwner(String personId) {
        String id = personId;
        String role = null;
        if (id != null) {
            if (id.startsWith("C-")) {
                role = "CLIENT";
                id = id.substring(2);
            } else if (id.startsWith("E-")) {
                role = "EMPLOYEE";
                id = id.substring(2);
            }
        }
        return new PersonOwner(role, Long.parseLong(id));
    }

    /** BUG-2: role + numericki owner id izveden iz prefiksiranog Person foreign-id-a. */
    private record PersonOwner(String role, long id) {}

    /**
     * N3 helper: is this posting a charge against a LOCAL currency account?
     *
     * <p>We only reach the N3 gate for non-remote (local) postings (remote ones are
     * skipped by {@code isPostingRemote}). A currency-account charge is a posting whose
     * account is a {@link TxAccount.Account} (currency account) or a {@link TxAccount.Person}
     * carrying a monetary asset (resolved to the holder's money account). Option
     * pseudo-accounts ({@link TxAccount.Option}) are explicitly NOT money accounts —
     * their reservation is gated by the OTC contract relationship, so they are excluded.
     */
    private boolean isLocalChargePosting(TxAccount account) {
        return account instanceof TxAccount.Account || account instanceof TxAccount.Person;
    }

    /**
     * N3 relationship gate — is this local currency CHARGE posting an authorized
     * §3.6 OTC accept premium debit?
     *
     * <p>The accept transaction (§3.6) is initiated by the SELLER's bank; the BUYER's
     * bank (us) receives it as inbound NEW_TX and legitimately debits the local buyer
     * for the agreed premium. This is the ONLY inbound flow that charges one of our
     * local currency accounts:
     * <ul>
     *   <li>OTC <b>exercise</b> inbound (we are seller) charges only Option pseudo-accounts
     *       (the strike money moves into the abstract option account); the buyer money leg
     *       is remote → skipped. No local currency charge.</li>
     *   <li>Plain inter-bank payments only RECEIVE into our local accounts (debit-into);
     *       the sender charge leg is remote.</li>
     * </ul>
     *
     * <p>Authorization is bound to the actual negotiation relationship, NOT to a
     * structural "any option leg present" flag. The transaction must carry an
     * {@link Asset.OptionAsset} leg whose {@code negotiationId} resolves to a local OTC
     * negotiation in which:
     * <ol>
     *   <li>we are the BUYER ({@code localPartyType == BUYER}),</li>
     *   <li>the charged party ({@code p.account()}) is exactly that buyer, and</li>
     *   <li>the charge amount and currency match the negotiation's premium exactly.</li>
     * </ol>
     * Anything that fails any of these is rejected by the caller as an arbitrary drain —
     * an attacker who attaches a legitimate option leg of their OWN contract cannot
     * smuggle a charge against an unrelated victim account, because that victim is not
     * the buyer of the resolved negotiation and the amount would not match the premium.
     */
    private boolean isAuthorizedOtcPremiumCharge(Posting chargePosting, Transaction tx) {
        if (!(chargePosting.asset() instanceof Asset.Monas chargeMonas)) return false;
        String chargeCcy = chargeMonas.asset().currency().name();
        BigDecimal chargeAmount = chargePosting.amount().abs();
        TxAccount chargeAccount = chargePosting.account();

        for (Posting optLeg : tx.postings()) {
            if (!(optLeg.asset() instanceof Asset.OptionAsset oa)) continue;
            ForeignBankId negId = oa.asset().negotiationId();
            if (negId == null) continue;

            Optional<InterbankOtcNegotiation> negOpt =
                    otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                            negId.routingNumber(), negId.id());
            if (negOpt.isEmpty()) continue;
            InterbankOtcNegotiation neg = negOpt.get();

            // We must be the BUYER for this charge to be a legitimate premium debit.
            if (neg.getLocalPartyType() != InterbankPartyType.BUYER) continue;

            // The charged party must be exactly the local buyer of this negotiation.
            if (!chargesLocalBuyer(chargeAccount, neg)) continue;

            // Amount and currency must match the negotiation's premium exactly.
            if (neg.getPremium() == null || neg.getPremiumCurrency() == null) continue;
            if (!neg.getPremiumCurrency().equalsIgnoreCase(chargeCcy)) continue;
            if (neg.getPremium().compareTo(chargeAmount) != 0) continue;

            return true;
        }
        return false;
    }

    /**
     * N3 helper: does {@code chargeAccount} identify the local buyer of {@code neg}?
     *
     * <p>The §3.6 accept charges the buyer via {@code TxAccount.Person} carrying the
     * buyer's {@link ForeignBankId} ({@code myRouting}, {@code "C-<id>"}/{@code "E-<id>"}).
     * We match it against the negotiation's local buyer id/role. A {@code TxAccount.Account}
     * charge is also matched defensively by resolving the account's owner, in case a
     * partner ever sends the premium leg as a concrete account number.
     */
    private boolean chargesLocalBuyer(TxAccount chargeAccount, InterbankOtcNegotiation neg) {
        if (chargeAccount instanceof TxAccount.Person pe) {
            if (pe.id().routingNumber() != routing.myRoutingNumber()) return false;
            return buyerForeignId(neg).equalsIgnoreCase(pe.id().id());
        }
        if (chargeAccount instanceof TxAccount.Account a) {
            return accountRepository.findByAccountNumber(a.num())
                    .map(acct -> acct.getClient() != null
                            && "CLIENT".equalsIgnoreCase(neg.getLocalPartyRole())
                            && neg.getLocalPartyId() != null
                            && neg.getLocalPartyId().equals(acct.getClient().getId()))
                    .orElse(false);
        }
        return false;
    }

    /** Formira "C-<id>"/"E-<id>" iz negotiation lokalne strane (mirror OtcNegotiationService.toForeignIdString). */
    private static String buyerForeignId(InterbankOtcNegotiation neg) {
        String prefix = "CLIENT".equalsIgnoreCase(neg.getLocalPartyRole()) ? "C-" : "E-";
        return prefix + neg.getLocalPartyId();
    }

    /** Formira "C-<id>"/"E-<id>" iz contract lokalne strane (mirror buyerForeignId, za contract). */
    private static String localPartyForeignId(InterbankOtcContract contract) {
        String prefix = "CLIENT".equalsIgnoreCase(contract.getLocalPartyRole()) ? "C-" : "E-";
        return prefix + contract.getLocalPartyId();
    }

    /**
     * BUG-1 [MONEY DESTROYED] — is {@code p} the §2.7.2/§S7 inbound EXERCISE
     * "credit seller real cash pi*k" leg targeting OUR local seller?
     *
     * <p>Na inbound EXERCISE-u (kupceva banka inicira, MI smo seller/option-host),
     * partner ukljucuje EKSPLICITAN seller-cash credit leg:
     * {@code Posting(Person(ourSeller@myRouting), -pi*k, Monas(strikeCcy))} — protokolarni
     * "credit" je NEGATIVAN iznos (prodavac PRIMA). Mi prodavca kreditiramo kroz
     * {@link #settleSellerOnInboundExercise} (OPTION→prodavac sweep iz Option money leg-a),
     * pa ovaj leg NE smemo da rezervisemo/teretimo/kreditiramo zasebno (to bi bilo
     * dvostruko knjizenje / pogresan smer / N3 drain-reject). Ovaj prepoznavac vraca
     * {@code true} SAMO za pravu seller-cash exercise nogu — vezano za RELACIJU ugovora
     * (NE strukturalno), pa napadac ne moze da ga zloupotrebi:
     * <ol>
     *   <li>tx je exercise-shape (nosi {@code (Stock, Option)} leg, ne {@code OptionAsset}),</li>
     *   <li>{@code p} je {@code (Monas, Person)} sa Person routing-om == nas routing (lokalno),
     *       negativan iznos (protokolarni credit),</li>
     *   <li>postoji {@code (Monas, Option)} ili {@code (Stock, Option)} leg cija opcija
     *       razresava LOKALNI ugovor u kojem smo SELLER, a {@code p}-ova Person je BAS
     *       taj prodavac (foreign-id match), valuta == strike valuta, a
     *       {@code |amount| == strikePrice*quantity}.</li>
     * </ol>
     */
    private boolean isInboundExerciseSellerCashCredit(Posting p, Transaction tx) {
        if (!(p.asset() instanceof Asset.Monas chargeMonas)) return false;
        if (!(p.account() instanceof TxAccount.Person sellerPerson)) return false;
        if (sellerPerson.id().routingNumber() != routing.myRoutingNumber()) return false;
        // Protokolarni "credit seller" = negativan iznos (prodavac PRIMA).
        if (p.amount().compareTo(BigDecimal.ZERO) >= 0) return false;

        // Mora biti exercise-shape: nosi (Stock, Option) leg (accept nosi OptionAsset).
        boolean exerciseShape = tx.postings().stream().anyMatch(
                sp -> sp.asset() instanceof Asset.Stock && sp.account() instanceof TxAccount.Option);
        if (!exerciseShape) return false;

        String chargeCcy = chargeMonas.asset().currency().name();
        BigDecimal chargeAmount = p.amount().abs();

        // Razresi opciju (Option pseudo-account) → lokalni ugovor u kojem smo SELLER.
        for (Posting optLeg : tx.postings()) {
            if (!(optLeg.account() instanceof TxAccount.Option opt)) continue;
            ForeignBankId negId = opt.id();
            if (negId == null) continue;

            Optional<InterbankOtcNegotiation> negOpt =
                    otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                            negId.routingNumber(), negId.id());
            if (negOpt.isEmpty()) continue;
            Optional<InterbankOtcContract> contractOpt =
                    otcContractRepository.findBySourceNegotiationId(negOpt.get().getId());
            if (contractOpt.isEmpty()) continue;
            InterbankOtcContract contract = contractOpt.get();

            // Moramo biti SELLER (option-host) ovog ugovora.
            if (contract.getLocalPartyType() != InterbankPartyType.SELLER) continue;
            // Person mora biti BAS nas prodavac (foreign-id match).
            if (!localPartyForeignId(contract).equalsIgnoreCase(sellerPerson.id().id())) continue;
            // Valuta + iznos moraju biti tacno strike (pi*k) u strike valuti.
            if (contract.getStrikeCurrency() == null
                    || !contract.getStrikeCurrency().equalsIgnoreCase(chargeCcy)) continue;
            BigDecimal strike = contract.getStrikePrice().multiply(contract.getQuantity());
            if (strike.compareTo(chargeAmount) != 0) continue;

            return true;
        }
        return false;
    }

    /**
     * T1/T2 — stabilan kupcev settlement racun za buyer-premium Monas+Person leg.
     * Kad {@code tx} nosi {@link Asset.OptionAsset} leg (accept-shape §3.6), razresava
     * pregovor isto kao {@link #isAuthorizedOtcPremiumCharge} (OptionAsset →
     * {@code negotiationId} → lokalni pregovor) i, ako taj pregovor ima postavljen
     * {@code buyerSettlementAccountNumber} (kupac ga je izabrao na FE-u pri accept-u),
     * vraca bas taj broj racuna. Prepare (rezervacija) i commit (commitMonas) tako
     * UVEK gadjaju ISTI racun — nema balance-desc drift-a koji bi rasporedio
     * rezervaciju i commit na razlicite racune.
     *
     * @return izabrani broj racuna ili {@code null} ako nema OptionAsset leg-a ili
     *         pregovor nema postavljen settlement racun (caller pada na deterministicki
     *         fallback po broju racuna).
     */
    private String resolveBuyerSettlementAccount(Transaction tx) {
        for (Posting optLeg : tx.postings()) {
            if (!(optLeg.asset() instanceof Asset.OptionAsset oa)) continue;
            ForeignBankId negId = oa.asset().negotiationId();
            if (negId == null) continue;
            Optional<InterbankOtcNegotiation> negOpt =
                    otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                            negId.routingNumber(), negId.id());
            if (negOpt.isEmpty()) continue;
            String chosen = negOpt.get().getBuyerSettlementAccountNumber();
            if (chosen != null && !chosen.isBlank()) return chosen;
        }
        return null;
    }

    /**
     * T1/T2 — deterministicki fallback resolucije lokalnog racuna za Person+Monas leg
     * kad pregovor nema izabran settlement racun (legacy pregovor ili plain inter-bank
     * placanje §2.6 T2-B). Bira PRVI ACTIVE racun vlasnika u trazenoj valuti SORTIRAN
     * po broju racuna RASTUCE — NE po available balansu. Balance-desc je nestabilan:
     * rezervacija u prepare fazi smanji balans pa commit moze izabrati drugi racun.
     *
     * <p><b>P0 [MONEY CREATED] ugovor:</b> oslanja se na {@code findByClientId...}, pa za
     * NE-klijentskog vlasnika (zaposleni/admin — nemaju klijentske racune) vraca
     * {@link Optional#empty()}. Vraca {@code Optional} BAS zato da pozivac NIKAD tiho
     * ne no-op-uje debit kad racuna nema — SVI pozivaoci MORAJU empty tretirati kao
     * tvrdu gresku ({@code .orElseThrow(...)} na commit/reserve/rollback granama, ili
     * {@code NO_SUCH_ACCOUNT} NoVote u validate fazi → 2PC abort/kompenzacija). Tiho
     * preskakanje debita = stvaranje novca. (Outbound strana je dodatno zatvorena:
     * {@code InterbankOtcWrapperService.acceptOffer} odbija accept PRE outbound 2PC ako
     * kupac nema resolvabilan settlement racun u valuti premije.)
     */
    private Optional<Account> resolveLocalAccountDeterministic(String ownerRole, Long ownerId, String postingCcy) {
        // BUG-2 [WRONG PARTY CHARGED / IDOR]: razresavamo SAMO klijentske racune i SAMO
        // kad je vlasnik klijent (role == CLIENT ili nepoznat-legacy prefiks). Za
        // zaposlenog (role == EMPLOYEE) NE smemo da zovemo findByClientId(ownerId) — to
        // bi razresilo racun KLIJENTA sa istim numerickim id-em (tudje lice). Zaposleni
        // nemaju klijentske settlement racune u ovom modelu → vracamo empty (pozivac to
        // tretira kao tvrdu gresku: NO_SUCH_ACCOUNT / accept reject / glasni inbound fail).
        if ("EMPLOYEE".equalsIgnoreCase(ownerRole)) {
            return Optional.empty();
        }
        return accountRepository
                .findByClientIdAndStatusOrderByAvailableBalanceDesc(ownerId, AccountStatus.ACTIVE)
                .stream()
                .filter(a -> a.getCurrency() != null && postingCcy.equals(a.getCurrency().getCode()))
                .min(Comparator.comparing(Account::getAccountNumber));
    }

    private boolean isPostingRemote(Posting p) {
        TxAccount account = p.account();
        if (account instanceof TxAccount.Account a) {
            return !routing.isLocalAccount(a.num());
        } else if (account instanceof TxAccount.Person pe) {
            return pe.id().routingNumber() != routing.myRoutingNumber();
        } else if (account instanceof TxAccount.Option o) {
            return o.id().routingNumber() != routing.myRoutingNumber();
        }
        return false;
    }

    /** Coordinator-side (outbound) overload — no FX pin needed. */
    private List<NoVoteReason> doValidateAndReserve(Transaction tx, boolean inbound) {
        return doValidateAndReserve(tx, inbound, null);
    }

    /**
     * Two-pass validate-and-reserve.
     * Pass 1: collect all violations (no DB writes).
     * Pass 2: make reservations only if Pass 1 found no violations.
     *
     * @param inbound when {@code true}, this transaction was initiated by a remote
     *        bank and we are the RECIPIENT — the N3 authorization gate applies: a
     *        remote sender must not be allowed to CHARGE (credit/reserve) any of our
     *        local currency accounts. When {@code false} (we are the coordinator),
     *        charging our own local sender accounts is legitimate.
     * @param pinHolder NIT2 — optional 1-element out-param. When non-null and this is a
     *        recipient cross-currency settlement, {@code pinHolder[0]} receives the
     *        {@code midRate} of the SAME live quote used for the balance check, so the
     *        caller can pin EXACTLY that rate (no second, possibly-drifted, live quote).
     */
    private List<NoVoteReason> doValidateAndReserve(Transaction tx, boolean inbound, BigDecimal[] pinHolder) {
        Map<String, BigDecimal> assetSums = new LinkedHashMap<>();
        for (Posting p : tx.postings()) {
            assetSums.merge(assetKey(p.asset()), p.amount(), BigDecimal::add);
        }
        for (BigDecimal groupSum : assetSums.values()) {
            if (groupSum.compareTo(BigDecimal.ZERO) != 0) {
                return List.of(new NoVoteReason(NoVoteReason.Reason.UNBALANCED_TX, null));
            }
        }

        List<NoVoteReason> violations = new ArrayList<>();

        for (Posting p : tx.postings()) {
            if (isPostingRemote(p)) continue;

            boolean isCredit = p.amount().compareTo(BigDecimal.ZERO) < 0;
            BigDecimal abs = p.amount().abs();
            Asset asset = p.asset();
            TxAccount account = p.account();

            // N3 — inbound authz gate (IDOR/drain). A remote-initiated transaction
            // (inbound=true) must NOT be allowed to CHARGE (credit/reserve) a LOCAL
            // currency account. The local side of an inbound transaction may only
            // RECEIVE money (debit-into) or reserve stock under an established OTC
            // option contract (gated separately via the option pseudo-account →
            // negotiation → contract relationship). Charging a local currency account
            // on a remote bank's say-so, with nothing but a valid X-Api-Key, is an
            // arbitrary-account drain. Reject it as UNACCEPTABLE_ASSET so the partner
            // gets a clean NO vote and our funds are never reserved.
            //
            // The ONLY legitimate inbound charge of a local currency account is the
            // §3.6 OTC accept premium: the SELLER's bank initiates the accept and the
            // BUYER's bank (us) receives it as NEW_TX, legitimately debiting our local
            // buyer for the agreed premium. We authorize that ONE case via a strict
            // RELATIONSHIP gate (isAuthorizedOtcPremiumCharge): the charged party must
            // be the BUYER of a real local OTC negotiation referenced by an OptionAsset
            // leg in THIS SAME tx, and the amount/currency must match that negotiation's
            // premium exactly. A structural "has any option leg" flag is NOT enough — an
            // attacker holding any ACTIVE OTC contract could attach a legit option leg of
            // their own and smuggle an unrelated victim-account charge alongside it. The
            // relationship gate binds the charged account to the buyer of the resolved
            // negotiation, so a victim's account never matches and is never reserved.
            //
            // BUG-1 [MONEY DESTROYED] addendum: a SECOND legitimate inbound "credit"
            // (negative MONAS) leg on a LOCAL Person is the §2.7.2/§S7 exercise
            // seller-cash credit ("credit seller real cash pi*k"). The partner buyer-bank
            // sends it so seller banks that credit their seller off an explicit leg get
            // paid; for US it is the book representation of the OPTION→seller sweep we
            // perform in settleSellerOnInboundExercise. It is NOT a drain (the seller
            // RECEIVES, not pays), so it must bypass the N3 reject and is then handled as
            // a recognized no-op below (exactly-once credit stays in the sweep).
            if (inbound && isCredit && asset instanceof Asset.Monas
                    && isLocalChargePosting(account)
                    && !isAuthorizedOtcPremiumCharge(p, tx)
                    && !isInboundExerciseSellerCashCredit(p, tx)) {
                log.warn("N3 inbound authz: rejecting credit (charge) posting on local currency "
                        + "account from remote tx {} — counterparty not authorized to debit it.",
                        tx.transactionId());
                violations.add(new NoVoteReason(NoVoteReason.Reason.UNACCEPTABLE_ASSET, p));
                continue;
            }

            if (asset instanceof Asset.Monas m && account instanceof TxAccount.Account a) {
                Optional<Account> acctOpt = accountRepository.findByAccountNumber(a.num());
                if (acctOpt.isEmpty() || acctOpt.get().getStatus() != AccountStatus.ACTIVE) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.NO_SUCH_ACCOUNT, p));
                    continue;
                }
                Account acct = acctOpt.get();
                String postingCcy = m.asset().currency().name();
                String accountCcy = acct.getCurrency().getCode();
                if (isCredit) {
                    // Sender (Banka A) leg: rezervacija MORA biti u valuti racuna —
                    // konverzija na source strani nije podrzana (klijent salje iz
                    // svoje valute; cross-currency obracun radi Banka B).
                    if (!postingCcy.equals(accountCcy)) {
                        violations.add(new NoVoteReason(NoVoteReason.Reason.UNACCEPTABLE_ASSET, p));
                        continue;
                    }
                    if (acct.getAvailableBalance().compareTo(abs) < 0) {
                        violations.add(new NoVoteReason(NoVoteReason.Reason.INSUFFICIENT_ASSET, p));
                    }
                } else {
                    // Recipient (Banka B) leg: wire valuta (postingCcy) moze da se
                    // razlikuje od valute primaocevog racuna. Banka B konvertuje +
                    // naplacuje proviziju (§Celina 5 §40-66). Validujemo da Banka B
                    // MOZE da izvrsi settlement pre nego sto glasa YES:
                    //  (1) cross-currency: kurs mora biti dostupan (podrzane valute);
                    //  (2) bankin pool racun u target valuti mora postojati i imati
                    //      dovoljno za isplatu "Krajnje vrednosti" (converted iznos).
                    if (!postingCcy.equalsIgnoreCase(accountCcy)) {
                        InterbankFxService.InterbankFxQuote quote;
                        try {
                            quote = interbankFxService.quoteInboundSettlement(abs, postingCcy, accountCcy);
                        } catch (RuntimeException fxFail) {
                            // Nepodrzana valuta / kurs nedostupan → ne mozemo da obradimo.
                            violations.add(new NoVoteReason(NoVoteReason.Reason.UNACCEPTABLE_ASSET, p));
                            continue;
                        }
                        // NIT2 — capture THIS quote's mid-rate so the caller pins exactly
                        // the rate the balance check used (no second live quote that could
                        // drift). Only meaningful on the inbound recipient path (pinHolder
                        // is null for the coordinator side).
                        if (pinHolder != null) {
                            pinHolder[0] = quote.midRate();
                        }
                        BigDecimal payout = quote.targetAmount().add(quote.commission()); // converted
                        Optional<Account> bankAcctOpt = accountRepository.findBankAccountByCurrency(
                                bankRegistrationNumber, accountCcy);
                        if (bankAcctOpt.isEmpty()) {
                            violations.add(new NoVoteReason(NoVoteReason.Reason.UNACCEPTABLE_ASSET, p));
                            continue;
                        }
                        if (bankAcctOpt.get().getAvailableBalance().compareTo(payout) < 0) {
                            violations.add(new NoVoteReason(NoVoteReason.Reason.INSUFFICIENT_ASSET, p));
                            continue;
                        }
                        // N5 — vote↔commit symmetry: commit credits the source-ccy pool
                        // by 'amount' (incoming wire asset). The pool account in the
                        // SOURCE currency must exist at vote time too, else commit would
                        // throw after we voted YES.
                        if (accountRepository.findBankAccountByCurrency(
                                bankRegistrationNumber, postingCcy.toUpperCase()).isEmpty()) {
                            violations.add(new NoVoteReason(NoVoteReason.Reason.UNACCEPTABLE_ASSET, p));
                        }
                    }
                }

            } else if (asset instanceof Asset.Stock s && account instanceof TxAccount.Person pe) {
                Long userId;
                try {
                    // BUG-2: strip "C-"/"E-" prefix pre parse (vidi parsePersonOwnerId).
                    userId = parsePersonOwnerId(pe.id().id());
                } catch (NumberFormatException e) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.NO_SUCH_ACCOUNT, p));
                    continue;
                }
                Optional<InternalListingDto> listingOpt =
                        tradingServiceClient.findListingByTicker(s.asset().ticker());
                if (listingOpt.isEmpty()) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.NO_SUCH_ASSET, p));
                    continue;
                }
                if (isCredit) {
                    InternalPortfolioHoldingDto holding = tradingServiceClient.findHolding(
                            userId, "CLIENT", s.asset().ticker());
                    if (!holding.exists()) {
                        violations.add(new NoVoteReason(NoVoteReason.Reason.NO_SUCH_ASSET, p));
                        continue;
                    }
                    if (holding.availableQuantity() < abs.intValueExact()) {
                        violations.add(new NoVoteReason(NoVoteReason.Reason.INSUFFICIENT_ASSET, p));
                    }
                }

            } else if (account instanceof TxAccount.Option opt) {
                // §2.8.6 rule 5+6: option pseudo-account validacija (TxAccount.Option).
                // Triggered ne samo za OptionAsset (postojeci marker case) vec i
                // za stock/monas postings ka Option pseudo-acc-u (§2.7.2 exercise
                // tx ima Stock+Option i Monas+Option, ne OptionAsset+Option).
                ForeignBankId negotiationId = opt.id();
                Optional<InterbankOtcNegotiation> negotiationOptional =
                        otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                                negotiationId.routingNumber(), negotiationId.id());
                if (negotiationOptional.isEmpty()) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.OPTION_NEGOTIATION_NOT_FOUND, p));
                    continue;
                }

                Optional<InterbankOtcContract> contractOptional =
                        otcContractRepository.findBySourceNegotiationId(negotiationOptional.get().getId());
                if (contractOptional.isEmpty()) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.OPTION_NEGOTIATION_NOT_FOUND, p));
                    continue;
                }

                InterbankOtcContract contract = contractOptional.get();

                // §2.8.6 rule 5: option must not be used or expired (UTC compare;
                // M-2 fix: contract.settlementDate je sad OffsetDateTime).
                if (contract.getStatus() != InterbankOtcContractStatus.ACTIVE
                        || !contract.getSettlementDate().isAfter(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC))) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.OPTION_USED_OR_EXPIRED, p));
                    continue;
                }

                // §2.8.6 rule 6: companion postings must match contract terms exactly.
                // Validujemo samo jednom po transakciji (na prvom Option postingu).
                // Heuristika: oba potencijalna trigger-a (Stock+Option i Monas+Option)
                // ce gadjati istu validaciju — ali sve istraga radi anyMatch po celom
                // tx-u, pa redundantnost ne pokvari ispravnost.
                BigDecimal requiredMoney = contract.getQuantity().multiply(contract.getStrikePrice());

                boolean stockOk = tx.postings().stream().anyMatch(sp ->
                        sp.asset() instanceof Asset.Stock ss
                        && ss.asset().ticker().equals(contract.getTicker())
                        && sp.amount().abs().compareTo(contract.getQuantity()) == 0);

                boolean moneyOk = tx.postings().stream().anyMatch(mp ->
                        mp.asset() instanceof Asset.Monas mm
                        && mm.asset().currency().name().equals(contract.getStrikeCurrency())
                        && mp.amount().abs().compareTo(requiredMoney) == 0);

                if (!stockOk || !moneyOk) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.OPTION_AMOUNT_INCORRECT, p));
                }

            } else if (asset instanceof Asset.OptionAsset && account instanceof TxAccount.Person) {
                // §3.6 accept-shape: OptionAsset+Person je "contract creation"
                // posting (Buyer — Debit one optionContract / Seller — Credit one
                // optionContract). U validate fazi nema sta da rezervisemo na
                // racunu (option-as-asset zivi van Account-a; stvarna rezervacija
                // hartija ide kroz acceptReceivedNegotiation-ov reservationApplier.
                // C-3 fix). Validan posting — no-op.

            } else if (asset instanceof Asset.Monas && account instanceof TxAccount.Person
                    && isInboundExerciseSellerCashCredit(p, tx)) {
                // BUG-1 [MONEY DESTROYED] — inbound exercise seller-cash credit leg.
                // §2.7.2 / §S7: kupceva banka (partner) salje EXERCISE tx koji nosi
                // EKSPLICITAN "credit seller real cash pi*k" leg (Person@ourRouting, iznos
                // negativan = protokolarni "credit" = prodavac PRIMA). Ovaj leg je SAMO
                // knjizni izraz onoga sto MI ionako radimo kao seller-host banka u
                // settleSellerOnInboundExercise (OPTION→prodavac sweep iz Option money
                // leg-a). Validacija ga MORA prepoznati (inace bi pala kroz N3 inbound
                // drain-gate ILI kroz Person+Monas funds-check jer prodavac "ne moze da
                // plati" strike koji zapravo PRIMA). Tretiramo ga kao validan no-op leg:
                // bez rezervacije, bez tereta — exactly-once kredit ide kroz
                // settleSellerOnInboundExercise. Balance-check je vec verifikovao da
                // OPTION money (+pi*k) i ovaj seller leg (-pi*k) ponistavaju MONAS grupu.

            } else if (asset instanceof Asset.Monas m && account instanceof TxAccount.Person pe) {
                // T2-B fix (Tim 1 cross-bank Stage A, 2026-05-20): Person+Monas
                // defenzivna grana — partner banka moze poslati MONAS leg sa
                // TxAccount.Person umesto TxAccount.Account kad nema otkrivene
                // konkretne brojeve racuna nase strane (spec §2.6: Person je
                // opaque foreign-bank-id koji receiver bank resolve-uje). isPostingRemote
                // je vec uklonio partner-side Person (line 605); ovde stizemo samo
                // za lokalne (myRouting) Person. Mirror Tim 1 P0.1 resolvePersonToAccount.
                PersonOwner owner;
                try {
                    // BUG-2: role-aware (E- zaposleni NE razresava se na klijentski racun).
                    owner = resolvePersonOwner(pe.id().id());
                } catch (NumberFormatException ex) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.NO_SUCH_ACCOUNT, p));
                    continue;
                }
                String postingCcy = m.asset().currency().name();
                // T1/T2 — stabilna resolucija: ako je ovo buyer-premium leg (tx ima
                // OptionAsset) i pregovor nosi izabran settlement racun, koristi BAS taj.
                // Inace deterministicki fallback po broju racuna (NE balance-desc).
                String chosenAccount = resolveBuyerSettlementAccount(tx);
                Optional<Account> resolved = (chosenAccount != null)
                        ? accountRepository.findByAccountNumber(chosenAccount)
                                .filter(a -> a.getStatus() == AccountStatus.ACTIVE
                                        && a.getCurrency() != null
                                        && postingCcy.equals(a.getCurrency().getCode()))
                        : resolveLocalAccountDeterministic(owner.role(), owner.id(), postingCcy);
                if (resolved.isEmpty()) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.NO_SUCH_ACCOUNT, p));
                    continue;
                }
                if (isCredit && resolved.get().getAvailableBalance().compareTo(abs) < 0) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.INSUFFICIENT_ASSET, p));
                }

            } else {
                violations.add(new NoVoteReason(NoVoteReason.Reason.UNACCEPTABLE_ASSET, p));
            }
        }

        if (!violations.isEmpty()) return violations;

        // Pass 2 — reservations (credit postings only, amount < 0).
        // I-5 fix po Celini 5 audit-u: ako reserveStock (HTTP poziv ka
        // trading-service-u) uspe za jedan posting a sledeci pukne, prvi je vec
        // ulazio out-of-process u trading_db. @Transactional rollback nije pravi
        // rollback — trading-service ima sopstvenu Tx granicu. Pratimo svaki
        // uspeh i kompenzujemo immediate na mid-failure.
        List<StockReservationRecord> stockReservations = new ArrayList<>();
        List<Posting> postings = tx.postings();
        try {
            for (int i = 0; i < postings.size(); i++) {
                Posting p = postings.get(i);
                if (isPostingRemote(p)) continue;
                if (p.amount().compareTo(BigDecimal.ZERO) >= 0) continue;

                BigDecimal abs = p.amount().abs();
                Asset asset = p.asset();
                TxAccount account = p.account();

                if (asset instanceof Asset.Monas && account instanceof TxAccount.Account a) {
                    reservationApplier.reserveMonas(a.num(), abs);

                } else if (asset instanceof Asset.Monas && account instanceof TxAccount.Person
                        && isInboundExerciseSellerCashCredit(p, tx)) {
                    // BUG-1: inbound exercise seller-cash credit leg — NE rezervisemo nista.
                    // Prodavac PRIMA strike (kredit ide kroz settleSellerOnInboundExercise
                    // OPTION→prodavac sweep), pa rezervacija na prodavcevom racunu ne sme da
                    // se desi (to bi bio teret koji ne postoji). Recognized no-op.

                } else if (asset instanceof Asset.Monas m && account instanceof TxAccount.Person pe) {
                    // T2-B reservation pair: Person+Monas — resolve do 18-cifrenog
                    // racuna pa rezervisi normalno. validacija je vec prosla u Pass 1
                    // (NO_SUCH_ACCOUNT / INSUFFICIENT_ASSET) pa ovde garantovano postoji.
                    // T1/T2 — MORA koristiti istu (stabilnu) resoluciju kao Pass 1 i
                    // commitLocal: izabrani settlement racun ako postoji, inace
                    // deterministicki po broju racuna (NE balance-desc).
                    // BUG-2: role-aware (E- zaposleni NE razresava se na klijentski racun).
                    PersonOwner owner = resolvePersonOwner(pe.id().id());
                    String postingCcy = m.asset().currency().name();
                    String chosenAccount = resolveBuyerSettlementAccount(tx);
                    String reserveOn = (chosenAccount != null)
                            ? chosenAccount
                            : resolveLocalAccountDeterministic(owner.role(), owner.id(), postingCcy)
                                    .map(Account::getAccountNumber)
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Person+Monas reservation: missing account for ownerId=" + pe.id().id()
                                                    + " currency=" + postingCcy + " (validation prosao ali resolve fail)"));
                    reservationApplier.reserveMonas(reserveOn, abs);

                } else if (asset instanceof Asset.Stock s && account instanceof TxAccount.Person pe) {
                    // BUG-2: strip "C-"/"E-" prefix pre parse (vidi parsePersonOwnerId).
                    Long userId = parsePersonOwnerId(pe.id().id());
                    String ticker = s.asset().ticker();
                    reservationApplier.reserveStock(
                            stockIdempotencyKey(tx.transactionId(), "reserve", userId, "CLIENT", ticker, i),
                            userId, "CLIENT", ticker, abs.intValueExact());
                    stockReservations.add(new StockReservationRecord(
                            userId, "CLIENT", ticker, abs.intValueExact(), i));
                }
            }
        } catch (RuntimeException reservationFail) {
            // Kompenzacija: oslobodi sve hartije koje smo vec rezervisali u ovom
            // pass-u. releaseStock je idempotentan, kljuc ukljucuje fazu "release"
            // da ne sudara sa reserve kljucem.
            for (StockReservationRecord r : stockReservations) {
                try {
                    reservationApplier.releaseStock(
                            stockIdempotencyKey(tx.transactionId(), "release-on-prepare-fail",
                                    r.userId(), r.role(), r.ticker(), r.postingIndex()),
                            r.userId(), r.role(), r.ticker(), r.quantity());
                } catch (RuntimeException releaseFail) {
                    // Best-effort — log + nastavi. Idempotency-driven retry
                    // scheduler ce verovatno preuzeti, ali to je deferred concern.
                    log.warn("Compensation releaseStock failed for tx={} posting={}: {}",
                            tx.transactionId(), r.postingIndex(), releaseFail.getMessage());
                }
            }
            throw reservationFail;
        }

        return violations;
    }

    /** Trag uspesne rezervacije hartije u Pass 2 — koristi se za kompenzaciju. */
    private record StockReservationRecord(Long userId, String role, String ticker,
                                          int quantity, int postingIndex) {}

    /**
     * Trag uspesnog commit-a hartije u {@code commitLocal} — koristi se za reverznu
     * kompenzaciju (BE-INT-06). Cuva {@code isDebit} smer da kompenzacija moze da ga
     * invertuje.
     */
    private record StockCommitRecord(Long userId, String role, String ticker,
                                     int quantity, boolean isDebit, int postingIndex) {}

    /**
     * Determinisitcki idempotency kljuc za hartijsku nogu inter-bank transakcije.
     * Kombinuje {@code transactionId}, fazu ({@code reserve}/{@code commit}/{@code release}),
     * posting identitet ({@code userId}, {@code role}, {@code ticker}) i indeks
     * posting-a u transakciji ({@code postingIndex}) — retry iste transakcije
     * gadja isti kljuc, pa trading-service vraca kesiran odgovor.
     *
     * <p>{@code postingIndex} cini svaki posting zaseban idempotentan poziv:
     * ako bi transakcija nosila dva hartijska posting-a sa istim
     * {@code (userId, role, ticker)}, bez indeksa bi se sudarili na istom kljucu
     * i drugo kretanje bi trading-service-ov idempotency kes tiho progutao.
     */
    private static String stockIdempotencyKey(ForeignBankId txId, String phase,
                                              Long userId, String role, String ticker,
                                              int postingIndex) {
        return "ib-" + txId.routingNumber() + "-" + txId.id() + ":stock-" + phase
                + ":" + userId + ":" + role + ":" + ticker + ":" + postingIndex;
    }
}
