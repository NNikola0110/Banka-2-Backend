package rs.raf.banka2_bek.interbank.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalListingDto;
import rs.raf.banka2.contracts.internal.InternalPortfolioHoldingDto;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContract;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContractStatus;
import rs.raf.banka2_bek.interbank.model.InterbankOtcNegotiation;
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
                self.rollbackLocal(tx.transactionId());
            }
            return;
        }

        // §2.8.5: promote to coordinator — prepare + log outbound atomically
        Phase1Result phase1 = self.prepareTxPhase(tx, remoteRns);
        if (phase1.vote().vote() == TransactionVote.Vote.NO) return;

        // Network I/O outside any @Transactional
        Map<Integer, TransactionVote> votes = sendPhase1Network(phase1);
        boolean allYes = votes.values().stream().allMatch(v -> v.vote() == TransactionVote.Vote.YES);

        if (allYes) {
            Map<Integer, IdempotenceKey> commitKeys = self.commitTxPhase(tx.transactionId(), remoteRns);
            sendPhase2Network(commitKeys, MessageType.COMMIT_TX, new CommitTransaction(tx.transactionId()));
        } else {
            Map<Integer, IdempotenceKey> rollbackKeys = self.rollbackTxPhase(tx.transactionId(), remoteRns);
            sendPhase2Network(rollbackKeys, MessageType.ROLLBACK_TX, new RollbackTransaction(tx.transactionId()));
        }
    }

    /**
     * §2.8.5: atomically saves coordinator state, validates/reserves locally, and logs
     * outbound NEW_TX messages — all in one DB transaction.
     */
    @Transactional
    Phase1Result prepareTxPhase(Transaction tx, Set<Integer> remoteRns) {
        saveCoordinatorState(tx, InterbankTransactionStatus.PREPARING);

        List<NoVoteReason> violations = doValidateAndReserve(tx);
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
        List<NoVoteReason> violations = doValidateAndReserve(tx);
        if (violations.isEmpty())
            return new TransactionVote(TransactionVote.Vote.YES, List.of());
        return new TransactionVote(TransactionVote.Vote.NO, violations);
    }

    @Transactional
    public void commitLocal(ForeignBankId transactionId) {
        InterbankTransaction ibTx = txRepo.findByTransactionRoutingNumberAndTransactionIdString(
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

        for (int i = 0; i < postings.size(); i++) {
            Posting p = postings.get(i);
            if (isPostingRemote(p)) continue;
            boolean isDebit = p.amount().compareTo(BigDecimal.ZERO) > 0;
            BigDecimal abs = p.amount().abs();

            if (p.asset() instanceof Asset.Monas && p.account() instanceof TxAccount.Account a) {
                reservationApplier.commitMonas(a.num(), abs, isDebit);

            } else if (p.asset() instanceof Asset.Stock s && p.account() instanceof TxAccount.Person pe) {
                String ticker = s.asset().ticker();
                // commitStock razresava listing po ticker-u u trading-service-u i
                // mapira odsustvo u "Listing not found: <ticker>" (→ InterbankProtocolException),
                // pa zaseban findListingByTicker pre-check ne treba — bio je redundantan
                // HTTP round-trip unutar @Transactional.
                Long userId = Long.parseLong(pe.id().id());
                reservationApplier.commitStock(
                        stockIdempotencyKey(transactionId, "commit", userId, "CLIENT", ticker, i),
                        userId, "CLIENT", ticker, abs.intValueExact(), isDebit);

            }
            // OptionAsset+Person posting (accept-shape) i Monas/Stock+Option posting
            // (exercise-shape) ne barataju nikakvim portfolio/account state-om u nasoj
            // banci ako smo seller (option pseudo-account je apstraktan); contract
            // status flip se desava ispod, posle petlje.
        }

        // Posle iteracije: ako je exercise-shape tx, oznaci kontaminirani contract
        // kao EXERCISED. Trazimo prvu (Stock, Option) posting i razresimo negotiationId
        // iz nje (TxAccount.Option.id() je negotiation id po §2.7.2).
        if (isExercise) {
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
                                .ifPresent(neg -> otcContractRepository
                                        .findBySourceNegotiationId(neg.getId())
                                        .ifPresent(contract -> {
                                            if (contract.getStatus() == InterbankOtcContractStatus.ACTIVE) {
                                                contract.setStatus(InterbankOtcContractStatus.EXERCISED);
                                                contract.setExercisedAt(LocalDateTime.now());
                                                otcContractRepository.save(contract);
                                            }
                                        }));
                    });
        }

        ibTx.setStatus(InterbankTransactionStatus.COMMITTED);
        ibTx.setCommittedAt(LocalDateTime.now());
        ibTx.setLastActivityAt(LocalDateTime.now());
        txRepo.save(ibTx);
    }

    @Transactional
    public void rollbackLocal(ForeignBankId transactionId) {
        InterbankTransaction ibTx = txRepo.findByTransactionRoutingNumberAndTransactionIdString(
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

            } else if (p.asset() instanceof Asset.Stock s && p.account() instanceof TxAccount.Person pe) {
                String ticker = s.asset().ticker();
                // releaseStock razresava listing po ticker-u u trading-service-u i
                // mapira odsustvo u "Listing not found: <ticker>" (→ InterbankProtocolException),
                // pa zaseban findListingByTicker pre-check ne treba — bio je redundantan
                // HTTP round-trip unutar @Transactional.
                Long userId = Long.parseLong(pe.id().id());
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

        Optional<String> cached = messageService.findCachedResponse(key);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get(), TransactionVote.class);
            } catch (JsonProcessingException e) {
                throw new InterbankExceptions.InterbankProtocolException(
                        "Failed to parse cached vote: " + e.getMessage());
            }
        }

        saveRecipientState(tx);

        List<NoVoteReason> violations = doValidateAndReserve(tx);
        TransactionVote vote;
        if (violations.isEmpty()) {
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
        } catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Failed to serialize handleNewTx response: " + e.getMessage());
        }

        return vote;
    }

    /**
     * §2.12.2: inbound COMMIT_TX handler. Atomically commits and caches the response.
     */
    @Transactional
    public void handleCommitTx(CommitTransaction body, IdempotenceKey key) {
        if (messageService.findCachedResponse(key).isPresent()) return;

        commitLocal(body.transactionId()); // direct call — joins this @Transactional

        try {
            messageService.recordInboundResponse(key, MessageType.COMMIT_TX,
                    objectMapper.writeValueAsString(body), 204, "",
                    body.transactionId().id());
        } catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Failed to serialize handleCommitTx response: " + e.getMessage());
        }
    }

    /**
     * §2.12.3: inbound ROLLBACK_TX handler. Atomically rolls back and caches the response.
     */
    @Transactional
    public void handleRollbackTx(RollbackTransaction body, IdempotenceKey key) {
        if (messageService.findCachedResponse(key).isPresent()) return;

        rollbackLocal(body.transactionId()); // direct call — joins this @Transactional

        try {
            messageService.recordInboundResponse(key, MessageType.ROLLBACK_TX,
                    objectMapper.writeValueAsString(body), 204, "",
                    body.transactionId().id());
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
                rn = routing.parseRoutingNumber(a.num());
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
            } catch (InterbankExceptions.InterbankCommunicationException e) {
                messageService.markOutboundFailed(key, e.getMessage());
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
            } catch (InterbankExceptions.InterbankCommunicationException e) {
                messageService.markOutboundFailed(key, e.getMessage());
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

    private void saveRecipientState(Transaction tx) {
        if (txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                tx.transactionId().routingNumber(), tx.transactionId().id()).isPresent()) {
            return;
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
            txRepo.save(ibt);
        } catch (JsonProcessingException e) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Failed to serialize transaction body: " + e.getMessage());
        }
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

    /**
     * Two-pass validate-and-reserve.
     * Pass 1: collect all violations (no DB writes).
     * Pass 2: make reservations only if Pass 1 found no violations.
     */
    private List<NoVoteReason> doValidateAndReserve(Transaction tx) {
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

            if (asset instanceof Asset.Monas m && account instanceof TxAccount.Account a) {
                Optional<Account> acctOpt = accountRepository.findByAccountNumber(a.num());
                if (acctOpt.isEmpty() || acctOpt.get().getStatus() != AccountStatus.ACTIVE) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.NO_SUCH_ACCOUNT, p));
                    continue;
                }
                if (isCredit) {
                    Account acct = acctOpt.get();
                    String postingCcy = m.asset().currency().name();
                    String accountCcy = acct.getCurrency().getCode();
                    if (!postingCcy.equals(accountCcy)) {
                        violations.add(new NoVoteReason(NoVoteReason.Reason.UNACCEPTABLE_ASSET, p));
                        continue;
                    }
                    if (acct.getAvailableBalance().compareTo(abs) < 0) {
                        violations.add(new NoVoteReason(NoVoteReason.Reason.INSUFFICIENT_ASSET, p));
                    }
                }

            } else if (asset instanceof Asset.Stock s && account instanceof TxAccount.Person pe) {
                Long userId;
                try {
                    userId = Long.parseLong(pe.id().id());
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

            } else if (asset instanceof Asset.Monas m && account instanceof TxAccount.Person pe) {
                // T2-B fix (Tim 1 cross-bank Stage A, 2026-05-20): Person+Monas
                // defenzivna grana — partner banka moze poslati MONAS leg sa
                // TxAccount.Person umesto TxAccount.Account kad nema otkrivene
                // konkretne brojeve racuna nase strane (spec §2.6: Person je
                // opaque foreign-bank-id koji receiver bank resolve-uje). isPostingRemote
                // je vec uklonio partner-side Person (line 605); ovde stizemo samo
                // za lokalne (myRouting) Person. Mirror Tim 1 P0.1 resolvePersonToAccount.
                Long ownerId;
                try {
                    String foreignId = pe.id().id();
                    if (foreignId != null && (foreignId.startsWith("C-") || foreignId.startsWith("E-"))) {
                        foreignId = foreignId.substring(2);
                    }
                    ownerId = Long.valueOf(foreignId);
                } catch (NumberFormatException ex) {
                    violations.add(new NoVoteReason(NoVoteReason.Reason.NO_SUCH_ACCOUNT, p));
                    continue;
                }
                String postingCcy = m.asset().currency().name();
                Optional<Account> resolved = accountRepository
                        .findByClientIdAndStatusOrderByAvailableBalanceDesc(ownerId, AccountStatus.ACTIVE)
                        .stream()
                        .filter(a -> a.getCurrency() != null
                                && postingCcy.equals(a.getCurrency().getCode()))
                        .findFirst();
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

                } else if (asset instanceof Asset.Monas m && account instanceof TxAccount.Person pe) {
                    // T2-B reservation pair: Person+Monas — resolve do 18-cifrenog
                    // racuna pa rezervisi normalno. validacija je vec prosla u Pass 1
                    // (NO_SUCH_ACCOUNT / INSUFFICIENT_ASSET) pa ovde garantovano postoji.
                    String foreignId = pe.id().id();
                    if (foreignId != null && (foreignId.startsWith("C-") || foreignId.startsWith("E-"))) {
                        foreignId = foreignId.substring(2);
                    }
                    Long ownerId = Long.valueOf(foreignId);
                    String postingCcy = m.asset().currency().name();
                    Account resolvedAcct = accountRepository
                            .findByClientIdAndStatusOrderByAvailableBalanceDesc(ownerId, AccountStatus.ACTIVE)
                            .stream()
                            .filter(acc -> acc.getCurrency() != null
                                    && postingCcy.equals(acc.getCurrency().getCode()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "Person+Monas reservation: missing account for ownerId=" + pe.id().id()
                                            + " currency=" + postingCcy + " (validation prosao ali resolve fail)"));
                    reservationApplier.reserveMonas(resolvedAcct.getAccountNumber(), abs);

                } else if (asset instanceof Asset.Stock s && account instanceof TxAccount.Person pe) {
                    Long userId = Long.parseLong(pe.id().id());
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
