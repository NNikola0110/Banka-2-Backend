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
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private InvestmentFundRepository investmentFundRepository;
    @Mock private CurrencyConversionService currencyConversionService;

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
        when(portfolioRepository.findByUserIdAndUserRole(anyLong(), anyString())).thenReturn(List.of());
        fundLiquidationService.liquidateFor(fundId, bigAmount);
        verify(orderRepository, never()).save(any());
    }
}
