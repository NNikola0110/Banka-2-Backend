package rs.raf.banka2_bek.interbank.wrapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.config.InterbankProperties;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.interbank.model.InterbankOtcNegotiation;
import rs.raf.banka2_bek.interbank.model.InterbankOtcNegotiationStatus;
import rs.raf.banka2_bek.interbank.model.InterbankPartyType;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcContractRepository;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcNegotiationRepository;
import rs.raf.banka2_bek.interbank.service.InterbankReservationApplier;
import rs.raf.banka2_bek.interbank.service.OtcNegotiationService;
import rs.raf.banka2_bek.interbank.service.TransactionExecutorService;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6 1976 + R1 209 — state-machine guard i access gate na inter-bank OTC wrapper
 * mutirajucim akcijama (accept/decline).
 *
 * <ul>
 *   <li><b>1976:</b> accept/decline flip-uju status SAMO iz ACTIVE. Ilegalan prelaz
 *       (ACCEPTED→DECLINED, vec-DECLINED→ACCEPTED) → 409, i kriticno: drugi accept
 *       NE pokrece outbound 2PC accept (sprecava dupli premium debit).</li>
 *   <li><b>209:</b> agent (EMPLOYEE bez SUPERVISOR) i klijent bez canTradeStocks
 *       dobijaju 403 na accept/decline.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterbankOtcWrapperService — state-machine guard (1976) + access gate (209)")
class InterbankOtcWrapperServiceStateGuardTest {

    private static final int OUR_RN = 222;
    private static final int SELLER_RN = 111;
    private static final String OFFER_ID = SELLER_RN + ":neg-1";

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
    @Mock private InterbankReservationApplier reservationApplier;

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
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        // bagovi-fix-2: @Value reg-broj NASE banke (za bankin racun supervizor-trgovine).
        org.springframework.test.util.ReflectionTestUtils.setField(service, "bankRegistrationNumber", "22200022");

