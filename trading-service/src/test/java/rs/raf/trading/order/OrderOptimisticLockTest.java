package rs.raf.trading.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.recurringorder.model.RecurringCadence;
import rs.raf.trading.recurringorder.model.RecurringMode;
import rs.raf.trading.recurringorder.model.RecurringOrder;
import rs.raf.trading.recurringorder.repository.RecurringOrderRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * N3 (concurrency): {@code @Version} optimisticko zakljucavanje na hot order
 * entitetima ({@link Order}, {@link RecurringOrder}) sprecava double-fill /
 * dupli DCA buy / double-charge na 2 k8s replike (ili overlap istog tick-a).
 *
 * <p>Simuliramo dva konkurentna scheduler tick-a: oba ucitaju ISTI red (ista
 * verzija), oba ga izmene, prvi commit prodje (verzija++), drugi (stale) commit
 * baca {@code ObjectOptimisticLockingFailureException} — njegova REQUIRES_NEW tx
 * bi se rollback-ovala izolovano (order/DCA se ne izvrsi dvaput).
 *
 * <p>Spring Boot 4 nema {@code @DataJpaTest} — {@code @SpringBootTest} + H2
 * (application-test.properties), isti obrazac kao SagaLogPersistenceTest.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderOptimisticLockTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RecurringOrderRepository recurringOrderRepository;

    @Autowired
    private ListingRepository listingRepository;

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        recurringOrderRepository.deleteAll();
        listingRepository.deleteAll();
    }

    private Listing savedListing() {
        Listing l = new Listing();
        l.setTicker("VER");
        l.setName("Versioned Inc");
        l.setListingType(ListingType.STOCK);
        l.setExchangeAcronym("BELEX");
        l.setQuoteCurrency("RSD");
        l.setPrice(new BigDecimal("100"));
        l.setAsk(new BigDecimal("100"));
        l.setBid(new BigDecimal("100"));
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    private Order savedApprovedBuy(Listing listing) {
        Order o = new Order();
        o.setUserId(1L);
        o.setUserRole("CLIENT");
        o.setListing(listing);
        o.setOrderType(OrderType.MARKET);
        o.setDirection(OrderDirection.BUY);
        o.setQuantity(10);
        o.setRemainingPortions(10);
        o.setContractSize(1);
        o.setStatus(OrderStatus.APPROVED);
        o.setCreatedAt(LocalDateTime.now());
        o.setLastModification(LocalDateTime.now());
        return orderRepository.saveAndFlush(o);
    }

    @Test
    @DisplayName("N3: Order ima @Version koja se inicijalizuje (0) i inkrementira na update")
    void order_versionInitializedAndIncrements() {
        Listing listing = savedListing();
        Order saved = savedApprovedBuy(listing);

        Order reloaded = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isNotNull();
        Long v0 = reloaded.getVersion();

        reloaded.setRemainingPortions(8);
        orderRepository.saveAndFlush(reloaded);

        Order afterUpdate = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(afterUpdate.getVersion()).isGreaterThan(v0);
    }

    @Test
    @DisplayName("N3: konkurentni double-fill istog Order-a → drugi (stale) save baca OptimisticLock")
    void order_concurrentDoubleFill_secondSaveThrowsOptimisticLock() {
        Listing listing = savedListing();
        Order saved = savedApprovedBuy(listing);
        Long id = saved.getId();

        // Tick A ucitava sveze; Tick B je detached snapshot sa STAROM verzijom (oba "vide" remaining=10).
        Order tickA = orderRepository.findById(id).orElseThrow();
        Order tickB = detachedCopyOf(saved);

        // Tick A izvrsi fill (remaining 10 → 6) i commit-uje — verzija se inkrementira.
        tickA.setRemainingPortions(6);
        orderRepository.saveAndFlush(tickA);

        // Tick B (stale, jos uvek vidi remaining=10) pokusa svoj fill → OptimisticLock.
        tickB.setRemainingPortions(7);
        assertThatThrownBy(() -> orderRepository.saveAndFlush(tickB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Konacno stanje odrazava SAMO Tick A (jedan fill), ne oba (no double-fill).
        Order finalState = orderRepository.findById(id).orElseThrow();
        assertThat(finalState.getRemainingPortions()).isEqualTo(6);
    }

    @Test
    @DisplayName("N3: konkurentni dupli DCA cron tick istog RecurringOrder-a → drugi save baca OptimisticLock")
    void recurringOrder_concurrentDuplicateTick_secondSaveThrowsOptimisticLock() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(5);
        RecurringOrder ro = RecurringOrder.builder()
                .ownerId(1L)
                .ownerType("CLIENT")
                .listingId(1L)
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("3"))
                .accountId(1L)
                .cadence(RecurringCadence.DAILY)
                .nextRun(due)
                .active(true)
                .build();
        RecurringOrder saved = recurringOrderRepository.saveAndFlush(ro);
        Long id = saved.getId();
        Long initialVersion = saved.getVersion();
        assertThat(initialVersion).isNotNull();

        RecurringOrder tickA = recurringOrderRepository.findById(id).orElseThrow();
        RecurringOrder tickB = detachedRecurringCopyOf(saved, due, initialVersion);

        // Tick A pomeri nextRun i commit-uje (verzija++).
        tickA.setNextRun(due.plusDays(1));
        recurringOrderRepository.saveAndFlush(tickA);

        // Tick B (stale) pokusa svoje nextRun pomeranje → OptimisticLock (no dupli DCA buy).
        tickB.setNextRun(due.plusDays(1));
        assertThatThrownBy(() -> recurringOrderRepository.saveAndFlush(tickB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /** Detached kopija sa STAROM verzijom (snapshot pre Tick A commit-a). */
    private Order detachedCopyOf(Order original) {
        Order copy = new Order();
        copy.setId(original.getId());
        copy.setUserId(original.getUserId());
        copy.setUserRole(original.getUserRole());
        copy.setListing(original.getListing());
        copy.setOrderType(original.getOrderType());
        copy.setDirection(original.getDirection());
        copy.setQuantity(original.getQuantity());
        copy.setRemainingPortions(original.getRemainingPortions());
        copy.setContractSize(original.getContractSize());
        copy.setStatus(original.getStatus());
        copy.setCreatedAt(original.getCreatedAt());
        copy.setLastModification(original.getLastModification());
        copy.setVersion(original.getVersion()); // STARA verzija → stale na save
        return copy;
    }

    private RecurringOrder detachedRecurringCopyOf(RecurringOrder original, LocalDateTime nextRun,
                                                   Long staleVersion) {
        RecurringOrder copy = RecurringOrder.builder()
                .id(original.getId())
                .ownerId(original.getOwnerId())
                .ownerType(original.getOwnerType())
                .listingId(original.getListingId())
                .direction(original.getDirection())
                .mode(original.getMode())
                .value(original.getValue())
                .accountId(original.getAccountId())
                .cadence(original.getCadence())
                .nextRun(nextRun)
                .active(original.isActive())
                .createdAt(original.getCreatedAt())
                .version(staleVersion) // STARA verzija → stale na save
                .build();
        return copy;
    }
}
