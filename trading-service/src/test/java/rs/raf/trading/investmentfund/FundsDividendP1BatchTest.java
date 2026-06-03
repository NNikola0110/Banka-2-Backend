package rs.raf.trading.investmentfund;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.model.ClientFundPosition;
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.ClientFundTransactionStatus;
import rs.raf.trading.investmentfund.model.FundDividendDistributionLedger;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.ClientFundPositionRepository;
import rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository;
import rs.raf.trading.investmentfund.repository.FundDividendDistributionLedgerRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.investmentfund.scheduler.FundValueSnapshotScheduler;
import rs.raf.trading.investmentfund.service.FundDividendLedgerWriter;
import rs.raf.trading.investmentfund.service.FundDividendService;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.order.service.FundReservationService;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P1-funds-1 (31.05) — TDD regresija za fondovske dividende:
 * distribute per-klijent skip kad klijent nema RSD racun (1346/1554),
 * reinvest ledger guard protiv double auto-BUY (1347).
 */
@ExtendWith(MockitoExtension.class)
class FundsDividendP1BatchTest {

    @Mock private InvestmentFundRepository investmentFundRepository;
    @Mock private ClientFundTransactionRepository clientFundTransactionRepository;
    @Mock private ClientFundPositionRepository clientFundPositionRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private FundReservationService fundReservationService;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private FundValueSnapshotScheduler fundValueSnapshotScheduler;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private FundDividendDistributionLedgerRepository distributionLedgerRepository;
    @Mock private FundDividendLedgerWriter ledgerWriter;

    @InjectMocks
    private FundDividendService service;

    private InvestmentFund activeFund(Long id, Long accountId) {
        InvestmentFund f = new InvestmentFund();
        f.setId(id);
        f.setName("Fund " + id);
        f.setAccountId(accountId);
        f.setManagerEmployeeId(1L);
        f.setActive(true);
        return f;
    }

    private InternalAccountDto fundAcc(Long id, String avail) {
        return new InternalAccountDto(id, "FUND-" + id, "Fond", new BigDecimal(avail),
                new BigDecimal(avail), BigDecimal.ZERO, "RSD", "ACTIVE", null, null, "FUND");
    }

    private InternalAccountDto clientAcc(Long id, Long clientId) {
        return new InternalAccountDto(id, "CLIENT-" + id, "Klijent", BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "RSD", "ACTIVE", clientId, null, "PERSONAL");
    }

    private ClientFundTransaction inflow(Long fundId, Long listingId, String amount) {
        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setId(300L + listingId);
        tx.setFundId(fundId);
        tx.setUserId(fundId);
        tx.setUserRole(UserRole.FUND);
        tx.setAmountRsd(new BigDecimal(amount));
        tx.setSourceAccountId(100L);
        tx.setInflow(true);
        tx.setStatus(ClientFundTransactionStatus.DIVIDEND_INFLOW);
        tx.setFailureReason("DIVIDEND_INFLOW listingId=" + listingId);
        return tx;
    }

    private ClientFundPosition position(Long id, Long fundId, Long clientId, String invested) {
        ClientFundPosition p = new ClientFundPosition();
        p.setId(id);
        p.setFundId(fundId);
        p.setUserId(clientId);
        p.setUserRole(UserRole.CLIENT);
        p.setTotalInvested(new BigDecimal(invested));
        return p;
    }

    private Listing stock(Long id, String ticker, String price) {
        Listing l = new Listing();
        l.setId(id);
        l.setTicker(ticker);
        l.setName(ticker);
        l.setListingType(ListingType.STOCK);
        l.setExchangeAcronym("BELEX");
        l.setQuoteCurrency("RSD");
        l.setPrice(new BigDecimal(price));
        l.setAsk(new BigDecimal(price));
        return l;
    }

    // ── 1346/1554: distribute per-klijent skip kad nema RSD racun ──────────────

