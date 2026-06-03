package rs.raf.trading.order.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.berza.service.ExchangeManagementService;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage testovi {@link OrderServiceImpl} — adaptacija monolitnog
 * {@code OrderServiceImplCoverageTest} (faza 2c, money-seam: identitet ->
 * {@code TradingUserResolver}, racuni -> {@code BankaCoreClient}). Pokrivaju
 * grane koje glavni test propusta: razresavanje valute po berzi (LSE/XETRA/
 * BELEX/FOREX), LIMIT commission grana, computeAfterHours exception fallback,
 * i FUND-order putanju (supervizor kupuje u ime fonda).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceImplCoverageTest {

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
    @Mock private rs.raf.trading.notification.service.NotificationService notificationService;
    @Mock private rs.raf.trading.audit.service.AuditLogService auditLogService;
    @Mock private rs.raf.trading.margin.repository.MarginAccountRepository marginAccountRepository;
    @Mock private rs.raf.trading.margin.service.MarginOrderSettlementService marginOrderSettlementService;

    @InjectMocks
    private OrderServiceImpl orderService;

    private static final Long CLIENT_ID = 42L;
    private static final Long SUPERVISOR_ID = 99L;

    private InternalAccountDto clientUsd;
    private InternalAccountDto bankUsd;
    private InternalAccountDto fundAccount;
    private Portfolio testPortfolio;

    private InternalAccountDto account(Long id, String currency, String category) {
        return new InternalAccountDto(id, "acc-" + id, "Owner",
                new BigDecimal("10000000"), new BigDecimal("10000000"), BigDecimal.ZERO,
                currency, "ACTIVE", null, null, category);
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        clientUsd = account(100L, "USD", "CLIENT");
        bankUsd = account(900L, "USD", "BANK_TRADING");
        fundAccount = account(700L, "RSD", "FUND");

        testPortfolio = new Portfolio();
        testPortfolio.setId(500L);
        testPortfolio.setUserId(CLIENT_ID);
        testPortfolio.setListingId(1L);
        testPortfolio.setListingTicker("AAPL");
        testPortfolio.setListingType("STOCK");
        testPortfolio.setQuantity(30);
        testPortfolio.setReservedQuantity(0);
        testPortfolio.setPublicQuantity(0);
        testPortfolio.setAverageBuyPrice(new BigDecimal("140"));

        lenient().when(currencyConversionService.getRate(anyString(), anyString())).thenReturn(BigDecimal.ONE);
        // §66/R1-183: convertToRsdForLimit zove convert(amount, listing, "RSD").
        // Default mid-rate=1 (identitet) za postojece testove.
        lenient().when(currencyConversionService.convert(
                any(BigDecimal.class), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(currencyConversionService.convertForPurchase(
                any(BigDecimal.class), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> new CurrencyConversionService.ConversionResult(
                        inv.getArgument(0), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE));
        lenient().when(bankTradingAccountResolver.resolve(anyString())).thenReturn(bankUsd);
        lenient().when(bankaCoreClient.getAccount(100L)).thenReturn(clientUsd);
        lenient().when(bankaCoreClient.getAccount(700L)).thenReturn(fundAccount);
        lenient().when(portfolioRepository.findByUserIdAndUserRoleAndListingIdForUpdate(anyLong(), anyString(), anyLong()))
                .thenReturn(Optional.of(testPortfolio));
        lenient().when(orderValidationService.parseOrderType(anyString())).thenReturn(OrderType.MARKET);
        lenient().when(orderValidationService.parseDirection(anyString())).thenReturn(OrderDirection.BUY);
        lenient().when(tradingUserResolver.resolveName(anyLong(), anyString())).thenReturn("Test User");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void asClient() {
        when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(CLIENT_ID, "CLIENT"));
        // BE-ORD-02 fix: trading access guard zahteva TRADE_STOCKS za klijenta.
        setAuthorities("ROLE_CLIENT", "TRADE_STOCKS");
    }

    private void asSupervisor() {
        when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(SUPERVISOR_ID, "EMPLOYEE"));
        // BE-ORD-02 fix: trading access guard zahteva SUPERVISOR / ADMIN / AGENT.
        setAuthorities("ROLE_EMPLOYEE", "SUPERVISOR");
    }

    private void setAuthorities(String... authorities) {
        java.util.List<org.springframework.security.core.GrantedAuthority> auths =
                java.util.Arrays.stream(authorities)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                        .toList();
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken token =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "test@example.com", null, auths);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private Listing listing(ListingType type, String acronym) {
        Listing l = new Listing();
        l.setId(1L);
        l.setTicker("AAPL");
        l.setName("Apple");
        l.setListingType(type);
        l.setPrice(new BigDecimal("150"));
        l.setAsk(new BigDecimal("151"));
        l.setBid(new BigDecimal("149"));
        l.setExchangeAcronym(acronym);
        return l;
    }

    private CreateOrderDto dtoMarketBuy() {
        CreateOrderDto d = new CreateOrderDto();
        d.setListingId(1L);
        d.setOrderType("MARKET");
        d.setDirection("BUY");
        d.setQuantity(5);
        d.setContractSize(1);
        d.setAccountId(100L);
        return d;
    }

    private void stubPrices() {
        when(listingPriceService.getPricePerUnit(any(), any(), any(), any())).thenReturn(new BigDecimal("151"));
        when(listingPriceService.calculateApproximatePrice(anyInt(), any(), anyInt())).thenReturn(new BigDecimal("755"));
    }

    private void stubSave() {
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) o.setId(1L);
            return o;
        });
    }

    // ─── resolveListingCurrency grane ─────────────────────────────────────

    @Test
    @DisplayName("createOrder — FOREX listing sa baseCurrency koristi tu valutu")
    void forexListingUsesBaseCurrency() {
        asClient();
        Listing l = listing(ListingType.FOREX, "FX");
        l.setBaseCurrency("EUR");
        when(listingRepository.findById(1L)).thenReturn(Optional.of(l));
        stubPrices();
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        orderService.createOrder(dtoMarketBuy());

        verify(currencyConversionService, atLeastOnce())
                .convertForPurchase(any(BigDecimal.class), eq("EUR"), eq("USD"), anyBoolean());
    }

    @Test
    @DisplayName("createOrder — LSE exchange → GBP valuta")
    void lseExchangeUsesGbp() {
        asClient();
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "LSE")));
        stubPrices();
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        orderService.createOrder(dtoMarketBuy());

        verify(currencyConversionService, atLeastOnce())
                .convertForPurchase(any(BigDecimal.class), eq("GBP"), eq("USD"), anyBoolean());
    }

    @Test
    @DisplayName("createOrder — XETRA → EUR")
    void xetraExchangeUsesEur() {
        asClient();
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "XETRA")));
        stubPrices();
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        orderService.createOrder(dtoMarketBuy());

        verify(currencyConversionService, atLeastOnce())
                .convertForPurchase(any(BigDecimal.class), eq("EUR"), eq("USD"), anyBoolean());
    }

    @Test
    @DisplayName("createOrder — BELEX → RSD")
    void belexExchangeUsesRsd() {
        asClient();
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "BELEX")));
        stubPrices();
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        orderService.createOrder(dtoMarketBuy());

        verify(currencyConversionService, atLeastOnce())
                .convertForPurchase(any(BigDecimal.class), eq("RSD"), eq("USD"), anyBoolean());
    }

    @Test
    @DisplayName("createOrder — nepoznata berza → default USD")
    void unknownExchangeFallsToUsd() {
        asClient();
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "UNKNOWN")));
        stubPrices();
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        orderService.createOrder(dtoMarketBuy());

        verify(currencyConversionService, atLeastOnce())
                .convertForPurchase(any(BigDecimal.class), eq("USD"), eq("USD"), anyBoolean());
    }

    // ─── LIMIT commission grana ───────────────────────────────────────────

    @Test
    @DisplayName("createOrder — LIMIT tip aktivira 24% commission granu")
    void limitOrderCommissionBranch() {
        asClient();
        when(orderValidationService.parseOrderType(anyString())).thenReturn(OrderType.LIMIT);
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "NASDAQ")));
        when(listingPriceService.getPricePerUnit(any(), any(), any(), any())).thenReturn(new BigDecimal("10"));
        when(listingPriceService.calculateApproximatePrice(anyInt(), any(), anyInt())).thenReturn(new BigDecimal("50"));
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        OrderDto result = orderService.createOrder(dtoMarketBuy());

        assertThat(result).isNotNull();
    }

    // ─── CLIENT SELL postavlja exchangeRate ───────────────────────────────

    @Test
    @DisplayName("createOrder — CLIENT SELL postavlja exchangeRate")
    void clientSellSetsExchangeRate() {
        asClient();
        when(orderValidationService.parseDirection(anyString())).thenReturn(OrderDirection.SELL);
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "NASDAQ")));
        stubPrices();
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        CreateOrderDto sell = dtoMarketBuy();
        sell.setDirection("SELL");
        orderService.createOrder(sell);

        verify(currencyConversionService).getRate("USD", "USD");
    }

    // ─── computeAfterHours exception fallback ─────────────────────────────

    @Test
    @DisplayName("createOrder — computeAfterHours exception = afterHours false (ne pada)")
    void computeAfterHoursExceptionFallback() {
        asClient();
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "NASDAQ")));
        when(exchangeManagementService.isClosedOrAfterHours("NASDAQ")).thenThrow(new RuntimeException("boom"));
        stubPrices();
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        OrderDto result = orderService.createOrder(dtoMarketBuy());

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("OT-1082: createOrder — zatvorena/after-hours berza → order.afterHours=true "
            + "(happy-path; do sada testiran samo exception-fallback false)")
    void afterHoursTrue_whenExchangeClosedOrAfterHours() {
        asClient();
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "NASDAQ")));
        // §404 / R1-190: spori fill se primenjuje kad je berza ZATVORENA ili u
        // after-hours prozoru → computeAfterHours vraca true.
        when(exchangeManagementService.isClosedOrAfterHours("NASDAQ")).thenReturn(true);
        stubPrices();
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        var captor = org.mockito.ArgumentCaptor.forClass(Order.class);
        OrderDto result = orderService.createOrder(dtoMarketBuy());

        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().isAfterHours()).isTrue();
        assertThat(result.isAfterHours()).isTrue();
    }

    @Test
    @DisplayName("OT-1082: createOrder — otvorena berza (isClosedOrAfterHours=false) → order.afterHours=false")
    void afterHoursFalse_whenExchangeOpen() {
        asClient();
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "NASDAQ")));
        when(exchangeManagementService.isClosedOrAfterHours("NASDAQ")).thenReturn(false);
        stubPrices();
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        var captor = org.mockito.ArgumentCaptor.forClass(Order.class);
        orderService.createOrder(dtoMarketBuy());

        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().isAfterHours()).isFalse();
    }

    // ─── FUND-order putanja ───────────────────────────────────────────────

    @Test
    @DisplayName("createOrder — supervizor kupuje u ime fonda → order.userRole=FUND, fundId set")
    void supervisorBuyForFund() {
        asSupervisor();
        InvestmentFund fund = new InvestmentFund();
        fund.setId(7L);
        fund.setName("Stable Income");
        fund.setManagerEmployeeId(SUPERVISOR_ID);
        fund.setAccountId(700L);

        CreateOrderDto dto = dtoMarketBuy();
        dto.setFundId(7L);
        dto.setAccountId(null);

        when(investmentFundRepository.findById(7L)).thenReturn(Optional.of(fund));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "NASDAQ")));
        stubPrices();
        when(orderStatusService.determineStatus(anyString(), anyLong(), any())).thenReturn(OrderStatus.PENDING);
        stubSave();

        var captor = org.mockito.ArgumentCaptor.forClass(Order.class);
        orderService.createOrder(dto);

        verify(orderRepository).save(captor.capture());
        Order saved = captor.getValue();
        assertThat(saved.getFundId()).isEqualTo(7L);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getUserRole()).isEqualTo("FUND");
    }

    @Test
    @DisplayName("createOrder — klijent sa fundId → AccessDeniedException")
    void clientWithFundIdRejected() {
        asClient();
        CreateOrderDto dto = dtoMarketBuy();
        dto.setFundId(7L);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "NASDAQ")));
        stubPrices();

        assertThatThrownBy(() -> orderService.createOrder(dto))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("fond");
    }

    @Test
    @DisplayName("createOrder — supervizor koji nije manager fonda → AccessDeniedException")
    void supervisorNotFundManagerRejected() {
        asSupervisor();
        InvestmentFund fund = new InvestmentFund();
        fund.setId(7L);
        fund.setName("Stable Income");
        fund.setManagerEmployeeId(8888L); // drugi supervizor
        fund.setAccountId(700L);

        CreateOrderDto dto = dtoMarketBuy();
        dto.setFundId(7L);

        when(investmentFundRepository.findById(7L)).thenReturn(Optional.of(fund));
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "NASDAQ")));
        stubPrices();

        assertThatThrownBy(() -> orderService.createOrder(dto))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("manager");
    }

    @Test
    @DisplayName("createOrder — fond ne postoji → EntityNotFoundException")
    void fundNotFoundRejected() {
        asSupervisor();
        CreateOrderDto dto = dtoMarketBuy();
        dto.setFundId(404L);

        when(investmentFundRepository.findById(404L)).thenReturn(Optional.empty());
        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "NASDAQ")));
        stubPrices();

        assertThatThrownBy(() -> orderService.createOrder(dto))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("fond");
    }

    // ─── createOrder klijent bez accountId → 404 ──────────────────────────

    @Test
    @DisplayName("createOrder — klijent bez accountId (i bez fundId) → EntityNotFoundException")
    void clientWithoutAccountId() {
        asClient();
        // orderValidationService je mock — validate() je void no-op (ne pada).
        CreateOrderDto dto = dtoMarketBuy();
        dto.setAccountId(null);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing(ListingType.STOCK, "NASDAQ")));
        stubPrices();

        assertThatThrownBy(() -> orderService.createOrder(dto))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Racun ne postoji");
    }

    // ─── approveOrder agent sa null usedLimit ─────────────────────────────

    @Test
    @DisplayName("approveOrder — agent sa null usedLimit startuje od ZERO")
    void approveAgentNullUsedLimit() {
        when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(10L, "EMPLOYEE"));
        when(tradingUserResolver.resolveName(10L, "EMPLOYEE")).thenReturn("Nina Nikolic");

        Listing l = listing(ListingType.STOCK, "NASDAQ");
        Order pending = new Order();
        pending.setId(88L);
        pending.setStatus(OrderStatus.PENDING);
        pending.setDirection(OrderDirection.BUY);
        pending.setListing(l);
        pending.setUserRole("EMPLOYEE");
        pending.setUserId(SUPERVISOR_ID);
        pending.setApproximatePrice(new BigDecimal("500"));
        pending.setAccountId(900L);
        pending.setReservedAccountId(900L);
        pending.setQuantity(5);
        pending.setContractSize(1);

        ActuaryInfo ai = new ActuaryInfo();
        ai.setActuaryType(ActuaryType.AGENT);
        ai.setUsedLimit(null);

        when(orderRepository.findByIdForUpdate(88L)).thenReturn(Optional.of(pending));
        when(bankaCoreClient.getAccount(900L)).thenReturn(bankUsd);
        when(orderStatusService.getAgentInfo(SUPERVISOR_ID)).thenReturn(Optional.of(ai));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orderService.approveOrder(88L);

        assertThat(ai.getUsedLimit()).isNotNull();
        verify(actuaryInfoRepository).save(ai);
    }

    // ─── cancelOrder parcijalni — agent usedLimit rollback (holisticki review I-1) ─

    @Test
    @DisplayName("cancelOrder parcijalni BUY — agent usedLimit se vraca po ORIGINALNOJ rezervaciji")
    void partialCancelRollsBackUsedLimitFromOriginalReservation() {
        // AGENT BUY order, APPROVED: qty 10, originalna rezervacija 1000.
        // Parcijalni cancel 4 komada → usedLimit rollback mora biti
        // 1000 * 4/10 = 400 (verno monolitu — iz originalne rezervacije),
        // a NE 600 * 4/10 = 240 (iz prepisane umanjene re-rezervacije).
        when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(10L, "EMPLOYEE"));
        when(tradingUserResolver.resolveName(10L, "EMPLOYEE")).thenReturn("Agent Petar");

        Order approved = new Order();
        approved.setId(55L);
        approved.setStatus(OrderStatus.APPROVED);
        approved.setDirection(OrderDirection.BUY);
        approved.setUserRole("EMPLOYEE");
        approved.setUserId(10L);
        approved.setListing(listing(ListingType.STOCK, "NASDAQ"));
        approved.setQuantity(10);
        approved.setRemainingPortions(10);
        approved.setContractSize(1);
        approved.setReservedAccountId(900L);
        approved.setReservedAmount(new BigDecimal("1000.0000"));
        approved.setBankaCoreReservationId("res-orig-55");
        approved.setReservationReleased(false);

        ActuaryInfo ai = new ActuaryInfo();
        ai.setActuaryType(ActuaryType.AGENT);
        ai.setUsedLimit(new BigDecimal("1000.0000")); // koliko je order zauzeo

        when(orderRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(approved));
        when(bankaCoreClient.getAccount(900L)).thenReturn(bankUsd);
        when(bankaCoreClient.reserveFunds(anyString(), any()))
                .thenReturn(new rs.raf.banka2.contracts.internal.ReserveFundsResponse(
                        "res-rereserve-55", 900L, new BigDecimal("600.0000"),
                        new BigDecimal("9999400")));
        when(orderStatusService.getAgentInfo(10L)).thenReturn(Optional.of(ai));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancelOrder(55L, 4);

        // Rollback = originalReservedAmount(1000) * cancelQty(4) / originalQty(10) = 400.
        // usedLimit 1000 - 400 = 600.
        assertThat(ai.getUsedLimit()).isEqualByComparingTo("600.0000");
        // order.reservedAmount je i dalje prepisan na umanjenu re-rezervaciju (600) —
        // to je namerno (per-fill commit nad novom, manjom rezervacijom).
        assertThat(approved.getReservedAmount()).isEqualByComparingTo("600.0000");
        verify(actuaryInfoRepository).save(ai);
    }

    // ─── P1-dividends-order-1 (163): margin BUY parcijalni cancel ──────────────

    @Test
    @DisplayName("163: margin BUY parcijalni cancel oslobadja MARGIN rezervaciju, NE banka-core funds")
    void partialCancelMarginBuy_releasesMarginReservationNotBankaCoreFunds() {
        // Margin BUY order APPROVED, qty 10, approxPrice 100/kom -> reservedMargin po 10 kom.
        // Parcijalni cancel 4 kom -> mora osloboditi pro-rata MARGIN rezervaciju za 4 kom
        // (releaseMarginBuyReservation), a NE bankaCoreClient.releaseFunds (margin rezervacija
        // NIJE u banka-core funds reserve nego u MarginAccount.reservedMargin).
        when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(CLIENT_ID, "CLIENT"));
        when(tradingUserResolver.resolveName(CLIENT_ID, "CLIENT")).thenReturn("Stefan Jovanovic");

        Order approved = new Order();
        approved.setId(63L);
        approved.setStatus(OrderStatus.APPROVED);
        approved.setDirection(OrderDirection.BUY);
        approved.setUserRole("CLIENT");
        approved.setUserId(CLIENT_ID);
        approved.setListing(listing(ListingType.STOCK, "NASDAQ"));
        approved.setQuantity(10);
        approved.setRemainingPortions(10);
        approved.setContractSize(1);
        approved.setMargin(true);
        approved.setApproximatePrice(new BigDecimal("100.0000"));
        approved.setReservedAccountId(900L); // margin orderi i dalje nose linked racun
        approved.setReservedAmount(new BigDecimal("1000.0000"));
        approved.setReservationReleased(false);

        when(orderRepository.findByIdForUpdate(63L)).thenReturn(Optional.of(approved));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orderService.cancelOrder(63L, 4);

        // Pro-rata margin release za 4 kom: approxPrice 100 * 4 = 400.
        var amtCaptor = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        verify(marginOrderSettlementService).releaseMarginBuyReservation(eq(approved), amtCaptor.capture());
        assertThat(amtCaptor.getValue()).isEqualByComparingTo("400.0000");
        // NIKAD ne dira banka-core funds reserve za margin order.
        verify(bankaCoreClient, never()).releaseFunds(anyString(), anyString(), any());
        verify(bankaCoreClient, never()).reserveFunds(anyString(), any());
        // remainingPortions umanjen na 6.
        assertThat(approved.getRemainingPortions()).isEqualTo(6);
    }

    // ─── BE-ORD-04: cancelOrder race compensation ──────────────────────────────

    @Test
    @DisplayName("BE-ORD-04: parcijalni cancel pucanjem 409 na pro-rata re-reserve — restaurira originalnu rezervaciju")
    void partialCancelRaceCompensatesByRestoringOriginalReservation() {
        // Klijent BUY APPROVED, qty 10, originalna rezervacija 1000.
        // releaseFunds(full) uspe, ali pro-rata reserveFunds(newReservation=600) puca sa 409
        // jer je drugi paralelni order u medjuvremenu uzeo oslobodjena sredstva.
        // Kompenzacija: bacamo InsufficientFundsException, ali tek POSLE sto smo restaurirali
        // originalnu (vecu) rezervaciju.
        when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(CLIENT_ID, "CLIENT"));
        when(tradingUserResolver.resolveName(CLIENT_ID, "CLIENT")).thenReturn("Stefan Jovanovic");

        Order approved = new Order();
        approved.setId(77L);
        approved.setStatus(OrderStatus.APPROVED);
        approved.setDirection(OrderDirection.BUY);
        approved.setUserRole("CLIENT");
        approved.setUserId(CLIENT_ID);
        approved.setListing(listing(ListingType.STOCK, "NASDAQ"));
        approved.setQuantity(10);
        approved.setRemainingPortions(10);
        approved.setContractSize(1);
        approved.setReservedAccountId(900L);
        approved.setReservedAmount(new BigDecimal("1000.0000"));
        approved.setBankaCoreReservationId("res-original-77");
        approved.setReservationReleased(false);

        when(orderRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(approved));
        when(bankaCoreClient.getAccount(900L)).thenReturn(bankUsd);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        rs.raf.trading.client.BankaCoreClientException race409 =
                new rs.raf.trading.client.BankaCoreClientException(
                        409, "Nedovoljno sredstava na racunu");
        when(bankaCoreClient.reserveFunds(eq("order-77-cancel-rereserve"), any()))
                .thenThrow(race409);
        when(bankaCoreClient.reserveFunds(eq("order-77-cancel-restore"), any()))
                .thenReturn(new rs.raf.banka2.contracts.internal.ReserveFundsResponse(
                        "res-restored-77", 900L, new BigDecimal("1000.0000"),
                        new BigDecimal("9999000")));

        assertThatThrownBy(() -> orderService.cancelOrder(77L, 4))
                .isInstanceOf(rs.raf.trading.order.exception.InsufficientFundsException.class);

        verify(bankaCoreClient).reserveFunds(eq("order-77-cancel-restore"), any());
        assertThat(approved.getBankaCoreReservationId()).isEqualTo("res-restored-77");
        assertThat(approved.getReservedAmount()).isEqualByComparingTo("1000.0000");
        assertThat(approved.isReservationReleased()).isFalse();
    }

    @Test
    @DisplayName("BE-ORD-04: ako i restore padne, order se markira COMPENSATED i baca se InsufficientFundsException")
    void partialCancelRaceWithRestoreFailureMarksCompensated() {
        when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(CLIENT_ID, "CLIENT"));
        when(tradingUserResolver.resolveName(CLIENT_ID, "CLIENT")).thenReturn("Stefan Jovanovic");

        Order approved = new Order();
        approved.setId(78L);
        approved.setStatus(OrderStatus.APPROVED);
        approved.setDirection(OrderDirection.BUY);
        approved.setUserRole("CLIENT");
        approved.setUserId(CLIENT_ID);
        approved.setListing(listing(ListingType.STOCK, "NASDAQ"));
        approved.setQuantity(10);
        approved.setRemainingPortions(10);
        approved.setContractSize(1);
        approved.setReservedAccountId(900L);
        approved.setReservedAmount(new BigDecimal("1000.0000"));
        approved.setBankaCoreReservationId("res-original-78");
        approved.setReservationReleased(false);

        when(orderRepository.findByIdForUpdate(78L)).thenReturn(Optional.of(approved));
        when(bankaCoreClient.getAccount(900L)).thenReturn(bankUsd);
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        rs.raf.trading.client.BankaCoreClientException race409 =
                new rs.raf.trading.client.BankaCoreClientException(
                        409, "Nedovoljno sredstava");
        when(bankaCoreClient.reserveFunds(anyString(), any())).thenThrow(race409);

        assertThatThrownBy(() -> orderService.cancelOrder(78L, 4))
                .isInstanceOf(rs.raf.trading.order.exception.InsufficientFundsException.class);

        assertThat(approved.isReservationReleased()).isTrue();
    }
}
