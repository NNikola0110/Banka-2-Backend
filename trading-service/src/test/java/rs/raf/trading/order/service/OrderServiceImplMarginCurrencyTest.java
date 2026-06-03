package rs.raf.trading.order.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.berza.service.ExchangeManagementService;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.model.UserMarginAccount;
import rs.raf.trading.margin.repository.MarginAccountRepository;
import rs.raf.trading.margin.service.MarginOrderSettlementService;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.implementation.OrderServiceImpl;
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
 * P2-5: margin BUY rezervacija mora biti u valuti margin racuna (RSD), ne u
 * valuti listinga (USD). {@code OrderExecutionService}/{@code SingleOrderExecutor}
 * settle-uju u RSD (totalPriceInListing × exchangeRate); ako se rezervise sirov
 * USD broj protiv RSD initialMargin, red velicine je pogresan i IM moze otici u
 * minus pri fill-u.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrderServiceImpl — P2-5 margin BUY rezervacija u RSD")
class OrderServiceImplMarginCurrencyTest {

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
    @Mock private MarginAccountRepository marginAccountRepository;
    @Mock private MarginOrderSettlementService marginOrderSettlementService;

    @InjectMocks
    private OrderServiceImpl orderService;

    private static final Long CLIENT_ID = 42L;

    private Listing usdListing;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        usdListing = new Listing();
        usdListing.setId(1L);
        usdListing.setTicker("AAPL");
        usdListing.setName("Apple Inc.");
        usdListing.setListingType(ListingType.STOCK);
        usdListing.setPrice(new BigDecimal("150"));
        usdListing.setAsk(new BigDecimal("151"));
        usdListing.setBid(new BigDecimal("149"));
        // ISO valuta hartije = USD (NASDAQ → USD u ListingCurrencyResolver).
        usdListing.setExchangeAcronym("NASDAQ");

