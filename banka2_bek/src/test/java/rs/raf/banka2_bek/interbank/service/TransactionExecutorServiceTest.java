package rs.raf.banka2_bek.interbank.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2.contracts.internal.InternalListingDto;
import rs.raf.banka2.contracts.internal.InternalPortfolioHoldingDto;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TransactionExecutorService (§2.8 2PC coordinator + §2.12 inbound handlers).
 * The `self` proxy is replaced with a Mockito mock via ReflectionTestUtils so @Transactional
 * boundaries are exercised without a Spring context.
 */
@ExtendWith(MockitoExtension.class)
class TransactionExecutorServiceTest {

    @Mock private InterbankMessageService messageService;
    @Mock private InterbankClient client;
    @Mock private BankRoutingService routing;
    @Mock private InterbankTransactionRepository txRepo;
    @Mock private AccountRepository accountRepository;
    @Mock private InterbankReservationApplier reservationApplier;
    @Mock private TradingServiceInternalClient tradingServiceClient;
    @Mock private InterbankOtcNegotiationRepository otcNegotiationRepository;
    @Mock private InterbankOtcContractRepository otcContractRepository;
    @Mock private InterbankFxService interbankFxService;
    @Mock private InterbankInboundPaymentRecorder inboundPaymentRecorder;

    /** Self-proxy replaced with a mock so @Transactional sub-methods can be stubbed. */
    @Mock private TransactionExecutorService self;

    private TransactionExecutorService service;
    private ObjectMapper objectMapper;

    private static final int MY_RN     = 222;
    private static final int REMOTE_RN = 111;

    // Account numbers: prefix = routing number, padded to 9 chars for simplicity
    private static final String ACCT_A = MY_RN + "100001"; // local debit account
    private static final String ACCT_B = MY_RN + "100002"; // local credit account
    private static final String ACCT_REMOTE = REMOTE_RN + "900001";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        service = new TransactionExecutorService(
                messageService, client, routing, txRepo, objectMapper,
                accountRepository, reservationApplier, tradingServiceClient,
                otcNegotiationRepository, otcContractRepository, interbankFxService,
                inboundPaymentRecorder);

        ReflectionTestUtils.setField(service, "self", self);
        // P0-3: @Value polje za cross-currency inbound settlement bank lookup.
        ReflectionTestUtils.setField(service, "bankRegistrationNumber", "22200022");

        lenient().when(routing.myRoutingNumber()).thenReturn(MY_RN);

        // Parse routing number from account number prefix (first 3 digits)
        lenient().when(routing.parseRoutingNumber(any())).thenAnswer(inv -> {
            String num = (String) inv.getArgument(0);
            return Integer.parseInt(num.substring(0, 3));
        });

        // routingForAccount: za testne racune prefix == routing (isto kao parseRoutingNumber).
        // EXBanka 2 prefix->routing translacija (666->265) se zasebno pokriva u
        // BankRoutingServiceTest; ovde collectRemoteRoutingNumbers koristi routingForAccount.
        lenient().when(routing.routingForAccount(any())).thenAnswer(inv -> {
            String num = (String) inv.getArgument(0);
            return Integer.parseInt(num.substring(0, 3));
        });

