package rs.raf.trading.investmentfund.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.ClientFundTransactionStatus;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test {@link FundLiquidationService} — money-seam adaptacija monolitnog testa
 * (faza 2c).
 *
 * <p>Monolitna verzija je razresavala fond racun preko {@code AccountRepository}
 * ({@code findById} / {@code findForUpdateById}). trading-service verzija cita
 * {@link InternalAccountDto} preko banka-core internog API-ja
 * ({@link BankaCoreClient#getAccount}). Zbog toga je {@code AccountRepository}
 * stub zamenjen {@code bankaCoreClient.getAccount} stub-om koji vraca
 * {@link InternalAccountDto} sa visokim balansom. Likvidacijska logika
 * (sortiranje holdinga po vrednosti, "najveci first", kreiranje internih
 * FUND SELL {@link Order}-a) je lokalna i pokriva se verbatim.
 */
@ExtendWith(MockitoExtension.class)
class FundLiquidationServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private ClientFundTransactionRepository transactionRepository;
    @Mock private rs.raf.trading.investmentfund.repository.ClientFundPositionRepository clientFundPositionRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private InvestmentFundRepository investmentFundRepository;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private rs.raf.trading.notification.service.NotificationService notificationService;

    @InjectMocks
    private FundLiquidationService fundLiquidationService;

    private InternalAccountDto fundAccount(Long id, String balance) {
        return new InternalAccountDto(id, "222000100000000" + id, "Banka 2 d.o.o.",
                new BigDecimal(balance), new BigDecimal(balance), BigDecimal.ZERO,
                "RSD", "ACTIVE", null, null, "FUND");
    }

    @Test
    @DisplayName("T9 - Test algoritma: Najveci holding first")
    void testLiquidateFor_Success() {
        Long fundId = 1L;
        BigDecimal amountToLiquidate = new BigDecimal("70000");

        Portfolio p1 = new Portfolio();
        p1.setListingTicker("AAPL");
        p1.setQuantity(10);
        p1.setAverageBuyPrice(new BigDecimal("150"));

        Portfolio p2 = new Portfolio();
        p2.setListingTicker("MSFT");
        p2.setQuantity(100);
        p2.setAverageBuyPrice(new BigDecimal("300"));

        Listing listing = new Listing();
        listing.setTicker("MSFT");
        listing.setBid(new BigDecimal("300.00"));

        // getFundAccount() resolve-uje InvestmentFund.accountId pa cita racun
        // preko banka-core. Stub-ujemo InvestmentFund + InternalAccountDto sa
        // visokim balansom da matchira realan production putanju.
        InvestmentFund mockFund = new InvestmentFund();
        mockFund.setId(fundId);
        mockFund.setAccountId(99L);

        lenient().when(portfolioRepository.findByUserIdAndUserRole(anyLong(), anyString())).thenReturn(List.of(p1, p2));
        lenient().when(listingRepository.findByTicker(anyString())).thenReturn(Optional.of(listing));
        lenient().when(listingRepository.findById(any())).thenReturn(Optional.of(listing));
        lenient().when(investmentFundRepository.findById(eq(fundId))).thenReturn(Optional.of(mockFund));
        lenient().when(bankaCoreClient.getAccount(eq(99L))).thenReturn(fundAccount(99L, "1000000"));
        // P1-funds-1 (1345): liquidateFor sada agregira PENDING outflow.
        lenient().when(transactionRepository.findByStatus(any())).thenReturn(List.of());
        // Listing bez set-ovane valute (ListingCurrencyResolver vraca "RSD" default),
        // ali defensive lenient stub pokriva edge slucaj ako se setup promeni.
        lenient().when(currencyConversionService.convert(any(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(currencyConversionService.getRate(anyString(), anyString()))
                .thenReturn(BigDecimal.ONE);

        fundLiquidationService.liquidateFor(fundId, amountToLiquidate);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, atLeastOnce()).save(orderCaptor.capture());

        assertEquals("MSFT", orderCaptor.getAllValues().get(0).getListing().getTicker());
    }

    @Test
    @DisplayName("T9 - Edge Case: Prazan portfolio")
    void testLiquidateFor_InsufficientAssets() {
        Long fundId = 1L;
        BigDecimal bigAmount = new BigDecimal("1000000");

        // P1-funds-1 (1345/1979): liquidateFor sada cita fund account + agregira
        // PENDING outflow pre likvidacije.
        InvestmentFund mockFund = new InvestmentFund();
        mockFund.setId(fundId);
        mockFund.setAccountId(99L);
        when(investmentFundRepository.findById(eq(fundId))).thenReturn(Optional.of(mockFund));
        when(bankaCoreClient.getAccount(eq(99L))).thenReturn(fundAccount(99L, "0"));
        when(transactionRepository.findByStatus(any())).thenReturn(List.of());
        when(portfolioRepository.findByUserIdAndUserRole(anyLong(), anyString())).thenReturn(List.of());

        fundLiquidationService.liquidateFor(fundId, bigAmount);
        verify(orderRepository, never()).save(any());
    }

    /**
     * P1-funds-1 (1979): kad fond nema NIJEDNU hartiju za prodaju ni cash,
     * PENDING withdrawal se markira FAILED (ne ostaje stuck zauvek). Pozicija
     * NIJE dirana (umanjuje se tek pri stvarnoj isplati), pa nema gubitka novca.
     */
    @Test
    @DisplayName("1979 - Prazan fond: nepokriveni PENDING withdrawal -> FAILED (ne stuck)")
    void testLiquidateFor_emptyFund_failsUncoverablePending() {
        Long fundId = 1L;
        BigDecimal amount = new BigDecimal("5000");

        InvestmentFund mockFund = new InvestmentFund();
        mockFund.setId(fundId);
        mockFund.setAccountId(99L);

        ClientFundTransaction pending = new ClientFundTransaction();
        pending.setId(700L);
        pending.setFundId(fundId);
        pending.setUserId(5L);
        pending.setUserRole("CLIENT");
        pending.setAmountRsd(new BigDecimal("5000"));
        pending.setSourceAccountId(200L);
        pending.setInflow(false);
        pending.setStatus(ClientFundTransactionStatus.PENDING);
        pending.setCreatedAt(java.time.LocalDateTime.now());

        when(investmentFundRepository.findById(eq(fundId))).thenReturn(Optional.of(mockFund));
        when(bankaCoreClient.getAccount(eq(99L))).thenReturn(fundAccount(99L, "0"));
        when(transactionRepository.findByStatus(any())).thenReturn(List.of(pending));
        when(portfolioRepository.findByUserIdAndUserRole(anyLong(), anyString())).thenReturn(List.of());

        fundLiquidationService.liquidateFor(fundId, amount);

        // Withdrawal markiran FAILED, NIJE ostao PENDING.
        assertEquals(ClientFundTransactionStatus.FAILED, pending.getStatus());
        verify(transactionRepository).save(pending);
        verify(orderRepository, never()).save(any());
        // C-notif-email: klijent dobija FUND_PAYOUT obavestenje da isplata nije uspela
        // (pravo notify, ne log-stub). Blocker #3: referenca je per-transakcija
        // (FUND_TRANSACTION, tx.getId()) — NE fundId — da in-app kljuc bude jedinstven.
        verify(notificationService).notify(eq(5L), eq("CLIENT"),
                eq(rs.raf.trading.notification.model.NotificationType.FUND_PAYOUT),
                anyString(), anyString(), eq("FUND_TRANSACTION"), eq(700L));
    }

    /**
     * C-notif-email (Sc35/49/50 TestoviCelina4): uspesna FIFO isplata iz fonda
     * ({@code onFillCompleted} -> {@code executeTransactionPayout}) salje pravo
     * FUND_PAYOUT obavestenje klijentu (zamenjuje raniji log-only stub).
     */
    @Test
    @DisplayName("Sc35/49/50 - Uspesna isplata iz fonda -> FUND_PAYOUT notif klijentu")
    void successfulPayout_emitsFundPayoutNotification() {
        Long fundId = 1L;

        Order fundOrder = new Order();
        fundOrder.setId(800L);
        fundOrder.setUserRole(rs.raf.trading.common.UserRole.FUND);
        fundOrder.setUserId(fundId);
        fundOrder.setFundId(fundId);

        ClientFundTransaction pending = new ClientFundTransaction();
        pending.setId(900L);
        pending.setFundId(fundId);
        pending.setUserId(5L);
        pending.setUserRole("CLIENT");
        pending.setAmountRsd(new BigDecimal("2000"));
        pending.setSourceAccountId(200L);
        pending.setInflow(false);
        pending.setInvestedDelta(new BigDecimal("2000"));
        pending.setStatus(ClientFundTransactionStatus.PENDING);
        pending.setCreatedAt(java.time.LocalDateTime.now());

        InvestmentFund mockFund = new InvestmentFund();
        mockFund.setId(fundId);
        mockFund.setAccountId(99L);

        when(orderRepository.findById(eq(800L))).thenReturn(Optional.of(fundOrder));
        when(investmentFundRepository.findById(eq(fundId))).thenReturn(Optional.of(mockFund));
        // Fond ima dovoljno cash-a (5000 > 2000) -> isplata se izvrsava odmah.
        when(bankaCoreClient.getAccount(eq(99L))).thenReturn(fundAccount(99L, "5000"));
        // destinationAccount (RSD licni klijentski racun).
        when(bankaCoreClient.getAccount(eq(200L))).thenReturn(
                new InternalAccountDto(200L, "2220001000000200", "Stefan J",
                        new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO,
                        "RSD", "ACTIVE", 5L, null, "CURRENT"));
        when(transactionRepository.findByStatus(any())).thenReturn(List.of(pending));

        fundLiquidationService.onFillCompleted(800L);

        assertEquals(ClientFundTransactionStatus.COMPLETED, pending.getStatus());
        // Blocker #3: per-transakcija referenca (FUND_TRANSACTION, tx.getId()=900),
        // ne fundId — svaki payout event dobija jedinstven in-app idempotency kljuc.
        verify(notificationService).notify(eq(5L), eq("CLIENT"),
                eq(rs.raf.trading.notification.model.NotificationType.FUND_PAYOUT),
                anyString(), anyString(), eq("FUND_TRANSACTION"), eq(900L));
    }
}
