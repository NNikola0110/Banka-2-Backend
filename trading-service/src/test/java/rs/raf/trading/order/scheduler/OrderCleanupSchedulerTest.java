package rs.raf.trading.order.scheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.FundReservationService;
import rs.raf.trading.stock.model.Listing;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test {@link OrderCleanupScheduler} — porten iz monolita (faza 2c, package
 * rename). Posle B4 (PR #84) dodate verifikacije slanja notifikacije pri
 * automatskom otkazu naloga sa proteklim settlement datumom.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OrderCleanupSchedulerTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private FundReservationService fundReservationService;

    @InjectMocks
    private OrderCleanupScheduler orderCleanupScheduler;

    @Test
    void cleanupExpiredOrders_shouldDeclineOrderWithPassedSettlementDate() {
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(LocalDate.now().minusDays(1));
        when(listing.getTicker()).thenReturn("CLM24");
        when(order.getListing()).thenReturn(listing);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(order).setStatus(OrderStatus.DECLINED);
        verify(order).setApprovedBy("SYSTEM - Settlement date expired");
        verify(orderRepository).save(order);
    }

    @Test
    void cleanupExpiredOrders_shouldNotDeclineOrderWithFutureSettlement() {
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(LocalDate.now().plusDays(10));
        when(order.getListing()).thenReturn(listing);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(order, never()).setStatus(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cleanupExpiredOrders_shouldSkipOrderWithNullSettlementDate() {
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(null);
        when(order.getListing()).thenReturn(listing);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(order, never()).setStatus(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cleanupExpiredOrders_shouldDoNothing_whenNoCandidates() {
        when(orderRepository.findActiveNonDone()).thenReturn(List.of());

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(orderRepository, never()).save(any());
    }

    @Test
    void cleanupExpiredOrders_shouldSendOrderCancelledNotification_whenOrderExpired() {
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(LocalDate.now().minusDays(1));
        when(listing.getTicker()).thenReturn("CLM24");
        when(order.getListing()).thenReturn(listing);
        when(order.getUserId()).thenReturn(7L);
        when(order.getUserRole()).thenReturn("CLIENT");
        when(order.getId()).thenReturn(99L);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(notificationService).notify(
                eq(7L),
                eq("CLIENT"),
                eq(NotificationType.ORDER_CANCELLED),
                anyString(),
                anyString(),
                eq("ORDER"),
                eq(99L)
        );
    }

    // ── P1-dividends-order-1 (164): auto-decline oslobadja rezervaciju (leak fix) ──

    @Test
    void cleanupExpiredOrders_approvedBuy_releasesReservationBeforeDecline() {
        // APPROVED BUY order sa proteklim settlement-om: rezervacija MORA biti oslobodjena,
        // inace sredstva ostaju zauvek zakljucana (leak) iako je order DECLINED.
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(LocalDate.now().minusDays(1));
        when(listing.getTicker()).thenReturn("CLM24");
        when(order.getListing()).thenReturn(listing);
        when(order.getStatus()).thenReturn(OrderStatus.APPROVED);
        when(order.getDirection()).thenReturn(OrderDirection.BUY);
        when(order.isReservationReleased()).thenReturn(false);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));

        orderCleanupScheduler.cleanupExpiredOrders();

        // Release pre setStatus(DECLINED).
        verify(fundReservationService).releaseForBuy(order);
        verify(order).setStatus(OrderStatus.DECLINED);
        verify(orderRepository).save(order);
    }

    @Test
    void cleanupExpiredOrders_pendingOrder_doesNotReleaseReservation() {
        // PENDING order jos nema rezervaciju (rezervacija se desava pri APPROVED),
        // pa se release NE poziva (idempotentno, bez suvisnog banka-core poziva).
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(LocalDate.now().minusDays(1));
        when(listing.getTicker()).thenReturn("CLM24");
        when(order.getListing()).thenReturn(listing);
        when(order.getStatus()).thenReturn(OrderStatus.PENDING);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(fundReservationService, never()).releaseForBuy(any());
        verify(order).setStatus(OrderStatus.DECLINED);
        verify(orderRepository).save(order);
    }

    @Test
    void cleanupExpiredOrders_releaseFails_stillDeclinesOrder() {
        // Ako banka-core release padne, order se i dalje declime-uje (best-effort release).
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(LocalDate.now().minusDays(1));
        when(listing.getTicker()).thenReturn("CLM24");
        when(order.getListing()).thenReturn(listing);
        when(order.getStatus()).thenReturn(OrderStatus.APPROVED);
        when(order.getDirection()).thenReturn(OrderDirection.BUY);
        when(order.isReservationReleased()).thenReturn(false);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));
        doThrow(new RuntimeException("banka-core down")).when(fundReservationService).releaseForBuy(order);

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(order).setStatus(OrderStatus.DECLINED);
        verify(orderRepository).save(order);
    }

    @Test
    void cleanupExpiredOrders_shouldContinueEvenWhenNotificationFails() {
        Order order = mock(Order.class);
        Listing listing = mock(Listing.class);
        when(listing.getSettlementDate()).thenReturn(LocalDate.now().minusDays(1));
        when(listing.getTicker()).thenReturn("CLM24");
        when(order.getListing()).thenReturn(listing);
        when(order.getUserId()).thenReturn(7L);
        when(order.getUserRole()).thenReturn("CLIENT");
        when(order.getId()).thenReturn(99L);
        when(orderRepository.findActiveNonDone()).thenReturn(List.of(order));
        doThrow(new RuntimeException("notification failure")).when(notificationService)
                .notify(any(), any(), any(), any(), any(), any(), any());

        orderCleanupScheduler.cleanupExpiredOrders();

        verify(order).setStatus(OrderStatus.DECLINED);
        verify(orderRepository).save(order);
    }
}
