package rs.raf.trading.investmentfund;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.dto.FundDividendHistoryDto;
import rs.raf.trading.investmentfund.model.ClientFundPosition;
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.ClientFundTransactionStatus;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.ClientFundPositionRepository;
import rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.investmentfund.scheduler.FundValueSnapshotScheduler;
import rs.raf.trading.investmentfund.service.FundDividendService;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.order.service.FundReservationService;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit testovi za {@link FundDividendService} (mikroservisi varijanta).
 *
 * <p>Sve novcane noge idu kroz {@link BankaCoreClient} jer racuni zive u
 * banka-core domenu. Stanje fond racuna se modelira preko
 * {@link InternalAccountDto} record-a (immutable), pa testovi proveravaju
 * BANKA-CORE pozive umesto direktnih izmena {@code Account.balance}.
 */
@ExtendWith(MockitoExtension.class)
class FundDividendServiceTest {

    @Mock
    private InvestmentFundRepository investmentFundRepository;

    @Mock
    private ClientFundTransactionRepository clientFundTransactionRepository;

    @Mock
    private ClientFundPositionRepository clientFundPositionRepository;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private FundReservationService fundReservationService;

    @Mock
    private CurrencyConversionService currencyConversionService;

    @Mock
    private FundValueSnapshotScheduler fundValueSnapshotScheduler;

    @Mock
    private BankaCoreClient bankaCoreClient;

    @InjectMocks
    private FundDividendService service;

    @Test
    @DisplayName("creditDividendToFund credits fund account and saves DIVIDEND_INFLOW transaction")
    void creditDividendToFund_creditsCorrectAmount() {
        InvestmentFund fund = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAccountDto(100L, "1000.0000", "1000.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(invocation -> {
                    ClientFundTransaction tx = invocation.getArgument(0);
                    if (tx.getId() == null) {
                        tx.setId(501L);
                    }
                    return tx;
                });

        ClientFundTransaction result = service.creditDividendToFund(
                1L,
                10L,
                new BigDecimal("250.0000")
        );

        ArgumentCaptor<ClientFundTransaction> txCaptor =
                ArgumentCaptor.forClass(ClientFundTransaction.class);

        verify(clientFundTransactionRepository).save(txCaptor.capture());

        ClientFundTransaction saved = txCaptor.getValue();

        assertEquals(1L, saved.getFundId());
        assertEquals(1L, saved.getUserId());
        assertEquals(UserRole.FUND, saved.getUserRole());
        assertTrue(saved.isInflow());
        assertEquals(ClientFundTransactionStatus.DIVIDEND_INFLOW, saved.getStatus());
        assertBigDecimalEquals("250.0000", saved.getAmountRsd());
        assertEquals(100L, saved.getSourceAccountId());
        assertTrue(saved.getFailureReason().contains("listingId=10"));

        assertSame(saved, result);

        ArgumentCaptor<CreditFundsRequest> creditCaptor =
                ArgumentCaptor.forClass(CreditFundsRequest.class);
        verify(bankaCoreClient).creditFunds(eq("fund-dividend-inflow-501"), creditCaptor.capture());
        CreditFundsRequest credited = creditCaptor.getValue();
        assertEquals(100L, credited.accountId());
        assertBigDecimalEquals("250.0000", credited.amount());
        assertEquals("RSD", credited.currencyCode());