    @Test
    @DisplayName("1554: distribute preskace klijenta bez RSD racuna, ostali DOBIJAJU (ne fail-all)")
    void distribute_clientWithoutRsdAccount_skipsHimNotAll() {
        InvestmentFund f = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAcc(100L, "2000.0000");
        InternalAccountDto clientTwoAccount = clientAcc(202L, 22L);

        ClientFundTransaction pending = inflow(1L, 10L, "1000.0000");
        ClientFundPosition posOne = position(1L, 1L, 11L, "700.0000"); // nema RSD racun
        ClientFundPosition posTwo = position(2L, 1L, 22L, "300.0000"); // ima RSD racun

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(f));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L, ClientFundTransactionStatus.DIVIDEND_INFLOW)).thenReturn(List.of(pending));
        when(clientFundPositionRepository.findByFundId(1L)).thenReturn(List.of(posOne, posTwo));
        // Klijent 11 nema RSD racun → banka-core 404 → EntityNotFoundException.
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 11L, "RSD"))
                .thenThrow(new BankaCoreClientException(404, "nema RSD racun"));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 22L, "RSD"))
                .thenReturn(clientTwoAccount);
        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(inv -> {
                    ClientFundTransaction tx = inv.getArgument(0);
                    if (tx.getId() == null) tx.setId(950L);
                    return tx;
                });

        List<ClientFundTransaction> distributions = service.distributeDividendsToClients(1L);

        // Klijent 22 je placen (1 transfer), klijent 11 preskocen — NE rollback svih.
        assertEquals(1, distributions.size());
        ArgumentCaptor<TransferFundsRequest> cap = ArgumentCaptor.forClass(TransferFundsRequest.class);
        verify(bankaCoreClient, times(1)).transferFunds(anyString(), cap.capture());
        assertEquals(202L, cap.getValue().toAccountId());

        // Parcijalno → pending ostaje INFLOW (sledeci run pokupi klijenta 11 kad
        // dobije racun); ostali ne placaju opet (ledger/idempotency).
        assertEquals(ClientFundTransactionStatus.DIVIDEND_INFLOW, pending.getStatus());
    }

    // ── 1347: reinvest ledger guard (no double auto-BUY) ───────────────────────

    @Test
    @DisplayName("1347: reinvest preskace priliv koji je VEC reinvestiran (ledger guard)")
    void reinvest_alreadyInLedger_skipsNoDoubleBuy() {
        InvestmentFund f = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAcc(100L, "1000.0000");
        ClientFundTransaction pending = inflow(1L, 10L, "900.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(f));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L, ClientFundTransactionStatus.DIVIDEND_INFLOW)).thenReturn(List.of(pending));
        // Ledger vec sadrzi marker za ovaj priliv → reinvest mora preskociti.
        when(distributionLedgerRepository.existsByIdempotencyKey(
                "fund-dividend-reinvest-1-310")).thenReturn(true);

        List<Order> orders = service.reinvestDividends(1L);

        assertTrue(orders.isEmpty(), "Nijedan order ne sme biti kreiran za vec reinvestiran priliv");
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(fundReservationService);
        verify(ledgerWriter, never()).recordPaid(any());
        // tx ostaje INFLOW (nije ponovo flip-ovan).
        assertEquals(ClientFundTransactionStatus.DIVIDEND_INFLOW, pending.getStatus());
    }

    @Test
    @DisplayName("1347: reinvest zapisuje ledger marker posle kreiranog ordera")
    void reinvest_writesLedgerMarkerAfterOrder() {
        InvestmentFund f = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAcc(100L, "1000.0000");
        ClientFundTransaction pending = inflow(1L, 10L, "900.0000");
        Listing listing = stock(10L, "AAPL", "200.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(f));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L, ClientFundTransactionStatus.DIVIDEND_INFLOW)).thenReturn(List.of(pending));
        when(distributionLedgerRepository.existsByIdempotencyKey(anyString())).thenReturn(false);
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(700L);
            return o;
        });
        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<Order> orders = service.reinvestDividends(1L);

        assertEquals(1, orders.size());
        ArgumentCaptor<FundDividendDistributionLedger> ledCap =
                ArgumentCaptor.forClass(FundDividendDistributionLedger.class);
        verify(ledgerWriter).recordPaid(ledCap.capture());
        assertEquals("fund-dividend-reinvest-1-310", ledCap.getValue().getIdempotencyKey());
        assertEquals("reinvest", ledCap.getValue().getCycleKey());
        assertEquals(ClientFundTransactionStatus.DIVIDEND_REINVESTED, pending.getStatus());
    }
}
