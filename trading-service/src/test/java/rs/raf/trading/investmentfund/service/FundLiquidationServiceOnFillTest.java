package rs.raf.trading.investmentfund.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.model.ClientFundPosition;
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.ClientFundTransactionStatus;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.ClientFundPositionRepository;
import rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OT-1142 (TEST-tr-funds-dividends-profitbank-1): FIFO multi-fill payout u
 * {@link FundLiquidationService#onFillCompleted}.
 *
 * <p>Hook koji {@code OrderExecutionService} okida posle fill-a FUND SELL ordera.
 * Razresava PENDING withdrawal-e fonda FIFO redom (najstariji prvo), isplacujuci
 * svaki za koji fond ima dovoljno {@code availableBalance}, i ponovo cita stanje
 * racuna posle svake isplate (da naredni withdrawal vidi azuriran cash). Postojeci
 * {@code FundLiquidationServiceTest} pokriva {@code liquidateFor} (SELL kreiranje +
 * FAILED-uncoverable), ali {@code onFillCompleted} nije imao test.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FundLiquidationServiceOnFillTest {

    @Mock private OrderRepository orderRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private ClientFundTransactionRepository transactionRepository;
    @Mock private ClientFundPositionRepository clientFundPositionRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private InvestmentFundRepository investmentFundRepository;
    @Mock private CurrencyConversionService currencyConversionService;

    @InjectMocks
    private FundLiquidationService service;

    private static final Long FUND_ID = 1L;
    private static final Long FUND_ACCOUNT_ID = 99L;
    private static final Long DEST_ACCOUNT_ID = 200L;

    private InternalAccountDto fundAccount(String balance) {
        return new InternalAccountDto(FUND_ACCOUNT_ID, "2220001000000099", "Banka 2 d.o.o.",
                new BigDecimal(balance), new BigDecimal(balance), BigDecimal.ZERO,
                "RSD", "ACTIVE", null, null, "FUND");
    }

    private InternalAccountDto destAccount() {
        return new InternalAccountDto(DEST_ACCOUNT_ID, "2220001000000200", "Klijent",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "RSD", "ACTIVE", 5L, null, "CLIENT");
    }

    private Order fundSellOrder(Long orderId) {
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(FUND_ID);
        order.setUserRole(UserRole.FUND);
        order.setFundId(FUND_ID);
        return order;
    }

    private ClientFundTransaction pendingWithdrawal(Long id, String amount, LocalDateTime createdAt) {
        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setId(id);
        tx.setFundId(FUND_ID);
        tx.setUserId(5L);
        tx.setUserRole("CLIENT");
        tx.setAmountRsd(new BigDecimal(amount));
        tx.setInvestedDelta(new BigDecimal(amount));
        tx.setSourceAccountId(DEST_ACCOUNT_ID);
        tx.setInflow(false);
        tx.setStatus(ClientFundTransactionStatus.PENDING);
        tx.setCreatedAt(createdAt);
        return tx;
    }

    private InvestmentFund fund() {
        InvestmentFund f = new InvestmentFund();
        f.setId(FUND_ID);
        f.setAccountId(FUND_ACCOUNT_ID);
        return f;
    }

    @Test
    @DisplayName("onFillCompleted: FIFO isplacuje pokrivene PENDING withdrawal-e najstariji prvo, re-citajuci stanje racuna")
    void onFillCompleted_paysCoveredPendingFifo_andDecreasesPositions() {
        // Dva PENDING withdrawal-a: stariji 4000 (id=1), noviji 3000 (id=2).
        // Fond posle fill-a ima 7000 cash -> oba pokrivena.
        ClientFundTransaction older = pendingWithdrawal(1L, "4000", LocalDateTime.of(2026, 1, 1, 9, 0));
        ClientFundTransaction newer = pendingWithdrawal(2L, "3000", LocalDateTime.of(2026, 1, 2, 9, 0));

        when(orderRepository.findById(500L)).thenReturn(Optional.of(fundSellOrder(500L)));
        when(transactionRepository.findByStatus(ClientFundTransactionStatus.PENDING))
                .thenReturn(List.of(newer, older)); // namerno NE-FIFO redosled na ulazu
        when(investmentFundRepository.findById(FUND_ID)).thenReturn(Optional.of(fund()));
        // getFundAccount se cita vise puta: 7000 (pre), 3000 (posle 1. isplate), 0 (posle 2.)
        when(bankaCoreClient.getAccount(FUND_ACCOUNT_ID)).thenReturn(
                fundAccount("7000"), fundAccount("3000"), fundAccount("0"));
        when(bankaCoreClient.getAccount(DEST_ACCOUNT_ID)).thenReturn(destAccount());
        when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                .thenReturn(new TransferFundsResponse(0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(anyLong(), anyLong(), anyString()))
                .thenReturn(Optional.empty());

        service.onFillCompleted(500L);

        // Oba withdrawal-a isplacena -> COMPLETED.
        assertEquals(ClientFundTransactionStatus.COMPLETED, older.getStatus());
        assertEquals(ClientFundTransactionStatus.COMPLETED, newer.getStatus());

        // FIFO: stariji (fund-payout-1) prvi, pa noviji (fund-payout-2).
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(bankaCoreClient, times(2)).transferFunds(keyCap.capture(), any(TransferFundsRequest.class));
        assertEquals(List.of("fund-payout-1", "fund-payout-2"), keyCap.getAllValues());

        verify(transactionRepository).save(older);
        verify(transactionRepository).save(newer);
    }

    @Test
    @DisplayName("onFillCompleted: zaustavlja se na prvom NEpokrivenom withdrawal-u (FIFO break, ne preskace)")
    void onFillCompleted_stopsAtFirstUncoveredPending() {
        // Stariji 5000 (id=1) nije pokriven (cash 4000), noviji 1000 (id=2) bi bio
        // pokriven — ali FIFO ih NE preskace: break na prvom nepokrivenom.
        ClientFundTransaction older = pendingWithdrawal(1L, "5000", LocalDateTime.of(2026, 1, 1, 9, 0));
        ClientFundTransaction newer = pendingWithdrawal(2L, "1000", LocalDateTime.of(2026, 1, 2, 9, 0));

        when(orderRepository.findById(501L)).thenReturn(Optional.of(fundSellOrder(501L)));
        when(transactionRepository.findByStatus(ClientFundTransactionStatus.PENDING))
                .thenReturn(List.of(older, newer));
        when(investmentFundRepository.findById(FUND_ID)).thenReturn(Optional.of(fund()));
        when(bankaCoreClient.getAccount(FUND_ACCOUNT_ID)).thenReturn(fundAccount("4000"));

        service.onFillCompleted(501L);

        // Nijedna isplata: stariji nije pokriven -> break -> noviji se NE preskace.
        assertEquals(ClientFundTransactionStatus.PENDING, older.getStatus());
        assertEquals(ClientFundTransactionStatus.PENDING, newer.getStatus());
        verify(bankaCoreClient, never()).transferFunds(anyString(), any(TransferFundsRequest.class));
    }

    @Test
    @DisplayName("onFillCompleted: ignorise non-FUND order (no-op)")
    void onFillCompleted_ignoresNonFundOrder() {
        Order clientOrder = new Order();
        clientOrder.setId(502L);
        clientOrder.setUserId(5L);
        clientOrder.setUserRole(UserRole.CLIENT);
        when(orderRepository.findById(502L)).thenReturn(Optional.of(clientOrder));

        service.onFillCompleted(502L);

        verify(transactionRepository, never()).findByStatus(any());
        verify(bankaCoreClient, never()).transferFunds(anyString(), any());
    }

    @Test
    @DisplayName("onFillCompleted: nepostojeci order -> RuntimeException")
    void onFillCompleted_missingOrder_throws() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.onFillCompleted(999L));
    }

    @Test
    @DisplayName("onFillCompleted: decreaseClientPosition umanjuje poziciju za investedDelta pri isplati")
    void onFillCompleted_decreasesClientPositionByInvestedDelta() {
        ClientFundTransaction tx = pendingWithdrawal(1L, "2000", LocalDateTime.of(2026, 1, 1, 9, 0));

        ClientFundPosition position = new ClientFundPosition();
        position.setId(10L);
        position.setFundId(FUND_ID);
        position.setUserId(5L);
        position.setUserRole("CLIENT");
        position.setTotalInvested(new BigDecimal("5000.0000"));
        position.setLastModifiedAt(LocalDateTime.now());

        when(orderRepository.findById(503L)).thenReturn(Optional.of(fundSellOrder(503L)));
        when(transactionRepository.findByStatus(ClientFundTransactionStatus.PENDING)).thenReturn(List.of(tx));
        when(investmentFundRepository.findById(FUND_ID)).thenReturn(Optional.of(fund()));
        when(bankaCoreClient.getAccount(FUND_ACCOUNT_ID)).thenReturn(fundAccount("10000"), fundAccount("8000"));
        when(bankaCoreClient.getAccount(DEST_ACCOUNT_ID)).thenReturn(destAccount());
        when(bankaCoreClient.transferFunds(anyString(), any(TransferFundsRequest.class)))
                .thenReturn(new TransferFundsResponse(0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(FUND_ID, 5L, "CLIENT"))
                .thenReturn(Optional.of(position));

        service.onFillCompleted(503L);

        // 5000 - 2000 (investedDelta) = 3000 preostalo (pozicija ne brisana).
        ArgumentCaptor<ClientFundPosition> posCap = ArgumentCaptor.forClass(ClientFundPosition.class);
        verify(clientFundPositionRepository).save(posCap.capture());
        assertEquals(0, new BigDecimal("3000.0000").compareTo(posCap.getValue().getTotalInvested()));
        verify(clientFundPositionRepository, never()).delete(any());
    }
}
