package rs.raf.banka2_bek.interbank.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.interbank.config.InterbankProperties;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContract;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContractStatus;
import rs.raf.banka2_bek.interbank.model.InterbankPartyType;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcContractRepository;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcNegotiationRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-B6 (Nalaz 1) — auto-expiry isteklih inter-bank OTC ugovora.
 *
 * <p>Bug koji se reprodukuje: inter-bank OTC ugovor se kreira ACTIVE sa
 * {@code settlementDate}-om i rezervisanim sellerovim hartijama; jedini prelaz
 * iz ACTIVE je bio →EXERCISED. {@code EXPIRED} se NIGDE nije upisivao, pa kad
 * settlementDate prodje bez exercise-a ugovor je ostajao TRAJNO ACTIVE a
 * sellerova rezervacija hartija stranded zauvek. {@link
 * OtcNegotiationService#expireSettledContracts()} to ispravlja.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OtcNegotiationService — inter-bank OTC auto-expiry (P0-B6 Nalaz 1)")
class OtcNegotiationServiceExpiryTest {

    private static final int OUR_RN = 222;
    private static final int FOREIGN_RN = 111;

    @Mock private InterbankClient client;
    @Mock private InterbankOtcNegotiationRepository negotiationRepository;
    @Mock private InterbankOtcContractRepository contractRepository;
    @Mock private TradingServiceInternalClient tradingServiceClient;
    @Mock private ClientRepository clientRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private TransactionExecutorService transactionExecutor;
    @Mock private InterbankReservationApplier reservationApplier;

    private InterbankProperties properties;
    private OtcNegotiationService service;

    @BeforeEach
    void setUp() {
        properties = new InterbankProperties();
        properties.setMyRoutingNumber(OUR_RN);
        properties.setMyBankDisplayName("Banka 2");
        service = new OtcNegotiationService(client, properties,
                negotiationRepository, contractRepository, tradingServiceClient,
                clientRepository, employeeRepository, transactionExecutor, reservationApplier);
        // Realan self za testove koji prolaze kroz self.expireOneContract(...).
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    @DisplayName("expireSettledContracts: ACTIVE SELLER ugovor sa settlementDate u proslosti → "
            + "EXPIRED + releaseStock sellerove hartije (pre fix-a: ostaje ACTIVE, hartije stranded)")
    void expireSettledContracts_pastSellerContract_expiresAndReleasesStock() {
        InterbankOtcContract sellerContract = activeContract(
                7L, InterbankPartyType.SELLER, 42L, "CLIENT", "AAPL", 50,
                OffsetDateTime.now().minusDays(1));
        when(contractRepository.findByStatusAndSettlementDateBefore(
                eq(InterbankOtcContractStatus.ACTIVE), any(OffsetDateTime.class)))
                .thenReturn(List.of(sellerContract));
        when(contractRepository.findById(7L)).thenReturn(Optional.of(sellerContract));
        when(contractRepository.save(any(InterbankOtcContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        int count = service.expireSettledContracts();

        assertThat(count).isEqualTo(1);
        // Status flip ACTIVE → EXPIRED (mrtvo write-stanje sad ozivljeno).
        assertThat(sellerContract.getStatus()).isEqualTo(InterbankOtcContractStatus.EXPIRED);
        // Sellerova rezervacija hartija oslobodjena (un-reserve po §2.7.2).
        ArgumentCaptor<String> ticker = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> qty = ArgumentCaptor.forClass(Integer.class);
        verify(reservationApplier).releaseStock(anyString(), eq(42L), eq("CLIENT"),
                ticker.capture(), qty.capture());
        assertThat(ticker.getValue()).isEqualTo("AAPL");
        assertThat(qty.getValue()).isEqualTo(50);
        verify(contractRepository).save(sellerContract);
    }

    @Test
    @DisplayName("expireSettledContracts: ACTIVE BUYER ugovor → EXPIRED ali BEZ lokalnog releaseStock "
            + "(sellerova rezervacija je u partner banci)")
    void expireSettledContracts_buyerContract_expiresWithoutLocalRelease() {
        InterbankOtcContract buyerContract = activeContract(
                8L, InterbankPartyType.BUYER, 99L, "CLIENT", "MSFT", 10,
                OffsetDateTime.now().minusDays(2));
        when(contractRepository.findByStatusAndSettlementDateBefore(
                eq(InterbankOtcContractStatus.ACTIVE), any(OffsetDateTime.class)))
                .thenReturn(List.of(buyerContract));
        when(contractRepository.findById(8L)).thenReturn(Optional.of(buyerContract));
        when(contractRepository.save(any(InterbankOtcContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        int count = service.expireSettledContracts();

        assertThat(count).isEqualTo(1);
        assertThat(buyerContract.getStatus()).isEqualTo(InterbankOtcContractStatus.EXPIRED);
        // Kao buyer ne diramo lokalnu rezervaciju — ona zivi u partner banci.
        verify(reservationApplier, never()).releaseStock(anyString(), anyLong(), anyString(),
                anyString(), anyInt());
    }

    @Test
    @DisplayName("expireOneContract: status-guard — ugovor u medjuvremenu EXERCISED se NE pregazi (race)")
    void expireOneContract_alreadyExercised_notOverwritten() {
        InterbankOtcContract exercised = activeContract(
                9L, InterbankPartyType.SELLER, 42L, "CLIENT", "AAPL", 50,
                OffsetDateTime.now().minusDays(1));
        exercised.setStatus(InterbankOtcContractStatus.EXERCISED);
        when(contractRepository.findById(9L)).thenReturn(Optional.of(exercised));

        service.expireOneContract(9L);

        // Status ostaje EXERCISED, nema release-a ni save-a.
        assertThat(exercised.getStatus()).isEqualTo(InterbankOtcContractStatus.EXERCISED);
        verify(reservationApplier, never()).releaseStock(anyString(), anyLong(), anyString(),
                anyString(), anyInt());
        verify(contractRepository, never()).save(any(InterbankOtcContract.class));
    }

    @Test
    @DisplayName("expireSettledContracts: jedan release-fail ne blokira ostatak runde (REQUIRES_NEW izolacija)")
    void expireSettledContracts_oneFailureDoesNotBlockRest() {
        InterbankOtcContract failing = activeContract(
                1L, InterbankPartyType.SELLER, 42L, "CLIENT", "AAPL", 50,
                OffsetDateTime.now().minusDays(1));
        InterbankOtcContract ok = activeContract(
                2L, InterbankPartyType.SELLER, 43L, "CLIENT", "MSFT", 10,
                OffsetDateTime.now().minusDays(1));
        when(contractRepository.findByStatusAndSettlementDateBefore(
                eq(InterbankOtcContractStatus.ACTIVE), any(OffsetDateTime.class)))
                .thenReturn(List.of(failing, ok));
        when(contractRepository.findById(1L)).thenReturn(Optional.of(failing));
        when(contractRepository.findById(2L)).thenReturn(Optional.of(ok));
        when(contractRepository.save(any(InterbankOtcContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // Prvi releaseStock baca, drugi prolazi.
        org.mockito.Mockito.doThrow(new RuntimeException("trading-service down"))
                .when(reservationApplier).releaseStock(anyString(), eq(42L), anyString(),
                        anyString(), anyInt());

        int count = service.expireSettledContracts();

        // Samo drugi ugovor je uspesno istekao; prvi je preskocen (logovan), ne ruzi rundu.
        assertThat(count).isEqualTo(1);
        assertThat(ok.getStatus()).isEqualTo(InterbankOtcContractStatus.EXPIRED);
        verify(reservationApplier, times(2)).releaseStock(anyString(), anyLong(), anyString(),
                anyString(), anyInt());
    }

    @Test
    @DisplayName("expireSettledContracts: prazna runda → 0, nema interakcija sa reservation applier-om")
    void expireSettledContracts_noneDue_returnsZero() {
        when(contractRepository.findByStatusAndSettlementDateBefore(
                eq(InterbankOtcContractStatus.ACTIVE), any(OffsetDateTime.class)))
                .thenReturn(List.of());

        assertThat(service.expireSettledContracts()).isZero();
        verify(reservationApplier, never()).releaseStock(anyString(), anyLong(), anyString(),
                anyString(), anyInt());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private InterbankOtcContract activeContract(Long id, InterbankPartyType localType,
                                                Long localPartyId, String localPartyRole,
                                                String ticker, int qty, OffsetDateTime settlement) {
        InterbankOtcContract c = new InterbankOtcContract();
        c.setId(id);
        c.setSourceNegotiationId(id);
        c.setLocalPartyType(localType);
        c.setLocalPartyId(localPartyId);
        c.setLocalPartyRole(localPartyRole);
        c.setForeignPartyRoutingNumber(FOREIGN_RN);
        c.setForeignPartyIdString("C-1");
        c.setTicker(ticker);
        c.setQuantity(BigDecimal.valueOf(qty));
        c.setStrikePrice(new BigDecimal("100.00"));
        c.setStrikeCurrency("USD");
        c.setPremium(new BigDecimal("10.00"));
        c.setPremiumCurrency("USD");
        c.setSettlementDate(settlement);
        c.setStatus(InterbankOtcContractStatus.ACTIVE);
        c.setCreatedAt(LocalDateTime.now().minusDays(30));
        return c;
    }
}