        verify(fundValueSnapshotScheduler).snapshotFundIfMissing(fund);
    }

    @Test
    @DisplayName("creditDividendToFund throws when fund does not exist")
    void creditDividendToFund_fundNotFound_throwsException() {
        when(investmentFundRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> service.creditDividendToFund(999L, 10L, new BigDecimal("100.0000"))
        );

        verifyNoInteractions(bankaCoreClient);
        verifyNoInteractions(clientFundTransactionRepository);
    }

    @Test
    @DisplayName("listPendingDividends returns repository results for DIVIDEND_INFLOW status")
    void listPendingDividends_returnsPendingOnly() {
        ClientFundTransaction pending = dividendTx(
                1L,
                10L,
                "300.0000",
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        );

        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L,
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        )).thenReturn(List.of(pending));

        List<ClientFundTransaction> result = service.listPendingDividends(1L);

        assertEquals(1, result.size());
        assertSame(pending, result.get(0));

        verify(clientFundTransactionRepository).findByFundIdAndStatusOrderByCreatedAtAsc(
                1L,
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        );
    }

    @Test
    @DisplayName("reinvestDividends creates MARKET BUY order when dividend is sufficient")
    void reinvestDividends_sufficientCash_placesOrder() {
        InvestmentFund fund = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAccountDto(100L, "1000.0000", "1000.0000");

        ClientFundTransaction pendingDividend = dividendTx(
                1L,
                10L,
                "900.0000",
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        );

        Listing listing = stockListing(10L, "AAPL", "200.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L,
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        )).thenReturn(List.of(pendingDividend));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(700L);
            return order;
        });
        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Order> createdOrders = service.reinvestDividends(1L);

        assertEquals(1, createdOrders.size());

        Order order = createdOrders.get(0);

        assertEquals(700L, order.getId());
        assertEquals(1L, order.getUserId());
        assertEquals(UserRole.FUND, order.getUserRole());
        assertEquals(1L, order.getFundId());
        assertEquals(listing, order.getListing());
        assertEquals(OrderDirection.BUY, order.getDirection());
        assertEquals(OrderType.MARKET, order.getOrderType());
        assertEquals(OrderStatus.APPROVED, order.getStatus());
        assertEquals(4, order.getQuantity());
        assertEquals(4, order.getRemainingPortions());
        assertBigDecimalEquals("800.0000", order.getReservedAmount());

        assertEquals(ClientFundTransactionStatus.DIVIDEND_REINVESTED, pendingDividend.getStatus());
        assertNotNull(pendingDividend.getCompletedAt());
        assertTrue(pendingDividend.getFailureReason().contains("orderId=700"));

        verify(orderRepository).save(any(Order.class));
        verify(fundReservationService).reserveForBuy(order);
        verify(clientFundTransactionRepository).save(pendingDividend);
        verify(fundValueSnapshotScheduler).snapshotFundIfMissing(fund);
    }

    @Test
    @DisplayName("reinvestDividends does not create order when cash is below one share price")
    void reinvestDividends_insufficientCash_noOrderPlaced() {
        InvestmentFund fund = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAccountDto(100L, "100.0000", "100.0000");

        ClientFundTransaction pendingDividend = dividendTx(
                1L,
                10L,
                "100.0000",
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        );

        Listing listing = stockListing(10L, "AAPL", "200.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L,
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        )).thenReturn(List.of(pendingDividend));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        List<Order> result = service.reinvestDividends(1L);

        assertTrue(result.isEmpty());
        assertEquals(ClientFundTransactionStatus.DIVIDEND_INFLOW, pendingDividend.getStatus());

        verifyNoInteractions(orderRepository);
        verifyNoInteractions(fundReservationService);
        verify(clientFundTransactionRepository, never()).save(any(ClientFundTransaction.class));
        verify(fundValueSnapshotScheduler).snapshotFundIfMissing(fund);
    }

    @Test
    @DisplayName("reinvestDividends does nothing when there are no pending dividends")
    void reinvestDividends_noPendingDividends_doesNothing() {
        InvestmentFund fund = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAccountDto(100L, "1000.0000", "1000.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L,
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        )).thenReturn(List.of());

        List<Order> result = service.reinvestDividends(1L);

        assertTrue(result.isEmpty());

        verifyNoInteractions(listingRepository);
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(fundReservationService);
        verify(fundValueSnapshotScheduler, never()).snapshotFundIfMissing(fund);
    }

    @Test
    @DisplayName("distributeDividendsToClients distributes dividends proportionally by totalInvested")
    void distributeDividendsToClients_distributesSrazmerno() {
        InvestmentFund fund = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAccountDto(100L, "2000.0000", "2000.0000");

        InternalAccountDto clientOneAccount = clientAccountDto(201L, 11L);
        InternalAccountDto clientTwoAccount = clientAccountDto(202L, 22L);

        ClientFundTransaction pendingDividend = dividendTx(
                1L,
                10L,
                "1000.0000",
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        );

        ClientFundPosition positionOne = position(1L, 1L, 11L, "700.0000");
        ClientFundPosition positionTwo = position(2L, 1L, 22L, "300.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L,
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        )).thenReturn(List.of(pendingDividend));
        when(clientFundPositionRepository.findByFundId(1L)).thenReturn(List.of(positionOne, positionTwo));

        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 11L, "RSD")).thenReturn(clientOneAccount);
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 22L, "RSD")).thenReturn(clientTwoAccount);

        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(invocation -> {
                    ClientFundTransaction tx = invocation.getArgument(0);
                    if (tx.getId() == null) {
                        tx.setId(900L + (tx.getUserId() == null ? 0L : tx.getUserId()));
                    }
                    return tx;
                });

        List<ClientFundTransaction> distributions = service.distributeDividendsToClients(1L);

        assertEquals(2, distributions.size());

        // banka-core knjizenje: fond -> klijent 1 (700 RSD), fond -> klijent 2 (300 RSD).
        ArgumentCaptor<TransferFundsRequest> transferCaptor =
                ArgumentCaptor.forClass(TransferFundsRequest.class);
        verify(bankaCoreClient, org.mockito.Mockito.times(2))
                .transferFunds(anyString(), transferCaptor.capture());

        List<TransferFundsRequest> transfers = transferCaptor.getAllValues();
        assertEquals(100L, transfers.get(0).fromAccountId());
        assertEquals(201L, transfers.get(0).toAccountId());
        assertBigDecimalEquals("700.0000", transfers.get(0).debitAmount());
        assertBigDecimalEquals("700.0000", transfers.get(0).creditAmount());

        assertEquals(100L, transfers.get(1).fromAccountId());
        assertEquals(202L, transfers.get(1).toAccountId());
        assertBigDecimalEquals("300.0000", transfers.get(1).debitAmount());
        assertBigDecimalEquals("300.0000", transfers.get(1).creditAmount());

        assertEquals(ClientFundTransactionStatus.DIVIDEND_DISTRIBUTED, distributions.get(0).getStatus());
        assertEquals(ClientFundTransactionStatus.DIVIDEND_DISTRIBUTED, distributions.get(1).getStatus());
        assertEquals(ClientFundTransactionStatus.DIVIDEND_DISTRIBUTED, pendingDividend.getStatus());

        verify(fundValueSnapshotScheduler).snapshotFundIfMissing(fund);
    }

    @Test
    @DisplayName("distributeDividendsToClients is idempotent when dividend is already distributed")
    void distributeDividendsToClients_alreadyDistributed_isIdempotent() {
        InvestmentFund fund = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAccountDto(100L, "1000.0000", "1000.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L,
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        )).thenReturn(List.of());

        List<ClientFundTransaction> result = service.distributeDividendsToClients(1L);

        assertTrue(result.isEmpty());

        verifyNoInteractions(clientFundPositionRepository);
        verify(clientFundTransactionRepository, never()).save(any(ClientFundTransaction.class));
        verify(bankaCoreClient, never()).transferFunds(anyString(), any());
        verify(fundValueSnapshotScheduler, never()).snapshotFundIfMissing(fund);
    }

    @Test
    @DisplayName("distributeDividendsToClients skips when fund has no client positions")
    void distributeDividendsToClients_noPositions_skips() {
        InvestmentFund fund = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAccountDto(100L, "1000.0000", "1000.0000");

        ClientFundTransaction pendingDividend = dividendTx(
                1L,
                10L,
                "1000.0000",
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        );

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L,
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        )).thenReturn(List.of(pendingDividend));
        when(clientFundPositionRepository.findByFundId(1L)).thenReturn(List.of());

        List<ClientFundTransaction> result = service.distributeDividendsToClients(1L);

        assertTrue(result.isEmpty());
        assertEquals(ClientFundTransactionStatus.DIVIDEND_INFLOW, pendingDividend.getStatus());

        verify(clientFundTransactionRepository, never()).save(any(ClientFundTransaction.class));
        verify(bankaCoreClient, never()).transferFunds(anyString(), any());
        verify(fundValueSnapshotScheduler, never()).snapshotFundIfMissing(fund);
    }

    @Test
    @DisplayName("scheduledDividendProcessing continues with other funds if one fund fails")
    void scheduledDividendProcessing_exceptionInOneFund_continuesOthers() {
        InvestmentFund firstFund = activeFund(1L, 101L);
        InvestmentFund secondFund = activeFund(2L, 102L);

        FundDividendService spyService = spy(service);

        when(investmentFundRepository.findByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(firstFund, secondFund));

        doThrow(new RuntimeException("boom")).when(spyService).reinvestDividends(1L);
        doReturn(List.of()).when(spyService).reinvestDividends(2L);

        spyService.scheduledDividendProcessing();

        verify(spyService).reinvestDividends(1L);
        verify(spyService).reinvestDividends(2L);
    }

    @Test
    @DisplayName("getFundDividendHistory returns only dividend-lifecycle transactions sorted desc")
    void getFundDividendHistory_returnsOnlyDividendStatuses() {
        InvestmentFund fund = activeFund(1L, 100L);
        ClientFundTransaction inflowTx = dividendTx(1L, 10L, "300.0000",
                ClientFundTransactionStatus.DIVIDEND_INFLOW);
        ClientFundTransaction reinvestedTx = dividendTx(1L, 11L, "200.0000",
                ClientFundTransactionStatus.DIVIDEND_REINVESTED);
        ClientFundTransaction distributedTx = dividendTx(1L, 12L, "150.0000",
                ClientFundTransactionStatus.DIVIDEND_DISTRIBUTED);
        // Regularan invest red — NE sme se pojaviti u rezultatu.
        ClientFundTransaction investTx = dividendTx(1L, 13L, "999.0000",
                ClientFundTransactionStatus.COMPLETED);

        Listing listing10 = stockListing(10L, "AAPL", "150.0000");
        Listing listing11 = stockListing(11L, "MSFT", "300.0000");
        Listing listing12 = stockListing(12L, "GOOG", "120.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        when(clientFundTransactionRepository.findByFundIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(inflowTx, reinvestedTx, distributedTx, investTx));
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing10));
        when(listingRepository.findById(11L)).thenReturn(Optional.of(listing11));
        when(listingRepository.findById(12L)).thenReturn(Optional.of(listing12));

        List<FundDividendHistoryDto> result = service.getFundDividendHistory(1L);

        assertEquals(3, result.size());
        assertEquals("AAPL", result.get(0).getListingTicker());
        assertEquals("DIVIDEND_INFLOW", result.get(0).getStatus());
        assertEquals("RSD", result.get(0).getCurrency());
        assertBigDecimalEquals("300.0000", result.get(0).getGrossAmount());

        assertEquals("MSFT", result.get(1).getListingTicker());
        assertEquals("DIVIDEND_REINVESTED", result.get(1).getStatus());

        assertEquals("GOOG", result.get(2).getListingTicker());
        assertEquals("DIVIDEND_DISTRIBUTED", result.get(2).getStatus());
    }

    @Test
    @DisplayName("getFundDividendHistory throws EntityNotFoundException when fund does not exist")
    void getFundDividendHistory_fundMissing_throws() {
        when(investmentFundRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> service.getFundDividendHistory(999L)
        );

        verifyNoInteractions(clientFundTransactionRepository);
    }

    @Test
    @DisplayName("getFundDividendHistory returns empty list when fund has no dividend transactions")
    void getFundDividendHistory_noDividendsReturnsEmpty() {
        InvestmentFund fund = activeFund(1L, 100L);

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        when(clientFundTransactionRepository.findByFundIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of());

        List<FundDividendHistoryDto> result = service.getFundDividendHistory(1L);

        assertTrue(result.isEmpty());
    }

    private InvestmentFund activeFund(Long fundId, Long accountId) {
        InvestmentFund fund = new InvestmentFund();
        fund.setId(fundId);
        fund.setName("Test Fund " + fundId);
        fund.setAccountId(accountId);
        fund.setManagerEmployeeId(1L);
        fund.setActive(true);
        return fund;
    }

    private InternalAccountDto fundAccountDto(Long id, String balance, String availableBalance) {
        return new InternalAccountDto(
                id,
                "FUND-" + id,
                "Fund #" + id,
                new BigDecimal(balance),
                new BigDecimal(availableBalance),
                BigDecimal.ZERO,
                "RSD",
                "ACTIVE",
                null,
                null,
                "FUND"
        );
    }

    private InternalAccountDto clientAccountDto(Long accountId, Long clientId) {
        return new InternalAccountDto(
                accountId,
                "CLIENT-" + accountId,
                "Client #" + clientId,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "RSD",
                "ACTIVE",
                clientId,
                null,
                "CLIENT"
        );
    }

    private ClientFundTransaction dividendTx(
            Long fundId,
            Long listingId,
            String amount,
            ClientFundTransactionStatus status
    ) {
        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setId(300L + listingId);
        tx.setFundId(fundId);
        tx.setUserId(fundId);
        tx.setUserRole(UserRole.FUND);
        tx.setAmountRsd(new BigDecimal(amount));
        tx.setSourceAccountId(100L);
        tx.setInflow(true);
        tx.setStatus(status);
        tx.setFailureReason("DIVIDEND_INFLOW listingId=" + listingId);
        return tx;
    }

    private Listing stockListing(Long id, String ticker, String price) {
        Listing listing = new Listing();
        listing.setId(id);
        listing.setTicker(ticker);
        listing.setName(ticker + " stock");
        listing.setListingType(ListingType.STOCK);
        listing.setExchangeAcronym("BELEX");
        listing.setQuoteCurrency("RSD");
        listing.setPrice(new BigDecimal(price));
        listing.setAsk(new BigDecimal(price));
        return listing;
    }

    private ClientFundPosition position(Long id, Long fundId, Long clientId, String totalInvested) {
        ClientFundPosition position = new ClientFundPosition();
        position.setId(id);
        position.setFundId(fundId);
        position.setUserId(clientId);
        position.setUserRole(UserRole.CLIENT);
        position.setTotalInvested(new BigDecimal(totalInvested));
        return position;
    }

    private void assertBigDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
