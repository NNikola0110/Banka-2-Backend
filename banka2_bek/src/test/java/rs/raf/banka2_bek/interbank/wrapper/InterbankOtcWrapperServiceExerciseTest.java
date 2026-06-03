package rs.raf.banka2_bek.interbank.wrapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.config.InterbankProperties;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContract;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContractStatus;
import rs.raf.banka2_bek.interbank.model.InterbankOtcNegotiation;
import rs.raf.banka2_bek.interbank.model.InterbankPartyType;
import rs.raf.banka2_bek.interbank.protocol.Asset;
import rs.raf.banka2_bek.interbank.protocol.ForeignBankId;
import rs.raf.banka2_bek.interbank.protocol.Posting;
import rs.raf.banka2_bek.interbank.protocol.Transaction;
import rs.raf.banka2_bek.interbank.protocol.TxAccount;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcContractRepository;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcNegotiationRepository;
import rs.raf.banka2_bek.interbank.service.OtcNegotiationService;
import rs.raf.banka2_bek.interbank.service.TransactionExecutorService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-2 fix po Celini 5 audit-u: tests za {@link InterbankOtcWrapperService#exerciseContract}.
 *
 * <p>Pre fix-a, exerciseContract je samo postavljao status=EXERCISED lokalno — bez
 * ikakvog 2PC poziva ka prodavcevoj banci. Posle fix-a, formira 4-posting
 * transakciju po §2.7.2 i izvrsava je kroz {@link TransactionExecutorService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterbankOtcWrapperService — exerciseContract (C-2 fix)")
class InterbankOtcWrapperServiceExerciseTest {

    private static final int OUR_RN = 222;
    private static final int SELLER_RN = 111;

    @Mock private OtcNegotiationService negotiationService;
    @Mock private InterbankOtcNegotiationRepository negotiationRepository;
    @Mock private InterbankOtcContractRepository contractRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private TradingServiceInternalClient tradingServiceClient;
    @Mock private AccountRepository accountRepository;
    @Mock private TransactionExecutorService transactionExecutor;
    @Mock private rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository interbankTransactionRepository;
    @Mock private rs.raf.banka2_bek.payment.repository.PaymentRepository paymentRepository;
    @Mock private rs.raf.banka2_bek.interbank.service.InterbankReservationApplier reservationApplier;

    private InterbankProperties properties;
    private InterbankOtcWrapperService service;

    @BeforeEach
    void setUp() {
        properties = new InterbankProperties();
        properties.setMyRoutingNumber(OUR_RN);
        service = new InterbankOtcWrapperService(
                negotiationService, properties,
                negotiationRepository, contractRepository,
                clientRepository, employeeRepository, tradingServiceClient,
                accountRepository, transactionExecutor,
                interbankTransactionRepository, paymentRepository,
                reservationApplier);
        // R2 1336 — self-proxy za claim/revert (REQUIRES_NEW). U unit-testu nema
        // Spring AOP, pa direktno postavljamo realni service kao self.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        // R1 209 — ensureInterbankOtcAccess je sad prva provera u exerciseContract.
        // Default: klijent 7L sme da trguje (canTradeStocks=true). lenient jer
        // neki testovi bacaju pre/posle gate-a.
        Client tradingClient = new Client();
        tradingClient.setId(7L);
        tradingClient.setCanTradeStocks(true);
        org.mockito.Mockito.lenient().when(clientRepository.findById(7L))
                .thenReturn(Optional.of(tradingClient));
        // 999L (drugi klijent) takodje sme da trguje — koristi se u not-owned testu
        // da bismo dosli DO ownership provere (a ne da gate presretne).
        Client otherTradingClient = new Client();
        otherTradingClient.setId(999L);
        otherTradingClient.setCanTradeStocks(true);
        org.mockito.Mockito.lenient().when(clientRepository.findById(999L))
                .thenReturn(Optional.of(otherTradingClient));
    }

    /** Stub-uje claim happy-path: findByIdForUpdate vraca contract (ACTIVE → flip EXERCISING). */
    private void stubClaimLock(InterbankOtcContract contract) {
        when(contractRepository.findByIdForUpdate(contract.getId())).thenReturn(Optional.of(contract));
    }

    @Test
    @DisplayName("§2.7.2 happy path: formira 4-posting tx (Option↔Person, Stock+Monas), zove 2PC")
    void exerciseContract_happyPath_formsAndExecutes2PC() {
        Long contractId = 99L;
        Long sourceNegId = 42L;
        InterbankOtcContract contract = buildActiveBuyerSideContract(contractId, sourceNegId);
        when(contractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(sourceNegId);
        neg.setForeignNegotiationIdString("source-neg-uuid");
        when(negotiationRepository.findById(sourceNegId)).thenReturn(Optional.of(neg));

        Account buyerAccount = buildAccount(101L, "222000111111111111", "USD",
                new BigDecimal("10000.00"), AccountStatus.ACTIVE);
        Client buyer = new Client();
        buyer.setId(7L);
        buyerAccount.setClient(buyer);
        when(accountRepository.findById(101L)).thenReturn(Optional.of(buyerAccount));

        // R2 1336/1337 — claim lock + reserveMonas hold.
        stubClaimLock(contract);

        // formTransaction passthrough
        when(transactionExecutor.formTransaction(any(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<Posting> postings = (List<Posting>) inv.getArgument(0);
                    return new Transaction(postings,
                            new ForeignBankId(OUR_RN, "tx-ex"),
                            (String) inv.getArgument(1), (String) inv.getArgument(2),
                            (String) inv.getArgument(3), (String) inv.getArgument(4));
                });
        doNothing().when(transactionExecutor).execute(any(Transaction.class));

        service.exerciseContract(String.valueOf(contractId), 101L, 7L, "CLIENT");

        // C-2: 2PC pozvan sa 4-posting tx
        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionExecutor).execute(txCaptor.capture());
        Transaction tx = txCaptor.getValue();

        assertThat(tx.postings()).hasSize(4);

        // Po §2.7.2:
        //   (Monas, Option) +pi*k  → option pseudo-account "receives" money
        //   (Monas, Account) -pi*k → buyer pays
        //   (Stock, Option) -k     → option pseudo-account "gives" stocks
        //   (Stock, Person) +k     → buyer receives stocks
        BigDecimal money = new BigDecimal("10000"); // 50 × 200
        BigDecimal qty = new BigDecimal("50");

        long monasOptionCount = tx.postings().stream()
                .filter(p -> p.asset() instanceof Asset.Monas
                        && p.account() instanceof TxAccount.Option
                        && p.amount().compareTo(money) == 0)
                .count();
        assertThat(monasOptionCount).isEqualTo(1L);

        long monasAccountCount = tx.postings().stream()
                .filter(p -> p.asset() instanceof Asset.Monas
                        && p.account() instanceof TxAccount.Account
                        && p.amount().compareTo(money.negate()) == 0)
                .count();
        assertThat(monasAccountCount).isEqualTo(1L);

        long stockOptionCount = tx.postings().stream()
                .filter(p -> p.asset() instanceof Asset.Stock
                        && p.account() instanceof TxAccount.Option
                        && p.amount().compareTo(qty.negate()) == 0)
                .count();
        assertThat(stockOptionCount).isEqualTo(1L);

        long stockPersonCount = tx.postings().stream()
                .filter(p -> p.asset() instanceof Asset.Stock
                        && p.account() instanceof TxAccount.Person
                        && p.amount().compareTo(qty) == 0)
                .count();
        assertThat(stockPersonCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("§2.7.2 settlement-past: vraca 409 ExerciseConflict, ne zove 2PC")
    void exerciseContract_settlementPast_throws409() {
        Long contractId = 99L;
        InterbankOtcContract contract = buildActiveBuyerSideContract(contractId, 42L);
        // settlementDate u proslosti
        contract.setSettlementDate(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        when(contractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.exerciseContract(String.valueOf(contractId), 101L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankExerciseConflictException.class)
                .hasMessageContaining("istekao");

        verify(transactionExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("§2.7.2 status != ACTIVE: vraca 409 ExerciseConflict")
    void exerciseContract_alreadyExercised_throws409() {
        Long contractId = 99L;
        InterbankOtcContract contract = buildActiveBuyerSideContract(contractId, 42L);
        contract.setStatus(InterbankOtcContractStatus.EXERCISED);
        when(contractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.exerciseContract(String.valueOf(contractId), 101L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankExerciseConflictException.class)
                .hasMessageContaining("nije ACTIVE");

        verify(transactionExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("§2.7.2 caller nije buyer (seller pokusava): 409 ExerciseConflict")
    void exerciseContract_callerNotBuyer_throws409() {
        Long contractId = 99L;
        InterbankOtcContract contract = buildActiveBuyerSideContract(contractId, 42L);
        // Mi smo SELLER u ovom ugovoru — exercise je samo za BUYER
        contract.setLocalPartyType(InterbankPartyType.SELLER);
        when(contractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.exerciseContract(String.valueOf(contractId), 101L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankExerciseConflictException.class)
                .hasMessageContaining("SELLER");

        verify(transactionExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("§2.7.2 ugovor ne pripada caller-u: 409 ExerciseConflict")
    void exerciseContract_notOwned_throws409() {
        Long contractId = 99L;
        InterbankOtcContract contract = buildActiveBuyerSideContract(contractId, 42L);
        when(contractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.exerciseContract(String.valueOf(contractId), 101L, 999L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankExerciseConflictException.class)
                .hasMessageContaining("ne pripada");

        verify(transactionExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("§2.7.2 nedovoljno sredstava: 409 ExerciseConflict")
    void exerciseContract_insufficientFunds_throws409() {
        Long contractId = 99L;
        InterbankOtcContract contract = buildActiveBuyerSideContract(contractId, 42L);
        when(contractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        Account buyerAccount = buildAccount(101L, "222000111111111111", "USD",
                new BigDecimal("100.00"), // contract trazi 50*200=10000 USD
                AccountStatus.ACTIVE);
        Client buyer = new Client();
        buyer.setId(7L);
        buyerAccount.setClient(buyer);
        when(accountRepository.findById(101L)).thenReturn(Optional.of(buyerAccount));

        assertThatThrownBy(() -> service.exerciseContract(String.valueOf(contractId), 101L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankExerciseConflictException.class)
                .hasMessageContaining("Nedovoljno");

        verify(transactionExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("§2.7.2 racun u pogresnoj valuti: 409 ExerciseConflict")
    void exerciseContract_wrongCurrency_throws409() {
        Long contractId = 99L;
        InterbankOtcContract contract = buildActiveBuyerSideContract(contractId, 42L);
        when(contractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        Account buyerAccount = buildAccount(101L, "222000111111111111", "RSD",
                new BigDecimal("999999.00"), AccountStatus.ACTIVE);
        Client buyer = new Client();
        buyer.setId(7L);
        buyerAccount.setClient(buyer);
        when(accountRepository.findById(101L)).thenReturn(Optional.of(buyerAccount));

        assertThatThrownBy(() -> service.exerciseContract(String.valueOf(contractId), 101L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankExerciseConflictException.class)
                .hasMessageContaining("nije u valuti");

        verify(transactionExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("§2.7.2: contract ne postoji: ProtocolException (400)")
    void exerciseContract_notFound_throws() {
        when(contractRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exerciseContract("99", 101L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class)
                .hasMessageContaining("ne postoji");
    }

    // ── P1-9: getInterbankTransactionView ──

    @Test
    @DisplayName("P1-9: OTC contract EXERCISED → view status COMMITTED")
    void getInterbankTransactionView_contractExercised_committed() {
        InterbankOtcContract contract = buildActiveBuyerSideContract(33L, 42L);
        contract.setStatus(InterbankOtcContractStatus.EXERCISED);
        contract.setExercisedAt(java.time.LocalDateTime.now());
        when(contractRepository.findById(33L)).thenReturn(Optional.of(contract));

        InterbankOtcWrapperDtos.InterbankTransactionDto view =
                service.getInterbankTransactionView("33", 7L, "CLIENT");

        assertThat(view.status()).isEqualTo("COMMITTED");
        assertThat(view.type()).isEqualTo("OTC");
        assertThat(view.transactionId()).isEqualTo("33");
        assertThat(view.listingTicker()).isEqualTo("AAPL");
    }

    @Test
    @DisplayName("P1-9: OTC contract not owned by caller → AccessDenied (403, anti-IDOR)")
    void getInterbankTransactionView_contractNotOwned_forbidden() {
        InterbankOtcContract contract = buildActiveBuyerSideContract(33L, 42L); // localPartyId=7
        when(contractRepository.findById(33L)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.getInterbankTransactionView("33", 999L, "CLIENT"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("P1-9: inter-bank payment tx ROLLED_BACK → view status ABORTED (ROLLED_BACK→ABORTED mapping)")
    void getInterbankTransactionView_paymentRolledBack_aborted() {
        // Lookup id koji nije numeric → preskace contract pretragu, ide direktno na
        // payment-by-interbank-tx-id pretragu.
        String txIdString = "tx-payment-1";

        rs.raf.banka2_bek.account.model.Account fromAccount =
                buildAccount(5L, "222000000000000001", "RSD", new BigDecimal("0"), AccountStatus.ACTIVE);
        Client owner = new Client();
        owner.setId(7L);
        fromAccount.setClient(owner);

        rs.raf.banka2_bek.payment.model.Payment payment =
                rs.raf.banka2_bek.payment.model.Payment.builder()
                        .id(1L)
                        .fromAccount(fromAccount)
                        .toAccountNumber("111900001")
                        .amount(new BigDecimal("500"))
                        .currency(fromAccount.getCurrency())
                        .interbankTxIdString(txIdString)
                        .interbankTxRoutingNumber(OUR_RN)
                        .build();
        when(paymentRepository.findByInterbankTxRoutingNumberAndInterbankTxIdString(eq(OUR_RN), eq(txIdString)))
                .thenReturn(Optional.of(payment));

        rs.raf.banka2_bek.interbank.model.InterbankTransaction ibTx =
                new rs.raf.banka2_bek.interbank.model.InterbankTransaction();
        ibTx.setId(1L);
        ibTx.setTransactionRoutingNumber(OUR_RN);
        ibTx.setTransactionIdString(txIdString);
        ibTx.setStatus(rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus.ROLLED_BACK);
        ibTx.setRetryCount(0);
        ibTx.setFailureReason("Banka primaoca je odbacila transakciju.");
        when(interbankTransactionRepository.findByTransactionRoutingNumberAndTransactionIdString(
                eq(OUR_RN), eq(txIdString))).thenReturn(Optional.of(ibTx));

        InterbankOtcWrapperDtos.InterbankTransactionDto view =
                service.getInterbankTransactionView(txIdString, 7L, "CLIENT");

        // Kljucno mapiranje: ROLLED_BACK → ABORTED.
        assertThat(view.status()).isEqualTo("ABORTED");
        assertThat(view.type()).isEqualTo("PAYMENT");
        assertThat(view.failureReason()).isEqualTo("Banka primaoca je odbacila transakciju.");
    }

    @Test
    @DisplayName("P1-9: unknown lookup id → NoSuchElement (404)")
    void getInterbankTransactionView_notFound() {
        // Non-numeric → preskace contract pretragu; payment pretraga vraca empty.
        when(paymentRepository.findByInterbankTxRoutingNumberAndInterbankTxIdString(any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInterbankTransactionView("does-not-exist", 7L, "CLIENT"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    // ── R2 1336/1337: exercise claim (lock + reservation) ──

    @Test
    @DisplayName("R2 1336/1337: exercise rezervise sredstva (reserveMonas) i flip-uje EXERCISING pre 2PC")
    void exerciseContract_claimReservesFundsAndFlipsExercising() {
        Long contractId = 99L;
        InterbankOtcContract contract = buildActiveBuyerSideContract(contractId, 42L);
        when(contractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(42L);
        neg.setForeignNegotiationIdString("src-neg");
        when(negotiationRepository.findById(42L)).thenReturn(Optional.of(neg));

        Account buyerAccount = buildAccount(101L, "222000111111111111", "USD",
                new BigDecimal("10000.00"), AccountStatus.ACTIVE);
        Client buyer = new Client();
        buyer.setId(7L);
        buyerAccount.setClient(buyer);
        when(accountRepository.findById(101L)).thenReturn(Optional.of(buyerAccount));
        stubClaimLock(contract);
        when(transactionExecutor.formTransaction(any(), anyString(), any(), any(), any()))
                .thenReturn(new Transaction(List.of(),
                        new ForeignBankId(OUR_RN, "tx"), "d", "ref", "n", "desc"));
        doNothing().when(transactionExecutor).execute(any(Transaction.class));

        service.exerciseContract(String.valueOf(contractId), 101L, 7L, "CLIENT");

        // 1337: hold buyer-ovih sredstava (totalCost = 50 * 200 = 10000 USD).
        verify(reservationApplier).reserveMonas(eq("222000111111111111"), eq(new BigDecimal("10000")));
        // 1336: contract flip-ovan u EXERCISING pod lock-om PRE 2PC.
        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.EXERCISING);
    }

    @Test
    @DisplayName("R2 1336: konkurentni exercise — drugi pod lock-om vidi EXERCISING → 409, NE pokrece 2PC")
    void exerciseContract_concurrentSecondCall_seesNonActiveUnderLock_409() {
        Long contractId = 99L;
        InterbankOtcContract contract = buildActiveBuyerSideContract(contractId, 42L);
        // Prvi exercise je vec claim-ovao (EXERCISING). findById (ne-locking) vraca
        // ACTIVE snapshot (stale read pre lock-a), ali findByIdForUpdate vraca
        // EXERCISING (committed claim drugog thread-a).
        InterbankOtcContract staleActive = buildActiveBuyerSideContract(contractId, 42L);
        when(contractRepository.findById(contractId)).thenReturn(Optional.of(staleActive));

        Account buyerAccount = buildAccount(101L, "222000111111111111", "USD",
                new BigDecimal("10000.00"), AccountStatus.ACTIVE);
        Client buyer = new Client();
        buyer.setId(7L);
        buyerAccount.setClient(buyer);
        when(accountRepository.findById(101L)).thenReturn(Optional.of(buyerAccount));

        contract.setStatus(InterbankOtcContractStatus.EXERCISING);
        when(contractRepository.findByIdForUpdate(contractId)).thenReturn(Optional.of(contract));

        assertThatThrownBy(() -> service.exerciseContract(String.valueOf(contractId), 101L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankExerciseConflictException.class)
                .hasMessageContaining("vec u toku");

        verify(reservationApplier, never()).reserveMonas(anyString(), any());
        verify(transactionExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("R2 1336/1337: 2PC pad → revert (releaseMonas + EXERCISING→ACTIVE), rethrow")
    void exerciseContract_2pcFails_revertsClaimAndReleasesFunds() {
        Long contractId = 99L;
        InterbankOtcContract contract = buildActiveBuyerSideContract(contractId, 42L);
        when(contractRepository.findById(contractId)).thenReturn(Optional.of(contract));

        InterbankOtcNegotiation neg = new InterbankOtcNegotiation();
        neg.setId(42L);
        neg.setForeignNegotiationIdString("src-neg");
        when(negotiationRepository.findById(42L)).thenReturn(Optional.of(neg));

        Account buyerAccount = buildAccount(101L, "222000111111111111", "USD",
                new BigDecimal("10000.00"), AccountStatus.ACTIVE);
        Client buyer = new Client();
        buyer.setId(7L);
        buyerAccount.setClient(buyer);
        when(accountRepository.findById(101L)).thenReturn(Optional.of(buyerAccount));
        // findByIdForUpdate koriste i claim i revert (claim flip-uje na EXERCISING,
        // pa revert vidi EXERCISING i radi release).
        when(contractRepository.findByIdForUpdate(contractId)).thenReturn(Optional.of(contract));
        when(transactionExecutor.formTransaction(any(), anyString(), any(), any(), any()))
                .thenReturn(new Transaction(List.of(),
                        new ForeignBankId(OUR_RN, "tx"), "d", "ref", "n", "desc"));
        org.mockito.Mockito.doThrow(new RuntimeException("partner vote=NO"))
                .when(transactionExecutor).execute(any(Transaction.class));

        assertThatThrownBy(() -> service.exerciseContract(String.valueOf(contractId), 101L, 7L, "CLIENT"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("partner vote=NO");

        verify(reservationApplier).reserveMonas(eq("222000111111111111"), eq(new BigDecimal("10000")));
        // Revert: oslobodi rezervaciju + vrati ACTIVE.
        verify(reservationApplier).releaseMonas(eq("222000111111111111"), eq(new BigDecimal("10000")));
        assertThat(contract.getStatus()).isEqualTo(InterbankOtcContractStatus.ACTIVE);
    }

    // ── R1 209: inter-bank OTC access gate ──

    @Test
    @DisplayName("R1 209: klijent bez canTradeStocks → 403 na exercise, ne dira contract")
    void exerciseContract_clientWithoutTradeStocks_forbidden() {
        Client noTrade = new Client();
        noTrade.setId(7L);
        noTrade.setCanTradeStocks(false);
        when(clientRepository.findById(7L)).thenReturn(Optional.of(noTrade));

        assertThatThrownBy(() -> service.exerciseContract("99", 101L, 7L, "CLIENT"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(contractRepository, never()).findById(any());
        verify(transactionExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("R1 209: agent (EMPLOYEE bez SUPERVISOR) → 403 na exercise")
    void exerciseContract_agentEmployee_forbidden() {
        rs.raf.banka2_bek.employee.model.Employee agent =
                new rs.raf.banka2_bek.employee.model.Employee();
        agent.setId(50L);
        agent.setPermissions(new java.util.HashSet<>(java.util.Set.of("AGENT", "TRADE_STOCKS")));
        when(employeeRepository.findById(50L)).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.exerciseContract("99", 101L, 50L, "EMPLOYEE"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(transactionExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("R1 209: supervizor (EMPLOYEE sa SUPERVISOR) prolazi gate")
    void exerciseContract_supervisorEmployee_passesGate() {
        rs.raf.banka2_bek.employee.model.Employee supervisor =
                new rs.raf.banka2_bek.employee.model.Employee();
        supervisor.setId(60L);
        supervisor.setPermissions(new java.util.HashSet<>(java.util.Set.of("SUPERVISOR")));
        when(employeeRepository.findById(60L)).thenReturn(Optional.of(supervisor));
        // contract ne postoji → prolazak gate-a se dokazuje time sto pukne KASNIJE
        // (na contract lookup-u), a ne sa AccessDenied.
        when(contractRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.exerciseContract("99", 101L, 60L, "EMPLOYEE"))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class)
                .hasMessageContaining("ne postoji");
    }

    // ── helpers ──

    private InterbankOtcContract buildActiveBuyerSideContract(Long contractId, Long sourceNegId) {
        InterbankOtcContract c = new InterbankOtcContract();
        c.setId(contractId);
        c.setSourceNegotiationId(sourceNegId);
        c.setLocalPartyType(InterbankPartyType.BUYER); // mi smo BUYER
        c.setLocalPartyId(7L);
        c.setLocalPartyRole("CLIENT");
        c.setForeignPartyRoutingNumber(SELLER_RN);
        c.setForeignPartyIdString("C-seller-1");
        c.setTicker("AAPL");
        c.setQuantity(new BigDecimal("50"));
        c.setStrikePrice(new BigDecimal("200"));
        c.setStrikeCurrency("USD");
        c.setPremium(new BigDecimal("700"));
        c.setPremiumCurrency("USD");
        c.setSettlementDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30));
        c.setStatus(InterbankOtcContractStatus.ACTIVE);
        return c;
    }

    private Account buildAccount(Long id, String number, String currencyCode,
                                  BigDecimal availableBalance, AccountStatus status) {
        Account a = new Account();
        a.setId(id);
        a.setAccountNumber(number);
        Currency c = new Currency();
        c.setCode(currencyCode);
        a.setCurrency(c);
        a.setAvailableBalance(availableBalance);
        a.setStatus(status);
        return a;
    }
}
