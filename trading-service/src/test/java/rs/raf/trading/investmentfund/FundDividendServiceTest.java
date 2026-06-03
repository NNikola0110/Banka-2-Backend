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
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.investmentfund.dto.FundDividendHistoryDto;
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
import rs.raf.trading.order.exception.InsufficientFundsException;
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

    @Mock
    private FundDividendDistributionLedgerRepository distributionLedgerRepository;

    @Mock
    private FundDividendLedgerWriter ledgerWriter;

    @InjectMocks
    private FundDividendService service;

    /** P1-2: prati per-acct idempotency kljuceve transfera kroz oba run-a. */
    private final java.util.Map<Long, java.util.List<String>> transferKeysByAccount =
            new java.util.HashMap<>();
    /** P1-2: simulira nestabilan IDENTITY id (raste kroz oba run-a). */
    private final java.util.concurrent.atomic.AtomicLong rerunIdCounter =
            new java.util.concurrent.atomic.AtomicLong(900L);

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

        // P1-funds-1 (1552): stabilan idempotency kljuc izveden iz
        // (fundId, listingId, paymentDate=danas), NE iz IDENTITY id-a.
        ArgumentCaptor<CreditFundsRequest> creditCaptor =
                ArgumentCaptor.forClass(CreditFundsRequest.class);
        verify(bankaCoreClient).creditFunds(
                eq("fund-dividend-inflow-1-10-" + java.time.LocalDate.now()),
                creditCaptor.capture());
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
    @DisplayName("R2 1422: reinvest rezidual (cash < cena jedne hartije) markira TERMINALNO (FAILED), ne ostaje DIVIDEND_INFLOW")
    void reinvestDividends_residualBelowOneShare_markedTerminalNotEternalRetry() {
        // Fund cash 100, dividenda 100, cena hartije 200 → quantity = floor(100/200) = 0.
        // R2 1422 FIX: ranije je prazan `continue` ostavljao status DIVIDEND_INFLOW →
        // dnevni scheduler ga vecno ponovo pokusava. Sada se markira FAILED (terminalno)
        // pa ga listPendingDividends (samo DIVIDEND_INFLOW) vise ne pokupi.
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
        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Order> result = service.reinvestDividends(1L);

        assertTrue(result.isEmpty());
        // Terminalno markiran — vise NIJE DIVIDEND_INFLOW (eternal retry sprecen).
        assertEquals(ClientFundTransactionStatus.FAILED, pendingDividend.getStatus());
        assertNotNull(pendingDividend.getCompletedAt());
        assertTrue(pendingDividend.getFailureReason().toLowerCase().contains("rezidual"));

        verifyNoInteractions(orderRepository);
        verifyNoInteractions(fundReservationService);
        verify(clientFundTransactionRepository).save(pendingDividend);
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

    /**
     * BE-FND-01 regression test: ako concurrent withdrawal smanji
     * availableBalance na fond racunu izmedju iteracija, distribute mora da
     * EARLY EXIT-uje (ne pokusava sledeci transfer) i NE sme da markira
     * pendingDividends kao DISTRIBUTED — sledeci run pokuplja preostale
     * pozicije bez double-pay-a (idempotency kljucevi to dodatno cuvaju).
     */
    @Test
    @DisplayName("distributeDividendsToClients_concurrentWithdrawalMidLoop_exitsEarlyAndKeepsPending")
    void distributeDividendsToClients_concurrentWithdrawalMidLoop_exitsEarlyAndKeepsPending() {
        InvestmentFund fund = activeFund(1L, 100L);
        // Pretpocetni snimak fund racuna: 2000 raspolozivo (pre-check prolazi).
        InternalAccountDto initialFundAccount = fundAccountDto(100L, "2000.0000", "2000.0000");
        // Drugi snimak (refresh PRE prve iteracije) i dalje 2000 — prvi klijent prolazi.
        // Treci snimak (refresh PRE druge iteracije) je vec smanjen na 100 — DRUGI klijent fail-uje (100 < 300).
        InternalAccountDto drainedFundAccount = fundAccountDto(100L, "100.0000", "100.0000");

        InternalAccountDto clientOneAccount = clientAccountDto(201L, 11L);

        ClientFundTransaction pendingDividend = dividendTx(
                1L,
                10L,
                "1000.0000",
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        );

        ClientFundPosition positionOne = position(1L, 1L, 11L, "700.0000");
        ClientFundPosition positionTwo = position(2L, 1L, 22L, "300.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        // 1. poziv pri ulasku (pre-check totalDividend vs available)
        // 2. poziv refresh pre 1. iteracije (700 RSD, prolazi)
        // 3. poziv refresh pre 2. iteracije (100 RSD, fail - early exit)
        when(bankaCoreClient.getAccount(100L))
                .thenReturn(initialFundAccount, initialFundAccount, drainedFundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L,
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        )).thenReturn(List.of(pendingDividend));
        when(clientFundPositionRepository.findByFundId(1L))
                .thenReturn(List.of(positionOne, positionTwo));

        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 11L, "RSD"))
                .thenReturn(clientOneAccount);

        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(invocation -> {
                    ClientFundTransaction tx = invocation.getArgument(0);
                    if (tx.getId() == null) {
                        tx.setId(950L);
                    }
                    return tx;
                });

        List<ClientFundTransaction> distributions = service.distributeDividendsToClients(1L);

        // Samo prva isplata prosla (klijent 11), druga (klijent 22) NIJE pokusana.
        assertEquals(1, distributions.size());
        verify(bankaCoreClient, org.mockito.Mockito.times(1))
                .transferFunds(anyString(), any(TransferFundsRequest.class));

        // KRITICNO: pendingDividends NIJE oznacen DIVIDEND_DISTRIBUTED — ostaje INFLOW
        // za sledeci run koji ce pokupiti klijenta 22.
        assertEquals(ClientFundTransactionStatus.DIVIDEND_INFLOW, pendingDividend.getStatus());

        // resolveClientRsdAccount za klijenta 22 NIKAD nije pozvan (early exit pre toga).
        verify(bankaCoreClient, never())
                .getPreferredAccount(eq(UserRole.CLIENT), eq(22L), anyString());

        verify(fundValueSnapshotScheduler).snapshotFundIfMissing(fund);
    }

    /**
     * P1-2 double-pay regression: prvi run isplati klijenta A, zatim padne na
     * klijentu B (banka-core 409) → outer {@code @Transactional} radi rollback
     * (svi {@code ClientFundTransaction} redovi se ponistavaju u trading_db).
     * ALI banka-core transfer klijentu A je VEC commit-ovan out-of-process, a
     * trajni ledger marker (zapisan {@code REQUIRES_NEW}) prezivi rollback.
     *
     * <p>Drugi run (simulacija dnevnog cron retry-a) za ISTI dividendni priliv
     * MORA preskociti klijenta A (vec placen po trajnom ledger marker-u) i NE sme
     * ga platiti drugi put. Konzervacija: ukupno prebaceno A-u = tacno njegov
     * udeo, jednom.
     */
    @Test
    @DisplayName("partialThenRerun_doesNotDoublePay")
    void partialThenRerun_doesNotDoublePay() {
        InvestmentFund fund = activeFund(1L, 100L);
        InternalAccountDto fundAccount = fundAccountDto(100L, "2000.0000", "2000.0000");

        InternalAccountDto clientOneAccount = clientAccountDto(201L, 11L);
        InternalAccountDto clientTwoAccount = clientAccountDto(202L, 22L);

        // Jedan dividendni priliv (stabilna kotva — id=310 preko dividendTx helpera).
        ClientFundTransaction pendingDividend = dividendTx(
                1L, 10L, "1000.0000", ClientFundTransactionStatus.DIVIDEND_INFLOW);

        ClientFundPosition positionOne = position(1L, 1L, 11L, "700.0000");
        ClientFundPosition positionTwo = position(2L, 1L, 22L, "300.0000");

        when(investmentFundRepository.findById(1L)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAccount);
        when(clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                1L, ClientFundTransactionStatus.DIVIDEND_INFLOW))
                .thenReturn(List.of(pendingDividend));
        when(clientFundPositionRepository.findByFundId(1L))
                .thenReturn(List.of(positionOne, positionTwo));
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 11L, "RSD"))
                .thenReturn(clientOneAccount);
        when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, 22L, "RSD"))
                .thenReturn(clientTwoAccount);

        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(invocation -> {
                    ClientFundTransaction tx = invocation.getArgument(0);
                    if (tx.getId() == null) {
                        // IDENTITY id NIJE stabilan kroz rollback/re-run — namerno
                        // dodeljujemo razlicit id u drugom run-u da dokazemo da se
                        // idempotency kljuc NE sme izvoditi iz njega.
                        tx.setId(rerunIdCounter.getAndIncrement());
                    }
                    return tx;
                });

        // Trajni ledger marker: prezivi outer-tx rollback (writer ga upisuje u
        // REQUIRES_NEW). Modeliramo ga stateful Set-om kljuceva.
        java.util.Set<String> committedLedgerKeys = new java.util.HashSet<>();
        org.mockito.Mockito.lenient()
                .when(distributionLedgerRepository.existsByIdempotencyKey(anyString()))
                .thenAnswer(inv -> committedLedgerKeys.contains(inv.getArgument(0)));
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            FundDividendDistributionLedger ledger = inv.getArgument(0);
            committedLedgerKeys.add(ledger.getIdempotencyKey());
            return null;
        }).when(ledgerWriter).recordPaid(any(FundDividendDistributionLedger.class));

        // Stateful banka-core transfer: belezi SAMO USPESNE (commit-ovane) transfere
        // po odredisnom racunu. Prvi run: klijent A (acct 201) prolazi, klijent B
        // (acct 202) baca 409 PRE knjizenja (ne broji se kao isplata).
        org.mockito.Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            TransferFundsRequest req = inv.getArgument(1);
            if (req.toAccountId() == 202L) {
                throw new BankaCoreClientException(409, "Nedovoljno sredstava (concurrent withdrawal).");
            }
            transferKeysByAccount.computeIfAbsent(req.toAccountId(), k -> new java.util.ArrayList<>()).add(key);
            return null;
        }).when(bankaCoreClient).transferFunds(anyString(), any(TransferFundsRequest.class));

        // ── RUN 1: parcijalna isplata, pada na klijentu B → rollback. ──
        assertThrows(InsufficientFundsException.class,
                () -> service.distributeDividendsToClients(1L));

        // Klijent A je placen tacno jednom u run-u 1.
        assertEquals(1, transferKeysByAccount.getOrDefault(201L, List.of()).size(),
                "Klijent A mora biti placen tacno jednom u prvom run-u");

        // ── RUN 2: cron retry. Klijent B sad ima sredstva (uspeva). ──
        // Klijent A je VEC u trajnom ledgeru → mora biti preskocen (NIJE placen drugi put).
        org.mockito.Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            TransferFundsRequest req = inv.getArgument(1);
            transferKeysByAccount.computeIfAbsent(req.toAccountId(), k -> new java.util.ArrayList<>()).add(key);
            return null;
        }).when(bankaCoreClient).transferFunds(anyString(), any(TransferFundsRequest.class));

        service.distributeDividendsToClients(1L);

        // KRITICNO: klijent A NIJE placen drugi put — i dalje tacno 1 uspesan transfer ka acct 201.
        assertEquals(1, transferKeysByAccount.getOrDefault(201L, List.of()).size(),
                "Klijent A NE sme biti placen dvaput nakon parcijalnog run-a + cron re-run-a");

        // Klijent B je sad uspesno placen (tacno jednom).
        assertEquals(1, transferKeysByAccount.getOrDefault(202L, List.of()).size(),
                "Klijent B mora biti placen tacno jednom (drugi run)");

        // Stabilan kljuc: id NIJE izveden iz IDENTITY id-a (koji se menja kroz run-ove
        // preko rerunIdCounter); kotva je pending inflow id (310), a ne tx id.
        String aKey = transferKeysByAccount.get(201L).get(0);
        assertTrue(aKey.contains("-11-310-"),
                "Idempotency kljuc klijenta A mora biti stabilan (anchor=inflow 310), bio: " + aKey);
        assertTrue(aKey.startsWith("fund-dividend-distribution-1-11-"),
                "Idempotency kljuc klijenta A mora pratiti stabilan format, bio: " + aKey);

        // Konzervacija: tacno 2 USPESNA transfera ukupno (A 700 + B 300 = 1000 = priliv).
        assertEquals(2, transferKeysByAccount.values().stream()
                .mapToInt(List::size).sum(),
                "Ukupno tacno 2 uspesna transfera ka klijentskim racunima nakon obe runde (konzervacija)");
    }

    @Test
    @DisplayName("scheduledDividendProcessing continues with other funds if one fund fails")
    void scheduledDividendProcessing_exceptionInOneFund_continuesOthers() {
        InvestmentFund firstFund = activeFund(1L, 101L);
        firstFund.setReinvestDividends(true);
        InvestmentFund secondFund = activeFund(2L, 102L);
        secondFund.setReinvestDividends(true);

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