        // CLIENT sa TRADE_STOCKS.
        when(tradingUserResolver.resolveCurrent()).thenReturn(new UserContext(CLIENT_ID, "CLIENT"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client@test.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"),
                                new SimpleGrantedAuthority("TRADE_STOCKS"))));
        lenient().when(tradingUserResolver.resolveName(anyLong(), anyString())).thenReturn("Test Client");

        lenient().when(orderValidationService.parseOrderType(anyString())).thenReturn(OrderType.MARKET);
        lenient().when(orderValidationService.parseDirection(anyString())).thenReturn(OrderDirection.BUY);
        lenient().when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) o.setId(7L);
            return o;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private InternalAccountDto rsdAccount(Long id) {
        return new InternalAccountDto(id, "acc-" + id, "Owner",
                new BigDecimal("1000000"), new BigDecimal("1000000"), BigDecimal.ZERO,
                "RSD", "ACTIVE", id, null, "CLIENT");
    }

    private UserMarginAccount margin(String im, String mm, String bp, MarginAccountStatus status) {
        return UserMarginAccount.builder()
                .id(50L)
                .accountId(500L)
                .accountNumber("222000112345678911")
                .userId(CLIENT_ID)
                .currency("RSD")
                .initialMargin(new BigDecimal(im))
                .maintenanceMargin(new BigDecimal(mm))
                .bankParticipation(new BigDecimal(bp))
                .loanValue(BigDecimal.ZERO)
                .reservedMargin(BigDecimal.ZERO)
                .status(status)
                .build();
    }

    private CreateOrderDto marginBuyDto() {
        CreateOrderDto dto = new CreateOrderDto();
        dto.setListingId(1L);
        dto.setOrderType("MARKET");
        dto.setDirection("BUY");
        dto.setQuantity(5);
        dto.setContractSize(1);
        dto.setAccountId(100L); // RSD klijentski racun
        dto.setMargin(true);
        return dto;
    }

    @Test
    @DisplayName("USD listing, rate USD→RSD=117.5: rezervise RSD-ekvivalent, ne sirov USD broj")
    void marginBuyUsdListing_reservesRsdEquivalent() {
        CreateOrderDto dto = marginBuyDto();

        // approximatePrice = 1000 USD (5 kom × 200 USD npr.). BP=0.5.
        // RSD ekvivalent po rate 117.5 = 117500 RSD; userPart = 117500*(1-0.5)=58750 RSD.
        when(listingRepository.findById(1L)).thenReturn(Optional.of(usdListing));
        when(listingPriceService.getPricePerUnit(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("200"));
        when(listingPriceService.calculateApproximatePrice(anyInt(), any(), anyInt()))
                .thenReturn(new BigDecimal("1000")); // u USD (valuta listinga)
        when(bankaCoreClient.getAccount(100L)).thenReturn(rsdAccount(100L));
        when(orderStatusService.determineStatus(eq("CLIENT"), eq(CLIENT_ID), any()))
                .thenReturn(OrderStatus.APPROVED);

        // convertForPurchase (regularni BUY path, ovde account je RSD a listing USD).
        when(currencyConversionService.convertForPurchase(any(BigDecimal.class), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> new CurrencyConversionService.ConversionResult(
                        inv.getArgument(0), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE));
        // convert(listing→RSD) i getRate(listing→RSD) = 117.5
        when(currencyConversionService.convert(any(BigDecimal.class), eq("USD"), eq("RSD")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("117.5")));
        when(currencyConversionService.getRate("USD", "RSD")).thenReturn(new BigDecimal("117.5"));

        // Margin racun ACTIVE sa dovoljno IM (RSD).
        UserMarginAccount m = margin("100000", "10000", "0.50", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(CLIENT_ID, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(m));
        when(marginOrderSettlementService.reserveForMarginBuy(any(Order.class), any(BigDecimal.class)))
                .thenReturn(true);

        orderService.createOrder(dto);

        // Rezervacija mora biti RSD-ekvivalent: 1000 USD × 117.5 = 117500 RSD (NE sirovih 1000).
        ArgumentCaptor<BigDecimal> reserveCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(marginOrderSettlementService).reserveForMarginBuy(any(Order.class), reserveCap.capture());
        assertEquals(0, reserveCap.getValue().compareTo(new BigDecimal("117500")),
                "Margin BUY rezervacija mora biti RSD-konvertovani approximatePrice, ne sirov USD broj");

        // exchangeRate ordera mora biti listing→RSD (117.5) da settle racuna u RSD.
        ArgumentCaptor<Order> orderCap = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, atLeastOnce()).save(orderCap.capture());
        assertEquals(0, orderCap.getValue().getExchangeRate().compareTo(new BigDecimal("117.5")));
    }

    @Test
    @DisplayName("USD listing: pre-check koristi RSD userPart — odbija kad IM nedovoljan u RSD")
    void marginBuyUsdListing_preCheckUsesRsdUserPart_rejectsWhenInsufficient() {
        CreateOrderDto dto = marginBuyDto();

        when(listingRepository.findById(1L)).thenReturn(Optional.of(usdListing));
        when(listingPriceService.getPricePerUnit(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("200"));
        when(listingPriceService.calculateApproximatePrice(anyInt(), any(), anyInt()))
                .thenReturn(new BigDecimal("1000")); // USD
        when(bankaCoreClient.getAccount(100L)).thenReturn(rsdAccount(100L));
        when(orderStatusService.determineStatus(eq("CLIENT"), eq(CLIENT_ID), any()))
                .thenReturn(OrderStatus.APPROVED);
        when(currencyConversionService.convertForPurchase(any(BigDecimal.class), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> new CurrencyConversionService.ConversionResult(
                        inv.getArgument(0), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE));
        when(currencyConversionService.convert(any(BigDecimal.class), eq("USD"), eq("RSD")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("117.5")));

        // userPart u RSD = 117500*(1-0.5) = 58750. IM dostupno samo 10000 RSD → reject.
        // (Sa sirovim USD brojem userPart bi bio 500 i greska bi se sakrila — to je bug.)
        UserMarginAccount m = margin("10000", "5000", "0.50", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(CLIENT_ID, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(m));

        InsufficientFundsException ex = assertThrows(InsufficientFundsException.class,
                () -> orderService.createOrder(dto));
        assertTrue(ex.getMessage().contains("marzine"));
        verify(marginOrderSettlementService, never()).reserveForMarginBuy(any(), any());
    }

    @Test
    @DisplayName("RSD listing (ista valuta): rezervacija nepromenjena (rate=1)")
    void marginBuyRsdListing_unchanged() {
        CreateOrderDto dto = marginBuyDto();

        Listing rsdListing = new Listing();
        rsdListing.setId(1L);
        rsdListing.setTicker("NIS");
        rsdListing.setName("NIS a.d.");
        rsdListing.setListingType(ListingType.STOCK);
        rsdListing.setAsk(new BigDecimal("800"));
        rsdListing.setBid(new BigDecimal("790"));
        rsdListing.setExchangeAcronym("BELEX"); // BELEX → RSD

        when(listingRepository.findById(1L)).thenReturn(Optional.of(rsdListing));
        when(listingPriceService.getPricePerUnit(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("800"));
        when(listingPriceService.calculateApproximatePrice(anyInt(), any(), anyInt()))
                .thenReturn(new BigDecimal("4000")); // RSD
        when(bankaCoreClient.getAccount(100L)).thenReturn(rsdAccount(100L));
        when(orderStatusService.determineStatus(eq("CLIENT"), eq(CLIENT_ID), any()))
                .thenReturn(OrderStatus.APPROVED);
        when(currencyConversionService.convertForPurchase(any(BigDecimal.class), anyString(), anyString(), anyBoolean()))
                .thenAnswer(inv -> new CurrencyConversionService.ConversionResult(
                        inv.getArgument(0), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE));
        // ista valuta → convert vraca isti iznos (CurrencyConversionService.convert short-circuit),
        // getRate vraca 1.
        when(currencyConversionService.convert(any(BigDecimal.class), eq("RSD"), eq("RSD")))
                .thenAnswer(inv -> inv.getArgument(0));
        when(currencyConversionService.getRate("RSD", "RSD")).thenReturn(BigDecimal.ONE);

        UserMarginAccount m = margin("100000", "10000", "0.50", MarginAccountStatus.ACTIVE);
        when(marginAccountRepository.findFirstByUserIdAndStatus(CLIENT_ID, MarginAccountStatus.ACTIVE))
                .thenReturn(Optional.of(m));
        when(marginOrderSettlementService.reserveForMarginBuy(any(Order.class), any(BigDecimal.class)))
                .thenReturn(true);

        orderService.createOrder(dto);

        // RSD listing: rezervacija = approximatePrice (4000) bez konverzije (rate=1).
        ArgumentCaptor<BigDecimal> reserveCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(marginOrderSettlementService).reserveForMarginBuy(any(Order.class), reserveCap.capture());
        assertEquals(0, reserveCap.getValue().compareTo(new BigDecimal("4000")));
    }
}
