package rs.raf.trading.order.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integracioni test {@link OrderCleanupScheduler} — pun Spring kontekst (H2
 * test profil), realan scheduler + realan {@code OrderRepository}/{@code ListingRepository}
 * ({@code Order}/{@code Listing} su trading-service entiteti). Money/identitet
 * seam ({@link BankaCoreClient}, {@link TradingUserResolver}) je mockovan jer
 * cleanup ne dira novac. {@code @Scheduled} je inertan; metoda se poziva
 * eksplicitno.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderCleanupSchedulerIntegrationTest {

    @Autowired
    private OrderCleanupScheduler orderCleanupScheduler;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ListingRepository listingRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    @MockitoBean
    private TradingUserResolver tradingUserResolver;

    @BeforeEach
    void clean() {
        orderRepository.deleteAll();
        listingRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        listingRepository.deleteAll();
    }

    private Listing savedListing(LocalDate settlementDate) {
        Listing l = new Listing();
        l.setTicker("AAPL");
        l.setName("Apple Inc.");
        l.setListingType(ListingType.STOCK);
        l.setPrice(BigDecimal.valueOf(150));
        l.setAsk(BigDecimal.valueOf(151));
        l.setBid(BigDecimal.valueOf(149));
        l.setExchangeAcronym("NASDAQ");
        l.setLastRefresh(LocalDateTime.now());
        l.setSettlementDate(settlementDate);
        return listingRepository.save(l);
    }

    private Order savedOrder(OrderStatus status, LocalDate settlementDate) {
        Order o = new Order();
        o.setUserId(1L);
        o.setUserRole("CLIENT");
        o.setListing(savedListing(settlementDate));
        o.setOrderType(OrderType.MARKET);
        o.setDirection(OrderDirection.BUY);
        o.setQuantity(5);
        o.setContractSize(1);
        o.setPricePerUnit(BigDecimal.valueOf(150));
        o.setApproximatePrice(BigDecimal.valueOf(750));
        o.setStatus(status);
        o.setDone(false);
        o.setRemainingPortions(5);
        o.setAfterHours(false);
        o.setAllOrNone(false);
        o.setMargin(false);
        o.setCreatedAt(LocalDateTime.now());
        o.setLastModification(LocalDateTime.now());
        return orderRepository.save(o);
    }

    @Test
    void cleanupExpiredOrders_shouldDeclineExpiredOrder() {
        Order order = savedOrder(OrderStatus.APPROVED, LocalDate.now().minusDays(1));

        orderCleanupScheduler.cleanupExpiredOrders();

        Order updated = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OrderStatus.DECLINED);
        assertThat(updated.getApprovedBy()).isEqualTo("SYSTEM - Settlement date expired");
    }

    @Test
    void cleanupExpiredOrders_shouldNotDeclineRecentOrder() {
        Order order = savedOrder(OrderStatus.APPROVED, null);

        orderCleanupScheduler.cleanupExpiredOrders();

        Order updated = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test
    void cleanupExpiredOrders_shouldDoNothing_whenNoOrders() {
        orderCleanupScheduler.cleanupExpiredOrders();

        assertThat(orderRepository.findAll()).isEmpty();
    }
}