        // Default: klijent 7L sme da trguje.
        Client c = new Client();
        c.setId(7L);
        c.setCanTradeStocks(true);
        lenient().when(clientRepository.findById(7L)).thenReturn(Optional.of(c));
    }

    // ── 1976 state-machine guard ──

    @Test
    @DisplayName("1976: acceptOffer na vec-ACCEPTED pregovor → 409, NE pokrece outbound 2PC accept")
    void acceptOffer_alreadyAccepted_conflict_noDoubleAccept() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACCEPTED);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));

        assertThatThrownBy(() -> service.acceptOffer(OFFER_ID, 101L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankNegotiationConflictException.class)
                .hasMessageContaining("nije ACTIVE");

        // Kriticno: dupli premium debit sprecen — outbound accept NIJE pozvan.
        verify(negotiationService, never()).acceptOffer(any());
    }

    @Test
    @DisplayName("T2/F1: acceptOffer (BUYER) — vlasnistvo + ACTIVE + valuta OK → cuva buyerSettlementAccountNumber pre outbound accept-a")
    void acceptOffer_storesBuyerSettlementAccountNumber() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));
        when(negotiationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Buyer je na FE-u izabrao SVOJ ACTIVE USD racun id=101 → cuvamo njegov broj racuna.
        // Balans pokriva premiju (100) — premium-only guard (Bug 4) prolazi.
        Account usdAccount = buildOwnedAccount(OUR_RN + "700002", 7L, AccountStatus.ACTIVE, "USD");
        usdAccount.setAvailableBalance(new java.math.BigDecimal("5000"));
        when(accountRepository.findById(101L)).thenReturn(Optional.of(usdAccount));

        service.acceptOffer(OFFER_ID, 101L, 7L, "CLIENT");

        // Pre outbound accept-a, pregovor mora nositi izabrani settlement racun.
        assertThat(neg.getBuyerSettlementAccountNumber()).isEqualTo(OUR_RN + "700002");
        verify(negotiationService).acceptOffer(any());
    }

    @Test
    @DisplayName("F1 IDOR: acceptOffer sa racunom DRUGOG klijenta → 409, nista se ne cuva, NEMA outbound accept")
    void acceptOffer_accountOwnedByAnotherClient_rejected() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));

        // Tudji racun (vlasnik 999) — kupac (7) NE sme da naplati premiju s njega.
        when(accountRepository.findById(202L)).thenReturn(Optional.of(buildOwnedAccount(
                OUR_RN + "700099", 999L, AccountStatus.ACTIVE, "USD")));

        assertThatThrownBy(() -> service.acceptOffer(OFFER_ID, 202L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankExerciseConflictException.class)
                .hasMessageContaining("ne pripada");

        // Nista se ne cuva, dupli/tudji premium debit sprecen — outbound accept NIJE pozvan.
        assertThat(neg.getBuyerSettlementAccountNumber()).isNull();
        verify(negotiationRepository, never()).save(any());
        verify(negotiationService, never()).acceptOffer(any());
    }

    @Test
    @DisplayName("F1: acceptOffer sa racunom u POGRESNOJ valuti (RSD≠premija USD) → 409, nista se ne cuva")
    void acceptOffer_nonPremiumCurrencyAccount_rejected() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));

        // Sopstveni ACTIVE racun, ali RSD — premija je u USD (vidi buildBuyerSideNegotiation).
        when(accountRepository.findById(303L)).thenReturn(Optional.of(buildOwnedAccount(
                OUR_RN + "700003", 7L, AccountStatus.ACTIVE, "RSD")));

        assertThatThrownBy(() -> service.acceptOffer(OFFER_ID, 303L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankExerciseConflictException.class)
                .hasMessageContaining("valuti");

        assertThat(neg.getBuyerSettlementAccountNumber()).isNull();
        verify(negotiationRepository, never()).save(any());
        verify(negotiationService, never()).acceptOffer(any());
    }

    // ── Bug 4 (PDF): accept naplacuje SAMO premiju (strike tek na exercise) ──

    @Test
    @DisplayName("Bug 4: acceptOffer naplacuje SAMO premiju — uspeva i kad kupac NEMA za ceo strike; NE rezervise strike @accept")
    void acceptOffer_chargesPremiumOnly_noStrikeReservation() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));
        when(negotiationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Premium = 100 USD, strike = 200*10 = 2000 USD. Racun ima 150 USD: dovoljno za
        // PREMIJU, ali NE i za premium+strike (2100). Po Bug 4 accept MORA proci (strike se
        // ne dira na sklapanju — naplacuje se tek pri exercise-u).
        Account usdAccount = buildOwnedAccount(OUR_RN + "700002", 7L, AccountStatus.ACTIVE, "USD");
        usdAccount.setAvailableBalance(new java.math.BigDecimal("150"));
        when(accountRepository.findById(101L)).thenReturn(Optional.of(usdAccount));

        service.acceptOffer(OFFER_ID, 101L, 7L, "CLIENT");

        // Outbound accept (premium debit kod partnera) JESTE poslat; ugovor je ACCEPTED.
        verify(negotiationService).acceptOffer(any());
        assertThat(neg.getStatus()).isEqualTo(InterbankOtcNegotiationStatus.ACCEPTED);
        // Strike se NE rezervise @accept — rezervacija/naplata ide tek u exerciseContract.
        verify(reservationApplier, never()).reserveMonas(any(), any());
    }

    @Test
    @DisplayName("Bug 4: acceptOffer — nedovoljno za PREMIJU → 409, NEMA outbound accept, NEMA rezervacije")
    void acceptOffer_insufficientPremium_rejected() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));
        lenient().when(negotiationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Premium = 100 USD; racun ima samo 50 USD → ispod premije → reject (strike se vise
        // NE racuna pri accept-u, samo premija).
        Account usdAccount = buildOwnedAccount(OUR_RN + "700002", 7L, AccountStatus.ACTIVE, "USD");
        usdAccount.setAvailableBalance(new java.math.BigDecimal("50"));
        when(accountRepository.findById(101L)).thenReturn(Optional.of(usdAccount));

        assertThatThrownBy(() -> service.acceptOffer(OFFER_ID, 101L, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankExerciseConflictException.class)
                .hasMessageContaining("Nedovoljno sredstava");

        // Premium debit kod partnera sprecen; nista se ne salje napolje i nista se ne rezervise.
        verify(negotiationService, never()).acceptOffer(any());
        verify(reservationApplier, never()).reserveMonas(any(), any());
    }

    // ── P0 [MONEY CREATED] settlement racun OBAVEZAN pre outbound 2PC za sve role ──
    // Live failure: EMPLOYEE/ADMIN kupac (ili kupac bez prosledjenog buyerAccountId)
    // ostane bez settlementAccount-a → ceo funds-guard se preskoci → outbound §3.6
    // accept se svejedno posalje → partner kreditira prodavca +premium, a inbound
    // premium-debit kod nas resolve-uje kupca preko findByClientId (prazno za zaposlenog)
    // → premium debit tiho no-op → NOVAC SE STVARA. Fix: reject PRE outbound-a.

    @Test
    @DisplayName("bagovi-fix-2: EMPLOYEE/supervizor kupac koristi BANKIN racun (findBankAccountByCurrency) → accept prolazi, settlement = bankin racun")
    void acceptOffer_employeeBuyer_usesBankAccount_proceeds() {
        // Pregovor gde smo MI buyer, lokalna strana je zaposleni (supervizor).
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        neg.setLocalPartyRole("EMPLOYEE");
        neg.setLocalPartyId(50L);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));
        when(negotiationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 209 access gate: supervizor (EMPLOYEE sa SUPERVISOR) sme da prihvati OTC.
        Employee supervisor = new Employee();
        supervisor.setId(50L);
        supervisor.setPermissions(new HashSet<>(Set.of("SUPERVISOR")));
        when(employeeRepository.findById(50L)).thenReturn(Optional.of(supervisor));

        // Bankin ACTIVE USD racun (valuta premije) — server ga razresava preko
        // findBankAccountByCurrency (zaposleni nema licne racune; trguje na bankinom).
        Account bankAcc = buildOwnedAccount(OUR_RN + "000140", 999L, AccountStatus.ACTIVE, "USD");
        bankAcc.setAvailableBalance(new java.math.BigDecimal("1000000"));
        when(accountRepository.findBankAccountByCurrency("22200022", "USD")).thenReturn(Optional.of(bankAcc));

        // buyerAccountId=null se IGNORISE za zaposlenog (server bira bankin racun, anti-IDOR).
        service.acceptOffer(OFFER_ID, null, 50L, "EMPLOYEE");

        // Bankin racun zapamcen kao settlement; outbound accept (premium s bankinog racuna) pokrenut.
        assertThat(neg.getBuyerSettlementAccountNumber()).isEqualTo(OUR_RN + "000140");
        verify(negotiationService).acceptOffer(any());
    }

    @Test
    @DisplayName("P0: CLIENT kupac bez prosledjenog buyerAccountId i bez racuna u valuti premije → 400, NEMA outbound accept")
    void acceptOffer_clientBuyerNoAccountInPremiumCcy_rejectedBeforeOutbound() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));

        // Klijent 7 (canTradeStocks=true iz setUp) nema USD (premium) racun — samo prazno.
        lenient().when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(7L, AccountStatus.ACTIVE))
                .thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.acceptOffer(OFFER_ID, null, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class)
                .hasMessageContaining("nedostaje nalog kupca");

        verify(negotiationService, never()).acceptOffer(any());
        verify(reservationApplier, never()).reserveMonas(any(), any());
        assertThat(neg.getBuyerSettlementAccountNumber()).isNull();
    }

    @Test
    @DisplayName("P0: CLIENT kupac bez buyerAccountId ali SA deterministicki resolvabilnim USD racunom → settlement zapamcen, outbound prolazi")
    void acceptOffer_clientBuyerNoAccountId_resolvesDeterministically_proceeds() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));
        when(negotiationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Klijent 7 ima ACTIVE USD racun sa dovoljno za premiju (100).
        Account usdAccount = buildOwnedAccount(OUR_RN + "700002", 7L, AccountStatus.ACTIVE, "USD");
        usdAccount.setAvailableBalance(new java.math.BigDecimal("5000"));
        when(accountRepository.findByClientIdAndStatusOrderByAvailableBalanceDesc(7L, AccountStatus.ACTIVE))
                .thenReturn(java.util.List.of(usdAccount));

        service.acceptOffer(OFFER_ID, null, 7L, "CLIENT");

        // Deterministicki resolvovan racun je zapamcen na pregovoru (inbound premium-debit
        // ga koristi → nema no-op-a), i outbound accept je pokrenut.
        assertThat(neg.getBuyerSettlementAccountNumber()).isEqualTo(OUR_RN + "700002");
        verify(negotiationService).acceptOffer(any());
    }

    @Test
    @DisplayName("1976: declineOffer na vec-DECLINED pregovor → 409 (ilegalan prelaz)")
    void declineOffer_alreadyDeclined_conflict() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.DECLINED);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));

        assertThatThrownBy(() -> service.declineOffer(OFFER_ID, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankNegotiationConflictException.class)
                .hasMessageContaining("nije ACTIVE");

        verify(negotiationService, never()).closeNegotiation(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("1976: declineOffer na ACTIVE pregovor prolazi (status→DECLINED)")
    void declineOffer_active_succeeds() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));
        when(negotiationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tradingServiceClient.findListingByTicker(any())).thenReturn(Optional.empty());

        service.declineOffer(OFFER_ID, 7L, "CLIENT");

        // FIX 2: lokalni DECLINED+save se izvrsava TEK POSLE uspesnog outbound DELETE-a.
        assertThat(neg.getStatus()).isEqualTo(InterbankOtcNegotiationStatus.DECLINED);
        verify(negotiationRepository).save(any());
        verify(negotiationService).closeNegotiation(any(), anyInt());
    }

    @Test
    @DisplayName("FIX 2: outbound DELETE 404 (partner vec nema pregovor) → benigno, lokalno DECLINED")
    void declineOffer_outboundNotFound_treatedBenign() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));
        when(negotiationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new InterbankExceptions.InterbankNegotiationNotFoundException("nema pregovora"))
                .when(negotiationService).closeNegotiation(any(), anyInt());

        service.declineOffer(OFFER_ID, 7L, "CLIENT");

        // 404 je benigno (idempotentno zatvaranje) → lokalno svejedno DECLINED.
        assertThat(neg.getStatus()).isEqualTo(InterbankOtcNegotiationStatus.DECLINED);
        verify(negotiationRepository).save(any());
    }

    @Test
    @DisplayName("FIX 2: outbound DELETE komunikaciona greska → propagira se, lokalno OSTAJE ACTIVE "
            + "(bez laznog uspeha, bez save)")
    void declineOffer_outboundCommunicationError_propagatesAndKeepsActive() {
        InterbankOtcNegotiation neg = buildBuyerSideNegotiation(InterbankOtcNegotiationStatus.ACTIVE);
        when(negotiationRepository.findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                eq(SELLER_RN), eq("neg-1"))).thenReturn(Optional.of(neg));
        doThrow(new InterbankExceptions.InterbankCommunicationException("partner nedostupan"))
                .when(negotiationService).closeNegotiation(any(), anyInt());

        assertThatThrownBy(() -> service.declineOffer(OFFER_ID, 7L, "CLIENT"))
                .isInstanceOf(InterbankExceptions.InterbankCommunicationException.class);

        // Reject NIJE propagiran ka partneru → lokalni status NE sme da bude DECLINED
        // (nema laznog uspeha), i nista se ne perzistuje kao zatvoreno.
        assertThat(neg.getStatus()).isEqualTo(InterbankOtcNegotiationStatus.ACTIVE);
        verify(negotiationRepository, never()).save(any());
    }

    // ── 209 access gate ──

    @Test
    @DisplayName("209: agent (EMPLOYEE bez SUPERVISOR) → 403 na acceptOffer, ne dira pregovor")
    void acceptOffer_agent_forbidden() {
        Employee agent = new Employee();
        agent.setId(50L);
        agent.setPermissions(new HashSet<>(Set.of("AGENT")));
        when(employeeRepository.findById(50L)).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.acceptOffer(OFFER_ID, 101L, 50L, "EMPLOYEE"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(negotiationRepository, never())
                .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(any(), any());
        verify(negotiationService, never()).acceptOffer(any());
    }

    @Test
    @DisplayName("209: klijent bez canTradeStocks → 403 na declineOffer")
    void declineOffer_clientNoTrade_forbidden() {
        Client noTrade = new Client();
        noTrade.setId(8L);
        noTrade.setCanTradeStocks(false);
        when(clientRepository.findById(8L)).thenReturn(Optional.of(noTrade));

        assertThatThrownBy(() -> service.declineOffer(OFFER_ID, 8L, "CLIENT"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(negotiationService, never()).closeNegotiation(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ── helpers ──

    private InterbankOtcNegotiation buildBuyerSideNegotiation(InterbankOtcNegotiationStatus status) {
        InterbankOtcNegotiation n = new InterbankOtcNegotiation();
        n.setId(1L);
        n.setForeignNegotiationRoutingNumber(SELLER_RN);
        n.setForeignNegotiationIdString("neg-1");
        n.setLocalPartyType(InterbankPartyType.BUYER);
        n.setLocalPartyId(7L);
        n.setLocalPartyRole("CLIENT");
        n.setForeignPartyRoutingNumber(SELLER_RN);
        n.setForeignPartyIdString("C-seller-1");
        n.setTicker("AAPL");
        n.setAmount(new java.math.BigDecimal("10"));
        n.setPricePerUnit(new java.math.BigDecimal("200"));
        n.setPriceCurrency("USD");
        n.setPremium(new java.math.BigDecimal("100"));
        n.setPremiumCurrency("USD");
        n.setSettlementDate(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(30));
        n.setLastModifiedByRoutingNumber(SELLER_RN);
        n.setLastModifiedByIdString("C-seller-1");
        n.setOngoing(status == InterbankOtcNegotiationStatus.ACTIVE);
        n.setStatus(status);
        return n;
    }

    /** Account sa vlasnikom (Client), statusom i valutom — za F1 settlement-account guard. */
    private Account buildOwnedAccount(String accountNumber, Long ownerId, AccountStatus status, String ccyCode) {
        Account a = new Account();
        a.setAccountNumber(accountNumber);
        a.setStatus(status);
        Client owner = new Client();
        owner.setId(ownerId);
        a.setClient(owner);
        Currency ccy = new Currency();
        ccy.setCode(ccyCode);
        a.setCurrency(ccy);
        return a;
    }

}
