package rs.raf.banka2_bek.interbank.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2.contracts.internal.InternalPublicStockSellerDto;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.config.InterbankProperties;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.interbank.model.InterbankOtcNegotiation;
import rs.raf.banka2_bek.interbank.protocol.CurrencyCode;
import rs.raf.banka2_bek.interbank.protocol.ForeignBankId;
import rs.raf.banka2_bek.interbank.protocol.MonetaryValue;
import rs.raf.banka2_bek.interbank.protocol.OtcOffer;
import rs.raf.banka2_bek.interbank.protocol.StockDescription;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcContractRepository;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcNegotiationRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R3 1539 — seller-side over-allocation pri inbound kreiranju pregovora
 * ({@code acceptCreatedNegotiation}).
 *
 * <p>Bez serijalizacije, dva partnera koja istovremeno kreiraju pregovor za istog
 * prodavca/ticker citaju isti {@code available} (sellerPublic − alreadyReserved) i
 * oba persist-uju → prodavac obecao vise akcija nego sto ima. Fix: per-(seller,ticker)
 * monitor oko check+persist + transakcioni commit pre oslobadjanja lock-a.
 *
 * <p>Unit-test verifikuje kvota matematiku i kumulativ (drugi pregovor vidi prvi):
 * stub {@code sumActiveAmountForSellerAndTicker} simulira committed prvi pregovor.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OtcNegotiationService — seller kvota / over-allocation (1539)")
class OtcNegotiationServiceQuotaTest {

    private static final int OUR_RN = 222;
    private static final int BUYER_RN = 111;

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
                negotiationRepository, contractRepository,
                tradingServiceClient, clientRepository, employeeRepository,
                transactionExecutor, reservationApplier);
        // acceptCreatedNegotiation deleguje na self.acceptCreatedNegotiationLocked.
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    @DisplayName("1539: pregovor preko raspolozive kvote (sellerPublic − reserved) → ProtocolException, NE persist")
    void acceptCreatedNegotiation_overAllocation_rejected() {
        // Seller (C-7) ima 100 javnih AAPL; vec rezervisano 70 (committed prethodni
        // pregovor). Novi zahtev za 50 → 70+50=120 > 100 → odbijen.
        when(tradingServiceClient.findPublicStockForSeller(eq(7L), eq("CLIENT"), eq("AAPL")))
                .thenReturn(List.of(new InternalPublicStockSellerDto(7L, "CLIENT", "AAPL", 100)));
        when(negotiationRepository.sumActiveAmountForSellerAndTicker(eq(7L), eq("CLIENT"), eq("AAPL")))
                .thenReturn(new BigDecimal("70"));

        OtcOffer offer = buildOffer("C-7", "AAPL", new BigDecimal("50"));

        assertThatThrownBy(() -> service.acceptCreatedNegotiation(offer))
                .isInstanceOf(InterbankExceptions.InterbankProtocolException.class)
                .hasMessageContaining("nema dovoljno javnih akcija");

        verify(negotiationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("1539: pregovor unutar kvote (drugi vidi prvi rezervisan) prolazi i persist-uje")
    void acceptCreatedNegotiation_withinQuota_persists() {
        // 100 javnih, 70 vec rezervisano; novi 30 → 70+30=100 == 100 → OK.
        when(tradingServiceClient.findPublicStockForSeller(eq(7L), eq("CLIENT"), eq("AAPL")))
                .thenReturn(List.of(new InternalPublicStockSellerDto(7L, "CLIENT", "AAPL", 100)));
        when(negotiationRepository.sumActiveAmountForSellerAndTicker(eq(7L), eq("CLIENT"), eq("AAPL")))
                .thenReturn(new BigDecimal("70"));
        when(negotiationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OtcOffer offer = buildOffer("C-7", "AAPL", new BigDecimal("30"));

        ForeignBankId id = service.acceptCreatedNegotiation(offer);

        org.assertj.core.api.Assertions.assertThat(id.routingNumber()).isEqualTo(OUR_RN);
        verify(negotiationRepository, times(1)).save(any(InterbankOtcNegotiation.class));
    }

    private OtcOffer buildOffer(String sellerId, String ticker, BigDecimal amount) {
        ForeignBankId buyer = new ForeignBankId(BUYER_RN, "C-buyer-1");
        ForeignBankId seller = new ForeignBankId(OUR_RN, sellerId);
        return new OtcOffer(
                new StockDescription(ticker),
                OffsetDateTime.now().plusDays(30),
                new MonetaryValue(CurrencyCode.USD, new BigDecimal("200")),
                new MonetaryValue(CurrencyCode.USD, new BigDecimal("100")),
                buyer, seller, amount,
                buyer /* lastModifiedBy == buyerId pri kreiranju */);
    }
}
