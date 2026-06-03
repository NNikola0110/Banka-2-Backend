package rs.raf.trading.recurringorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.service.OrderService;
import rs.raf.trading.recurringorder.model.RecurringCadence;
import rs.raf.trading.recurringorder.model.RecurringMode;
import rs.raf.trading.recurringorder.model.RecurringOrder;
import rs.raf.trading.recurringorder.repository.RecurringOrderRepository;
import rs.raf.trading.recurringorder.scheduler.RecurringOrderScheduler;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scheduler-cycle integracioni test za DCA / trajne naloge (B8, TODO_final #21,
 * Sc49). Za razliku od {@link RecurringOrderServiceTest} (cisti unit, sve
 * mock-ovano), ovde se podize {@code @SpringBootTest} sa H2 i poziva STVARNI
 * {@link RecurringOrderScheduler#runCycle()} koji ide kroz pravi
 * {@code RecurringOrderRepository.findDue(now)} → {@code executeOne}.
 *
 * <p>Dokazuje end-to-end ciklus krona koji pomera novac:
 * <ul>
 *   <li>dospeli ({@code nextRun <= now}) aktivan nalog → Market BUY order
 *       (pravi iznos/owner/listing) + {@code nextRun} pomeren za jednu cadence
 *       periodu i PERZISTIRAN u H2;</li>
 *   <li>skip slucaj (nedovoljna sredstva) → bez ordera, best-effort
 *       {@code RECURRING_ORDER_SKIPPED} notifikacija, {@code nextRun} svejedno
 *       pomeren (no busy-loop);</li>
 *   <li>pauziran nalog → {@code findDue} ga ne vraca, ciklus ga ignorise.</li>
 * </ul>
 *
 * <p>{@link OrderService} je {@code @MockitoBean} — pun order-execution engine
 * (rezervacije, aktuarski limiti, margin) je pokriven drugde; ovde proveravamo
 * kron-orkestraciju + tacan {@link CreateOrderDto} koji kron sastavi. Racuni
 * zive u banka-core domenu pa je {@link BankaCoreClient} mock-ovan za balance
 * lookup; {@link RecurringOrder}/{@link Listing} su lokalni i pisu se u H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecurringOrderSchedulerCycleIntegrationTest {

    @Autowired
    private RecurringOrderScheduler scheduler;

    @Autowired
    private RecurringOrderRepository recurringOrderRepository;

    @Autowired
    private ListingRepository listingRepository;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    @MockitoBean
    private NotificationService notificationService;

    // Kron ne resolve-uje trenutnog korisnika (sistemska akcija), ali servis ga
    // ima kao dependency; mock-ujemo ga da kontekst ne padne pri start-up-u.
    @MockitoBean
    private TradingUserResolver tradingUserResolver;

    private static final Long CLIENT_ID = 4001L;
    private static final Long ACCOUNT_ID = 9100L;

    @AfterEach
    void tearDown() {
        recurringOrderRepository.deleteAll();
        listingRepository.deleteAll();
    }

    private Listing savedRsdStock(String ticker, String price) {
        Listing l = new Listing();
        l.setTicker(ticker);
        l.setName(ticker + " d.o.o.");
        l.setListingType(ListingType.STOCK);
        l.setExchangeAcronym("BELEX");
        l.setQuoteCurrency("RSD");
        l.setPrice(new BigDecimal(price));
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    private RecurringOrder savedRecurringOrder(Long listingId, RecurringMode mode,
                                               BigDecimal value, RecurringCadence cadence,
                                               LocalDateTime nextRun, boolean active) {
        RecurringOrder ro = RecurringOrder.builder()
                .ownerId(CLIENT_ID)
                .ownerType("CLIENT")
                .listingId(listingId)
                .direction("BUY")
                .mode(mode)
                .value(value)
                .accountId(ACCOUNT_ID)
                .cadence(cadence)
                .nextRun(nextRun)
                .active(active)
                .build();
        return recurringOrderRepository.save(ro);
    }

    private InternalAccountDto account(BigDecimal available) {
        return new InternalAccountDto(
                ACCOUNT_ID, "RS-" + ACCOUNT_ID, "Owner",
                available, available, BigDecimal.ZERO, "RSD",
                "ACTIVE", CLIENT_ID, null, "PERSONAL");
    }

    /**
     * N1: runCycle sad postavlja sistemski SecurityContext iz vlasnika naloga pre
     * createOrder-a — owner identitet (email) se razresava preko banka-core
     * {@code getUserById}. Stub vraca email za CLIENT_ID.
     */
    private InternalUserDto ownerClient() {
        return new InternalUserDto(CLIENT_ID, "CLIENT", "owner.dca@example.com",
                "Owner", "Dca", true, null);
    }

    /**
     * Sc49: dospeli BY_AMOUNT mesecni nalog (5000 RSD @ 250 RSD/kom = 20 kom) →
     * runCycle() kreira Market BUY za 20 komada na pravom racunu, i pomera
     * nextRun za ~1 mesec u H2.
     */
    @Test
    @DisplayName("runCycle: dospeli BY_AMOUNT MONTHLY nalog → Market BUY (20 kom) + nextRun +1 mesec (perzistirano)")
    void runCycle_dueByAmountMonthly_createsBuyOrderAndAdvancesNextRun() {
        Listing listing = savedRsdStock("DCA1", "250");
        LocalDateTime due = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        RecurringOrder ro = savedRecurringOrder(
                listing.getId(), RecurringMode.BY_AMOUNT, new BigDecimal("5000"),
                RecurringCadence.MONTHLY, due, true);

        // Dovoljno sredstava: 20 kom × 250 = 5000 <= 6000 available.
        when(bankaCoreClient.getAccount(ACCOUNT_ID)).thenReturn(account(new BigDecimal("6000")));
        // N1: owner identitet za sistemski SecurityContext (email lookup pre createOrder-a).
        when(bankaCoreClient.getUserById("CLIENT", CLIENT_ID)).thenReturn(ownerClient());
        // createOrder vraca OrderDto; kron ignorise povratnu vrednost — default mock (null) je dovoljan.

        scheduler.runCycle();

        // Kreiran je TACNO jedan Market BUY order sa pravim iznosom/owner-om/listing-om.
        org.mockito.ArgumentCaptor<CreateOrderDto> captor =
                org.mockito.ArgumentCaptor.forClass(CreateOrderDto.class);
        verify(orderService).createOrder(captor.capture(), eq(true));
        CreateOrderDto created = captor.getValue();
        assertThat(created.getOrderType()).isEqualTo("MARKET");
        assertThat(created.getDirection()).isEqualTo("BUY");
        assertThat(created.getListingId()).isEqualTo(listing.getId());
        assertThat(created.getQuantity()).isEqualTo(20); // floor(5000 / 250)
        assertThat(created.getAccountId()).isEqualTo(ACCOUNT_ID);
        // Sistemska akcija — bez TOTP koda; internalActor=true.
        assertThat(created.getOtpCode()).isNull();

        // nextRun pomeren za ~1 mesec i perzistiran u H2 (anti-busy-loop invarijanta).
        RecurringOrder reloaded = recurringOrderRepository.findById(ro.getId()).orElseThrow();
        assertThat(reloaded.getNextRun()).isAfter(due);
        long days = ChronoUnit.DAYS.between(due, reloaded.getNextRun());
        assertThat(days).isBetween(28L, 31L); // +1 kalendarski mesec
        assertThat(reloaded.isActive()).isTrue();
    }

    /**
     * Sc49 skip grana: nedovoljna sredstva → BEZ ordera, best-effort
     * RECURRING_ORDER_SKIPPED notifikacija, nextRun svejedno pomeren (no busy-loop).
     */
    @Test
    @DisplayName("runCycle: dospeli nalog ali nedovoljna sredstva → bez ordera, notifikacija, nextRun pomeren")
    void runCycle_insufficientFunds_skipsGracefullyAndAdvancesNextRun() {
        Listing listing = savedRsdStock("DCA2", "250");
        LocalDateTime due = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        RecurringOrder ro = savedRecurringOrder(
                listing.getId(), RecurringMode.BY_AMOUNT, new BigDecimal("5000"),
                RecurringCadence.WEEKLY, due, true);

        // 20 kom × 250 = 5000 potrebno, ali samo 1000 dostupno → skip.
        when(bankaCoreClient.getAccount(ACCOUNT_ID)).thenReturn(account(new BigDecimal("1000")));

        scheduler.runCycle();

        // Nijedan order nije kreiran.
        verify(orderService, never()).createOrder(any(CreateOrderDto.class), anyBoolean());
        // Best-effort notifikacija o preskocenom nalogu.
        verify(notificationService).notify(
                eq(CLIENT_ID), eq("CLIENT"),
                eq(NotificationType.RECURRING_ORDER_SKIPPED),
                any(), any(), eq("RECURRING_ORDER"), eq(ro.getId()));

        // nextRun pomeren (~1 nedelja) i perzistiran — nalog se ne vrti u petlji.
        RecurringOrder reloaded = recurringOrderRepository.findById(ro.getId()).orElseThrow();
        assertThat(reloaded.getNextRun()).isAfter(due);
        long days = ChronoUnit.DAYS.between(due, reloaded.getNextRun());
        assertThat(days).isBetween(6L, 8L);
        assertThat(reloaded.isActive()).isTrue();
    }

    /**
     * Pauziran nalog NIJE dospeo po {@code findDue} (active=false) — ciklus ga
     * preskace u potpunosti (bez balance lookup-a, bez ordera, nextRun netaknut).
     */
    @Test
    @DisplayName("runCycle: pauziran (active=false) nalog se ignorise (findDue ga ne vraca)")
    void runCycle_pausedOrder_isIgnored() {
        Listing listing = savedRsdStock("DCA3", "100");
        LocalDateTime due = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        RecurringOrder ro = savedRecurringOrder(
                listing.getId(), RecurringMode.BY_QUANTITY, new BigDecimal("3"),
                RecurringCadence.DAILY, due, false);

        // nextRun pre ciklusa (procitan iz H2 da bismo izbegli sub-mikro precision drift
        // izmedju in-memory LocalDateTime i H2 TIMESTAMP-a).
        LocalDateTime nextRunBefore = recurringOrderRepository.findById(ro.getId())
                .orElseThrow().getNextRun();

        scheduler.runCycle();

        verify(orderService, never()).createOrder(any(CreateOrderDto.class), anyBoolean());
        RecurringOrder reloaded = recurringOrderRepository.findById(ro.getId()).orElseThrow();
        // nextRun netaknut — kron ga nije ni dotakao (findDue ne vraca pauzirane).
        assertThat(reloaded.getNextRun()).isEqualTo(nextRunBefore);
        assertThat(reloaded.isActive()).isFalse();
    }
}
