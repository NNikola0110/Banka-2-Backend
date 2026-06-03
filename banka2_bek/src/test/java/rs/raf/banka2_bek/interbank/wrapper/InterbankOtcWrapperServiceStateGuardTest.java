package rs.raf.banka2_bek.interbank.wrapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

        verify(negotiationRepository).save(any());
        // outbound DELETE pokusan (best-effort).
        verify(negotiationService).closeNegotiation(any(), org.mockito.ArgumentMatchers.anyInt());
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
}
