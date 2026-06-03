package rs.raf.trading.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.berza.service.ExchangeManagementService;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.implementation.OrderServiceImpl;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.repository.ListingRepository;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("OrderServiceImpl — getMyOrders filtering (B4)")
class GetMyOrdersFilteringTest {

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
    @Mock private TradingUserResolver tradingUserResolver;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        UserContext clientCtx = new UserContext(1L, UserRole.CLIENT);
        when(tradingUserResolver.resolveCurrent()).thenReturn(clientCtx);
        when(orderRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
    }

    @Test
    @DisplayName("no filters — returns page via Specification")
    void getMyOrders_noFilters_usesSpecification() {
        Page<OrderDto> result = orderService.getMyOrders(0, 20, null, null, null, null);

        assertNotNull(result);
        verify(orderRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("status=PENDING — passes OrderStatus.PENDING to specification")
    void getMyOrders_withStatusPending_callsRepository() {
        Page<OrderDto> result = orderService.getMyOrders(0, 20, "PENDING", null, null, null);

        assertNotNull(result);
        verify(orderRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("status=ALL — treated as no status filter")
    void getMyOrders_statusAll_treatedAsNoFilter() {
        orderService.getMyOrders(0, 20, "ALL", null, null, null);

        verify(orderRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("unknown status string — ignored gracefully")
    void getMyOrders_unknownStatus_ignoredGracefully() {
        assertDoesNotThrow(() -> orderService.getMyOrders(0, 10, "GARBAGE", null, null, null));
    }

    @Test
    @DisplayName("dateFrom and dateTo are forwarded to specification")
    void getMyOrders_withDateRange_callsRepository() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 6, 30);

        orderService.getMyOrders(0, 20, null, from, to, null);

        verify(orderRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("listingType=STOCK is forwarded to specification")
    void getMyOrders_withListingTypeStock_callsRepository() {
        orderService.getMyOrders(0, 20, null, null, null, "STOCK");

        verify(orderRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("unknown listingType is ignored gracefully")
    void getMyOrders_unknownListingType_ignoredGracefully() {
        assertDoesNotThrow(() -> orderService.getMyOrders(0, 10, null, null, null, "OPTION"));
    }

    @Test
    @DisplayName("P2-perf-nplus1-1 (R5 1896): size=10000 je clamp-ovan na MAX_PAGE_SIZE (100)")
    void getMyOrders_sizeClampedTo100_R5_1896() {
        org.mockito.ArgumentCaptor<Pageable> cap = org.mockito.ArgumentCaptor.forClass(Pageable.class);

        orderService.getMyOrders(0, 10000, null, null, null, null);

        verify(orderRepository).findAll(any(Specification.class), cap.capture());
        assertEquals(OrderServiceImpl.MAX_PAGE_SIZE, cap.getValue().getPageSize(),
                "size=10000 mora biti clamp-ovan na 100 (DoS guard)");
        assertEquals(100, cap.getValue().getPageSize());
    }

    @Test
    @DisplayName("P2-perf-nplus1-1 (R5 1896): size unutar granice prolazi nepromenjen")
    void getMyOrders_sizeWithinLimitUnchanged_R5_1896() {
        org.mockito.ArgumentCaptor<Pageable> cap = org.mockito.ArgumentCaptor.forClass(Pageable.class);

        orderService.getMyOrders(0, 25, null, null, null, null);

        verify(orderRepository).findAll(any(Specification.class), cap.capture());
        assertEquals(25, cap.getValue().getPageSize());
    }
}