        // isLocalAccount: true when account number starts with MY_RN
        lenient().when(routing.isLocalAccount(any())).thenAnswer(inv -> {
            String num = (String) inv.getArgument(0);
            return num.startsWith(String.valueOf(MY_RN));
        });
    }

    // =========================================================================
    // formTransaction
    // =========================================================================

    @Test
    @DisplayName("formTransaction: transactionId routingNumber equals our routing number")
    void formTransaction_routingNumberIsOurs() {
        Transaction tx = service.formTransaction(List.of(), "msg", "ref", "289", "transfer");
        assertThat(tx.transactionId().routingNumber()).isEqualTo(MY_RN);
    }

    @Test
    @DisplayName("formTransaction: each call produces a unique 64-char hex id")
    void formTransaction_uniqueIds() {
        Transaction tx1 = service.formTransaction(List.of(), null, null, null, null);
        Transaction tx2 = service.formTransaction(List.of(), null, null, null, null);
        assertThat(tx1.transactionId().id()).hasSize(64);
        assertThat(tx1.transactionId().id()).isNotEqualTo(tx2.transactionId().id());
    }

    @Test
    @DisplayName("formTransaction: postings and metadata are preserved")
    void formTransaction_preservesPostingsAndMetadata() {
        Posting p = new Posting(
                new TxAccount.Account(ACCT_A),
                BigDecimal.valueOf(100),
                new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)));
        Transaction tx = service.formTransaction(
                List.of(p), "my message", "ref-001", "289", "salary");
        assertThat(tx.postings()).containsExactly(p);
        assertThat(tx.message()).isEqualTo("my message");
    }

    // =========================================================================
    // execute — local-only path
    // =========================================================================

    @Test
    @DisplayName("execute local-only: YES vote → commitLocal called, no messageService/client interaction")
    void execute_localOnly_yesVote_commitsLocal() {
        Transaction tx = localMonasTx();
        stubTxSave();
        when(self.prepareLocal(tx)).thenReturn(yesVote());

        service.execute(tx);

        verify(self).prepareLocal(tx);
        verify(self).commitLocal(tx.transactionId());
        verify(self, never()).rollbackLocal(any());
        verifyNoInteractions(messageService, client);
    }

    @Test
    @DisplayName("execute local-only: NO vote → rollbackLocal called THEN aborts (throws), no messageService/client interaction")
    void execute_localOnly_noVote_rollsBackLocal() {
        // CONTRACT FIX (2PC atomicity): a NO vote is an ABORT and MUST be signalled to
        // the caller by THROWING. Pre-fix execute() returned silently on every abort
        // branch, so OtcNegotiationService.acceptReceivedNegotiation never ran its
        // compensation (negotiation stayed ACCEPTED, contract persisted, stock reserved)
        // while the money tx was rolled back — both banks left inconsistent and the
        // inbound endpoint wrongly returned 204 success. The abort throw is the
        // corrected contract; the local rollback still runs BEFORE the throw.
        Transaction tx = localMonasTx();
        stubTxSave();
        when(self.prepareLocal(tx)).thenReturn(noVote());

        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class);

        verify(self).prepareLocal(tx);
        verify(self).rollbackLocal(tx.transactionId());
        verify(self, never()).commitLocal(any());
        verifyNoInteractions(messageService, client);
    }

    @Test
    @DisplayName("execute local-only: saves INITIATOR/PREPARING coordinator record before prepareLocal")
    void execute_localOnly_savesCoordinatorStateFirst() {
        Transaction tx = localMonasTx();
        stubTxSave();
        when(self.prepareLocal(tx)).thenReturn(yesVote());

        service.execute(tx);

        ArgumentCaptor<InterbankTransaction> cap = ArgumentCaptor.forClass(InterbankTransaction.class);
        verify(txRepo).save(cap.capture());
        InterbankTransaction saved = cap.getValue();
        assertThat(saved.getRole()).isEqualTo(InterbankTransaction.InterbankTransactionRole.INITIATOR);
        assertThat(saved.getStatus()).isEqualTo(InterbankTransactionStatus.PREPARING);
        assertThat(saved.getTransactionRoutingNumber()).isEqualTo(MY_RN);
        assertThat(saved.getTransactionIdString()).isEqualTo(tx.transactionId().id());
        assertThat(saved.getTransactionBody()).isNotBlank();
    }

    // =========================================================================
    // execute — coordinator path
    // =========================================================================

    @Test
    @DisplayName("execute coordinator: all YES → commitTxPhase called + COMMIT_TX sent to remote")
    void execute_coordinator_allYes_commitsAndSendsCommit() {
        Transaction tx = mixedMonasTx();
        IdempotenceKey p1Key = new IdempotenceKey(MY_RN, "p1-key");
        IdempotenceKey commitKey = new IdempotenceKey(MY_RN, "commit-key");

        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(
                phase1Yes(p1Key, tx));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), eq(TransactionVote.class)))
                .thenReturn(yesVote());
        when(self.commitTxPhase(tx.transactionId(), Set.of(REMOTE_RN)))
                .thenReturn(Map.of(REMOTE_RN, commitKey));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.COMMIT_TX), any(), eq(Void.class)))
                .thenReturn(null);

        service.execute(tx);

        verify(self).commitTxPhase(tx.transactionId(), Set.of(REMOTE_RN));
        verify(self, never()).rollbackTxPhase(any(), any());
        verify(client).sendMessage(eq(REMOTE_RN), eq(MessageType.COMMIT_TX), any(), eq(Void.class));
        verify(messageService).markOutboundSent(eq(commitKey), eq(204), isNull());
    }

    @Test
    @DisplayName("1537: phase-2 COMMIT_TX throws InterbankAuthException (partner 401) → "
            + "execute() does NOT propagate (fire-and-forget); message marked failed for retransmit")
    void execute_coordinator_phase2AuthException_doesNotPropagate() {
        // 1537 — phase-2 mora biti fire-and-forget. Pre fix-a sendPhase2Network je
        // hvatao SAMO InterbankCommunicationException; InterbankAuthException (partner
        // 401) je propagirao iz execute() POSLE lokalnog commit-a → pozivalac
        // (acceptReceivedNegotiation / OTC saga) je usao u catch→compensate i
        // rollback-ovao pregovor iako je novac vec lokalno pomeren (divergencija).
        Transaction tx = mixedMonasTx();
        IdempotenceKey p1Key = new IdempotenceKey(MY_RN, "p1-401");
        IdempotenceKey commitKey = new IdempotenceKey(MY_RN, "commit-401");

        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(phase1Yes(p1Key, tx));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), eq(TransactionVote.class)))
                .thenReturn(yesVote());
        when(self.commitTxPhase(tx.transactionId(), Set.of(REMOTE_RN)))
                .thenReturn(Map.of(REMOTE_RN, commitKey));
        // Phase-2 COMMIT_TX → partner 401 (auth), NE communication exception.
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.COMMIT_TX), any(), eq(Void.class)))
                .thenThrow(new InterbankExceptions.InterbankAuthException("Invalid API key for routing 111."));

        // Kljucna invarijanta: NE sme da baci — lokalni commit je vec primenjen,
        // execute() mora da se zavrsi cisto (recipient ce ishod primiti kroz retransmit).
        service.execute(tx);

        // Poruka je oznacena failed (ostaje PENDING za §2.9 retransmisiju), ne SENT.
        verify(messageService).markOutboundFailed(eq(commitKey), contains("Invalid API key"));
        verify(messageService, never()).markOutboundSent(eq(commitKey), anyInt(), any());
    }

    @Test
    @DisplayName("1537 + abort: phase-2 ROLLBACK_TX send throws → that send-error does NOT propagate "
            + "(marked failed for retransmit), but the abort itself still throws InterbankTransactionAbortedException")
    void execute_coordinator_phase2ProtocolException_doesNotPropagate() {
        // Two distinct concerns coexist here:
        //  (1) 1537 fire-and-forget — a phase-2 SEND failure (partner 4xx/5xx) must NOT
        //      propagate; it is marked failed for §2.9 retransmit and swallowed.
        //  (2) 2PC atomicity — because the partner voted NO at phase-1, this is an ABORT,
        //      so execute() MUST signal failure to the caller by throwing the abort
        //      exception (AFTER local rollback + best-effort ROLLBACK_TX dispatch).
        // The thrown type is the ABORT exception, NOT the swallowed phase-2 send error.
        Transaction tx = mixedMonasTx();
        IdempotenceKey p1Key = new IdempotenceKey(MY_RN, "p1-proto");
        IdempotenceKey rbKey = new IdempotenceKey(MY_RN, "rb-proto");

        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(phase1Yes(p1Key, tx));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), eq(TransactionVote.class)))
                .thenReturn(noVote());
        when(self.rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN)))
                .thenReturn(Map.of(REMOTE_RN, rbKey));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.ROLLBACK_TX), any(), eq(Void.class)))
                .thenThrow(new InterbankExceptions.InterbankProtocolException("malformed"));

        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class)
                // the swallowed phase-2 send error must NOT be the surfaced message
                .hasMessageNotContaining("malformed");

        // phase-2 send-error was swallowed and the message marked failed for retransmit.
        verify(messageService).markOutboundFailed(eq(rbKey), contains("malformed"));
    }

    @Test
    @DisplayName("execute coordinator: remote votes NO → rollbackTxPhase called + ROLLBACK_TX sent THEN aborts (throws)")
    void execute_coordinator_remoteNo_rollsBackAndSendsRollback() {
        // CONTRACT FIX (2PC atomicity): a partner NO vote is an ABORT — execute() MUST
        // throw so the caller's compensation runs. The throw happens ONLY AFTER the
        // local rollback (rollbackTxPhase → rollbackLocal sets ROLLED_BACK) and AFTER
        // ROLLBACK_TX has been dispatched to the remote.
        Transaction tx = mixedMonasTx();
        IdempotenceKey p1Key = new IdempotenceKey(MY_RN, "p1-key");
        IdempotenceKey rbKey = new IdempotenceKey(MY_RN, "rb-key");

        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(phase1Yes(p1Key, tx));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), eq(TransactionVote.class)))
                .thenReturn(noVote());
        when(self.rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN)))
                .thenReturn(Map.of(REMOTE_RN, rbKey));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.ROLLBACK_TX), any(), eq(Void.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class);

        // Rollback ran and ROLLBACK_TX was dispatched BEFORE the abort throw.
        verify(self).rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN));
        verify(self, never()).commitTxPhase(any(), any());
        verify(client).sendMessage(eq(REMOTE_RN), eq(MessageType.ROLLBACK_TX), any(), eq(Void.class));
    }

    @Test
    @DisplayName("exercise-400: partner NO with reasons → abort message carries the concrete "
            + "protocol reason (not the misleading 'network/auth failure treated as NO')")
    void execute_coordinator_partnerNoWithReasons_messageCarriesReason() {
        // exercise-400 fix: kad partner vrati dobro-formiran protokol NO (http 200) sa
        // reasons (npr. OPTION_USED_OR_EXPIRED), abort poruka MORA da nosi taj razlog kroz
        // " reasons=[...]" da bi wrapper handler korisniku vratio akcionabilan 409.
        Transaction tx = mixedMonasTx();
        IdempotenceKey p1Key = new IdempotenceKey(MY_RN, "p1-key");
        IdempotenceKey rbKey = new IdempotenceKey(MY_RN, "rb-key");

        TransactionVote partnerNo = new TransactionVote(TransactionVote.Vote.NO,
                List.of(new NoVoteReason(NoVoteReason.Reason.OPTION_USED_OR_EXPIRED, null)));

        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(phase1Yes(p1Key, tx));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), eq(TransactionVote.class)))
                .thenReturn(partnerNo);
        when(self.rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN)))
                .thenReturn(Map.of(REMOTE_RN, rbKey));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.ROLLBACK_TX), any(), eq(Void.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class)
                .hasMessageContaining("OPTION_USED_OR_EXPIRED")
                // misleading network/auth phrasing MUST NOT be used for an explicit protocol NO
                .hasMessageNotContaining("network/auth failure treated as NO");
    }

    @Test
    @DisplayName("execute coordinator: prepareTxPhase returns NO → no network calls at all THEN aborts (throws)")
    void execute_coordinator_phase1No_noNetworkCalls() {
        // CONTRACT FIX (2PC atomicity): local-prepare NO is an ABORT. prepareTxPhase has
        // already set the InterbankTransaction status to ROLLED_BACK (no local reservation
        // was made — two-pass validate-and-reserve found violations in pass 1, so pass 2
        // never ran). execute() must throw so the caller compensates; no network calls.
        Transaction tx = mixedMonasTx();
        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(
                new TransactionExecutorService.Phase1Result(noVote(), Map.of(), Map.of()));

        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class);

        verify(self, never()).commitTxPhase(any(), any());
        verify(self, never()).rollbackTxPhase(any(), any());
        verifyNoInteractions(client, messageService);
    }

    @Test
    @DisplayName("execute coordinator: remote returns null (202 Accepted) → treated as NO → rollback")
    void execute_coordinator_remote202_treatedAsNo() {
        Transaction tx = mixedMonasTx();
        IdempotenceKey p1Key = new IdempotenceKey(MY_RN, "key-202");

        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(phase1Yes(p1Key, tx));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), eq(TransactionVote.class)))
                .thenReturn(null);
        when(self.rollbackTxPhase(any(), any())).thenReturn(Map.of());

        // 202 → treated as NO → abort → execute() throws after rollback.
        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class);

        verify(self).rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN));
        verify(messageService).markOutboundSent(eq(p1Key), eq(202), isNull());
    }

    @Test
    @DisplayName("execute coordinator: communication exception → treated as NO → rollback")
    void execute_coordinator_communicationException_rollsBack() {
        Transaction tx = mixedMonasTx();
        IdempotenceKey p1Key = new IdempotenceKey(MY_RN, "key-err");

        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(phase1Yes(p1Key, tx));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), eq(TransactionVote.class)))
                .thenThrow(new InterbankExceptions.InterbankCommunicationException("timeout"));
        when(self.rollbackTxPhase(any(), any())).thenReturn(Map.of());

        // Comm failure → treated as NO → abort → execute() throws after rollback.
        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class);

        verify(self).rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN));
        // Facet 1: faza-1 NEW_TX je definitivno abortovana (mrezna greska tretirana kao
        // NO → lokalni rollback). Outbound NEW_TX red se TERMINALIZUJE (markOutboundAborted),
        // a NE ostavlja PENDING — scheduler ne sme da re-salje NEW_TX za abortovanu tx.
        verify(messageService).markOutboundAborted(eq(p1Key), contains("timeout"));
        verify(messageService, never()).markOutboundFailed(eq(p1Key), any());
    }

    @Test
    @DisplayName("execute coordinator: partner 401 (InterbankAuthException) → treated as NO → "
            + "rollbackTxPhase called so sender reservation is released (P1-5)")
    void execute_coordinator_authException_rollsBackAndReleasesReservation() {
        // P1-5: InterbankClient.sendMessage baca InterbankAuthException (NE
        // InterbankCommunicationException) na partner 401. Pre fix-a, ovaj izuzetak
        // je propagirao neuhvacen iz execute() → rollbackTxPhase/rollbackLocal se
        // nikad nije pozvao → senderova rezervacija (commit-ovana u prepareTxPhase)
        // ostala zakljucana zauvek. Posle fix-a, 401 se tretira kao NO glas →
        // rollbackTxPhase se poziva (koji interno radi rollbackLocal → releaseMonas).
        Transaction tx = mixedMonasTx();
        IdempotenceKey p1Key = new IdempotenceKey(MY_RN, "key-401");

        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(phase1Yes(p1Key, tx));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), eq(TransactionVote.class)))
                .thenThrow(new InterbankExceptions.InterbankAuthException(
                        "Invalid API key for routing " + REMOTE_RN + "."));
        when(self.rollbackTxPhase(any(), any())).thenReturn(Map.of());

        // Partner 401 → treated as NO → abort → execute() throws after rollback.
        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class);

        // Kljucna invarijanta: rollbackTxPhase (→ rollbackLocal → releaseMonas) MORA
        // biti pozvan, inace rezervacija ostaje zakljucana.
        verify(self).rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN));
        verify(self, never()).commitTxPhase(any(), any());
        // Facet 1: NEW_TX outbound red se TERMINALIZUJE na abort (markOutboundAborted),
        // ne ostaje PENDING.
        verify(messageService).markOutboundAborted(eq(p1Key), contains("Invalid API key"));
        verify(messageService, never()).markOutboundFailed(eq(p1Key), any());
    }

    @Test
    @DisplayName("execute coordinator: partner 401 then rollbackLocal releases sender reservation "
            + "end-to-end — availableBalance restored, status ROLLED_BACK (P1-5)")
    void execute_coordinator_authException_endToEndReleasesReservation() throws Exception {
        // P1-5 end-to-end: ovaj test ne mockuje rollbackLocal, vec pusta pravi kod
        // da odradi rollbackLocal → releaseMonas (kroz self.rollbackTxPhase delegaciju
        // na realnu instancu). Time dokazujemo da senderova rezervacija stvarno biva
        // oslobodjena (releaseMonas pozvan) i da status zavrsi kao ROLLED_BACK kad
        // partner vrati 401. Sender (kreditni, -amount) leg MORA biti lokalan da bi
        // rollbackLocal oslobodio rezervaciju (rollbackLocal oslobadja samo lokalne
        // credit postings). Local sender ACCT_A (-100), remote receiver (+100).
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Account(ACCT_A), BigDecimal.valueOf(-100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD))),
                new Posting(new TxAccount.Account(ACCT_REMOTE), BigDecimal.valueOf(100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)))
        ), new ForeignBankId(MY_RN, "tx-401-e2e"), null, null, null, null);
        IdempotenceKey p1Key = new IdempotenceKey(MY_RN, "key-401-e2e");
        IdempotenceKey rbKey = new IdempotenceKey(MY_RN, "rb-key-401");

        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(phase1Yes(p1Key, tx));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), eq(TransactionVote.class)))
                .thenThrow(new InterbankExceptions.InterbankAuthException("Invalid API key for routing 111."));
        // Pusti rollbackTxPhase da pozove pravi rollbackLocal preko self → delegira na
        // realnu instancu (service).
        when(self.rollbackTxPhase(eq(tx.transactionId()), eq(Set.of(REMOTE_RN))))
                .thenAnswer(inv -> service.rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN)));

        // rollbackLocal cita ibTx iz repo-a (PREPARED, sa rezervacijom) i oslobadja je.
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        when(messageService.generateKey()).thenReturn(rbKey);
        stubTxSave();

        // Abort → throws. The terminal-state guarantee is the key assertion: by the time
        // the caller's catch sees the throw, rollbackLocal has already released the sender
        // reservation AND the InterbankTransaction is ROLLED_BACK (so any caller that reads
        // status post-catch — e.g. InterbankPaymentAsyncService — sees the terminal state).
        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class);

        // Lokalni credit (sender) posting je ACCT_A (amount -100). rollbackLocal
        // MORA osloboditi tu rezervaciju — to je kljucna P1-5 invarijanta.
        verify(reservationApplier).releaseMonas(eq(ACCT_A), eq(BigDecimal.valueOf(100)));
        assertThat(ibt.getStatus()).isEqualTo(InterbankTransactionStatus.ROLLED_BACK);
    }

    // =========================================================================
    // execute — abort signalling (2PC atomicity contract)
    // =========================================================================

    @Test
    @DisplayName("ABORT contract: local-only NO end-to-end → throws AbortedException AND "
            + "InterbankTransaction ends ROLLED_BACK (terminal state visible after the throw)")
    void execute_localOnly_no_throwsAndEndsRolledBack() throws Exception {
        // End-to-end (real prepareLocal/rollbackLocal via self → real instance): a
        // local-only NO vote must (a) throw the abort exception, and (b) leave the
        // coordinator record ROLLED_BACK so a caller reading status in its catch sees
        // the terminal state. ACCT_B (credit, -100) has only 50 → INSUFFICIENT_ASSET NO.
        Transaction tx = localMonasTx();
        when(accountRepository.findByAccountNumber(ACCT_A))
                .thenReturn(Optional.of(buildAccount(ACCT_A, "RSD", BigDecimal.ZERO, AccountStatus.ACTIVE)));
        when(accountRepository.findByAccountNumber(ACCT_B))
                .thenReturn(Optional.of(buildAccount(ACCT_B, "RSD", BigDecimal.valueOf(50), AccountStatus.ACTIVE)));
        // Real prepareLocal + rollbackLocal through self.
        when(self.prepareLocal(tx)).thenAnswer(inv -> service.prepareLocal(tx));
        doAnswer(inv -> { service.rollbackLocal(tx.transactionId()); return null; })
                .when(self).rollbackLocal(tx.transactionId());
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class);

        // Terminal state guarantee after the throw.
        assertThat(ibt.getStatus()).isEqualTo(InterbankTransactionStatus.ROLLED_BACK);
        verify(self, never()).commitLocal(any());
    }

    @Test
    @DisplayName("ABORT contract: promoted-coordinator partner NO end-to-end → throws AbortedException, "
            + "ROLLBACK_TX dispatched, InterbankTransaction ends ROLLED_BACK")
    void execute_coordinator_partnerNo_throwsRollsBackAndSendsRollback_endToEnd() throws Exception {
        // Real rollbackTxPhase → rollbackLocal via self. Local sender ACCT_A (-100),
        // remote receiver (+100). Partner votes NO → coordinator must roll back locally
        // (releaseMonas + ROLLED_BACK), dispatch ROLLBACK_TX, THEN throw the abort.
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Account(ACCT_A), BigDecimal.valueOf(-100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD))),
                new Posting(new TxAccount.Account(ACCT_REMOTE), BigDecimal.valueOf(100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)))
        ), new ForeignBankId(MY_RN, "tx-partner-no-e2e"), null, null, null, null);
        IdempotenceKey p1Key = new IdempotenceKey(MY_RN, "p1-no-e2e");
        IdempotenceKey rbKey = new IdempotenceKey(MY_RN, "rb-no-e2e");

        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(phase1Yes(p1Key, tx));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), eq(TransactionVote.class)))
                .thenReturn(noVote());
        when(self.rollbackTxPhase(eq(tx.transactionId()), eq(Set.of(REMOTE_RN))))
                .thenAnswer(inv -> service.rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN)));
        when(client.sendMessage(eq(REMOTE_RN), eq(MessageType.ROLLBACK_TX), any(), eq(Void.class)))
                .thenReturn(null);
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        when(messageService.generateKey()).thenReturn(rbKey);
        stubTxSave();

        assertThatThrownBy(() -> service.execute(tx))
                .isInstanceOf(InterbankExceptions.InterbankTransactionAbortedException.class);

        // Local rollback released the sender reservation, ROLLBACK_TX was dispatched,
        // and the record is terminal — all BEFORE the abort surfaced.
        verify(reservationApplier).releaseMonas(eq(ACCT_A), eq(BigDecimal.valueOf(100)));
        verify(client).sendMessage(eq(REMOTE_RN), eq(MessageType.ROLLBACK_TX), any(), eq(Void.class));
        assertThat(ibt.getStatus()).isEqualTo(InterbankTransactionStatus.ROLLED_BACK);
    }

    // =========================================================================
    // prepareTxPhase
    // =========================================================================

    @Test
    @DisplayName("prepareTxPhase: saves coordinator record, reserves, and logs outbound atomically")
    void prepareTxPhase_allYes_savesAndLogsAndReserves() throws Exception {
        Transaction tx = localMonasTx();
        stubTxSave();
        stubMonasAccounts("RSD");
        IdempotenceKey key = new IdempotenceKey(MY_RN, "prep-key");
        when(messageService.generateKey()).thenReturn(key);
        when(messageService.recordOutbound(any(), anyInt(), any(), any(), any())).thenReturn(null);

        TransactionExecutorService.Phase1Result result =
                service.prepareTxPhase(tx, Set.of(REMOTE_RN));

        assertThat(result.vote().vote()).isEqualTo(TransactionVote.Vote.YES);
        assertThat(result.keys()).containsKey(REMOTE_RN);
        assertThat(result.envelopes()).containsKey(REMOTE_RN);
        verify(txRepo).save(any(InterbankTransaction.class));
        verify(reservationApplier).reserveMonas(eq(ACCT_B), eq(BigDecimal.valueOf(100)));
        verify(messageService).recordOutbound(eq(key), eq(REMOTE_RN), eq(MessageType.NEW_TX), any(), any());
    }

    @Test
    @DisplayName("prepareTxPhase: violations found → NO vote, no recordOutbound calls")
    void prepareTxPhase_violations_noVoteAndNoOutbound() throws Exception {
        Transaction tx = unbalancedTx();
        stubTxSave();

        TransactionExecutorService.Phase1Result result =
                service.prepareTxPhase(tx, Set.of(REMOTE_RN));

        assertThat(result.vote().vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(result.keys()).isEmpty();
        assertThat(result.envelopes()).isEmpty();
        verify(messageService, never()).recordOutbound(any(), anyInt(), any(), any(), any());
    }

    // =========================================================================
    // prepareLocal
    // =========================================================================

    @Test
    @DisplayName("prepareLocal: balanced MONAS tx → YES + reserveMonas called for credit posting")
    void prepareLocal_balanced_yesVote_reservesMonas() {
        stubMonasAccounts("RSD");

        TransactionVote vote = service.prepareLocal(localMonasTx());

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
        verify(reservationApplier).reserveMonas(eq(ACCT_B), eq(BigDecimal.valueOf(100)));
        verify(reservationApplier, never()).reserveMonas(eq(ACCT_A), any());
    }

    @Test
    @DisplayName("prepareLocal: unbalanced postings → NO + UNBALANCED_TX, no repo calls")
    void prepareLocal_unbalanced_noVote() {
        TransactionVote vote = service.prepareLocal(unbalancedTx());

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).extracting(NoVoteReason::reason)
                .containsExactly(NoVoteReason.Reason.UNBALANCED_TX);
        verifyNoInteractions(accountRepository, reservationApplier);
    }

    @Test
    @DisplayName("prepareLocal: account not found → NO + NO_SUCH_ACCOUNT")
    void prepareLocal_accountNotFound_noVote() {
        when(accountRepository.findByAccountNumber(any())).thenReturn(Optional.empty());

        TransactionVote vote = service.prepareLocal(localMonasTx());

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.NO_SUCH_ACCOUNT));
    }

    @Test
    @DisplayName("prepareLocal: account INACTIVE → NO + NO_SUCH_ACCOUNT")
    void prepareLocal_accountInactive_noVote() {
        Account inactive = buildAccount(ACCT_A, "RSD", BigDecimal.ZERO, AccountStatus.INACTIVE);
        when(accountRepository.findByAccountNumber(ACCT_A)).thenReturn(Optional.of(inactive));
        when(accountRepository.findByAccountNumber(ACCT_B))
                .thenReturn(Optional.of(buildAccount(ACCT_B, "RSD", BigDecimal.valueOf(500), AccountStatus.ACTIVE)));

        TransactionVote vote = service.prepareLocal(localMonasTx());

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.NO_SUCH_ACCOUNT));
    }

    @Test
    @DisplayName("prepareLocal: MONAS credit currency mismatch → NO + UNACCEPTABLE_ASSET")
    void prepareLocal_currencyMismatch_noVote() {
        // Posting is RSD but account ACCT_B is EUR
        when(accountRepository.findByAccountNumber(ACCT_A))
                .thenReturn(Optional.of(buildAccount(ACCT_A, "RSD", BigDecimal.ZERO, AccountStatus.ACTIVE)));
        when(accountRepository.findByAccountNumber(ACCT_B))
                .thenReturn(Optional.of(buildAccount(ACCT_B, "EUR", BigDecimal.valueOf(500), AccountStatus.ACTIVE)));

        TransactionVote vote = service.prepareLocal(localMonasTx()); // posting currency = RSD

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.UNACCEPTABLE_ASSET));
    }

    @Test
    @DisplayName("prepareLocal: MONAS credit insufficient balance → NO + INSUFFICIENT_ASSET")
    void prepareLocal_insufficientBalance_noVote() {
        // ACCT_B has only 50, but credit posting requires 100
        when(accountRepository.findByAccountNumber(ACCT_A))
                .thenReturn(Optional.of(buildAccount(ACCT_A, "RSD", BigDecimal.ZERO, AccountStatus.ACTIVE)));
        when(accountRepository.findByAccountNumber(ACCT_B))
                .thenReturn(Optional.of(buildAccount(ACCT_B, "RSD", BigDecimal.valueOf(50), AccountStatus.ACTIVE)));

        TransactionVote vote = service.prepareLocal(localMonasTx());

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.INSUFFICIENT_ASSET));
    }

    @Test
    @DisplayName("prepareLocal: MONAS on Person account with no resolvable account → NO + NO_SUCH_ACCOUNT")
    void prepareLocal_monasOnPerson_noVote() {
        // Person+Monas is a valid shape per spec §2.6 (Tim 1 P0.1 mirror): resolver
        // tries to locate the holder's account by clientId from the foreign id and
        // currency. With no account mocked the resolver returns empty → NO_SUCH_ACCOUNT.
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "99")),
                        BigDecimal.valueOf(100), new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD))),
                new Posting(new TxAccount.Account(ACCT_B),
                        BigDecimal.valueOf(-100), new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)))
        ), new ForeignBankId(MY_RN, "tx-bad"), null, null, null, null);
        when(accountRepository.findByAccountNumber(ACCT_B))
                .thenReturn(Optional.of(buildAccount(ACCT_B, "RSD", BigDecimal.valueOf(500), AccountStatus.ACTIVE)));

        TransactionVote vote = service.prepareLocal(tx);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.NO_SUCH_ACCOUNT));
    }

    @Test
    @DisplayName("prepareLocal: STOCK on Account → NO + UNACCEPTABLE_ASSET")
    void prepareLocal_stockOnAccount_noVote() {
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Account(ACCT_A),
                        BigDecimal.valueOf(10), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Account(ACCT_B),
                        BigDecimal.valueOf(-10), new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(MY_RN, "tx-stock-acct"), null, null, null, null);
        TransactionVote vote = service.prepareLocal(tx);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.UNACCEPTABLE_ASSET));
    }

    @Test
    @DisplayName("prepareLocal: OPTION posting → NO + OPTION_NEGOTIATION_NOT_FOUND")
    void prepareLocal_optionPosting_noVote() {
        OptionDescription opt = new OptionDescription(
                new ForeignBankId(MY_RN, "neg-1"), new StockDescription("AAPL"),
                new MonetaryValue(CurrencyCode.USD, BigDecimal.valueOf(150)), null, BigDecimal.valueOf(5));
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(new ForeignBankId(MY_RN, "neg-1")),
                        BigDecimal.valueOf(5), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Option(new ForeignBankId(MY_RN, "neg-1")),
                        BigDecimal.valueOf(-5), new Asset.OptionAsset(opt))
        ), new ForeignBankId(MY_RN, "tx-opt"), null, null, null, null);

        TransactionVote vote = service.prepareLocal(tx);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).allSatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.OPTION_NEGOTIATION_NOT_FOUND));
    }

    @Test
    @DisplayName("prepareLocal: ticker not found → NO + NO_SUCH_ASSET")
    void prepareLocal_tickerNotFound_noVote() {
        when(tradingServiceClient.findListingByTicker(any())).thenReturn(Optional.empty());

        TransactionVote vote = service.prepareLocal(localStockTx());

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.NO_SUCH_ASSET));
    }

    @Test
    @DisplayName("prepareLocal: STOCK credit — portfolio not found → NO + NO_SUCH_ASSET")
    void prepareLocal_portfolioMissing_noVote() {
        when(tradingServiceClient.findListingByTicker("AAPL"))
                .thenReturn(Optional.of(listingDto(1L, "AAPL")));
        when(tradingServiceClient.findHolding(anyLong(), any(), eq("AAPL")))
                .thenReturn(holdingMissing("AAPL"));

        TransactionVote vote = service.prepareLocal(localStockTx());

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.NO_SUCH_ASSET));
    }

    @Test
    @DisplayName("prepareLocal: STOCK credit — insufficient quantity → NO + INSUFFICIENT_ASSET")
    void prepareLocal_insufficientStock_noVote() {
        when(tradingServiceClient.findListingByTicker("AAPL"))
                .thenReturn(Optional.of(listingDto(1L, "AAPL")));
        when(tradingServiceClient.findHolding(anyLong(), any(), eq("AAPL")))
                .thenReturn(holding(1L, "AAPL", 3)); // needs 10, has 3

        TransactionVote vote = service.prepareLocal(localStockTx());

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.INSUFFICIENT_ASSET));
    }

    @Test
    @DisplayName("prepareLocal: STOCK YES → reserveStock called via trading-service seam with correct args")
    void prepareLocal_stockYes_reservesStock() {
        when(tradingServiceClient.findListingByTicker("AAPL"))
                .thenReturn(Optional.of(listingDto(7L, "AAPL")));
        when(tradingServiceClient.findHolding(anyLong(), any(), eq("AAPL")))
                .thenReturn(holding(7L, "AAPL", 20)); // has 20, needs 10

        TransactionVote vote = service.prepareLocal(localStockTx());

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
        // credit posting: person 42 gives 10 AAPL — reserveStock via seam (idempotency key + ticker)
        verify(reservationApplier).reserveStock(anyString(), eq(42L), eq("CLIENT"), eq("AAPL"), eq(10));
    }

    // ── BUG-2: Stock+Person leg sa "C-"/"E-" prefiksiranim Person id-em ────────
    // §2.6 — nasi Person id-evi su "C-<n>" (klijent) / "E-<n>" (zaposleni). Stock+Person
    // grane su ranije zvale Long.parseLong("C-7") DIREKTNO → NumberFormatException →
    // prepare NO sa NO_SUCH_ACCOUNT → buyer stock-delivery exercise je pucao. Fix:
    // parsePersonOwnerId strip-uje prefiks (kao sto Monas+Person grane vec rade).

    /** Stock+Person tx gde credit (delivery) leg nosi "C-"-prefiksiran Person id. */
    private Transaction localStockTxPrefixed(String creditPersonId) {
        return new Transaction(List.of(
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "C-99")),
                        BigDecimal.valueOf(10), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, creditPersonId)),
                        BigDecimal.valueOf(-10), new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(MY_RN, "local-stock-prefixed-1"), null, null, null, null);
    }

    @Test
    @DisplayName("BUG-2 prepareLocal: Stock+Person id \"C-7\" → owner 7 resolved (no NumberFormatException), reserveStock(7)")
    void prepareLocal_stockPersonPrefixedId_resolvesOwner() {
        when(tradingServiceClient.findListingByTicker("AAPL"))
                .thenReturn(Optional.of(listingDto(7L, "AAPL")));
        when(tradingServiceClient.findHolding(eq(7L), any(), eq("AAPL")))
                .thenReturn(holding(7L, "AAPL", 20));

        TransactionVote vote = service.prepareLocal(localStockTxPrefixed("C-7"));

        // PRE fix: Long.parseLong("C-7") → NumberFormatException → NO + NO_SUCH_ACCOUNT.
        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
        // Owner id 7 (prefiks skinut), holding lookup i reserveStock gadjaju 7 (ne pad).
        verify(tradingServiceClient).findHolding(eq(7L), any(), eq("AAPL"));
        verify(reservationApplier).reserveStock(anyString(), eq(7L), eq("CLIENT"), eq("AAPL"), eq(10));
    }

    @Test
    @DisplayName("BUG-2 commitLocal: Stock+Person id \"C-7\" → commitStock(7) (no prefix-parse crash)")
    void commitLocal_stockPersonPrefixedId_resolvesOwner() throws Exception {
        Transaction tx = localStockTxPrefixed("C-7");
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.commitLocal(tx.transactionId());

        // PRE fix: Long.parseLong("C-7") → NumberFormatException u commit grani.
        // Debit leg (C-99, +10) i credit leg (C-7, -10) oba commit-uju stock pod owner-om bez prefiksa.
        verify(reservationApplier).commitStock(anyString(), eq(99L), eq("CLIENT"), eq("AAPL"), eq(10), eq(true));
        verify(reservationApplier).commitStock(anyString(), eq(7L), eq("CLIENT"), eq("AAPL"), eq(10), eq(false));
    }

    @Test
    @DisplayName("BUG-2 rollbackLocal: Stock+Person id \"C-7\" → releaseStock(7) (no prefix-parse crash)")
    void rollbackLocal_stockPersonPrefixedId_resolvesOwner() throws Exception {
        Transaction tx = localStockTxPrefixed("C-7");
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.rollbackLocal(tx.transactionId());

        // Rollback oslobadja samo credit (reserved) leg → C-7 → owner 7. PRE fix: parse crash.
        verify(reservationApplier).releaseStock(anyString(), eq(7L), eq("CLIENT"), eq("AAPL"), eq(10));
    }

    // ── BUG-2 [WRONG PARTY CHARGED / IDOR]: role-aware Person id resolucija ────
    // §2.6 — Person id "C-<n>" (klijent) i "E-<n>" (zaposleni) dele numericki prostor.
    // resolveLocalAccountDeterministic je RANIJE skidao prefiks i slepo zvao
    // findByClientId(n) → "E-3" (zaposleni) se razresavao na racun KLIJENTA "C-3"
    // (tudje lice). Fix: role-aware — "C-" → findByClientId; "E-" → nista (empty).

    @SuppressWarnings("unchecked")
    private Optional<Account> invokeResolve(String role, Long ownerId, String ccy) throws Exception {
        java.lang.reflect.Method m = TransactionExecutorService.class.getDeclaredMethod(
                "resolveLocalAccountDeterministic", String.class, Long.class, String.class);
        m.setAccessible(true);
        return (Optional<Account>) m.invoke(service, role, ownerId, ccy);
    }

    @Test
    @DisplayName("BUG-2: \"C-3\" (CLIENT role) → resolve-uje racun klijenta 3")
    void resolveLocalAccount_clientRole_resolvesClientAccount() throws Exception {
        Account c3 = buildAccount(MY_RN + "300001", "USD", BigDecimal.valueOf(5000), AccountStatus.ACTIVE);
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(eq(3L), eq(AccountStatus.ACTIVE)))
                .thenReturn(List.of(c3));

        Optional<Account> resolved = invokeResolve("CLIENT", 3L, "USD");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getAccountNumber()).isEqualTo(MY_RN + "300001");
    }

    @Test
    @DisplayName("BUG-2 [IDOR]: \"E-3\" (EMPLOYEE role) → NE resolve-uje racun klijenta 3 (empty, findByClientId NIKAD ne zvano)")
    void resolveLocalAccount_employeeRole_resolvesNothing() throws Exception {
        // Ako bi se zvao findByClientId(3), vratio bi racun KLIJENTA 3 — tudje lice.
        // Role-aware fix: za EMPLOYEE uopste ne pitamo klijentske racune → empty.
        Optional<Account> resolved = invokeResolve("EMPLOYEE", 3L, "USD");

        assertThat(resolved).isEmpty();
        verify(accountRepository, never()).findByClientIdAndStatusOrderByAvailableBalanceDesc(eq(3L), any());
    }

    @Test
    @DisplayName("BUG-2: resolvePersonOwner — \"C-3\"→(CLIENT,3), \"E-3\"→(EMPLOYEE,3), \"3\"→(null,3)")
    void resolvePersonOwner_parsesRoleAndId() throws Exception {
        java.lang.reflect.Method m = TransactionExecutorService.class.getDeclaredMethod(
                "resolvePersonOwner", String.class);
        m.setAccessible(true);

        Object c3 = m.invoke(null, "C-3");
        Object e3 = m.invoke(null, "E-3");
        Object plain = m.invoke(null, "3");

        java.lang.reflect.Method roleM = c3.getClass().getDeclaredMethod("role");
        java.lang.reflect.Method idM = c3.getClass().getDeclaredMethod("id");
        roleM.setAccessible(true);
        idM.setAccessible(true);

        assertThat(roleM.invoke(c3)).isEqualTo("CLIENT");
        assertThat(idM.invoke(c3)).isEqualTo(3L);
        assertThat(roleM.invoke(e3)).isEqualTo("EMPLOYEE");
        assertThat(idM.invoke(e3)).isEqualTo(3L);
        assertThat(roleM.invoke(plain)).isNull();
        assertThat(idM.invoke(plain)).isEqualTo(3L);
    }

    @Test
    @DisplayName("BUG-2 [IDOR] inbound: §3.6 premium charge na buyer \"E-3\" → NO (NO_SUCH_ACCOUNT), klijent 3 NIJE terecen")
    void handleNewTx_premiumChargeOnEmployeeBuyer_rejected_noClientAccountCharged() throws Exception {
        // Inbound accept gde je buyer Person "E-3" (zaposleni). resolveLocalAccountDeterministic
        // mora vratiti empty za EMPLOYEE → NO_SUCH_ACCOUNT → NO vote → NICIJI racun se ne
        // rezervise (pogotovo ne racun klijenta 3 — tudje lice).
        ForeignBankId negId = new ForeignBankId(REMOTE_RN, "neg-emp-buyer");
        OptionDescription opt = buildOptionDescription(
                negId, "AAPL", BigDecimal.valueOf(50), "USD", BigDecimal.valueOf(200));
        BigDecimal premium = BigDecimal.valueOf(700);

        InterbankOtcNegotiation buyerNeg = new InterbankOtcNegotiation();
        buyerNeg.setId(950L);
        buyerNeg.setLocalPartyType(InterbankPartyType.BUYER);
        buyerNeg.setLocalPartyId(3L);
        buyerNeg.setLocalPartyRole("EMPLOYEE");
        buyerNeg.setPremium(premium);
        buyerNeg.setPremiumCurrency("USD");
        lenient().when(otcNegotiationRepository
                        .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(eq(REMOTE_RN), eq("neg-emp-buyer")))
                .thenReturn(Optional.of(buyerNeg));

        // Klijent 3 IMA USD racun — ako bi role bio zanemaren, premija bi pala na NJEGA.
        Account client3Usd = buildAccount(MY_RN + "300009", "USD", BigDecimal.valueOf(9999), AccountStatus.ACTIVE);
        lenient().when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(eq(3L), eq(AccountStatus.ACTIVE)))
                .thenReturn(List.of(client3Usd));

        ForeignBankId buyerParty = new ForeignBankId(MY_RN, "E-3");      // local EMPLOYEE buyer
        ForeignBankId sellerParty = new ForeignBankId(REMOTE_RN, "C-1"); // remote seller
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(buyerParty), premium.negate(),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(ACCT_REMOTE), premium,
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Person(buyerParty), BigDecimal.ONE,
                        new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Person(sellerParty), BigDecimal.ONE.negate(),
                        new Asset.OptionAsset(opt))
        ), new ForeignBankId(REMOTE_RN, "accept-emp-buyer-1"), null, null, null, null);
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "accept-emp-key-1");
        stubTxSave();

        TransactionVote vote = service.handleNewTx(tx, key);

        // Zaposleni nema klijentski settlement racun → NO (NO_SUCH_ACCOUNT).
        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        // KLJUCNO (anti-IDOR): racun klijenta 3 NIKAD nije rezervisan/terecen.
        verify(reservationApplier, never()).reserveMonas(eq(client3Usd.getAccountNumber()), any());
        verify(reservationApplier, never()).reserveMonas(any(), any());
    }

    @Test
    @DisplayName("prepareLocal: remote postings skipped — accountRepository never called for remote account")
    void prepareLocal_remotePostingsSkipped() {
        when(accountRepository.findByAccountNumber(MY_RN + "999001"))
                .thenReturn(Optional.of(buildAccount(MY_RN + "999001", "EUR", BigDecimal.ZERO, AccountStatus.ACTIVE)));

        service.prepareLocal(mixedMonasTx());

        verify(accountRepository, never()).findByAccountNumber(ACCT_REMOTE);
    }

    @Test
    @DisplayName("prepareLocal: multiple violations are all returned together")
    void prepareLocal_multipleViolations_allReturned() {
        // Both accounts not found → two NO_SUCH_ACCOUNT violations
        when(accountRepository.findByAccountNumber(any())).thenReturn(Optional.empty());

        TransactionVote vote = service.prepareLocal(localMonasTx());

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).hasSizeGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // commitLocal
    // =========================================================================

    @Test
    @DisplayName("commitLocal: MONAS debit (recipient) → commitRecipientCredit (FX-aware); MONAS credit (sender) → commitMonas (sender debit)")
    void commitLocal_monasPostings_callsCommitMonasCorrectly() throws Exception {
        // P0-3: cross-currency FX wiring promenila routing. Recipient (debit, +amount)
        // leg sad ide kroz commitRecipientCredit (koji interno radi konverziju +
        // Banka-B proviziju ako se valute razlikuju, ili je no-op za same-currency).
        // R1-681: Sender (credit, −amount) leg ide kroz commitMonas (2-arg sender debit).
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.commitLocal(tx.transactionId());

        // N5: commitRecipientCredit sad nosi i pinned FX rate (null za same-currency / nepinned).
        verify(reservationApplier).commitRecipientCredit(eq(ACCT_A), eq(BigDecimal.valueOf(100)), eq("RSD"), any());
        verify(reservationApplier).commitMonas(eq(ACCT_B), eq(BigDecimal.valueOf(100)));
    }

    @Test
    @DisplayName("commitLocal: status set to COMMITTED with timestamp")
    void commitLocal_statusSetToCommitted() throws Exception {
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.commitLocal(tx.transactionId());

        assertThat(ibt.getStatus()).isEqualTo(InterbankTransactionStatus.COMMITTED);
        assertThat(ibt.getCommittedAt()).isNotNull();
        assertThat(ibt.getLastActivityAt()).isNotNull();
        verify(txRepo).save(ibt);
    }

    @Test
    @DisplayName("commitLocal RECIPIENT inbound: lokalni MONAS kredit → records INCOMING Payment (recordIncoming)")
    void commitLocal_recipientInbound_recordsIncomingPayment() throws Exception {
        // Druga banka -> mi: remote sender debit (-100 RSD na ACCT_REMOTE),
        // lokalni primalac kredit (+100 RSD na ACCT_B). Uloga RECIPIENT → MORA da
        // zabelezi tacno JEDAN INCOMING Payment red za primljeni kredit.
        Transaction tx = inboundReceiveTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        ibt.setRole(InterbankTransaction.InterbankTransactionRole.RECIPIENT);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.commitLocal(tx.transactionId());

        // Kredit primaocu se i dalje primenjuje (regression-safe).
        verify(reservationApplier).commitRecipientCredit(eq(ACCT_B), eq(BigDecimal.valueOf(100)), eq("RSD"), any());
        // INCOMING Payment zapis: routing+id tx-a, primaocev racun ACCT_B, iznos 100,
        // wire valuta RSD, sender-label = remote sender racun.
        verify(inboundPaymentRecorder).recordIncoming(
                eq(tx.transactionId().routingNumber()), eq(tx.transactionId().id()),
                eq(ACCT_B), eq(BigDecimal.valueOf(100)), eq("RSD"), eq(ACCT_REMOTE));
    }

    @Test
    @DisplayName("commitLocal INITIATOR (nas outbound): NE belezi INCOMING Payment (Payment vec postoji iz createInterbankPayment)")
    void commitLocal_initiatorOutbound_doesNotRecordIncoming() throws Exception {
        // Lokalna MONAS tx u INITIATOR ulozi (default savedIbt) — mi smo koordinator.
        // Recipient credit leg postoji, ali pošto NISMO RECIPIENT, ne sme se kreirati
        // INCOMING zapis (outbound placanja vec imaju svoj Payment red).
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING); // INITIATOR
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.commitLocal(tx.transactionId());

        verifyNoInteractions(inboundPaymentRecorder);
    }

    @Test
    @DisplayName("S4: commitLocal accept-shape, mi smo BUYER → kreira InterbankOtcContract (localPartyType=BUYER)")
    void commitLocal_buyerAccept_createsBuyerContract() throws Exception {
        ForeignBankId negId = new ForeignBankId(REMOTE_RN, "neg-seller"); // autoritativan kod prodavca
        OptionDescription opt = buildOptionDescription(
                negId, "AAPL", BigDecimal.valueOf(50), "USD", BigDecimal.valueOf(200));
        BigDecimal premium = BigDecimal.valueOf(700);

        // Nas lokalni mirror pregovora: MI smo BUYER (lokalni klijent 7).
        InterbankOtcNegotiation buyerNeg = new InterbankOtcNegotiation();
        buyerNeg.setId(900L);
        buyerNeg.setLocalPartyType(InterbankPartyType.BUYER);
        buyerNeg.setLocalPartyId(7L);
        buyerNeg.setLocalPartyRole("CLIENT");
        buyerNeg.setForeignPartyRoutingNumber(REMOTE_RN);
        buyerNeg.setForeignPartyIdString("C-1");
        buyerNeg.setTicker("AAPL");
        buyerNeg.setAmount(BigDecimal.valueOf(50));
        buyerNeg.setPricePerUnit(BigDecimal.valueOf(200));
        buyerNeg.setPriceCurrency("USD");
        buyerNeg.setPremium(premium);
        buyerNeg.setPremiumCurrency("USD");
        buyerNeg.setSettlementDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30));
        // T1/T2 — realisticno stanje posle accept-a: kupac je izabrao settlement racun.
        String settlementAccount = MY_RN + "700002";
        buyerNeg.setBuyerSettlementAccountNumber(settlementAccount);
        when(otcNegotiationRepository
                        .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(eq(REMOTE_RN), eq("neg-seller")))
                .thenReturn(Optional.of(buyerNeg));
        when(otcContractRepository.findBySourceNegotiationId(900L)).thenReturn(Optional.empty());

        // §3.6 accept-shape kako ga vidi buyer banka: buyer (LOCAL) charge premium,
        // seller (REMOTE) prima; optionContract legovi Person↔Person.
        ForeignBankId buyerParty = new ForeignBankId(MY_RN, "C-7");
        ForeignBankId sellerParty = new ForeignBankId(REMOTE_RN, "C-1");
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(buyerParty), premium.negate(),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(ACCT_REMOTE), premium,
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Person(buyerParty), BigDecimal.ONE,
                        new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Person(sellerParty), BigDecimal.ONE.negate(),
                        new Asset.OptionAsset(opt))
        ), new ForeignBankId(REMOTE_RN, "accept-inbound-1"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.commitLocal(tx.transactionId());

        ArgumentCaptor<InterbankOtcContract> cap = ArgumentCaptor.forClass(InterbankOtcContract.class);
        verify(otcContractRepository).save(cap.capture());
        InterbankOtcContract c = cap.getValue();
        assertThat(c.getLocalPartyType()).isEqualTo(InterbankPartyType.BUYER);
        assertThat(c.getSourceNegotiationId()).isEqualTo(900L);
        assertThat(c.getTicker()).isEqualTo("AAPL");
        assertThat(c.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(c.getStrikePrice()).isEqualByComparingTo(BigDecimal.valueOf(200));
        assertThat(c.getStrikeCurrency()).isEqualTo("USD");
        assertThat(c.getStatus()).isEqualTo(InterbankOtcContractStatus.ACTIVE);
        // T1/T2 — premija se konzumira na izabranom settlement racunu (vise ne ostaje
        // zaglavljena u rezervaciji).
        verify(reservationApplier).commitMonas(eq(settlementAccount), eq(premium));
    }

    @Test
    @DisplayName("commitLocal: second call is a no-op when already COMMITTED (idempotent)")
    void commitLocal_idempotent_alreadyCommitted() throws Exception {
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.COMMITTED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));

        service.commitLocal(tx.transactionId());

        verifyNoInteractions(accountRepository, reservationApplier);
        verify(txRepo, never()).save(any());
    }

    @Test
    @DisplayName("1536: commitLocal acquires PESSIMISTIC_WRITE lock (findForUpdate) — "
            + "duplicate-delivery COMMIT_TX serialized, NOT the unlocked lookup")
    void commitLocal_usesPessimisticLockLookup() throws Exception {
        // 1536 — duplicate-delivery COMMIT_TX → double Monas commit. §2.9 dozvoljava
        // duplikat dostavu; pre fix-a commitLocal je citao ibTx BEZ locka i COMMITTED-
        // guard se evaluirao pre serijalizacije → 2 paralelne COMMIT_TX obe vide
        // PREPARED → obe primene Monas leg (koji NEMA idempotency kljuc) → dupli
        // debit/credit. Fix: findForUpdate (PESSIMISTIC_WRITE) lock + status re-read
        // pod lock-om. Ovaj test cementira da commitLocal NE koristi vise unlocked
        // lookup — da je regresija (vracanje na findBy...) odmah vidljiva.
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.commitLocal(tx.transactionId());

        // Lock-ovani lookup je iskoriscen; unlocked nikad (cementira lock-acquire).
        verify(txRepo).findForUpdateByTransactionRoutingNumberAndTransactionIdString(
                eq(tx.transactionId().routingNumber()), eq(tx.transactionId().id()));
        verify(txRepo, never()).findByTransactionRoutingNumberAndTransactionIdString(anyInt(), any());
        assertThat(ibt.getStatus()).isEqualTo(InterbankTransactionStatus.COMMITTED);
    }

    @Test
    @DisplayName("1536: duplicate COMMIT_TX after first commit → Monas leg applied EXACTLY once "
            + "(second delivery sees COMMITTED under lock → no-op)")
    void commitLocal_duplicateDelivery_monasAppliedOnce() throws Exception {
        // Simuliramo dve sekvencijalne (= lock-serijalizovane) COMMIT_TX dostave nad
        // istim redom. Prva dostava commit-uje (Monas leg primenjen). Druga dostava,
        // kad uzme lock, vidi status COMMITTED (mutiran in-place na deljenom ibt) i
        // izlazi kao no-op — Monas leg se NE primenjuje ponovo (exactly-once).
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.commitLocal(tx.transactionId()); // prva dostava
        service.commitLocal(tx.transactionId()); // duplikat dostava (lock → COMMITTED → no-op)

        // Recipient credit (debit leg) i sender debit (credit leg) — svaki TACNO jednom.
        verify(reservationApplier, times(1))
                .commitRecipientCredit(eq(ACCT_A), eq(BigDecimal.valueOf(100)), eq("RSD"), any());
        verify(reservationApplier, times(1))
                .commitMonas(eq(ACCT_B), eq(BigDecimal.valueOf(100)));
    }

    @Test
    @DisplayName("commitLocal: throws InterbankProtocolException when transaction is ROLLED_BACK")
    void commitLocal_throwsOnRolledBack() throws Exception {
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.ROLLED_BACK);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));

        assertThatThrownBy(() -> service.commitLocal(tx.transactionId()))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class);
    }

    @Test
    @DisplayName("commitLocal: STOCK debit → commitStock(isDebit=true); credit → commitStock(isDebit=false)")
    void commitLocal_stockPostings_callsCommitStockCorrectly() throws Exception {
        Transaction tx = localStockTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        // H4: commitLocal vise ne radi findListingByTicker pre-check — commitStock
        // (trading-service seam) sam razresava listing po ticker-u.
        stubTxSave();

        service.commitLocal(tx.transactionId());

        // commitStock via trading-service seam: idempotency key + ticker (ne vise Listing objekat)
        verify(reservationApplier).commitStock(anyString(), eq(99L), eq("CLIENT"), eq("AAPL"), eq(10), eq(true));
        verify(reservationApplier).commitStock(anyString(), eq(42L), eq("CLIENT"), eq("AAPL"), eq(10), eq(false));
    }

    @Test
    @DisplayName("commitLocal: stock idempotency kljuc nosi posting indeks — svaki posting "
            + "transakcije je zaseban idempotentan poziv (M1)")
    void commitLocal_stockIdempotencyKeyIncludesPostingIndex() throws Exception {
        Transaction tx = localStockTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        // H4: commitLocal vise ne radi findListingByTicker pre-check.
        stubTxSave();

        service.commitLocal(tx.transactionId());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(reservationApplier, times(2)).commitStock(keyCaptor.capture(),
                anyLong(), anyString(), anyString(), anyInt(), anyBoolean());

        // localStockTx ima 2 posting-a (indeks 0 i 1) — kljucevi moraju biti
        // razliciti i nositi tacan ":<index>" sufiks.
        assertThat(keyCaptor.getAllValues()).hasSize(2);
        assertThat(keyCaptor.getAllValues()).doesNotHaveDuplicates();
        assertThat(keyCaptor.getAllValues())
                .anySatisfy(k -> assertThat(k).endsWith(":0"))
                .anySatisfy(k -> assertThat(k).endsWith(":1"));
        assertThat(keyCaptor.getAllValues())
                .allSatisfy(k -> assertThat(k).startsWith("ib-").contains(":stock-commit:"));
    }

    @Test
    @DisplayName("commitLocal: STOCK listing not found → throws InterbankProtocolException")
    void commitLocal_stockListingNotFound_throws() throws Exception {
        // H4: commitLocal vise ne radi findListingByTicker pre-check — odsustvo
        // listinga sad povrsava commitStock (trading-service seam): realan
        // InterbankReservationApplier.commitStock prevodi trading-service
        // "Listing not found" gresku u InterbankProtocolException. Tu putanju
        // simuliramo direktnim throw-om iz mock-ovanog reservationApplier-a.
        Transaction tx = localStockTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        doThrow(new InterbankExceptions.InterbankProtocolException("Listing not found: AAPL"))
                .when(reservationApplier)
                .commitStock(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyBoolean());

        assertThatThrownBy(() -> service.commitLocal(tx.transactionId()))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class)
                .hasMessageContaining("Listing not found");
    }

    // =========================================================================
    // rollbackLocal
    // =========================================================================

    @Test
    @DisplayName("rollbackLocal: only local CREDIT postings released; debit postings skipped")
    void rollbackLocal_onlyCreditPostingsReleased() throws Exception {
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.rollbackLocal(tx.transactionId());

        verify(reservationApplier).releaseMonas(eq(ACCT_B), eq(BigDecimal.valueOf(100)));
        verify(reservationApplier, never()).releaseMonas(eq(ACCT_A), any());
    }

    @Test
    @DisplayName("rollbackLocal: status set to ROLLED_BACK with timestamp")
    void rollbackLocal_statusSetToRolledBack() throws Exception {
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.rollbackLocal(tx.transactionId());

        assertThat(ibt.getStatus()).isEqualTo(InterbankTransactionStatus.ROLLED_BACK);
        assertThat(ibt.getRolledBackAt()).isNotNull();
        verify(txRepo).save(ibt);
    }

    @Test
    @DisplayName("rollbackLocal: second call is a no-op when already ROLLED_BACK (idempotent)")
    void rollbackLocal_idempotent_alreadyRolledBack() throws Exception {
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.ROLLED_BACK);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));

        service.rollbackLocal(tx.transactionId());

        verifyNoInteractions(accountRepository, reservationApplier);
        verify(txRepo, never()).save(any());
    }

    @Test
    @DisplayName("rollbackLocal: also a no-op when already COMMITTED (idempotent)")
    void rollbackLocal_idempotent_alreadyCommitted() throws Exception {
        Transaction tx = localMonasTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.COMMITTED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));

        service.rollbackLocal(tx.transactionId());

        verifyNoInteractions(accountRepository, reservationApplier);
        verify(txRepo, never()).save(any());
    }

    @Test
    @DisplayName("rollbackLocal: STOCK credit posting → releaseStock called; debit posting skipped")
    void rollbackLocal_stockCreditPosting_callsReleaseStock() throws Exception {
        Transaction tx = localStockTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        // H4: rollbackLocal vise ne radi findListingByTicker pre-check — releaseStock
        // (trading-service seam) sam razresava listing po ticker-u.
        stubTxSave();

        service.rollbackLocal(tx.transactionId());

        // releaseStock via trading-service seam: idempotency key + ticker
        verify(reservationApplier).releaseStock(anyString(), eq(42L), eq("CLIENT"), eq("AAPL"), eq(10));
        verify(reservationApplier, never()).releaseStock(anyString(), eq(99L), any(), any(), anyInt());
    }

    @Test
    @DisplayName("rollbackLocal: STOCK debit-only posting — releaseStock never called")
    void rollbackLocal_stockDebitPosting_skipped() throws Exception {
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "77")),
                        BigDecimal.valueOf(5), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "88")),
                        BigDecimal.valueOf(-5), new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(MY_RN, "stock-debit-only"), null, null, null, null);
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        // H4: rollbackLocal vise ne radi findListingByTicker pre-check.
        stubTxSave();

        service.rollbackLocal(tx.transactionId());

        verify(reservationApplier, never()).releaseStock(anyString(), eq(77L), any(), any(), anyInt());
        verify(reservationApplier).releaseStock(anyString(), eq(88L), eq("CLIENT"), eq("AAPL"), eq(5));
    }

    @Test
    @DisplayName("rollbackLocal: STOCK listing not found → throws InterbankProtocolException")
    void rollbackLocal_stockListingNotFound_throws() throws Exception {
        // H4: rollbackLocal vise ne radi findListingByTicker pre-check — odsustvo
        // listinga sad povrsava releaseStock (trading-service seam): realan
        // InterbankReservationApplier.releaseStock prevodi trading-service
        // "Listing not found" gresku u InterbankProtocolException.
        Transaction tx = localStockTx();
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        doThrow(new InterbankExceptions.InterbankProtocolException("Listing not found: AAPL"))
                .when(reservationApplier)
                .releaseStock(anyString(), anyLong(), anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> service.rollbackLocal(tx.transactionId()))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class)
                .hasMessageContaining("Listing not found");
    }

    // =========================================================================
    // handleNewTx
    // =========================================================================

    @Test
    @DisplayName("handleNewTx: clean inbound tx (remote charge + local receive) → YES, saves RECIPIENT, records response")
    void handleNewTx_cleanTx_yesVoteAndSavesRecipient() throws Exception {
        // N3: realisticna inbound NEW_TX payload — REMOTE strana se tereti (charge),
        // LOKALNA strana SAMO prima (debit-into). Lokalni racun se ne tereti.
        Transaction tx = inboundReceiveTx();
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "k1");
        // BE-INT-01: handler vise ne radi cache lookup interno — idempotency je
        // na dispatch nivou (InterbankInboundController).
        when(accountRepository.findByAccountNumber(ACCT_B))
                .thenReturn(Optional.of(buildAccount(ACCT_B, "RSD", BigDecimal.valueOf(500), AccountStatus.ACTIVE)));
        stubTxSave();

        TransactionVote vote = service.handleNewTx(tx, key);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
        // P2-concurrency-locks-1 (R3-1582): recipient red se sada perzistuje saveAndFlush
        // (UNIQUE-constraint double-reserve barijera) umesto plain save.
        ArgumentCaptor<InterbankTransaction> cap = ArgumentCaptor.forClass(InterbankTransaction.class);
        verify(txRepo).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getRole())
                .isEqualTo(InterbankTransaction.InterbankTransactionRole.RECIPIENT);
        assertThat(cap.getValue().getStatus())
                .isEqualTo(InterbankTransactionStatus.PREPARED);
        verify(messageService).recordInboundResponse(
                eq(key), eq(MessageType.NEW_TX), any(), eq(200), any(), any());
    }

    @Test
    @DisplayName("R3-1582: duplicate NEW_TX (recipient red PREPARED pod lockom) → replay YES, NE rezervise ponovo (double-reserve guard)")
    void handleNewTx_duplicateNewTx_replaysYesWithoutReReserving() throws Exception {
        // P2-concurrency-locks-1 (R3-1582): dispatch cache (InterbankMessage) ne moze
        // da iznudi idempotenciju jer je tabela particionisana po created_at. Dva
        // konkurentna NEW_TX sa istim transactionId su pre fix-a OBA prosla
        // saveRecipientState (ne-zakljucan check-then-act) i OBA rezervisala.
        // Posle fix-a: pesimisticki findForUpdate vidi da recipient red VEC postoji
        // (racing prvi NEW_TX ga je commit-ovao) → preskace rezervaciju i replay-uje
        // vote izvedenu iz statusa AUTORITATIVNOG recipient reda (PREPARED → YES).
        Transaction tx = inboundReceiveTx();
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "dup-newtx-1");

        // findForUpdate vraca postojeci recipient red (duplicate-delivery / racing drugi).
        InterbankTransaction existing = new InterbankTransaction();
        existing.setTransactionRoutingNumber(REMOTE_RN);
        existing.setTransactionIdString(tx.transactionId().id());
        existing.setRole(InterbankTransaction.InterbankTransactionRole.RECIPIENT);
        existing.setStatus(InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(
                eq(REMOTE_RN), eq(tx.transactionId().id())))
                .thenReturn(Optional.of(existing));
        // Replay se sad izvodi iz AUTORITATIVNOG reda (non-locking lookup), ne iz cache-a.
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                eq(REMOTE_RN), eq(tx.transactionId().id())))
                .thenReturn(Optional.of(existing));

        TransactionVote vote = service.handleNewTx(tx, key);

        // Replay YES (originalni glas je bio YES, red je PREPARED)...
        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
        // ...ali NIJE rezervisao ponovo (kljucni double-reserve guard)...
        verify(reservationApplier, never()).reserveMonas(any(), any());
        // ...niti je ponovo upisao recipient red ili cache (drugi NEW_TX je vlasnik).
        verify(txRepo, never()).saveAndFlush(any());
        verify(messageService, never()).recordInboundResponse(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("R3-1582 phantom-vote: duplicate NEW_TX sa DRUGIM kljucem (K2) za tx cija je "
            + "original NEW_TX glasao NO (red ROLLED_BACK) → replay NO, NE phantom YES "
            + "(cross-bank conservation leak zatvoren)")
    void handleNewTx_duplicateNewTx_differentKey_rolledBackRow_replaysNoNotPhantomYes() throws Exception {
        // REVIEWER-FOUND phantom-vote bug (R3-1582 sub-fix): §2.2 posiljalac generise
        // SVEZ idempotency kljuc po poruci. Original NEW_TX#1 stigne pod kljucem K1,
        // glasa NO (npr. nedovoljno sredstava) → recipient red postaje ROLLED_BACK i
        // dispatch cache kesira NO POD K1. Same-transaction redelivery NEW_TX#2 stigne
        // sa DRUGIM kljucem K2 → findCachedMessage(K2) je PRAZNO. Pre fix-a, replay je
        // padao na bezuslovni "phantom YES" → koordinatoru bi se prijavila rezervacija
        // koja NE postoji → cross-bank conservation leak / stranded state na COMMIT_TX.
        // Posle fix-a: replay se izvodi iz STATUSA autoritativnog reda (ROLLED_BACK → NO).
        Transaction tx = inboundReceiveTx();
        IdempotenceKey k2 = new IdempotenceKey(REMOTE_RN, "redelivery-K2");

        // Recipient red postoji i ima status ROLLED_BACK (original NEW_TX#1 glasao NO).
        InterbankTransaction rolledBack = new InterbankTransaction();
        rolledBack.setTransactionRoutingNumber(REMOTE_RN);
        rolledBack.setTransactionIdString(tx.transactionId().id());
        rolledBack.setRole(InterbankTransaction.InterbankTransactionRole.RECIPIENT);
        rolledBack.setStatus(InterbankTransactionStatus.ROLLED_BACK);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(
                eq(REMOTE_RN), eq(tx.transactionId().id())))
                .thenReturn(Optional.of(rolledBack));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                eq(REMOTE_RN), eq(tx.transactionId().id())))
                .thenReturn(Optional.of(rolledBack));

        // Dispatch cache MISS za K2 (glas je kesiran pod K1) — ovo je sustina buga.
        lenient().when(messageService.findCachedMessage(k2)).thenReturn(Optional.empty());

        TransactionVote vote = service.handleNewTx(tx, k2);

        // KLJUCNA invarijanta: replay NO (ne phantom YES) — ne tvrdimo rezervaciju
        // koja ne postoji.
        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        // I dalje ne rezervise ponovo niti pise red (double-reserve guard ocuvan).
        verify(reservationApplier, never()).reserveMonas(any(), any());
        verify(txRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("R3-1582 phantom-vote (positive): duplicate NEW_TX sa DRUGIM kljucem (K2) za tx "
            + "cija je original NEW_TX glasao YES (red PREPARED) → replay YES, NE rezervise ponovo")
    void handleNewTx_duplicateNewTx_differentKey_preparedRow_replaysYesNoSecondReserve() throws Exception {
        // Pozitivan par: original NEW_TX#1 (kljuc K1) je glasao YES → red PREPARED,
        // rezervacija postoji. Redelivery NEW_TX#2 sa DRUGIM kljucem K2 (cache miss)
        // mora replay-ovati YES iz statusa reda — ALI bez druge rezervacije
        // (double-reserve guard ostaje). Dokazuje da fix nije polomio YES putanju.
        Transaction tx = inboundReceiveTx();
        IdempotenceKey k2 = new IdempotenceKey(REMOTE_RN, "redelivery-K2-yes");

        InterbankTransaction prepared = new InterbankTransaction();
        prepared.setTransactionRoutingNumber(REMOTE_RN);
        prepared.setTransactionIdString(tx.transactionId().id());
        prepared.setRole(InterbankTransaction.InterbankTransactionRole.RECIPIENT);
        prepared.setStatus(InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(
                eq(REMOTE_RN), eq(tx.transactionId().id())))
                .thenReturn(Optional.of(prepared));
        when(txRepo.findByTransactionRoutingNumberAndTransactionIdString(
                eq(REMOTE_RN), eq(tx.transactionId().id())))
                .thenReturn(Optional.of(prepared));
        lenient().when(messageService.findCachedMessage(k2)).thenReturn(Optional.empty());

        TransactionVote vote = service.handleNewTx(tx, k2);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
        verify(reservationApplier, never()).reserveMonas(any(), any());
        verify(txRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("N3: inbound NEW_TX charging a LOCAL currency account → NO vote (drain rejected, no reservation)")
    void handleNewTx_chargesLocalCurrencyAccount_rejected() throws Exception {
        // N3 (IDOR/drain): sa validnim X-Api-Key, partner banka NE SME da inicira
        // inbound transakciju koja TERETI (credit/charge, negativan iznos) lokalni
        // racun. Lokalna strana inbound transakcije sme SAMO da prima (debit-into).
        // localMonasTx tereti lokalni ACCT_B (-100) — to je tacno drain payload.
        // PRE fix-a: ovo bi proslo (YES) i rezervisalo ACCT_B → drenaza.
        // POSLE fix-a: NO vote, nikakva rezervacija.
        Transaction tx = localMonasTx(); // ACCT_A +100 (debit-into), ACCT_B -100 (CHARGE lokalni)
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "drain-1");
        // ACCT_A (debit-into) se i dalje validira; ACCT_B (charge) je odbijen N3 gate-om
        // pre lookup-a, pa ga ne stub-ujemo (Mockito strict bi prijavio unused stub).
        lenient().when(accountRepository.findByAccountNumber(ACCT_A))
                .thenReturn(Optional.of(buildAccount(ACCT_A, "RSD", BigDecimal.ZERO, AccountStatus.ACTIVE)));
        stubTxSave();

        TransactionVote vote = service.handleNewTx(tx, key);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        // Nijedna rezervacija na lokalnom racunu — drenaza odbijena pre Pass-2.
        verify(reservationApplier, never()).reserveMonas(any(), any());
    }

    @Test
    @DisplayName("N3: inbound NEW_TX charging a LOCAL person's money account → NO vote (drain rejected)")
    void handleNewTx_chargesLocalPersonMonas_rejected() throws Exception {
        // N3: isti drain preko Person+Monas oblika (lokalni Person se tereti).
        // Balansirano: lokalni Person -100 (charge) + remote account +100 (receive).
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "77")),
                        BigDecimal.valueOf(-100), new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD))),
                new Posting(new TxAccount.Account(ACCT_REMOTE),
                        BigDecimal.valueOf(100), new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)))
        ), new ForeignBankId(REMOTE_RN, "drain-person"), null, null, null, null);
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "drain-2");
        stubTxSave();

        TransactionVote vote = service.handleNewTx(tx, key);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        verify(reservationApplier, never()).reserveMonas(any(), any());
    }

    @Test
    @DisplayName("N3 BYPASS: inbound NEW_TX with a legit option leg + malicious charge on a "
            + "victim local account → NO vote, victim NEVER reserved (carve-out closed)")
    void handleNewTx_optionContextSmugglesVictimCharge_rejected() throws Exception {
        // N3 BYPASS exploit (the blocking hole the carve-out left open): a partner bank
        // that holds ANY ACTIVE OTC contract attaches a LEGITIMATE option leg of its own
        // negotiation (→ structural hasOptionContext=true) and smuggles a SEPARATE
        // malicious credit(-X) leg charging an UNRELATED victim local RSD account
        // (balanced by a remote +X leg). Under the old `!hasOptionContext` blanket
        // carve-out the N3 gate was skipped for ALL Monas charges in this tx, so the
        // victim's account was reserved (drain). After the fix the relationship gate binds
        // the charge to the BUYER of the resolved negotiation — the victim is not that
        // buyer → NO vote, no reservation.
        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-attacker");
        OptionDescription opt = buildOptionDescription(
                negId, "AAPL", BigDecimal.valueOf(10), "USD", BigDecimal.valueOf(150));

        // The attacker's own (real) ACTIVE negotiation/contract — makes the option leg
        // structurally valid. We are SELLER here (the attacker is buyer in another bank),
        // so even resolving the negotiation, a buyer-premium charge is NOT authorized.
        InterbankOtcNegotiation attackerNeg = new InterbankOtcNegotiation();
        attackerNeg.setId(500L);
        attackerNeg.setLocalPartyType(InterbankPartyType.SELLER); // we are seller, not buyer
        attackerNeg.setLocalPartyId(7L);
        attackerNeg.setLocalPartyRole("CLIENT");
        attackerNeg.setPremium(BigDecimal.valueOf(700));
        attackerNeg.setPremiumCurrency("USD");
        lenient().when(otcNegotiationRepository
                        .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(eq(MY_RN), eq("neg-attacker")))
                .thenReturn(Optional.of(attackerNeg));

        // The victim's local account is a real, ACTIVE, fully-funded RSD account. This is
        // load-bearing: under the OLD carve-out the smuggled charge would fall through to
        // the normal Monas branch, pass validation (account exists, balance sufficient),
        // and reserve the victim's funds (the drain). If we left ACCT_B unstubbed the test
        // would pass for the WRONG reason (NO_SUCH_ACCOUNT) even under the buggy carve-out.
        lenient().when(accountRepository.findByAccountNumber(ACCT_B))
                .thenReturn(Optional.of(buildAccount(ACCT_B, "RSD", BigDecimal.valueOf(100000), AccountStatus.ACTIVE)));

        // Tx: balanced option legs (Person↔Person, accept-shape) + a SMUGGLED malicious
        // charge on victim local account ACCT_B (-100 RSD) balanced by remote +100.
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "C-1")),
                        BigDecimal.ONE, new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "C-7")),
                        BigDecimal.ONE.negate(), new Asset.OptionAsset(opt)),
                // SMUGGLED: charge victim local account (credit, -100) — drain attempt
                new Posting(new TxAccount.Account(ACCT_B), BigDecimal.valueOf(-100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD))),
                // balancing remote receive (+100)
                new Posting(new TxAccount.Account(ACCT_REMOTE), BigDecimal.valueOf(100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)))
        ), new ForeignBankId(REMOTE_RN, "bypass-1"), null, null, null, null);
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "bypass-key-1");
        stubTxSave();

        TransactionVote vote = service.handleNewTx(tx, key);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        // Victim's local account must NEVER be reserved — this is the drain that the
        // carve-out allowed and the relationship gate now blocks.
        verify(reservationApplier, never()).reserveMonas(eq(ACCT_B), any());
        verify(reservationApplier, never()).reserveMonas(any(), any());
    }

    @Test
    @DisplayName("N3 legit accept inbound: buyer premium charge bound to our BUYER negotiation "
            + "(amount==premium) → YES vote (legitimate §3.6 path NOT broken)")
    void handleNewTx_legitAcceptPremiumCharge_authorized() throws Exception {
        // §3.6 accept inbound on the BUYER's bank (us): the seller's bank initiates the
        // accept; we receive NEW_TX and legitimately debit our local buyer the agreed
        // premium. The relationship gate must AUTHORIZE this: the charged party is the
        // BUYER of our local negotiation referenced by the option leg, and the amount/
        // currency match the negotiation's premium. Proves the fix did not break the
        // legitimate path (it would, under a blanket-remove).
        ForeignBankId negId = new ForeignBankId(REMOTE_RN, "neg-seller"); // authoritative at seller
        OptionDescription opt = buildOptionDescription(
                negId, "AAPL", BigDecimal.valueOf(50), "USD", BigDecimal.valueOf(200));
        BigDecimal premium = BigDecimal.valueOf(700);

        // Our local mirror of the negotiation: we are BUYER, local client id 7.
        InterbankOtcNegotiation buyerNeg = new InterbankOtcNegotiation();
        buyerNeg.setId(900L);
        buyerNeg.setLocalPartyType(InterbankPartyType.BUYER);
        buyerNeg.setLocalPartyId(7L);
        buyerNeg.setLocalPartyRole("CLIENT");
        buyerNeg.setPremium(premium);
        buyerNeg.setPremiumCurrency("USD");
        when(otcNegotiationRepository
                        .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(eq(REMOTE_RN), eq("neg-seller")))
                .thenReturn(Optional.of(buyerNeg));

        // §3.6 4-posting accept tx as seen on the buyer's bank: buyer (LOCAL) charged
        // premium, seller (REMOTE) receives; option contract legs Person↔Person.
        ForeignBankId buyerParty = new ForeignBankId(MY_RN, "C-7");   // local buyer
        ForeignBankId sellerParty = new ForeignBankId(REMOTE_RN, "C-1"); // remote seller
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(buyerParty), premium.negate(),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),       // local buyer charge
                new Posting(new TxAccount.Account(ACCT_REMOTE), premium,
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),       // remote seller receive
                new Posting(new TxAccount.Person(buyerParty), BigDecimal.ONE,
                        new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Person(sellerParty), BigDecimal.ONE.negate(),
                        new Asset.OptionAsset(opt))
        ), new ForeignBankId(REMOTE_RN, "accept-inbound-1"), null, null, null, null);
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "accept-key-1");

        // Resolve local buyer (C-7) money account for the reservation in Pass 2.
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(eq(7L), eq(AccountStatus.ACTIVE)))
                .thenReturn(List.of(buildAccount(MY_RN + "700001", "USD",
                        BigDecimal.valueOf(5000), AccountStatus.ACTIVE)));
        stubTxSave();

        TransactionVote vote = service.handleNewTx(tx, key);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
        // Legitimate premium reservation happens on the resolved local buyer account.
        verify(reservationApplier).reserveMonas(eq(MY_RN + "700001"), eq(premium));
    }

    @Test
    @DisplayName("N3 relationship gate: option leg present but charge amount != premium → NO vote")
    void handleNewTx_optionContextWrongPremiumAmount_rejected() throws Exception {
        // Even with a real local BUYER negotiation referenced by the option leg, a charge
        // whose amount does NOT equal the negotiation premium is NOT authorized — the
        // relationship gate binds amount+currency, not just party. Defeats a "right buyer,
        // inflated amount" variant of the smuggle.
        ForeignBankId negId = new ForeignBankId(REMOTE_RN, "neg-amt");
        OptionDescription opt = buildOptionDescription(
                negId, "AAPL", BigDecimal.valueOf(50), "USD", BigDecimal.valueOf(200));

        InterbankOtcNegotiation buyerNeg = new InterbankOtcNegotiation();
        buyerNeg.setId(901L);
        buyerNeg.setLocalPartyType(InterbankPartyType.BUYER);
        buyerNeg.setLocalPartyId(7L);
        buyerNeg.setLocalPartyRole("CLIENT");
        buyerNeg.setPremium(BigDecimal.valueOf(700));
        buyerNeg.setPremiumCurrency("USD");
        lenient().when(otcNegotiationRepository
                        .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(eq(REMOTE_RN), eq("neg-amt")))
                .thenReturn(Optional.of(buyerNeg));

        ForeignBankId buyerParty = new ForeignBankId(MY_RN, "C-7");
        ForeignBankId sellerParty = new ForeignBankId(REMOTE_RN, "C-1");
        BigDecimal inflated = BigDecimal.valueOf(9000); // != premium 700
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(buyerParty), inflated.negate(),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(ACCT_REMOTE), inflated,
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Person(buyerParty), BigDecimal.ONE,
                        new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Person(sellerParty), BigDecimal.ONE.negate(),
                        new Asset.OptionAsset(opt))
        ), new ForeignBankId(REMOTE_RN, "accept-bad-amt-1"), null, null, null, null);
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "accept-bad-amt-key");
        stubTxSave();

        TransactionVote vote = service.handleNewTx(tx, key);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        verify(reservationApplier, never()).reserveMonas(any(), any());
    }

    @Test
    @DisplayName("T1/T2 prepare: buyer premium reserved on negotiation.buyerSettlementAccountNumber "
            + "(NOT the highest-balance account)")
    void handleNewTx_acceptPremium_reservesOnChosenSettlementAccount() throws Exception {
        // §3.6 accept inbound on the BUYER's bank (us). Buyer je na FE-u izabrao
        // konkretan racun (buyerSettlementAccountNumber). Rezervacija premije MORA
        // ici na bas taj racun — NE na racun sa najvecim available balansom (koji bi
        // balance-desc resolucija inace izabrala), inace prepare i commit ne biraju
        // isti racun pa premija ostaje zaglavljena u rezervaciji.
        ForeignBankId negId = new ForeignBankId(REMOTE_RN, "neg-chosen-acct");
        OptionDescription opt = buildOptionDescription(
                negId, "AAPL", BigDecimal.valueOf(50), "USD", BigDecimal.valueOf(200));
        BigDecimal premium = BigDecimal.valueOf(700);

        String chosenAccount = MY_RN + "700002";   // izabran na FE-u (manji balans)
        String richestAccount = MY_RN + "700001";  // najveci balans (NE sme biti izabran)

        InterbankOtcNegotiation buyerNeg = new InterbankOtcNegotiation();
        buyerNeg.setId(910L);
        buyerNeg.setLocalPartyType(InterbankPartyType.BUYER);
        buyerNeg.setLocalPartyId(7L);
        buyerNeg.setLocalPartyRole("CLIENT");
        buyerNeg.setPremium(premium);
        buyerNeg.setPremiumCurrency("USD");
        buyerNeg.setBuyerSettlementAccountNumber(chosenAccount);
        when(otcNegotiationRepository
                        .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(eq(REMOTE_RN), eq("neg-chosen-acct")))
                .thenReturn(Optional.of(buyerNeg));

        ForeignBankId buyerParty = new ForeignBankId(MY_RN, "C-7");
        ForeignBankId sellerParty = new ForeignBankId(REMOTE_RN, "C-1");
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(buyerParty), premium.negate(),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(ACCT_REMOTE), premium,
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Person(buyerParty), BigDecimal.ONE,
                        new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Person(sellerParty), BigDecimal.ONE.negate(),
                        new Asset.OptionAsset(opt))
        ), new ForeignBankId(REMOTE_RN, "accept-chosen-acct-1"), null, null, null, null);
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "accept-chosen-key-1");

        // Chosen account je validan (USD, dovoljan balans) ali NIJE najbogatiji.
        when(accountRepository.findByAccountNumber(chosenAccount))
                .thenReturn(Optional.of(buildAccount(chosenAccount, "USD",
                        BigDecimal.valueOf(1000), AccountStatus.ACTIVE)));
        // Balance-desc resolucija bi vratila richestAccount prvi — dokaz da ga NE biramo.
        lenient().when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(eq(7L), eq(AccountStatus.ACTIVE)))
                .thenReturn(List.of(
                        buildAccount(richestAccount, "USD", BigDecimal.valueOf(99999), AccountStatus.ACTIVE),
                        buildAccount(chosenAccount, "USD", BigDecimal.valueOf(1000), AccountStatus.ACTIVE)));
        stubTxSave();

        TransactionVote vote = service.handleNewTx(tx, key);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
        verify(reservationApplier).reserveMonas(eq(chosenAccount), eq(premium));
        verify(reservationApplier, never()).reserveMonas(eq(richestAccount), any());
    }

    @Test
    @DisplayName("T1/T2 commit: buyer premium (Monas+Person credit) consumed via commitMonas "
            + "on negotiation.buyerSettlementAccountNumber")
    void commitLocal_acceptPremium_commitsMonasOnChosenSettlementAccount() throws Exception {
        // commitLocal MORA da konzumira rezervisanu premiju (commitMonas) na ISTOM
        // racunu koji je prepare faza rezervisala (buyerSettlementAccountNumber). Pre
        // fix-a commitLocal nije imao Monas+Person granu pa je rezervacija ostajala
        // zauvek (novac zaglavljen, narusena konzervacija).
        ForeignBankId negId = new ForeignBankId(REMOTE_RN, "neg-commit-acct");
        OptionDescription opt = buildOptionDescription(
                negId, "AAPL", BigDecimal.valueOf(50), "USD", BigDecimal.valueOf(200));
        BigDecimal premium = BigDecimal.valueOf(700);
        String chosenAccount = MY_RN + "700002";

        InterbankOtcNegotiation buyerNeg = new InterbankOtcNegotiation();
        buyerNeg.setId(911L);
        buyerNeg.setLocalPartyType(InterbankPartyType.BUYER);
        buyerNeg.setLocalPartyId(7L);
        buyerNeg.setLocalPartyRole("CLIENT");
        buyerNeg.setForeignPartyRoutingNumber(REMOTE_RN);
        buyerNeg.setForeignPartyIdString("C-1");
        buyerNeg.setTicker("AAPL");
        buyerNeg.setAmount(BigDecimal.valueOf(50));
        buyerNeg.setPricePerUnit(BigDecimal.valueOf(200));
        buyerNeg.setPriceCurrency("USD");
        buyerNeg.setPremium(premium);
        buyerNeg.setPremiumCurrency("USD");
        buyerNeg.setSettlementDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30));
        buyerNeg.setBuyerSettlementAccountNumber(chosenAccount);
        lenient().when(otcNegotiationRepository
                        .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(eq(REMOTE_RN), eq("neg-commit-acct")))
                .thenReturn(Optional.of(buyerNeg));
        // S4 contract creation grana: pregovor vec ima ugovor → preskoci (idempotentno).
        lenient().when(otcContractRepository.findBySourceNegotiationId(911L))
                .thenReturn(Optional.of(new InterbankOtcContract()));

        ForeignBankId buyerParty = new ForeignBankId(MY_RN, "C-7");
        ForeignBankId sellerParty = new ForeignBankId(REMOTE_RN, "C-1");
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(buyerParty), premium.negate(),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),     // local buyer charge (credit)
                new Posting(new TxAccount.Account(ACCT_REMOTE), premium,
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),     // remote seller receive
                new Posting(new TxAccount.Person(buyerParty), BigDecimal.ONE,
                        new Asset.OptionAsset(opt)),                               // buyer debit option (local)
                new Posting(new TxAccount.Person(sellerParty), BigDecimal.ONE.negate(),
                        new Asset.OptionAsset(opt))                                // seller credit option (remote)
        ), new ForeignBankId(REMOTE_RN, "accept-commit-acct-1"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.commitLocal(tx.transactionId());

        // Premija se konzumira (commitMonas, sender-debit) na izabranom settlement racunu.
        verify(reservationApplier).commitMonas(eq(chosenAccount), eq(premium));
    }

    @Test
    @DisplayName("handleNewTx: violation tx → returns NO vote and still records inbound response")
    void handleNewTx_violation_noVoteStillRecorded() throws Exception {
        Transaction tx = unbalancedTx();
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "k2");
        // BE-INT-01: handler vise ne radi cache lookup interno.
        stubTxSave();

        TransactionVote vote = service.handleNewTx(tx, key);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        verify(messageService).recordInboundResponse(
                eq(key), eq(MessageType.NEW_TX), any(), eq(200), any(), any());
    }

    @Test
    @DisplayName("handleNewTx: race - DataIntegrityViolation on recordInboundResponse swallowed, vote returned (BE-INT-01)")
    void handleNewTx_raceOnCacheInsert_swallowed() throws Exception {
        // BE-INT-01: ako dva paralelna request-a sa istim key-em istovremeno udju
        // u handler, drugi ce dobiti DataIntegrityViolationException pri save-u
        // (UNIQUE constraint). Mi to swallow-ujemo i vracamo izracunatu vote —
        // jer je handler deterministicki, vote je isti.
        Transaction tx = inboundReceiveTx();
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "race-k1");
        when(accountRepository.findByAccountNumber(ACCT_B))
                .thenReturn(Optional.of(buildAccount(ACCT_B, "RSD", BigDecimal.valueOf(500), AccountStatus.ACTIVE)));
        stubTxSave();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("UNIQUE violation"))
                .when(messageService).recordInboundResponse(eq(key), any(), any(), anyInt(), any(), any());

        TransactionVote vote = service.handleNewTx(tx, key);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
    }

    @Test
    @DisplayName("handleNewTx: YES vote response body is persisted as JSON containing YES")
    void handleNewTx_yesVote_responseBodyContainsYes() throws Exception {
        Transaction tx = inboundReceiveTx();
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "k4");
        // BE-INT-01: handler vise ne radi cache lookup interno.
        when(accountRepository.findByAccountNumber(ACCT_B))
                .thenReturn(Optional.of(buildAccount(ACCT_B, "RSD", BigDecimal.valueOf(500), AccountStatus.ACTIVE)));
        stubTxSave();

        service.handleNewTx(tx, key);

        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).recordInboundResponse(
                any(), any(), any(), anyInt(), responseCaptor.capture(), any());
        assertThat(responseCaptor.getValue()).containsIgnoringCase("YES");
    }

    // =========================================================================
    // handleCommitTx
    // =========================================================================

    @Test
    @DisplayName("handleCommitTx: commits locally and records 204 inbound response")
    void handleCommitTx_commitsAndRecords204() throws Exception {
        Transaction tx = localMonasTx();
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "ck1");
        // BE-INT-01: handler vise ne radi cache lookup interno.

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.handleCommitTx(new CommitTransaction(tx.transactionId()), key);

        assertThat(ibt.getStatus()).isEqualTo(InterbankTransactionStatus.COMMITTED);
        verify(messageService).recordInboundResponse(
                eq(key), eq(MessageType.COMMIT_TX), any(), eq(204), any(), any());
    }

    @Test
    @DisplayName("handleCommitTx: race - DataIntegrityViolation on recordInboundResponse swallowed (BE-INT-01)")
    void handleCommitTx_raceOnCacheInsert_swallowed() throws Exception {
        // BE-INT-01: commitLocal je idempotent (vraca ranije ako je COMMITTED),
        // pa race na cache insert se safe-ly handle-uje swallow-om.
        Transaction tx = localMonasTx();
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "ck-race");
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("UNIQUE violation"))
                .when(messageService).recordInboundResponse(eq(key), any(), any(), anyInt(), any(), any());

        // Ne baca exception — race je swallow-an.
        service.handleCommitTx(new CommitTransaction(tx.transactionId()), key);
    }

    // =========================================================================
    // handleRollbackTx
    // =========================================================================

    @Test
    @DisplayName("handleRollbackTx: rolls back locally and records 204 inbound response")
    void handleRollbackTx_rollsBackAndRecords204() throws Exception {
        Transaction tx = localMonasTx();
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "rk1");
        // BE-INT-01: handler vise ne radi cache lookup interno.

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.handleRollbackTx(new RollbackTransaction(tx.transactionId()), key);

        assertThat(ibt.getStatus()).isEqualTo(InterbankTransactionStatus.ROLLED_BACK);
        verify(messageService).recordInboundResponse(
                eq(key), eq(MessageType.ROLLBACK_TX), any(), eq(204), any(), any());
    }

    @Test
    @DisplayName("handleRollbackTx: race - DataIntegrityViolation on recordInboundResponse swallowed (BE-INT-01)")
    void handleRollbackTx_raceOnCacheInsert_swallowed() throws Exception {
        // BE-INT-01: rollbackLocal je idempotent, pa race se swallow-uje.
        Transaction tx = localMonasTx();
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "rk-race");
        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("UNIQUE violation"))
                .when(messageService).recordInboundResponse(eq(key), any(), any(), anyInt(), any(), any());

        service.handleRollbackTx(new RollbackTransaction(tx.transactionId()), key);
        // Ne baca — race je swallow-an.
    }

    // =========================================================================
    // prepareLocal — option pseudo-account (§2.8.6 rules 5 and 6)
    // =========================================================================

    @Test
    @DisplayName("prepareLocal: option negotiation not found → NO + OPTION_NEGOTIATION_NOT_FOUND")
    void prepareLocal_option_negotiationNotFound_noVote() {
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                anyInt(), any())).thenReturn(Optional.empty());

        TransactionVote vote = service.prepareLocal(optionOnlyTx("neg-1", "AAPL", 10, "USD", BigDecimal.valueOf(150)));

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).allSatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.OPTION_NEGOTIATION_NOT_FOUND));
    }

    @Test
    @DisplayName("prepareLocal: negotiation found but contract not found → NO + OPTION_NEGOTIATION_NOT_FOUND")
    void prepareLocal_option_contractNotFound_noVote() {
        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(10L);
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                anyInt(), any())).thenReturn(Optional.of(neg));
        when(otcContractRepository.findBySourceNegotiationId(10L)).thenReturn(Optional.empty());

        TransactionVote vote = service.prepareLocal(optionOnlyTx("neg-1", "AAPL", 10, "USD", BigDecimal.valueOf(150)));

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).allSatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.OPTION_NEGOTIATION_NOT_FOUND));
    }

    @Test
    @DisplayName("prepareLocal: contract status EXERCISED → NO + OPTION_USED_OR_EXPIRED")
    void prepareLocal_option_contractExercised_noVote() {
        stubOptionNegAndContract(buildContract(InterbankOtcContractStatus.EXERCISED,
                LocalDate.now().plusDays(30), "AAPL", BigDecimal.valueOf(10), BigDecimal.valueOf(150), "USD"));

        TransactionVote vote = service.prepareLocal(optionOnlyTx("neg-1", "AAPL", 10, "USD", BigDecimal.valueOf(150)));

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).allSatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.OPTION_USED_OR_EXPIRED));
    }

    @Test
    @DisplayName("prepareLocal: contract status EXPIRED → NO + OPTION_USED_OR_EXPIRED")
    void prepareLocal_option_contractExpired_noVote() {
        stubOptionNegAndContract(buildContract(InterbankOtcContractStatus.EXPIRED,
                LocalDate.now().plusDays(30), "AAPL", BigDecimal.valueOf(10), BigDecimal.valueOf(150), "USD"));

        TransactionVote vote = service.prepareLocal(optionOnlyTx("neg-1", "AAPL", 10, "USD", BigDecimal.valueOf(150)));

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).allSatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.OPTION_USED_OR_EXPIRED));
    }

    @Test
    @DisplayName("prepareLocal: contract ACTIVE but settlementDate in the past → NO + OPTION_USED_OR_EXPIRED")
    void prepareLocal_option_settlementDatePast_noVote() {
        stubOptionNegAndContract(buildContract(InterbankOtcContractStatus.ACTIVE,
                LocalDate.now().minusDays(1), "AAPL", BigDecimal.valueOf(10), BigDecimal.valueOf(150), "USD"));

        TransactionVote vote = service.prepareLocal(optionOnlyTx("neg-1", "AAPL", 10, "USD", BigDecimal.valueOf(150)));

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).allSatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.OPTION_USED_OR_EXPIRED));
    }

    @Test
    @DisplayName("prepareLocal: contract valid but no stock companion posting → NO + OPTION_AMOUNT_INCORRECT")
    void prepareLocal_option_missingStockPosting_noVote() {
        stubOptionNegAndContract(buildContract(InterbankOtcContractStatus.ACTIVE,
                LocalDate.now().plusDays(30), "AAPL", BigDecimal.valueOf(10), BigDecimal.valueOf(150), "USD"));

        // tx has balanced option postings + monas companion but NO stock
        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-1");
        OptionDescription opt = buildOptionDescription(negId, "AAPL", BigDecimal.valueOf(10), "USD", BigDecimal.valueOf(150));
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(10), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(-10), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Account(REMOTE_RN + "000001"), BigDecimal.valueOf(-1500), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(REMOTE_RN + "000002"), BigDecimal.valueOf(1500), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD)))
        ), new ForeignBankId(MY_RN, "tx-no-stock"), null, null, null, null);

        TransactionVote vote = service.prepareLocal(tx);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).allSatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.OPTION_AMOUNT_INCORRECT));
    }

    @Test
    @DisplayName("prepareLocal: contract valid but no monas companion posting → NO + OPTION_AMOUNT_INCORRECT")
    void prepareLocal_option_missingMoneyPosting_noVote() {
        stubOptionNegAndContract(buildContract(InterbankOtcContractStatus.ACTIVE,
                LocalDate.now().plusDays(30), "AAPL", BigDecimal.valueOf(10), BigDecimal.valueOf(150), "USD"));

        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-1");
        OptionDescription opt = buildOptionDescription(negId, "AAPL", BigDecimal.valueOf(10), "USD", BigDecimal.valueOf(150));
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(10), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(-10), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "seller")), BigDecimal.valueOf(-10), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "buyer")), BigDecimal.valueOf(10), new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(MY_RN, "tx-no-monas"), null, null, null, null);

        TransactionVote vote = service.prepareLocal(tx);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).allSatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.OPTION_AMOUNT_INCORRECT));
    }

    @Test
    @DisplayName("prepareLocal: wrong stock amount → NO + OPTION_AMOUNT_INCORRECT")
    void prepareLocal_option_wrongStockAmount_noVote() {
        stubOptionNegAndContract(buildContract(InterbankOtcContractStatus.ACTIVE,
                LocalDate.now().plusDays(30), "AAPL", BigDecimal.valueOf(10), BigDecimal.valueOf(150), "USD"));

        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-1");
        OptionDescription opt = buildOptionDescription(negId, "AAPL", BigDecimal.valueOf(10), "USD", BigDecimal.valueOf(150));
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(10), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(-10), new Asset.OptionAsset(opt)),
                // wrong stock amount: 5 instead of 10
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "seller")), BigDecimal.valueOf(-5), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "buyer")), BigDecimal.valueOf(5), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Account(REMOTE_RN + "000001"), BigDecimal.valueOf(-1500), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(REMOTE_RN + "000002"), BigDecimal.valueOf(1500), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD)))
        ), new ForeignBankId(MY_RN, "tx-wrong-stock-amt"), null, null, null, null);

        TransactionVote vote = service.prepareLocal(tx);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).allSatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.OPTION_AMOUNT_INCORRECT));
    }

    @Test
    @DisplayName("prepareLocal: wrong money amount → NO + OPTION_AMOUNT_INCORRECT")
    void prepareLocal_option_wrongMoneyAmount_noVote() {
        stubOptionNegAndContract(buildContract(InterbankOtcContractStatus.ACTIVE,
                LocalDate.now().plusDays(30), "AAPL", BigDecimal.valueOf(10), BigDecimal.valueOf(150), "USD"));

        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-1");
        OptionDescription opt = buildOptionDescription(negId, "AAPL", BigDecimal.valueOf(10), "USD", BigDecimal.valueOf(150));
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(10), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(-10), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "seller")), BigDecimal.valueOf(-10), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "buyer")), BigDecimal.valueOf(10), new Asset.Stock(new StockDescription("AAPL"))),
                // wrong money amount: 999 instead of 1500 (10 × 150)
                new Posting(new TxAccount.Account(REMOTE_RN + "000001"), BigDecimal.valueOf(-999), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(REMOTE_RN + "000002"), BigDecimal.valueOf(999), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD)))
        ), new ForeignBankId(MY_RN, "tx-wrong-money-amt"), null, null, null, null);

        TransactionVote vote = service.prepareLocal(tx);

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.NO);
        assertThat(vote.reasons()).allSatisfy(r ->
                assertThat(r.reason()).isEqualTo(NoVoteReason.Reason.OPTION_AMOUNT_INCORRECT));
    }

    @Test
    @DisplayName("prepareLocal: valid option with correct companions → YES, no reserve calls for option")
    void prepareLocal_option_allCorrect_yesVote() {
        stubOptionNegAndContract(buildContract(InterbankOtcContractStatus.ACTIVE,
                LocalDate.now().plusDays(30), "AAPL", BigDecimal.valueOf(10), BigDecimal.valueOf(150), "USD"));

        TransactionVote vote = service.prepareLocal(fullOptionTx("neg-1", "AAPL", 10, "USD", BigDecimal.valueOf(150)));

        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
        verifyNoInteractions(reservationApplier);
    }

    // =========================================================================
    // commitLocal — option (C-1 fix po Celini 5 audit-u: EXERCISED se flip-uje
    // SAMO za exercise-shape transakcije, ne za sve OptionAsset+Option postings.
    // Accept tx (po §3.6 PERSON-only) NE sme da flip-uje EXERCISED.)
    // =========================================================================

    @Test
    @DisplayName("commitLocal: exercise-shape tx (Stock+Option posting) → contract marked EXERCISED")
    void commitLocal_exerciseShape_contractMarkedExercised() throws Exception {
        // §2.7.2 exercise tx ima (Stock, Option) posting — to je nas EXERCISED marker.
        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-exercise");
        BigDecimal qty = BigDecimal.valueOf(10);
        BigDecimal money = BigDecimal.valueOf(1500); // 10 × 150
        Transaction tx = new Transaction(List.of(
                // (Stock, Option) posting — exercise marker
                new Posting(new TxAccount.Option(negId), qty.negate(), new Asset.Stock(new StockDescription("AAPL"))),
                // (Stock, Person) posting — receiving buyer (REMOTE, skipped lokalno)
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "buyer")), qty, new Asset.Stock(new StockDescription("AAPL"))),
                // (Monas, Option) — option ac receives money
                new Posting(new TxAccount.Option(negId), money, new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                // (Monas, Account) — buyer's account REMOTE
                new Posting(new TxAccount.Account(REMOTE_RN + "000001"), money.negate(), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD)))
        ), new ForeignBankId(MY_RN, "tx-exercise"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(55L);
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(MY_RN), eq("neg-exercise"))).thenReturn(Optional.of(neg));

        InterbankOtcContract contract = new InterbankOtcContract();
        contract.setStatus(InterbankOtcContractStatus.ACTIVE);
        when(otcContractRepository.findBySourceNegotiationIdForUpdate(55L)).thenReturn(Optional.of(contract));
        when(otcContractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.commitLocal(tx.transactionId());

        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.EXERCISED);
        assertThat(contract.getExercisedAt()).isNotNull();
        verify(otcContractRepository, atLeastOnce()).save(contract);
    }

    // =========================================================================
    // T5(A) — S9 seller-settlement na INBOUND exercise (MI smo prodavac/option host).
    // Option pseudo-account legovi su LOKALNI (negotiationId.routingNumber == MY_RN).
    // commitLocal MORA: (1) osloboditi+predati prodavcevih k rezervisanih hartija
    // (commitStock isDebit=false), (2) kreditirati prodavca strike k*pi
    // (commitRecipientCredit), i (3) flip-ovati EXERCISED TEK posle settlement-a.
    // =========================================================================

    @Test
    @DisplayName("T5(A): inbound exercise (we are seller) → commitStock(debit=false) deliver k + commitRecipientCredit strike, EXERCISED after settlement")
    void commitLocal_inboundExercise_weAreSeller_settlesAndExercises() throws Exception {
        // Inbound exercise tx koju je kreirala kupceva banka (§2.7.2). Option
        // pseudo-account je u NASOJ banci (MI smo seller/host) → Option legovi su LOKALNI.
        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-sell-ex");
        BigDecimal qty = BigDecimal.valueOf(10);
        BigDecimal money = BigDecimal.valueOf(1500); // 10 × 150
        Transaction tx = new Transaction(List.of(
                // (Monas, Option) +pi*k — option ac prima novac (LOKALNO)
                new Posting(new TxAccount.Option(negId), money, new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                // (Monas, Account) -pi*k — kupac placa (REMOTE)
                new Posting(new TxAccount.Account(REMOTE_RN + "000001"), money.negate(), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                // (Stock, Option) -k — option ac predaje hartije (LOKALNO)
                new Posting(new TxAccount.Option(negId), qty.negate(), new Asset.Stock(new StockDescription("AAPL"))),
                // (Stock, Person) +k — kupac prima hartije (REMOTE)
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "buyer")), qty, new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(REMOTE_RN, "tx-sell-ex"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        // Pregovor + ugovor: MI smo SELLER, lokalni prodavac je klijent 42, ticker/qty/strike.
        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(77L);
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(MY_RN), eq("neg-sell-ex"))).thenReturn(Optional.of(neg));

        InterbankOtcContract contract = new InterbankOtcContract();
        contract.setStatus(InterbankOtcContractStatus.ACTIVE);
        contract.setLocalPartyType(InterbankPartyType.SELLER);
        contract.setLocalPartyId(42L);
        contract.setLocalPartyRole("CLIENT");
        contract.setTicker("AAPL");
        contract.setQuantity(qty);
        contract.setStrikePrice(BigDecimal.valueOf(150));
        contract.setStrikeCurrency("USD");
        when(otcContractRepository.findBySourceNegotiationIdForUpdate(77L)).thenReturn(Optional.of(contract));
        when(otcContractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Prodavcev USD racun (rezolucija po klijent id + valuta).
        Account sellerUsd = buildAccount(MY_RN + "700055", "USD", BigDecimal.valueOf(1000), AccountStatus.ACTIVE);
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(42L, AccountStatus.ACTIVE))
                .thenReturn(List.of(sellerUsd));

        service.commitLocal(tx.transactionId());

        // (1) prodavcevih k rezervisanih hartija oslobodjeno+predato (debit=false).
        verify(reservationApplier).commitStock(
                anyString(), eq(42L), eq("CLIENT"), eq("AAPL"), eq(10), eq(false));
        // (2) prodavac kreditiran strike (k*pi) na svom USD racunu, FX-aware.
        verify(reservationApplier).commitRecipientCredit(
                eq(sellerUsd.getAccountNumber()), eq(money), eq("USD"), any());
        // (3) status EXERCISED (posle settlement-a).
        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.EXERCISED);
        assertThat(contract.getExercisedAt()).isNotNull();
    }

    // ── BUG-1 [MONEY DESTROYED]: inbound exercise sa EKSPLICITNIM seller-cash leg-om ──
    // Banka-1 (i nas novi outbound) salju EXERCISE tx sa eksplicitnim
    // "credit seller real cash pi*k" leg-om: Posting(Person(ourSeller), -pi*k, Monas).
    // Kad smo MI seller (option-host), prodavca kreditiramo TACNO JEDNOM kroz
    // settleSellerOnInboundExercise (OPTION→prodavac sweep); eksplicitni leg je
    // recognized no-op (bez druge rezervacije/commit-a → bez dvostrukog kredita).

    /** Inbound EXERCISE tx u Banka-1 obliku: OPTION +pi*k, seller(Person, lokalan) -pi*k, OPTION -k stock, buyer(Person, remote) +k. */
    private Transaction inboundExerciseWithSellerCashLeg(String negIdStr, String sellerForeignId,
            BigDecimal qty, BigDecimal money, String txId) {
        ForeignBankId negId = new ForeignBankId(MY_RN, negIdStr);
        return new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), money,
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),                 // OPTION money +pi*k (LOKALNO host)
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, sellerForeignId)),
                        money.negate(), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))), // seller-cash credit -pi*k (LOKALNO)
                new Posting(new TxAccount.Option(negId), qty.negate(),
                        new Asset.Stock(new StockDescription("AAPL"))),                         // OPTION stock -k (LOKALNO)
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "C-buyer")),
                        qty, new Asset.Stock(new StockDescription("AAPL")))                     // buyer +k (REMOTE)
        ), new ForeignBankId(REMOTE_RN, txId), null, null, null, null);
    }

    private InterbankOtcContract sellerSideContract(InterbankOtcNegotiation neg, Long sourceNegId,
            String sellerRole, Long sellerId, BigDecimal qty, BigDecimal strikePrice) {
        neg.setId(sourceNegId);
        InterbankOtcContract contract = new InterbankOtcContract();
        contract.setStatus(InterbankOtcContractStatus.ACTIVE);
        contract.setLocalPartyType(InterbankPartyType.SELLER);
        contract.setLocalPartyId(sellerId);
        contract.setLocalPartyRole(sellerRole);
        contract.setTicker("AAPL");
        contract.setQuantity(qty);
        contract.setStrikePrice(strikePrice);
        contract.setStrikeCurrency("USD");
        return contract;
    }

    @Test
    @DisplayName("BUG-1: inbound exercise WITH seller-cash leg (we are seller) → seller credited EXACTLY once (sweep), no commitMonas/reserveMonas on the leg, money conserved")
    void commitLocal_inboundExercise_withSellerCashLeg_creditsSellerExactlyOnce() throws Exception {
        BigDecimal qty = BigDecimal.valueOf(10);
        BigDecimal money = BigDecimal.valueOf(1500); // 10 × 150
        Transaction tx = inboundExerciseWithSellerCashLeg("neg-ex-scash", "C-42", qty, money, "tx-ex-scash");

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(MY_RN), eq("neg-ex-scash"))).thenReturn(Optional.of(neg));
        InterbankOtcContract contract = sellerSideContract(neg, 77L, "CLIENT", 42L, qty, BigDecimal.valueOf(150));
        // isInboundExerciseSellerCashCredit gleda non-locking; settle gleda forUpdate.
        lenient().when(otcContractRepository.findBySourceNegotiationId(77L)).thenReturn(Optional.of(contract));
        when(otcContractRepository.findBySourceNegotiationIdForUpdate(77L)).thenReturn(Optional.of(contract));
        when(otcContractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account sellerUsd = buildAccount(MY_RN + "700055", "USD", BigDecimal.valueOf(1000), AccountStatus.ACTIVE);
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(42L, AccountStatus.ACTIVE))
                .thenReturn(List.of(sellerUsd));

        service.commitLocal(tx.transactionId());

        // Prodavac kreditiran TACNO JEDNOM kroz sweep (commitRecipientCredit), strike u USD.
        verify(reservationApplier, times(1)).commitRecipientCredit(
                eq(sellerUsd.getAccountNumber()), eq(money), eq("USD"), any());
        // Eksplicitni seller-cash leg NIJE prouzrokovao commitMonas (to bi DEBITOVALO prodavca → dvostruko).
        verify(reservationApplier, never()).commitMonas(eq(sellerUsd.getAccountNumber()), any());
        // Hartije isporucene jednom; status EXERCISED.
        verify(reservationApplier, times(1)).commitStock(
                anyString(), eq(42L), eq("CLIENT"), eq("AAPL"), eq(10), eq(false));
        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.EXERCISED);
    }

    @Test
    @DisplayName("BUG-1 N3: inbound exercise WITH seller-cash leg → handleNewTx YES (NOT rejected as drain), seller account NOT reserved")
    void handleNewTx_inboundExerciseSellerCashLeg_votesYes_noReserveOnSeller() throws Exception {
        BigDecimal qty = BigDecimal.valueOf(10);
        BigDecimal money = BigDecimal.valueOf(1500);
        Transaction tx = inboundExerciseWithSellerCashLeg("neg-ex-n3", "C-42", qty, money, "tx-ex-n3");
        IdempotenceKey key = new IdempotenceKey(REMOTE_RN, "ex-n3-key");

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(MY_RN), eq("neg-ex-n3"))).thenReturn(Optional.of(neg));
        InterbankOtcContract contract = sellerSideContract(neg, 88L, "CLIENT", 42L, qty, BigDecimal.valueOf(150));
        contract.setSettlementDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30));
        when(otcContractRepository.findBySourceNegotiationId(88L)).thenReturn(Optional.of(contract));

        // Prodavcev USD racun POSTOJI (ali NE sme da bude rezervisan — prodavac prima, ne placa).
        Account sellerUsd = buildAccount(MY_RN + "700055", "USD", BigDecimal.valueOf(1000), AccountStatus.ACTIVE);
        lenient().when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(42L, AccountStatus.ACTIVE))
                .thenReturn(List.of(sellerUsd));
        // Prodavac drzi rezervisane hartije (Stock+Option validacija companion-a).
        lenient().when(tradingServiceClient.findListingByTicker("AAPL"))
                .thenReturn(Optional.of(listingDto(7L, "AAPL")));
        stubTxSave();

        TransactionVote vote = service.handleNewTx(tx, key);

        // Seller-cash leg je prepoznat (NIJE N3 drain) → balansiran exercise → YES.
        assertThat(vote.vote()).isEqualTo(TransactionVote.Vote.YES);
        // Prodavcev racun NIJE rezervisan (seller PRIMA strike, ne placa).
        verify(reservationApplier, never()).reserveMonas(eq(sellerUsd.getAccountNumber()), any());
    }

    @Test
    @DisplayName("T5(A): seller settlement fails (commitStock throws) → status NOT flipped to EXERCISED")
    void commitLocal_inboundExercise_settlementFails_doesNotFlipExercised() throws Exception {
        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-sell-fail");
        BigDecimal qty = BigDecimal.valueOf(10);
        BigDecimal money = BigDecimal.valueOf(1500);
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), money, new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(REMOTE_RN + "000001"), money.negate(), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Option(negId), qty.negate(), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "buyer")), qty, new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(REMOTE_RN, "tx-sell-fail"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(88L);
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(MY_RN), eq("neg-sell-fail"))).thenReturn(Optional.of(neg));

        InterbankOtcContract contract = new InterbankOtcContract();
        contract.setStatus(InterbankOtcContractStatus.ACTIVE);
        contract.setLocalPartyType(InterbankPartyType.SELLER);
        contract.setLocalPartyId(42L);
        contract.setLocalPartyRole("CLIENT");
        contract.setTicker("AAPL");
        contract.setQuantity(qty);
        contract.setStrikePrice(BigDecimal.valueOf(150));
        contract.setStrikeCurrency("USD");
        when(otcContractRepository.findBySourceNegotiationIdForUpdate(88L)).thenReturn(Optional.of(contract));

        // Prodavcev USD racun POSTOJI (FINDING 1 reorder: resolucija racuna ide PRVA;
        // bez stuba bi settle pukao na resolve-u, ne na commitStock-u — a ovde testiramo
        // bas commitStock-failure granu).
        Account sellerUsd = buildAccount(MY_RN + "700088", "USD", BigDecimal.valueOf(1000), AccountStatus.ACTIVE);
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(42L, AccountStatus.ACTIVE))
                .thenReturn(List.of(sellerUsd));

        // Settlement pukne na isporuci hartija (commitStock) — POSLE uspesne rezolucije
        // racuna. Status NE sme da se flip-uje (settle baca → @Transactional rollback).
        doThrow(new InterbankExceptions.InterbankProtocolException("trading down"))
                .when(reservationApplier).commitStock(anyString(), eq(42L), eq("CLIENT"), eq("AAPL"), eq(10), eq(false));

        assertThatThrownBy(() -> service.commitLocal(tx.transactionId()))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class);

        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.ACTIVE);
        assertThat(contract.getExercisedAt()).isNull();
    }

    @Test
    @DisplayName("T5(A) FINDING-1: inbound exercise, prodavac nema racun u strike valuti → settle baca PRE commitStock (hartije se NE isporucuju), contract ostaje ACTIVE")
    void settleSellerInboundExercise_sellerHasNoStrikeAccount_noStockDelivered() throws Exception {
        // FINDING 1 — stranded share delivery: ako prodavac nema ACTIVE racun u
        // strike valuti, resolucija racuna baca. Pre fix-a je commitStock (REALAN
        // out-of-process side effect — isporuka k hartija trading-service-u) bio
        // pozvan PRE bacajuce rezolucije → hartije zaglavljene, prodavac nikad
        // kreditiran, contract nikad ne flip-uje → §2.9 re-delivery petlja. Posle
        // fix-a (resolve-first), settle baca PRE bilo kakvog commitStock-a.
        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-sell-noacct");
        BigDecimal qty = BigDecimal.valueOf(10);
        BigDecimal money = BigDecimal.valueOf(1500); // 10 × 150
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), money, new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(REMOTE_RN + "000001"), money.negate(), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Option(negId), qty.negate(), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "buyer")), qty, new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(REMOTE_RN, "tx-sell-noacct"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(99L);
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(MY_RN), eq("neg-sell-noacct"))).thenReturn(Optional.of(neg));

        InterbankOtcContract contract = new InterbankOtcContract();
        contract.setStatus(InterbankOtcContractStatus.ACTIVE);
        contract.setLocalPartyType(InterbankPartyType.SELLER);
        contract.setLocalPartyId(42L);
        contract.setLocalPartyRole("CLIENT");
        contract.setTicker("AAPL");
        contract.setQuantity(qty);
        contract.setStrikePrice(BigDecimal.valueOf(150));
        contract.setStrikeCurrency("USD");
        when(otcContractRepository.findBySourceNegotiationIdForUpdate(99L)).thenReturn(Optional.of(contract));

        // Prodavac NEMA nijedan racun u strike valuti (USD) → resolucija baca.
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(42L, AccountStatus.ACTIVE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.commitLocal(tx.transactionId()))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class);

        // commitStock NIJE pozvan (resolve-first → nema stranded isporuke hartija).
        verify(reservationApplier, never())
                .commitStock(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyBoolean());
        // Prirodno ni kredit nije isao, i contract ostaje ACTIVE (nema flip-a).
        verify(reservationApplier, never())
                .commitRecipientCredit(anyString(), any(), anyString(), any());
        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.ACTIVE);
        assertThat(contract.getExercisedAt()).isNull();
    }

    @Test
    @DisplayName("T5(A) FINDING-2: drugi commit exercise-a cija je contract vec EXERCISED → settle se preskace (nema dvostruke isporuke/kredita)")
    void commitLocal_inboundExercise_alreadyExercised_skipsSettlement() throws Exception {
        // FINDING 2 — konkurentni double-exercise: dve razlicite-txId exercise poruke
        // istog ugovora oba commit-uju. Bez statusnog gate-a + lock-a, oba settle-uju
        // → 2k hartija isporuceno i 2×strike kreditirano. Posle fix-a, settle se gate-uje
        // na contract.status: ako je vec EXERCISED, settle je no-op (nema novog
        // commitStock-a ni commitRecipientCredit-a). Ovde simuliramo "drugi" commit
        // tako sto je contract vec u EXERCISED stanju.
        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-sell-dup");
        BigDecimal qty = BigDecimal.valueOf(10);
        BigDecimal money = BigDecimal.valueOf(1500);
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), money, new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(REMOTE_RN + "000001"), money.negate(), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Option(negId), qty.negate(), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "buyer")), qty, new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(REMOTE_RN, "tx-sell-dup-2"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(101L);
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(MY_RN), eq("neg-sell-dup"))).thenReturn(Optional.of(neg));

        // Contract je VEC EXERCISED (prvi exercise ga je vec settle-ovao i flip-ovao).
        InterbankOtcContract contract = new InterbankOtcContract();
        contract.setStatus(InterbankOtcContractStatus.EXERCISED);
        contract.setExercisedAt(LocalDateTime.now().minusMinutes(1));
        contract.setLocalPartyType(InterbankPartyType.SELLER);
        contract.setLocalPartyId(42L);
        contract.setLocalPartyRole("CLIENT");
        contract.setTicker("AAPL");
        contract.setQuantity(qty);
        contract.setStrikePrice(BigDecimal.valueOf(150));
        contract.setStrikeCurrency("USD");
        when(otcContractRepository.findBySourceNegotiationIdForUpdate(101L)).thenReturn(Optional.of(contract));

        // Seller-account stub je lenient: NE sme da bude potreban (settle se preskace),
        // ali ga drzimo da bi pre-fix kod (koji bi settle-ovao) stigao do verify-a.
        Account sellerUsd = buildAccount(MY_RN + "700099", "USD", BigDecimal.valueOf(1000), AccountStatus.ACTIVE);
        lenient().when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(42L, AccountStatus.ACTIVE))
                .thenReturn(List.of(sellerUsd));

        service.commitLocal(tx.transactionId());

        // Settle se preskace — NEMA dvostruke isporuke hartija ni dvostrukog kredita.
        verify(reservationApplier, never())
                .commitStock(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyBoolean());
        verify(reservationApplier, never())
                .commitRecipientCredit(anyString(), any(), anyString(), any());
        // Status ostaje EXERCISED (idempotentno).
        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.EXERCISED);
    }

    @Test
    @DisplayName("FIX B (buyer-credit simetrija): drugi commit exercise-a gde smo BUYER a contract je vec EXERCISED → buyer stock kredit se preskace (nema dvostrukog kredita kupcu)")
    void commitLocal_inboundExercise_buyerSide_alreadyExercised_skipsBuyerStockCredit() throws Exception {
        // FIX B: kad smo MI BUYER, buyer-stock leg (Stock+Person@MY_RN, +k) se primenjuje
        // UNUTAR petlje (commitStock isDebit=false) i bio je gated SAMO per-txId. Retry sa
        // NOVIM txId za vec-EXERCISED ugovor bi dvostruko kreditirao kupcu hartije. Sad je
        // taj leg gated per-CONTRACT (findBySourceNegotiationIdForUpdate + status). Ovde
        // simuliramo "drugi" commit: contract je vec EXERCISED → buyer stock commit se
        // preskace.
        ForeignBankId negId = new ForeignBankId(REMOTE_RN, "neg-buy-dup"); // option pseudo na SELLER (B1) routingu
        BigDecimal qty = BigDecimal.valueOf(7);
        BigDecimal money = BigDecimal.valueOf(861);
        // Kanonski reverse-exercise oblik, MI (222) smo BUYER:
        //  p1 OPTION@111 +money  (option pseudo prima novac — REMOTE, skip)
        //  p2 PERSON@111 -money  (seller credit — REMOTE, skip)
        //  p3 OPTION@111 -k STOCK (seller delivery — REMOTE, ali nosi (Stock,Option) signature → isExercise)
        //  p4 PERSON@222 +k STOCK (NASA buyer-stock isporuka — LOKALNO, gated)
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), money, new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "C-5")), money.negate(), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Option(negId), qty.negate(), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "C-9")), qty, new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(MY_RN, "tx-buy-dup-2"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(202L);
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(REMOTE_RN), eq("neg-buy-dup"))).thenReturn(Optional.of(neg));

        // Contract je VEC EXERCISED i MI smo BUYER.
        InterbankOtcContract contract = new InterbankOtcContract();
        contract.setStatus(InterbankOtcContractStatus.EXERCISED);
        contract.setExercisedAt(LocalDateTime.now().minusMinutes(1));
        contract.setLocalPartyType(InterbankPartyType.BUYER);
        contract.setLocalPartyId(9L);
        contract.setLocalPartyRole("CLIENT");
        contract.setTicker("AAPL");
        contract.setQuantity(qty);
        contract.setStrikePrice(BigDecimal.valueOf(123));
        contract.setStrikeCurrency("USD");
        when(otcContractRepository.findBySourceNegotiationIdForUpdate(202L)).thenReturn(Optional.of(contract));

        service.commitLocal(tx.transactionId());

        // FIX B: buyer-stock kredit se preskace — NEMA commitStock poziva (ni isDebit=false).
        verify(reservationApplier, never())
                .commitStock(anyString(), anyLong(), anyString(), anyString(), anyInt(), anyBoolean());
        // Nismo SELLER pa ni seller-settle (nema commitRecipientCredit).
        verify(reservationApplier, never())
                .commitRecipientCredit(anyString(), any(), anyString(), any());
        // Status ostaje EXERCISED (idempotentno).
        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.EXERCISED);
    }

    @Test
    @DisplayName("FIX B (buyer-credit simetrija): prvi commit exercise-a gde smo BUYER a contract je ACTIVE → buyer stock kredit SE primenjuje (gate otvoren)")
    void commitLocal_inboundExercise_buyerSide_active_appliesBuyerStockCredit() throws Exception {
        // Regression guard: gate ne sme da blokira PRVI exercise (ACTIVE ugovor). Buyer
        // stock kredit MORA da se primeni tacno jednom.
        ForeignBankId negId = new ForeignBankId(REMOTE_RN, "neg-buy-first");
        BigDecimal qty = BigDecimal.valueOf(5);
        BigDecimal money = BigDecimal.valueOf(615);
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), money, new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "C-5")), money.negate(), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Option(negId), qty.negate(), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "C-9")), qty, new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(MY_RN, "tx-buy-first"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(303L);
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(REMOTE_RN), eq("neg-buy-first"))).thenReturn(Optional.of(neg));

        InterbankOtcContract contract = new InterbankOtcContract();
        contract.setStatus(InterbankOtcContractStatus.ACTIVE);
        contract.setLocalPartyType(InterbankPartyType.BUYER);
        contract.setLocalPartyId(9L);
        contract.setLocalPartyRole("CLIENT");
        contract.setTicker("AAPL");
        contract.setQuantity(qty);
        contract.setStrikePrice(BigDecimal.valueOf(123));
        contract.setStrikeCurrency("USD");
        when(otcContractRepository.findBySourceNegotiationIdForUpdate(303L)).thenReturn(Optional.of(contract));

        service.commitLocal(tx.transactionId());

        // Gate otvoren (ACTIVE): buyer stock leg (PERSON@MY_RN, +k → isDebit=true u B2
        // konvenciji `isDebit = amount > 0`) primenjen TACNO JEDNOM (kredit kupcu).
        verify(reservationApplier, times(1))
                .commitStock(anyString(), eq(9L), eq("CLIENT"), eq("AAPL"), eq(5), eq(true));
        // Posle settle-a contract flip-ovan na EXERCISED (seller blok flip).
        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.EXERCISED);
    }

    @Test
    @DisplayName("commitLocal: accept-shape tx (OptionAsset+Person only) → contract NOT flipped to EXERCISED")
    void commitLocal_acceptShape_doesNotFlipExercised() throws Exception {
        // §3.6 accept-shape: 4 PERSON-only postings, NO TxAccount.Option, NO Stock+Option.
        // Pre C-1 fix-a, ovakav tx je krsio §3.6 (Option umesto Person) i komitovao
        // bi se kao "EXERCISED" — sto je bilo greska (accept != exercise). Sad sa
        // ispravnim postings-ima, contract status MORA da ostane ACTIVE.
        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-accept");
        OptionDescription opt = buildOptionDescription(negId, "AAPL", BigDecimal.ONE, "USD", BigDecimal.valueOf(150));
        BigDecimal premium = BigDecimal.valueOf(700);
        Transaction tx = new Transaction(List.of(
                // Buyer credit premium (remote, skipped lokalno)
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "C-1")),
                        premium.negate(), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                // Seller debit premium (local)
                new Posting(new TxAccount.Account(MY_RN + "000001"), premium, new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                // Buyer debit option (remote, skipped lokalno)
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "C-1")),
                        BigDecimal.ONE, new Asset.OptionAsset(opt)),
                // Seller credit option (local, PERSON ne OPTION — C-1 fix)
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "C-7")),
                        BigDecimal.ONE.negate(), new Asset.OptionAsset(opt))
        ), new ForeignBankId(MY_RN, "tx-accept"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        // Note: commitMonas i razne reservation pozive ide kroz reservationApplier
        // mock — accountRepository nije direktno dotaknuto.

        InterbankOtcContract contract = new InterbankOtcContract();
        contract.setStatus(InterbankOtcContractStatus.ACTIVE);

        service.commitLocal(tx.transactionId());

        // C-1 fix: accept-shape tx (NO Stock+Option posting) ne flip-uje EXERCISED.
        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.ACTIVE);
        verify(otcContractRepository, never()).save(any());
    }

    @Test
    @DisplayName("commitLocal: already COMMITTED → no contract lookup (idempotent)")
    void commitLocal_alreadyCommitted_noContractLookup() throws Exception {
        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-idem");
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(-10), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "buyer")), BigDecimal.valueOf(10), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(1500), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(REMOTE_RN + "000001"), BigDecimal.valueOf(-1500), new Asset.Monas(new MonetaryAsset(CurrencyCode.USD)))
        ), new ForeignBankId(MY_RN, "tx-opt-idem"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.COMMITTED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));

        service.commitLocal(tx.transactionId());

        verifyNoInteractions(otcNegotiationRepository, otcContractRepository);
    }

    // =========================================================================
    // rollbackLocal — option (no-op)
    // =========================================================================

    @Test
    @DisplayName("rollbackLocal: option posting is a no-op — no contract lookup, no release calls")
    void rollbackLocal_optionPosting_noOpOnContract() throws Exception {
        ForeignBankId negId = new ForeignBankId(MY_RN, "neg-rb");
        OptionDescription opt = buildOptionDescription(negId, "AAPL", BigDecimal.valueOf(10), "USD", BigDecimal.valueOf(150));
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(-10), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(10), new Asset.OptionAsset(opt))
        ), new ForeignBankId(MY_RN, "tx-opt-rb"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARING);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.rollbackLocal(tx.transactionId());

        verifyNoInteractions(otcNegotiationRepository, otcContractRepository, reservationApplier);
        assertThat(ibt.getStatus()).isEqualTo(InterbankTransactionStatus.ROLLED_BACK);
    }

    @Test
    @DisplayName("F2: rollbackLocal accept-shape — buyer Monas+Person premium leg → releaseMonas(chosenAccount, premium)")
    void rollbackLocal_buyerPremiumMonasPerson_releasesReservation() throws Exception {
        // F2 — buyer premium se rezervise (prepare Pass-2) na Monas+Person leg-u na
        // stabilnom settlement racunu. Kad accept abortira (seller NO / 2PC abort /
        // presumed-abort), rollbackLocal MORA osloboditi BAS tu rezervaciju, inace
        // ostaje zaglavljena zauvek. Resolucija mora biti identicna prepare/commit-u
        // (izabrani settlement racun s pregovora).
        ForeignBankId negId = new ForeignBankId(REMOTE_RN, "neg-seller"); // autoritativan kod prodavca
        OptionDescription opt = buildOptionDescription(
                negId, "AAPL", BigDecimal.valueOf(50), "USD", BigDecimal.valueOf(200));
        BigDecimal premium = BigDecimal.valueOf(700);
        String settlementAccount = MY_RN + "700002";

        // Lokalni mirror pregovora: MI smo BUYER, settlement racun je izabran pri accept-u.
        InterbankOtcNegotiation buyerNeg = new InterbankOtcNegotiation();
        buyerNeg.setLocalPartyType(InterbankPartyType.BUYER);
        buyerNeg.setBuyerSettlementAccountNumber(settlementAccount);
        when(otcNegotiationRepository
                        .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(eq(REMOTE_RN), eq("neg-seller")))
                .thenReturn(Optional.of(buyerNeg));

        // §3.6 accept-shape: buyer (LOCAL Person) charge premium (CREDIT, negativan iznos),
        // seller (REMOTE Account) prima; optionContract legovi Person↔Person.
        ForeignBankId buyerParty = new ForeignBankId(MY_RN, "C-7");
        ForeignBankId sellerParty = new ForeignBankId(REMOTE_RN, "C-1");
        Transaction tx = new Transaction(List.of(
                new Posting(new TxAccount.Person(buyerParty), premium.negate(),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Account(ACCT_REMOTE), premium,
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.USD))),
                new Posting(new TxAccount.Person(buyerParty), BigDecimal.ONE,
                        new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Person(sellerParty), BigDecimal.ONE.negate(),
                        new Asset.OptionAsset(opt))
        ), new ForeignBankId(REMOTE_RN, "accept-rollback-1"), null, null, null, null);

        InterbankTransaction ibt = savedIbt(tx, InterbankTransactionStatus.PREPARED);
        when(txRepo.findForUpdateByTransactionRoutingNumberAndTransactionIdString(anyInt(), any()))
                .thenReturn(Optional.of(ibt));
        stubTxSave();

        service.rollbackLocal(tx.transactionId());

        // Rezervacija premije je oslobodjena na ISTOM (izabranom) settlement racunu.
        verify(reservationApplier).releaseMonas(eq(settlementAccount), eq(premium));
        assertThat(ibt.getStatus()).isEqualTo(InterbankTransactionStatus.ROLLED_BACK);
    }

    // =========================================================================
    // R1-663b — assetKey grupisanje (krhka pretpostavka: OptionAsset po negotiationId)
    // =========================================================================

    /**
     * R1-663b: {@code assetKey} grupisanje za balanced-check je krhka pretpostavka bez
     * testa. Pinujemo format po varijanti aseta:
     *  - Monas  → "MONAS:" + valuta (RSD/EUR/...)
     *  - Stock  → "STOCK:" + ticker
     *  - OptionAsset → "OPTION:" + negotiationId.id   (KLJUCNA pretpostavka: dve noge
     *    iste opcije moraju deliti negotiationId pa se grupisu zajedno i ponistavaju)
     * Tako se eksplicitno fiksira da postings ISTE opcije (isti negotiationId) padaju u
     * istu grupu, dok razlicit negotiationId → razlicite grupe (ne ponistavaju se).
     */
    @Test
    @DisplayName("R1-663b: assetKey — Monas po valuti, Stock po tickeru, Option po negotiationId")
    void assetKey_groupsByExpectedDiscriminator() throws Exception {
        java.lang.reflect.Method m = TransactionExecutorService.class
                .getDeclaredMethod("assetKey", Asset.class);
        m.setAccessible(true);

        Asset monasRsd = new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD));
        Asset monasEur = new Asset.Monas(new MonetaryAsset(CurrencyCode.EUR));
        Asset stock = new Asset.Stock(new StockDescription("AAPL"));
        OptionDescription optDesc = new OptionDescription(
                new ForeignBankId(REMOTE_RN, "neg-42"),
                new StockDescription("AAPL"),
                new MonetaryValue(CurrencyCode.RSD, BigDecimal.TEN),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(7),
                BigDecimal.ONE);
        OptionDescription optDescOther = new OptionDescription(
                new ForeignBankId(REMOTE_RN, "neg-99"),
                new StockDescription("AAPL"),
                new MonetaryValue(CurrencyCode.RSD, BigDecimal.TEN),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(7),
                BigDecimal.ONE);
        Asset option42 = new Asset.OptionAsset(optDesc);
        Asset option99 = new Asset.OptionAsset(optDescOther);

        assertThat(m.invoke(service, monasRsd)).isEqualTo("MONAS:RSD");
        assertThat(m.invoke(service, monasEur)).isEqualTo("MONAS:EUR");
        assertThat(m.invoke(service, stock)).isEqualTo("STOCK:AAPL");
        assertThat(m.invoke(service, option42)).isEqualTo("OPTION:neg-42");
        // Razlicit negotiationId → razlicit kljuc (ne grupisu se zajedno).
        assertThat(m.invoke(service, option99)).isEqualTo("OPTION:neg-99");
        assertThat(m.invoke(service, option42)).isNotEqualTo(m.invoke(service, option99));
    }

    // =========================================================================
    // Helpers — transactions
    // =========================================================================

    /** Balanced local MONAS: 100 RSD debit from ACCT_A, 100 RSD credit from ACCT_B. */
    private Transaction localMonasTx() {
        return new Transaction(List.of(
                new Posting(new TxAccount.Account(ACCT_A), BigDecimal.valueOf(100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD))),
                new Posting(new TxAccount.Account(ACCT_B), BigDecimal.valueOf(-100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)))
        ), new ForeignBankId(MY_RN, "local-monas-1"), null, null, null, null);
    }

    /**
     * N3: realisticna INBOUND NEW_TX payload — REMOTE strana se tereti (charge,
     * negativan iznos), LOKALNA strana SAMO prima (debit-into, pozitivan iznos).
     * Lokalni racun se nikad ne tereti, pa prolazi inbound authz gate.
     */
    private Transaction inboundReceiveTx() {
        return new Transaction(List.of(
                // remote sender se tereti (charge) — koordinator (REMOTE) je vec rezervisao
                new Posting(new TxAccount.Account(ACCT_REMOTE), BigDecimal.valueOf(-100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD))),
                // lokalni primalac prima (debit-into)
                new Posting(new TxAccount.Account(ACCT_B), BigDecimal.valueOf(100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)))
        ), new ForeignBankId(REMOTE_RN, "inbound-receive-1"), null, null, null, null);
    }

    /** Same as localMonasTx but posting currency is EUR. */
    private Transaction localMonasTxEur() {
        return new Transaction(List.of(
                new Posting(new TxAccount.Account(ACCT_A), BigDecimal.valueOf(100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.EUR))),
                new Posting(new TxAccount.Account(ACCT_B), BigDecimal.valueOf(-100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.EUR)))
        ), new ForeignBankId(MY_RN, "local-monas-eur"), null, null, null, null);
    }

    /** Unbalanced: single posting, sum != 0. */
    private Transaction unbalancedTx() {
        return new Transaction(List.of(
                new Posting(new TxAccount.Account(ACCT_A), BigDecimal.valueOf(50),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.RSD)))
        ), new ForeignBankId(MY_RN, "unbalanced-1"), null, null, null, null);
    }

    /** Local STOCK: 10 AAPL, person-42 gives (credit), person-99 receives (debit). */
    private Transaction localStockTx() {
        return new Transaction(List.of(
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "99")),
                        BigDecimal.valueOf(10), new Asset.Stock(new StockDescription("AAPL"))),
                new Posting(new TxAccount.Person(new ForeignBankId(MY_RN, "42")),
                        BigDecimal.valueOf(-10), new Asset.Stock(new StockDescription("AAPL")))
        ), new ForeignBankId(MY_RN, "local-stock-1"), null, null, null, null);
    }

    /** One local posting (222999001) + one remote posting (111900001). */
    private Transaction mixedMonasTx() {
        return new Transaction(List.of(
                new Posting(new TxAccount.Account(MY_RN + "999001"), BigDecimal.valueOf(100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.EUR))),
                new Posting(new TxAccount.Account(ACCT_REMOTE), BigDecimal.valueOf(-100),
                        new Asset.Monas(new MonetaryAsset(CurrencyCode.EUR)))
        ), new ForeignBankId(MY_RN, "mixed-tx-1"), null, null, null, null);
    }

    // =========================================================================
    // Helpers — Phase1Result factory
    // =========================================================================

    private TransactionExecutorService.Phase1Result phase1Yes(IdempotenceKey key, Transaction tx) {
        Message<Transaction> env = new Message<>(key, MessageType.NEW_TX, tx);
        return new TransactionExecutorService.Phase1Result(
                yesVote(), Map.of(REMOTE_RN, key), Map.of(REMOTE_RN, env));
    }

    // =========================================================================
    // Helpers — model builders
    // =========================================================================

    private Account buildAccount(String number, String currencyCode,
            BigDecimal availableBalance, AccountStatus status) {
        Currency ccy = new Currency();
        ccy.setCode(currencyCode);
        Account a = new Account();
        a.setAccountNumber(number);
        a.setStatus(status);
        a.setAvailableBalance(availableBalance);
        a.setCurrency(ccy);
        return a;
    }

    /** InternalListingDto stub — listing-by-ticker odgovor trading-service seam-a. */
    private InternalListingDto listingDto(Long id, String ticker) {
        return new InternalListingDto(id, ticker, ticker + " Inc.", "STOCK",
                BigDecimal.valueOf(180), null, null);
    }

    /** InternalPortfolioHoldingDto stub — holding postoji, availableQuantity = quantity. */
    private InternalPortfolioHoldingDto holding(Long listingId, String ticker, int quantity) {
        return new InternalPortfolioHoldingDto(true, 1L, listingId, ticker, quantity, 0, quantity);
    }

    /** InternalPortfolioHoldingDto stub — vlasnik nema portfolio za hartiju. */
    private InternalPortfolioHoldingDto holdingMissing(String ticker) {
        return new InternalPortfolioHoldingDto(false, null, null, ticker, 0, 0, 0);
    }

    private InterbankTransaction savedIbt(Transaction tx, InterbankTransactionStatus status)
            throws JsonProcessingException {
        InterbankTransaction ibt = new InterbankTransaction();
        ibt.setTransactionRoutingNumber(tx.transactionId().routingNumber());
        ibt.setTransactionIdString(tx.transactionId().id());
        ibt.setStatus(status);
        ibt.setRole(InterbankTransaction.InterbankTransactionRole.INITIATOR);
        ibt.setTransactionBody(objectMapper.writeValueAsString(tx));
        ibt.setRetryCount(0);
        return ibt;
    }

    // =========================================================================
    // Helpers — stubs
    // =========================================================================

    /** Stubs both local accounts for a standard RSD MONAS validation. */
    private void stubMonasAccounts(String currency) {
        when(accountRepository.findByAccountNumber(ACCT_A))
                .thenReturn(Optional.of(buildAccount(ACCT_A, currency, BigDecimal.ZERO, AccountStatus.ACTIVE)));
        when(accountRepository.findByAccountNumber(ACCT_B))
                .thenReturn(Optional.of(buildAccount(ACCT_B, currency, BigDecimal.valueOf(500), AccountStatus.ACTIVE)));
    }

    private void stubTxSave() {
        lenient().when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // P2-concurrency-locks-1 (R3-1582): saveRecipientState sada koristi saveAndFlush
        // (UNIQUE constraint barijera) + findForUpdate (default Optional.empty → fresh insert).
        lenient().when(txRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private TransactionVote yesVote() {
        return new TransactionVote(TransactionVote.Vote.YES, List.of());
    }

    private TransactionVote noVote() {
        return new TransactionVote(TransactionVote.Vote.NO,
                List.of(new NoVoteReason(NoVoteReason.Reason.INSUFFICIENT_ASSET, null)));
    }

    // =========================================================================
    // Helpers — option builders
    // =========================================================================

    private OptionDescription buildOptionDescription(ForeignBankId negId, String ticker,
            BigDecimal quantity, String currencyCode, BigDecimal strikePrice) {
        CurrencyCode ccy = CurrencyCode.valueOf(currencyCode);
        return new OptionDescription(negId, new StockDescription(ticker),
                new MonetaryValue(ccy, strikePrice),
                OffsetDateTime.now().plusDays(30), quantity);
    }

    /** Balanced tx with only option postings (no companion stock/monas). */
    private Transaction optionOnlyTx(String negIdStr, String ticker, int quantity,
            String currencyCode, BigDecimal strikePrice) {
        ForeignBankId negId = new ForeignBankId(MY_RN, negIdStr);
        OptionDescription opt = buildOptionDescription(negId, ticker, BigDecimal.valueOf(quantity), currencyCode, strikePrice);
        return new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(quantity), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(-quantity), new Asset.OptionAsset(opt))
        ), new ForeignBankId(MY_RN, "tx-opt-only"), null, null, null, null);
    }

    /**
     * Full option exercise tx: balanced option postings + remote companion stock and monas.
     * amount × strikePrice = required money. All companion postings are remote so they pass
     * the local posting validation loop but are visible to the anyMatch companion check.
     */
    private Transaction fullOptionTx(String negIdStr, String ticker, int quantity,
            String currencyCode, BigDecimal strikePrice) {
        ForeignBankId negId = new ForeignBankId(MY_RN, negIdStr);
        OptionDescription opt = buildOptionDescription(negId, ticker, BigDecimal.valueOf(quantity), currencyCode, strikePrice);
        BigDecimal money = strikePrice.multiply(BigDecimal.valueOf(quantity));
        CurrencyCode ccy = CurrencyCode.valueOf(currencyCode);
        return new Transaction(List.of(
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(quantity), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Option(negId), BigDecimal.valueOf(-quantity), new Asset.OptionAsset(opt)),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "seller")), BigDecimal.valueOf(-quantity), new Asset.Stock(new StockDescription(ticker))),
                new Posting(new TxAccount.Person(new ForeignBankId(REMOTE_RN, "buyer")), BigDecimal.valueOf(quantity), new Asset.Stock(new StockDescription(ticker))),
                new Posting(new TxAccount.Account(REMOTE_RN + "000001"), money.negate(), new Asset.Monas(new MonetaryAsset(ccy))),
                new Posting(new TxAccount.Account(REMOTE_RN + "000002"), money, new Asset.Monas(new MonetaryAsset(ccy)))
        ), new ForeignBankId(MY_RN, "tx-opt-full"), null, null, null, null);
    }

    private InterbankOtcContract buildContract(InterbankOtcContractStatus status, LocalDate settlementDate,
            String ticker, BigDecimal quantity, BigDecimal strikePrice, String strikeCurrency) {
        InterbankOtcContract c = new InterbankOtcContract();
        c.setStatus(status);
        // M-2: settlement_date je sad OffsetDateTime u entitetu.
        c.setSettlementDate(settlementDate.atStartOfDay().atOffset(ZoneOffset.UTC));
        c.setTicker(ticker);
        c.setQuantity(quantity);
        c.setStrikePrice(strikePrice);
        c.setStrikeCurrency(strikeCurrency);
        return c;
    }

    private void stubOptionNegAndContract(InterbankOtcContract contract) {
        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(10L);
        when(otcNegotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                anyInt(), any())).thenReturn(Optional.of(neg));
        when(otcContractRepository.findBySourceNegotiationId(10L)).thenReturn(Optional.of(contract));
    }
}
