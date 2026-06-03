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
                otcNegotiationRepository, otcContractRepository, interbankFxService);

        ReflectionTestUtils.setField(service, "self", self);
        // P0-3: @Value polje za cross-currency inbound settlement bank lookup.
        ReflectionTestUtils.setField(service, "bankRegistrationNumber", "22200022");

        lenient().when(routing.myRoutingNumber()).thenReturn(MY_RN);

        // Parse routing number from account number prefix (first 3 digits)
        lenient().when(routing.parseRoutingNumber(any())).thenAnswer(inv -> {
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
    @DisplayName("execute local-only: NO vote → rollbackLocal called, no messageService/client interaction")
    void execute_localOnly_noVote_rollsBackLocal() {
        Transaction tx = localMonasTx();
        stubTxSave();
        when(self.prepareLocal(tx)).thenReturn(noVote());

        service.execute(tx);

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
    @DisplayName("1537: phase-2 ROLLBACK_TX throws InterbankProtocolException → execute() does NOT propagate")
    void execute_coordinator_phase2ProtocolException_doesNotPropagate() {
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

        service.execute(tx); // ne sme da baci

        verify(messageService).markOutboundFailed(eq(rbKey), contains("malformed"));
    }

    @Test
    @DisplayName("execute coordinator: remote votes NO → rollbackTxPhase called + ROLLBACK_TX sent")
    void execute_coordinator_remoteNo_rollsBackAndSendsRollback() {
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

        service.execute(tx);

        verify(self).rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN));
        verify(self, never()).commitTxPhase(any(), any());
        verify(client).sendMessage(eq(REMOTE_RN), eq(MessageType.ROLLBACK_TX), any(), eq(Void.class));
    }

    @Test
    @DisplayName("execute coordinator: prepareTxPhase returns NO → no network calls at all")
    void execute_coordinator_phase1No_noNetworkCalls() {
        Transaction tx = mixedMonasTx();
        when(self.prepareTxPhase(eq(tx), eq(Set.of(REMOTE_RN)))).thenReturn(
                new TransactionExecutorService.Phase1Result(noVote(), Map.of(), Map.of()));

        service.execute(tx);

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

        service.execute(tx);

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

        service.execute(tx);

        verify(self).rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN));
        verify(messageService).markOutboundFailed(eq(p1Key), contains("timeout"));
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

        service.execute(tx);

        // Kljucna invarijanta: rollbackTxPhase (→ rollbackLocal → releaseMonas) MORA
        // biti pozvan, inace rezervacija ostaje zakljucana.
        verify(self).rollbackTxPhase(tx.transactionId(), Set.of(REMOTE_RN));
        verify(self, never()).commitTxPhase(any(), any());
        verify(messageService).markOutboundFailed(eq(p1Key), contains("Invalid API key"));
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

        service.execute(tx);

        // Lokalni credit (sender) posting je ACCT_A (amount -100). rollbackLocal
        // MORA osloboditi tu rezervaciju — to je kljucna P1-5 invarijanta.
        verify(reservationApplier).releaseMonas(eq(ACCT_A), eq(BigDecimal.valueOf(100)));
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
        when(otcContractRepository.findBySourceNegotiationId(55L)).thenReturn(Optional.of(contract));
        when(otcContractRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.commitLocal(tx.transactionId());

        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.EXERCISED);
        assertThat(contract.getExercisedAt()).isNotNull();
        verify(otcContractRepository, atLeastOnce()).save(contract);
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
