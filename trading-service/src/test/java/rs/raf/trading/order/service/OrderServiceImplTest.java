package rs.raf.trading.order.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.berza.service.ExchangeManagementService;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.exception.InsufficientHoldingsException;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.implementation.OrderServiceImpl;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
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
 * Test {@link OrderServiceImpl} — money-seam adaptacija monolitnog testa
 * (faza 2c). Monolit je mockovao {@code ClientRepository}/{@code EmployeeRepository}
 * (identitet) i {@code AccountRepository} (racuni). U trading-service-u racuni +
 * identitet zive u banka-core domenu, pa je:
 * <ul>
 *   <li>identitet -&gt; {@link rs.raf.trading.security.TradingUserResolver} mock</li>
 *   <li>racuni -&gt; {@link BankaCoreClient#getAccount} mock ({@link InternalAccountDto})</li>
 * </ul>
 * {@code reserveForBuy(Order, Account)} je u trading-service-u {@code reserveForBuy(Order)} —
 * monolitni 2-arg verify je zamenjen 1-arg verify-em. {@code getOrderById}
 * supervizorska provera i dalje cita Spring Security authorities direktno.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("OrderServiceImpl — createOrder / approve / decline / queries")
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private ActuaryInfoRepository actuaryInfoRepository;
    @Mock private OrderValidationService orderValidationService;
    @Mock private ListingPriceService listingPriceService;
    @Mock private OrderStatusService orderStatusService;
    @Mock private ExchangeManagementService exchangeManagementService;
    @Mock private FundReservationService fundReservationService;
    @Mock private BankTradingAccountResolver bankTradingAccountResolver;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private InvestmentFundRepository investmentFundRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private rs.raf.trading.security.TradingUserResolver tradingUserResolver;

    @InjectMocks
    private OrderServiceImpl orderService;

    private static final Long CLIENT_ID = 42L;
    private static final Long EMPLOYEE_ID = 99L;

    private Listing testListing;
    private InternalAccountDto testClientAccount;
    private InternalAccountDto testBankUsdAccount;
    private Portfolio testPortfolio;

    private InternalAccountDto account(Long id, String currency, String category, BigDecimal balance) {
        return new InternalAccountDto(id, "acc-" + id, "Owner",
                balance, balance, BigDecimal.ZERO, currency, "ACTIVE",
                "CLIENT".equals(category) ? id : null, null, category);
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        testListing = new Listing();
        testListing.setId(1L);
        testListing.setTicker("AAPL");
        testListing.setName("Apple Inc.");
        testListing.setListingType(ListingType.STOCK);
        testListing.setPrice(new BigDecimal("150"));
        testListing.setAsk(new BigDecimal("151"));
        testListing.setBid(new BigDecimal("149"));
        testListing.setExchangeAcronym("NASDAQ");

        testClientAccount = account(100L, "USD", "CLIENT", new BigDecimal("10000.0000"));
        testBankUsdAccount = account(900L, "USD", "BANK_TRADING", new BigDecimal("5000000.0000"));

        testPortfolio = new Portfolio();
        testPortfolio.setId(500L);
        testPortfolio.setUserId(CLIENT_ID);
        testPortfolio.setListingId(1L);
        testPortfolio.setListingTicker("AAPL");
        testPortfolio.setListingName("Apple Inc.");
        testPortfolio.setListingType("STOCK");
        testPortfolio.setQuantity(30);
        testPortfolio.setReservedQuantity(0);
        testPortfolio.setPublicQuantity(0);
        testPortfolio.setAverageBuyPrice(new BigDecimal("140.0000"));

        // Default seam stubovi (LENIENT).
        lenient().when(bankaCoreClient.getAccount(100L)).thenReturn(testClientAccount);
        lenient().when(bankTradingAccountResolver.resolve(anyString())).thenReturn(testBankUsdAccount);
        lenient().when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(anyLong(), anyString(), anyLong()))
                .thenReturn(Optional.of(testPortfolio));
        lenient().when(currencyConversionService.getRate(anyString(), anyString())).thenReturn(BigDecimal.ONE);
        lenient().when(currencyConversionService.convertForPurchase(
                any(BigDecimal.class), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> new CurrencyConversionService.ConversionResult(
                        inv.getArgument(0), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE));
        lenient().when(orderValidationService.parseOrderType(anyString())).thenReturn(OrderType.MARKET);
        lenient().when(orderValidationService.parseDirection(anyString())).thenReturn(OrderDirection.BUY);
        lenient().when(tradingUserResolver.resolveName(anyLong(), anyString())).thenReturn("Test User");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void asClient() {
        when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(CLIENT_ID, "CLIENT"));
    }

    private void asEmployee() {
        when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(EMPLOYEE_ID, "EMPLOYEE"));
    }

    /** SecurityContext samo za getOrderById supervizorsku proveru authorities-a. */
    private void securityAuthorities(String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@test.com", null,
                        List.of(new SimpleGrantedAuthority(authority))));
    }

    private CreateOrderDto validMarketBuyDto() {
        CreateOrderDto dto = new CreateOrderDto();
        dto.setListingId(1L);
        dto.setOrderType("MARKET");
        dto.setDirection("BUY");
        dto.setQuantity(5);
        dto.setContractSize(1);
        dto.setAccountId(100L);
        return dto;
    }

    private CreateOrderDto validMarketSellDto() {
        CreateOrderDto dto = validMarketBuyDto();
        dto.setDirection("SELL");
        return dto;
    }

    private void stubPriceServices(String price, String approx) {
        when(listingPriceService.getPricePerUnit(any(), any(), any(), any())).thenReturn(new BigDecimal(price));
        when(listingPriceService.calculateApproximatePrice(anyInt(), any(), anyInt())).thenReturn(new BigDecimal(approx));
    }

    private void stubSave() {
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) o.setId(1L);
            return o;
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("CLIENT kreiranje ordera")
    class ClientCreateOrder {

        @Test
        @DisplayName("CLIENT MARKET BUY → status APPROVED, approvedBy='No need for approval'")
        void clientMarketBuyApproved() {
            CreateOrderDto dto = validMarketBuyDto();
            asClient();
            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");
            when(orderStatusService.determineStatus("CLIENT", CLIENT_ID, new BigDecimal("755.0000")))
                    .thenReturn(OrderStatus.APPROVED);
            stubSave();

            OrderDto result = orderService.createOrder(dto);

            assertNotNull(result);
            assertEquals("APPROVED", result.getStatus());
            assertEquals("No need for approval", result.getApprovedBy());
            assertEquals("CLIENT", result.getUserRole());

            verify(orderRepository).save(any(Order.class));
            verify(actuaryInfoRepository, never()).save(any());
        }

        @Test
        @DisplayName("CLIENT BUY — rezervise sredstva preko FundReservationService")
        void clientBuyReservesFunds() {
            CreateOrderDto dto = validMarketBuyDto();
            asClient();
            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");
            when(orderStatusService.determineStatus(eq("CLIENT"), eq(CLIENT_ID), any())).thenReturn(OrderStatus.APPROVED);
            stubSave();

            orderService.createOrder(dto);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).save(captor.capture());
            Order saved = captor.getValue();
            assertEquals(OrderStatus.APPROVED, saved.getStatus());
            assertEquals("CLIENT", saved.getUserRole());
            assertEquals(testClientAccount.id(), saved.getReservedAccountId());
            assertNotNull(saved.getReservedAmount());
            assertTrue(saved.getReservedAmount().compareTo(BigDecimal.ZERO) > 0);
            assertNotNull(saved.getApprovedAt());
            verify(fundReservationService).reserveForBuy(any(Order.class));
        }
    }

    @Nested
    @DisplayName("AGENT kreiranje ordera")
    class AgentCreateOrder {

        private ActuaryInfo agentInfo(BigDecimal usedLimit) {
            ActuaryInfo a = new ActuaryInfo();
            a.setActuaryType(ActuaryType.AGENT);
            a.setUsedLimit(usedLimit);
            a.setDailyLimit(new BigDecimal("100000"));
            a.setNeedApproval(false);
            return a;
        }

        @Test
        @DisplayName("AGENT — APPROVED → usedLimit se ažurira")
        void agentApprovedUpdatesUsedLimit() {
            CreateOrderDto dto = validMarketBuyDto();
            asEmployee();
            ActuaryInfo info = agentInfo(new BigDecimal("1000"));

            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");
            when(orderStatusService.determineStatus("EMPLOYEE", EMPLOYEE_ID, new BigDecimal("755.0000")))
                    .thenReturn(OrderStatus.APPROVED);
            when(orderStatusService.getAgentInfo(EMPLOYEE_ID)).thenReturn(Optional.of(info));
            stubSave();

            OrderDto result = orderService.createOrder(dto);

            assertEquals("APPROVED", result.getStatus());
            verify(actuaryInfoRepository).save(info);
            assertEquals(new BigDecimal("1755.0000"), info.getUsedLimit());
        }

        @Test
        @DisplayName("AGENT — PENDING → usedLimit se NE ažurira")
        void agentPendingDoesNotUpdateUsedLimit() {
            CreateOrderDto dto = validMarketBuyDto();
            asEmployee();
            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");
            when(orderStatusService.determineStatus("EMPLOYEE", EMPLOYEE_ID, new BigDecimal("755.0000")))
                    .thenReturn(OrderStatus.PENDING);
            stubSave();

            OrderDto result = orderService.createOrder(dto);

            assertEquals("PENDING", result.getStatus());
            assertNull(result.getApprovedBy());
            verify(actuaryInfoRepository, never()).save(any());
        }

        @Test
        @DisplayName("AGENT APPROVED koji je SUPERVISOR — usedLimit se NE ažurira")
        void supervisorApprovedDoesNotUpdateUsedLimit() {
            CreateOrderDto dto = validMarketBuyDto();
            asEmployee();
            ActuaryInfo supervisorInfo = new ActuaryInfo();
            supervisorInfo.setActuaryType(ActuaryType.SUPERVISOR);

            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");
            when(orderStatusService.determineStatus("EMPLOYEE", EMPLOYEE_ID, new BigDecimal("755.0000")))
                    .thenReturn(OrderStatus.APPROVED);
            when(orderStatusService.getAgentInfo(EMPLOYEE_ID)).thenReturn(Optional.of(supervisorInfo));
            stubSave();

            orderService.createOrder(dto);

            verify(actuaryInfoRepository, never()).save(any());
        }

        @Test
        @DisplayName("AGENT BUY bez accountId — koristi bankin trading racun, provizija=0")
        void agentBuyUsesBankTradingAccount() {
            CreateOrderDto dto = validMarketBuyDto();
            dto.setAccountId(null);
            asEmployee();
            ActuaryInfo info = agentInfo(BigDecimal.ZERO);

            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");
            when(orderStatusService.determineStatus(eq("EMPLOYEE"), eq(EMPLOYEE_ID), any()))
                    .thenReturn(OrderStatus.APPROVED);
            when(orderStatusService.getAgentInfo(EMPLOYEE_ID)).thenReturn(Optional.of(info));
            stubSave();

            orderService.createOrder(dto);

            verify(bankTradingAccountResolver).resolve("USD");
            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).save(captor.capture());
            Order saved = captor.getValue();
            assertEquals("EMPLOYEE", saved.getUserRole());
            assertEquals(testBankUsdAccount.id(), saved.getReservedAccountId());
            // provizija 0 za zaposlenog → reservedAmount == approximatePrice
            assertEquals(0, new BigDecimal("755.0000").compareTo(saved.getReservedAmount()));
        }

        @Test
        @DisplayName("AGENT BUY agent sa null usedLimit → ZERO fallback")
        void agentNullUsedLimit_usesZero() {
            CreateOrderDto dto = validMarketBuyDto();
            asEmployee();
            ActuaryInfo info = new ActuaryInfo();
            info.setActuaryType(ActuaryType.AGENT);
            info.setUsedLimit(null);

            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");
            when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.APPROVED);
            when(orderStatusService.getAgentInfo(EMPLOYEE_ID)).thenReturn(Optional.of(info));
            stubSave();

            orderService.createOrder(dto);

            assertNotNull(info.getUsedLimit());
            verify(actuaryInfoRepository).save(info);
        }
    }

    @Nested
    @DisplayName("Greške")
    class ErrorCases {

        @Test
        @DisplayName("Listing ne postoji → EntityNotFoundException")
        void listingNotFound() {
            CreateOrderDto dto = validMarketBuyDto();
            asClient();
            when(listingRepository.findById(1L)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> orderService.createOrder(dto));
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Validacija baca grešku → ne nastavlja se")
        void validationFailurePropagates() {
            CreateOrderDto dto = validMarketBuyDto();
            doThrow(new IllegalArgumentException("Invalid order type or direction"))
                    .when(orderValidationService).validate(dto);

            assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(dto));
            verify(listingRepository, never()).findById(any());
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("availableBalance nedovoljan → InsufficientFundsException, order se ne čuva")
        void insufficientAvailableBalancePropagates() {
            CreateOrderDto dto = validMarketBuyDto();
            asClient();
            when(bankaCoreClient.getAccount(100L))
                    .thenReturn(account(100L, "USD", "CLIENT", new BigDecimal("100.0000")));
            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");

            InsufficientFundsException ex = assertThrows(InsufficientFundsException.class,
                    () -> orderService.createOrder(dto));
            assertTrue(ex.getMessage().contains("Nedovoljno"));
            verify(orderRepository, never()).save(any());
            verify(fundReservationService, never()).reserveForBuy(any());
        }

        @Test
        @DisplayName("CLIENT BUY: nepostojeci racun (banka-core 404) → EntityNotFoundException")
        void createBuy_accountMissing_throwsNotFound() {
            CreateOrderDto dto = validMarketBuyDto();
            asClient();
            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");
            when(bankaCoreClient.getAccount(100L))
                    .thenThrow(new rs.raf.trading.client.BankaCoreClientException(404, "404"));

            assertThrows(EntityNotFoundException.class, () -> orderService.createOrder(dto));
        }
    }

    @Nested
    @DisplayName("Sistemska polja ordera")
    class SystemFields {

        @Test
        @DisplayName("Order se čuva sa isDone=false, remainingPortions=quantity, createdAt != null")
        void systemFieldsSetCorrectly() {
            CreateOrderDto dto = validMarketBuyDto();
            asClient();
            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");
            when(orderStatusService.determineStatus(any(), any(), any())).thenReturn(OrderStatus.APPROVED);
            stubSave();

            orderService.createOrder(dto);

            verify(orderRepository).save(argThat(order ->
                    !order.isDone()
                            && order.getRemainingPortions().equals(dto.getQuantity())
                            && order.getCreatedAt() != null
                            && order.getLastModification() != null));
        }

        @Test
        @DisplayName("userId i userRole se ispravno postavljaju za CLIENT")
        void userIdAndRoleSetForClient() {
            CreateOrderDto dto = validMarketBuyDto();
            asClient();
            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("151", "755.0000");
            when(orderStatusService.determineStatus(any(), any(), any())).thenReturn(OrderStatus.APPROVED);
            stubSave();

            orderService.createOrder(dto);

            verify(orderRepository).save(argThat(order ->
                    order.getUserId().equals(CLIENT_ID) && "CLIENT".equals(order.getUserRole())));
        }
    }

    @Nested
    @DisplayName("SELL flow sa portfolio rezervacijom")
    class SellFlow {

        @Test
        @DisplayName("CLIENT SELL — rezervise kolicinu u portfoliu, status APPROVED")
        void clientSellReservesPortfolioQuantity() {
            CreateOrderDto dto = validMarketSellDto();
            asClient();
            when(orderValidationService.parseDirection(anyString())).thenReturn(OrderDirection.SELL);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("149", "745.0000");
            when(orderStatusService.determineStatus(eq("CLIENT"), eq(CLIENT_ID), any())).thenReturn(OrderStatus.APPROVED);
            stubSave();

            orderService.createOrder(dto);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).save(captor.capture());
            Order saved = captor.getValue();
            assertEquals(OrderStatus.APPROVED, saved.getStatus());
            assertEquals(OrderDirection.SELL, saved.getDirection());
            assertEquals(testClientAccount.id(), saved.getReservedAccountId());
            verify(fundReservationService).reserveForSell(any(Order.class), eq(testPortfolio));
            verify(fundReservationService, never()).reserveForBuy(any());
        }

        @Test
        @DisplayName("CLIENT SELL — odbija kolicinu 27 kada je dostupno samo 3")
        void clientSellRejects27WhenOnlyThreeAvailable() {
            CreateOrderDto dto = validMarketSellDto();
            dto.setQuantity(27);
            asClient();
            testPortfolio.setReservedQuantity(27); // dostupno 30 - 27 = 3

            when(orderValidationService.parseDirection(anyString())).thenReturn(OrderDirection.SELL);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("149", "4023.0000");

            InsufficientHoldingsException ex = assertThrows(InsufficientHoldingsException.class,
                    () -> orderService.createOrder(dto));
            assertTrue(ex.getMessage().contains("Nedovoljno"));
            verify(orderRepository, never()).save(any());
            verify(fundReservationService, never()).reserveForSell(any(), any());
        }

        @Test
        @DisplayName("CLIENT SELL — baca InsufficientHoldings kada portfolio ne postoji")
        void clientSellThrowsWhenNoPortfolio() {
            CreateOrderDto dto = validMarketSellDto();
            asClient();
            when(orderValidationService.parseDirection(anyString())).thenReturn(OrderDirection.SELL);
            when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
            stubPriceServices("149", "745.0000");
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(CLIENT_ID, "CLIENT", 1L))
                    .thenReturn(Optional.empty());

            assertThrows(InsufficientHoldingsException.class, () -> orderService.createOrder(dto));
            verify(orderRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Odobravanje ordera")
    class ApproveOrder {

        private Order pendingOrder(OrderDirection direction) {
            Listing l = new Listing();
            l.setId(1L);
            l.setTicker("AAPL");
            l.setListingType(ListingType.STOCK);
            l.setSettlementDate(null);
            l.setExchangeAcronym("NASDAQ");

            Order o = new Order();
            o.setId(1L);
            o.setStatus(OrderStatus.PENDING);
            o.setListing(l);
            o.setUserId(5L);
            o.setUserRole("EMPLOYEE");
            o.setDirection(direction);
            o.setOrderType(OrderType.MARKET);
            o.setQuantity(5);
            o.setContractSize(1);
            o.setApproximatePrice(new BigDecimal("755.0000"));
            o.setAccountId(900L);
            o.setReservedAccountId(900L);
            o.setRemainingPortions(5);
            return o;
        }

        @BeforeEach
        void prep() {
            when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(10L, "EMPLOYEE"));
            lenient().when(bankaCoreClient.getAccount(900L)).thenReturn(testBankUsdAccount);
        }

        @Test
        @DisplayName("PENDING order se uspesno odobrava")
        void approveOrderSuccess() {
            Order o = pendingOrder(OrderDirection.BUY);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(tradingUserResolver.resolveName(10L, "EMPLOYEE")).thenReturn("Nina Nikolic");
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderDto result = orderService.approveOrder(1L);

            assertEquals("APPROVED", result.getStatus());
            assertEquals("Nina Nikolic", result.getApprovedBy());
            assertNotNull(result.getLastModification());
            verify(orderRepository).save(any(Order.class));
        }

        @Test
        @DisplayName("Order ne postoji → EntityNotFoundException")
        void approveOrderNotFound() {
            when(orderRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> orderService.approveOrder(99L));
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Order nije PENDING → IllegalStateException")
        void approveOrderNotPending() {
            Order o = pendingOrder(OrderDirection.BUY);
            o.setStatus(OrderStatus.APPROVED);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));

            assertThrows(IllegalStateException.class, () -> orderService.approveOrder(1L));
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Settlement date prosao → automatski DECLINED")
        void approveOrderSettlementDatePassed() {
            Order o = pendingOrder(OrderDirection.BUY);
            o.getListing().setSettlementDate(java.time.LocalDate.now().minusDays(1));
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(tradingUserResolver.resolveName(10L, "EMPLOYEE")).thenReturn("Nina Nikolic");
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderDto result = orderService.approveOrder(1L);

            assertEquals("DECLINED", result.getStatus());
            assertEquals("Nina Nikolic", result.getApprovedBy());
        }

        @Test
        @DisplayName("Settlement date u buducnosti → APPROVED")
        void approveOrderSettlementDateFuture() {
            Order o = pendingOrder(OrderDirection.BUY);
            o.getListing().setSettlementDate(java.time.LocalDate.now().plusDays(10));
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderDto result = orderService.approveOrder(1L);

            assertEquals("APPROVED", result.getStatus());
        }

        @Test
        @DisplayName("PENDING BUY order rezervise sredstva u trenutku odobravanja")
        void approvePendingBuyReservesFunds() {
            Order o = pendingOrder(OrderDirection.BUY);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderDto result = orderService.approveOrder(1L);

            assertEquals("APPROVED", result.getStatus());
            assertNotNull(o.getApprovedAt());
            assertEquals(testBankUsdAccount.id(), o.getReservedAccountId());
            assertNotNull(o.getReservedAmount());
            verify(fundReservationService).reserveForBuy(eq(o));
            verify(fundReservationService, never()).reserveForSell(any(), any());
        }

        @Test
        @DisplayName("InsufficientFundsException ako availableBalance < totalReservation")
        void approveThrowsWhenInsufficientFunds() {
            Order o = pendingOrder(OrderDirection.BUY);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(bankaCoreClient.getAccount(900L))
                    .thenReturn(account(900L, "USD", "BANK_TRADING", new BigDecimal("100.0000")));

            assertThrows(InsufficientFundsException.class, () -> orderService.approveOrder(1L));
            verify(fundReservationService, never()).reserveForBuy(any());
            verify(orderRepository, never()).save(any(Order.class));
        }

        @Test
        @DisplayName("PENDING SELL order rezervise kolicinu hartija")
        void approvePendingSellReservesPortfolioQuantity() {
            Order o = pendingOrder(OrderDirection.SELL);
            o.setUserId(5L);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(5L, "EMPLOYEE", 1L))
                    .thenReturn(Optional.of(testPortfolio));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderDto result = orderService.approveOrder(1L);

            assertEquals("APPROVED", result.getStatus());
            verify(fundReservationService).reserveForSell(eq(o), any(Portfolio.class));
            verify(fundReservationService, never()).reserveForBuy(any());
        }

        @Test
        @DisplayName("approveOrder — CLIENT BUY bez racuna baca EntityNotFoundException")
        void approveClientBuyNoAccount() {
            Order o = pendingOrder(OrderDirection.BUY);
            o.setUserRole("CLIENT");
            o.setAccountId(null);
            o.setReservedAccountId(null);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));

            assertThrows(EntityNotFoundException.class, () -> orderService.approveOrder(1L));
        }

        @Test
        @DisplayName("approveOrder — SELL nedovoljno hartija baca InsufficientHoldings")
        void approveSellInsufficientHoldings() {
            Order o = pendingOrder(OrderDirection.SELL);
            o.setUserId(5L);
            o.setQuantity(100);
            Portfolio pf = new Portfolio();
            pf.setUserId(5L);
            pf.setListingId(1L);
            pf.setQuantity(10);
            pf.setReservedQuantity(0);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(5L, "EMPLOYEE", 1L))
                    .thenReturn(Optional.of(pf));

            assertThrows(InsufficientHoldingsException.class, () -> orderService.approveOrder(1L));
        }
    }

    @Nested
    @DisplayName("Odbijanje ordera")
    class DeclineOrder {

        private Order order(OrderDirection direction, OrderStatus status) {
            Listing l = new Listing();
            l.setId(1L);
            l.setTicker("AAPL");
            l.setListingType(ListingType.STOCK);

            Order o = new Order();
            o.setId(1L);
            o.setStatus(status);
            o.setListing(l);
            o.setUserId(5L);
            o.setUserRole("EMPLOYEE");
            o.setDirection(direction);
            o.setOrderType(OrderType.MARKET);
            o.setQuantity(5);
            o.setContractSize(1);
            o.setApproximatePrice(new BigDecimal("755.0000"));
            return o;
        }

        @BeforeEach
        void prep() {
            when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(10L, "EMPLOYEE"));
            lenient().when(tradingUserResolver.resolveName(10L, "EMPLOYEE")).thenReturn("Nina Nikolic");
        }

        @Test
        @DisplayName("PENDING order se uspesno odbija")
        void declineOrderSuccess() {
            Order o = order(OrderDirection.BUY, OrderStatus.PENDING);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderDto result = orderService.declineOrder(1L);

            assertEquals("DECLINED", result.getStatus());
            assertEquals("Nina Nikolic", result.getApprovedBy());
            verify(orderRepository).save(any(Order.class));
        }

        @Test
        @DisplayName("Order ne postoji → EntityNotFoundException")
        void declineOrderNotFound() {
            when(orderRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> orderService.declineOrder(99L));
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Order nije PENDING ni APPROVED → IllegalStateException")
        void declineOrderNotPending() {
            Order o = order(OrderDirection.BUY, OrderStatus.DONE);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));

            assertThrows(IllegalStateException.class, () -> orderService.declineOrder(1L));
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("PENDING decline samo menja status, bez release-a")
        void declinePendingNoRelease() {
            Order o = order(OrderDirection.BUY, OrderStatus.PENDING);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderDto result = orderService.declineOrder(1L);

            assertEquals("DECLINED", result.getStatus());
            verify(fundReservationService, never()).releaseForBuy(any());
            verify(fundReservationService, never()).releaseForSell(any(), any());
        }

        @Test
        @DisplayName("APPROVED BUY decline oslobadja rezervaciju + agent usedLimit rollback")
        void declineApprovedBuyReleasesFundReservation() {
            Order o = order(OrderDirection.BUY, OrderStatus.APPROVED);
            o.setReservedAccountId(900L);
            o.setReservedAmount(new BigDecimal("755.0000"));
            ActuaryInfo actuary = new ActuaryInfo();
            actuary.setActuaryType(ActuaryType.AGENT);
            actuary.setUsedLimit(new BigDecimal("1000.0000"));

            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(orderStatusService.getAgentInfo(5L)).thenReturn(Optional.of(actuary));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderDto result = orderService.declineOrder(1L);

            assertEquals("DECLINED", result.getStatus());
            verify(fundReservationService).releaseForBuy(o);
            verify(fundReservationService, never()).releaseForSell(any(), any());
            assertEquals(0, actuary.getUsedLimit().compareTo(new BigDecimal("245.0000")));
            verify(actuaryInfoRepository).save(actuary);
        }

        @Test
        @DisplayName("APPROVED SELL decline oslobadja rezervaciju hartija")
        void declineApprovedSellReleasesPortfolioReservation() {
            Order o = order(OrderDirection.SELL, OrderStatus.APPROVED);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(5L, "EMPLOYEE", 1L))
                    .thenReturn(Optional.of(testPortfolio));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderDto result = orderService.declineOrder(1L);

            assertEquals("DECLINED", result.getStatus());
            verify(fundReservationService).releaseForSell(eq(o), any(Portfolio.class));
            verify(fundReservationService, never()).releaseForBuy(any());
        }

        @Test
        @DisplayName("APPROVED sa reservationReleased=true preskace release")
        void declineAlreadyReleased() {
            Order o = order(OrderDirection.BUY, OrderStatus.APPROVED);
            o.setReservationReleased(true);
            when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(o));
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

            OrderDto result = orderService.declineOrder(1L);

            assertEquals("DECLINED", result.getStatus());
            verify(fundReservationService, never()).releaseForBuy(any());
        }
    }

    @Nested
    @DisplayName("getAllOrders")
    class GetAllOrdersTests {

        private Order makeOrder(Long id, OrderStatus status) {
            Order o = new Order();
            o.setId(id);
            o.setUserId(1L);
            o.setStatus(status);
            o.setUserRole("EMPLOYEE");
            o.setOrderType(OrderType.MARKET);
            o.setDirection(OrderDirection.BUY);
            o.setQuantity(10);
            o.setContractSize(1);
            o.setPricePerUnit(BigDecimal.valueOf(100));
            o.setDone(false);
            o.setRemainingPortions(10);
            o.setListing(testListing);
            return o;
        }

        @Test
        @DisplayName("Status ALL returns all orders")
        void getAllOrdersStatusAll() {
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            when(orderRepository.findAll(pageable))
                    .thenReturn(new PageImpl<>(List.of(makeOrder(1L, OrderStatus.PENDING), makeOrder(2L, OrderStatus.APPROVED))));

            Page<OrderDto> result = orderService.getAllOrders("ALL", 0, 20);

            assertEquals(2, result.getTotalElements());
            verify(orderRepository).findAll(pageable);
            verify(orderRepository, never()).findByStatus(any(), any());
        }

        @Test
        @DisplayName("Status null returns all orders")
        void getAllOrdersStatusNull() {
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            when(orderRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(makeOrder(1L, OrderStatus.DONE))));

            Page<OrderDto> result = orderService.getAllOrders(null, 0, 20);

            assertEquals(1, result.getTotalElements());
        }

        @Test
        @DisplayName("Blank string treated as ALL")
        void getAllOrdersBlankString() {
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            when(orderRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of()));

            Page<OrderDto> result = orderService.getAllOrders("   ", 0, 20);

            assertNotNull(result);
            verify(orderRepository).findAll(pageable);
        }

        @Test
        @DisplayName("Filters by PENDING status")
        void getAllOrdersStatusPending() {
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            when(orderRepository.findByStatus(OrderStatus.PENDING, pageable))
                    .thenReturn(new PageImpl<>(List.of(makeOrder(1L, OrderStatus.PENDING))));

            Page<OrderDto> result = orderService.getAllOrders("PENDING", 0, 20);

            assertEquals(1, result.getTotalElements());
            assertEquals("PENDING", result.getContent().get(0).getStatus());
        }

        @Test
        @DisplayName("Invalid status throws IllegalArgumentException")
        void getAllOrdersInvalidStatus() {
            assertThrows(IllegalArgumentException.class, () -> orderService.getAllOrders("INVALID", 0, 20));
        }
    }

    @Nested
    @DisplayName("getMyOrders")
    class GetMyOrdersTests {

        private Order makeOrder(Long id, Long userId) {
            Order o = new Order();
            o.setId(id);
            o.setUserId(userId);
            o.setStatus(OrderStatus.APPROVED);
            o.setUserRole("CLIENT");
            o.setOrderType(OrderType.MARKET);
            o.setDirection(OrderDirection.BUY);
            o.setQuantity(1);
            o.setContractSize(1);
            o.setPricePerUnit(BigDecimal.valueOf(100));
            o.setDone(false);
            o.setRemainingPortions(1);
            o.setListing(testListing);
            return o;
        }

        @Test
        @DisplayName("Client sees only their own orders")
        void getMyOrdersClient() {
            asClient();
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            when(orderRepository.findByUserId(CLIENT_ID, pageable))
                    .thenReturn(new PageImpl<>(List.of(makeOrder(1L, CLIENT_ID))));

            Page<OrderDto> result = orderService.getMyOrders(0, 20);

            assertEquals(1, result.getTotalElements());
            verify(orderRepository).findByUserId(CLIENT_ID, pageable);
        }

        @Test
        @DisplayName("Employee sees only their own orders")
        void getMyOrdersEmployee() {
            asEmployee();
            PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            when(orderRepository.findByUserId(EMPLOYEE_ID, pageable))
                    .thenReturn(new PageImpl<>(List.of(makeOrder(5L, EMPLOYEE_ID))));

            Page<OrderDto> result = orderService.getMyOrders(0, 20);

            assertEquals(1, result.getTotalElements());
        }
    }

    @Nested
    @DisplayName("getOrderById")
    class GetOrderByIdTests {

        private Order makeOrder(Long id, Long userId) {
            Order o = new Order();
            o.setId(id);
            o.setUserId(userId);
            o.setStatus(OrderStatus.APPROVED);
            o.setUserRole("CLIENT");
            o.setOrderType(OrderType.MARKET);
            o.setDirection(OrderDirection.BUY);
            o.setQuantity(1);
            o.setContractSize(1);
            o.setPricePerUnit(BigDecimal.valueOf(100));
            o.setDone(false);
            o.setRemainingPortions(1);
            o.setListing(testListing);
            return o;
        }

        @Test
        @DisplayName("Supervisor (ROLE_ADMIN) can see any order")
        void getOrderByIdSupervisorSeesAny() {
            securityAuthorities("ROLE_ADMIN");
            Order o = makeOrder(10L, 999L);
            when(orderRepository.findById(10L)).thenReturn(Optional.of(o));

            OrderDto result = orderService.getOrderById(10L);

            assertNotNull(result);
            assertEquals(10L, result.getId());
        }

        @Test
        @DisplayName("Supervisor (ROLE_EMPLOYEE) can see any order")
        void getOrderByIdRoleEmployeeSeesAny() {
            securityAuthorities("ROLE_EMPLOYEE");
            Order o = makeOrder(11L, 999L);
            when(orderRepository.findById(11L)).thenReturn(Optional.of(o));

            OrderDto result = orderService.getOrderById(11L);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Client can see their own order")
        void getOrderByIdOwnOrder() {
            securityAuthorities("ROLE_CLIENT");
            asClient();
            Order o = makeOrder(3L, CLIENT_ID);
            when(orderRepository.findById(3L)).thenReturn(Optional.of(o));

            OrderDto result = orderService.getOrderById(3L);

            assertNotNull(result);
            assertEquals(3L, result.getId());
        }

        @Test
        @DisplayName("Client cannot see another user's order — throws IllegalStateException")
        void getOrderByIdOtherUsersOrder() {
            securityAuthorities("ROLE_CLIENT");
            asClient();
            Order o = makeOrder(3L, 999L);
            when(orderRepository.findById(3L)).thenReturn(Optional.of(o));

            assertThrows(IllegalStateException.class, () -> orderService.getOrderById(3L));
        }

        @Test
        @DisplayName("Order not found throws EntityNotFoundException")
        void getOrderByIdNotFound() {
            securityAuthorities("ROLE_ADMIN");
            when(orderRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(EntityNotFoundException.class, () -> orderService.getOrderById(999L));
        }
    }
}
