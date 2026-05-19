package rs.raf.trading.order.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test {@link FundsVerificationService} — money-seam adaptacija monolitnog
 * testa (faza 2c). Monolit je citao {@code Account} iz {@code AccountRepository};
 * trading-service cita metadata racuna preko banka-core internog seam-a
 * ({@link BankaCoreClient#getAccount}). Verifikacija sredstava (BUY) i margine
 * je read-only i ostaje verno preneta — samo se izvor racuna menja. Verifikacija
 * hartija (SELL) dira lokalni {@code Portfolio} i kopirana je verbatim.
 * "Account not found" monolitni test je zamenjen 404-stub testom (banka-core
 * 404 -> {@link BankaCoreClientException}, monolit je bacao
 * {@code IllegalArgumentException}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FundsVerificationService")
class FundsVerificationServiceTest {

    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private PortfolioRepository portfolioRepository;

    @InjectMocks
    private FundsVerificationService service;

    private InternalAccountDto accountWithBalance(BigDecimal balance, BigDecimal availableBalance) {
        return new InternalAccountDto(1L, "111", "Owner", balance, availableBalance,
                BigDecimal.ZERO, "RSD", "ACTIVE", 1L, null, "CLIENT");
    }

    private Listing stockListing(BigDecimal price) {
        Listing l = new Listing();
        l.setId(1L);
        l.setListingType(ListingType.STOCK);
        l.setPrice(price);
        return l;
    }

    private Listing futuresListing(BigDecimal price, int contractSize) {
        Listing l = new Listing();
        l.setId(1L);
        l.setListingType(ListingType.FUTURES);
        l.setPrice(price);
        l.setContractSize(contractSize);
        return l;
    }

    private CreateOrderDto buyDto(Long accountId) {
        CreateOrderDto dto = new CreateOrderDto();
        dto.setAccountId(accountId);
        dto.setQuantity(10);
        dto.setContractSize(1);
        dto.setDirection("BUY");
        dto.setMargin(false);
        return dto;
    }

    private CreateOrderDto sellDto(Long accountId, int quantity) {
        CreateOrderDto dto = new CreateOrderDto();
        dto.setAccountId(accountId);
        dto.setQuantity(quantity);
        dto.setContractSize(1);
        dto.setDirection("SELL");
        dto.setMargin(false);
        return dto;
    }

    @Nested
    @DisplayName("BUY — provera sredstava")
    class BuyFundsCheck {

        @Test
        @DisplayName("MARKET BUY — dovoljno sredstava (sa provizijom 14%) → prolazi")
        void marketBuyWithSufficientFunds() {
            BigDecimal approxPrice = new BigDecimal("100");
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("200"), new BigDecimal("200")));

            assertDoesNotThrow(() ->
                    service.verify(buyDto(1L), 1L, "CLIENT", approxPrice, stockListing(new BigDecimal("10")), OrderType.MARKET, OrderDirection.BUY));
        }

        @Test
        @DisplayName("MARKET BUY — nedovoljno sredstava → Insufficient funds")
        void marketBuyWithInsufficientFunds() {
            BigDecimal approxPrice = new BigDecimal("100");
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("100"), new BigDecimal("100")));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    service.verify(buyDto(1L), 1L, "CLIENT", approxPrice, stockListing(new BigDecimal("10")), OrderType.MARKET, OrderDirection.BUY));
            assertEquals("Insufficient funds", ex.getMessage());
        }

        @Test
        @DisplayName("MARKET BUY — provizija je min(14%,7) — veća cena, 7 dominira")
        void marketBuyCommissionCappedAt7() {
            BigDecimal approxPrice = new BigDecimal("1000");
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("1200"), new BigDecimal("1200")));

            assertDoesNotThrow(() ->
                    service.verify(buyDto(1L), 1L, "CLIENT", approxPrice, stockListing(new BigDecimal("100")), OrderType.MARKET, OrderDirection.BUY));
        }

        @Test
        @DisplayName("LIMIT BUY — provizija je min(24%,12)")
        void limitBuyCommission() {
            BigDecimal approxPrice = new BigDecimal("100");
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("130"), new BigDecimal("130")));

            assertDoesNotThrow(() ->
                    service.verify(buyDto(1L), 1L, "CLIENT", approxPrice, stockListing(new BigDecimal("10")), OrderType.LIMIT, OrderDirection.BUY));
        }

        @Test
        @DisplayName("LIMIT BUY — nedovoljno sa provizijom → Insufficient funds")
        void limitBuyInsufficientWithCommission() {
            BigDecimal approxPrice = new BigDecimal("100");
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("110"), new BigDecimal("110")));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    service.verify(buyDto(1L), 1L, "CLIENT", approxPrice, stockListing(new BigDecimal("10")), OrderType.LIMIT, OrderDirection.BUY));
            assertEquals("Insufficient funds", ex.getMessage());
        }

        @Test
        @DisplayName("STOP BUY — provizija kao MARKET: min(14%,7)")
        void stopBuyCommission() {
            BigDecimal approxPrice = new BigDecimal("100");
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("120"), new BigDecimal("120")));

            assertDoesNotThrow(() ->
                    service.verify(buyDto(1L), 1L, "CLIENT", approxPrice, stockListing(new BigDecimal("10")), OrderType.STOP, OrderDirection.BUY));
        }

        @Test
        @DisplayName("STOP_LIMIT BUY — provizija kao LIMIT: min(24%,12)")
        void stopLimitBuyCommission() {
            BigDecimal approxPrice = new BigDecimal("100");
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("130"), new BigDecimal("130")));

            assertDoesNotThrow(() ->
                    service.verify(buyDto(1L), 1L, "CLIENT", approxPrice, stockListing(new BigDecimal("10")), OrderType.STOP_LIMIT, OrderDirection.BUY));
        }
    }

    @Nested
    @DisplayName("SELL — provera portfolija")
    class SellPortfolioCheck {

        private Portfolio portfolioWith(Long listingId, int quantity) {
            Portfolio p = new Portfolio();
            p.setListingId(listingId);
            p.setQuantity(quantity);
            return p;
        }

        @Test
        @DisplayName("SELL sa dovoljno hartija u portfoliju → prolazi")
        void sellWithSufficientPortfolio() {
            CreateOrderDto dto = sellDto(1L, 5);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(BigDecimal.ZERO, BigDecimal.ZERO));
            when(portfolioRepository.findByUserIdAndUserRole(1L, "CLIENT")).thenReturn(List.of(portfolioWith(1L, 10)));

            assertDoesNotThrow(() ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("500"), stockListing(new BigDecimal("100")), OrderType.MARKET, OrderDirection.SELL));
        }

        @Test
        @DisplayName("SELL sa tačno toliko hartija → prolazi")
        void sellWithExactPortfolio() {
            CreateOrderDto dto = sellDto(1L, 10);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(BigDecimal.ZERO, BigDecimal.ZERO));
            when(portfolioRepository.findByUserIdAndUserRole(1L, "CLIENT")).thenReturn(List.of(portfolioWith(1L, 10)));

            assertDoesNotThrow(() ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("1000"), stockListing(new BigDecimal("100")), OrderType.MARKET, OrderDirection.SELL));
        }

        @Test
        @DisplayName("SELL bez dovoljno hartija → Insufficient securities in portfolio")
        void sellWithInsufficientPortfolio() {
            CreateOrderDto dto = sellDto(1L, 15);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(BigDecimal.ZERO, BigDecimal.ZERO));
            when(portfolioRepository.findByUserIdAndUserRole(1L, "CLIENT")).thenReturn(List.of(portfolioWith(1L, 10)));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("1500"), stockListing(new BigDecimal("100")), OrderType.MARKET, OrderDirection.SELL));
            assertTrue(ex.getMessage().startsWith("Insufficient securities"));
        }

        @Test
        @DisplayName("SELL sa praznim portfolijem → Insufficient securities in portfolio")
        void sellWithEmptyPortfolio() {
            CreateOrderDto dto = sellDto(1L, 1);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(BigDecimal.ZERO, BigDecimal.ZERO));
            when(portfolioRepository.findByUserIdAndUserRole(1L, "CLIENT")).thenReturn(List.of());

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("100"), stockListing(new BigDecimal("100")), OrderType.MARKET, OrderDirection.SELL));
            assertTrue(ex.getMessage().startsWith("Insufficient securities"));
        }
    }

    @Nested
    @DisplayName("Margin order — precheck")
    class MarginCheck {

        @Test
        @DisplayName("STOCK margin — balance > initialMarginCost → prolazi")
        void stockMarginBalanceSufficient() {
            CreateOrderDto dto = buyDto(1L);
            dto.setMargin(true);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("100"), new BigDecimal("0")));

            assertDoesNotThrow(() ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("0"), stockListing(new BigDecimal("100")), OrderType.MARKET, OrderDirection.BUY));
        }

        @Test
        @DisplayName("STOCK margin — availableBalance (kredit) > initialMarginCost → prolazi")
        void stockMarginCreditSufficient() {
            CreateOrderDto dto = buyDto(1L);
            dto.setMargin(true);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("10"), new BigDecimal("100")));

            assertDoesNotThrow(() ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("0"), stockListing(new BigDecimal("100")), OrderType.MARKET, OrderDirection.BUY));
        }

        @Test
        @DisplayName("STOCK margin — ni balance ni kredit nisu dovoljni → Insufficient funds for margin order")
        void stockMarginBothInsufficient() {
            CreateOrderDto dto = buyDto(1L);
            dto.setMargin(true);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("30"), new BigDecimal("30")));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("0"), stockListing(new BigDecimal("100")), OrderType.MARKET, OrderDirection.BUY));
            assertEquals("Insufficient funds for margin order", ex.getMessage());
        }

        @Test
        @DisplayName("FUTURES margin — contractSize * price * 10% → ispravan izračun")
        void futuresMarginCalculation() {
            CreateOrderDto dto = buyDto(1L);
            dto.setMargin(true);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("200"), new BigDecimal("0")));

            assertDoesNotThrow(() ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("0"), futuresListing(new BigDecimal("100"), 10), OrderType.MARKET, OrderDirection.BUY));
        }

        @Test
        @DisplayName("FUTURES margin — nedovoljno → Insufficient funds for margin order")
        void futuresMarginInsufficient() {
            CreateOrderDto dto = buyDto(1L);
            dto.setMargin(true);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("50"), new BigDecimal("50")));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("0"), futuresListing(new BigDecimal("100"), 10), OrderType.MARKET, OrderDirection.BUY));
            assertEquals("Insufficient funds for margin order", ex.getMessage());
        }

        @Test
        @DisplayName("Account ne postoji — banka-core 404 → BankaCoreClientException")
        void accountNotFound_throws() {
            CreateOrderDto dto = buyDto(99L);
            when(bankaCoreClient.getAccount(99L))
                    .thenThrow(new BankaCoreClientException(404, "banka-core GET /internal/accounts/99 -> 404"));

            BankaCoreClientException ex = assertThrows(BankaCoreClientException.class, () ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("100"), stockListing(new BigDecimal("10")), OrderType.MARKET, OrderDirection.BUY));
            assertEquals(404, ex.getHttpStatus());
        }

        @Test
        @DisplayName("FUTURES margin — null contractSize default 1")
        void futuresMargin_nullContractSize_defaultsToOne() {
            CreateOrderDto dto = buyDto(1L);
            dto.setMargin(true);
            Listing l = new Listing();
            l.setId(1L);
            l.setListingType(ListingType.FUTURES);
            l.setPrice(new BigDecimal("100"));
            l.setContractSize(null);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("1000"), new BigDecimal("1000")));

            assertDoesNotThrow(() ->
                    service.verify(dto, 1L, "CLIENT", new BigDecimal("0"), l, OrderType.MARKET, OrderDirection.BUY));
        }

        @Test
        @DisplayName("@Deprecated verify overload delegira sa CLIENT rolom")
        void deprecatedVerifyOverload_delegatesAsClient() {
            CreateOrderDto dto = buyDto(1L);
            when(bankaCoreClient.getAccount(1L)).thenReturn(accountWithBalance(new BigDecimal("200"), new BigDecimal("200")));

            assertDoesNotThrow(() ->
                    service.verify(dto, 1L, new BigDecimal("100"), stockListing(new BigDecimal("10")), OrderType.MARKET, OrderDirection.BUY));
        }
    }
}
