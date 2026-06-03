package rs.raf.trading.recurringorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.recurringorder.model.RecurringCadence;
import rs.raf.trading.recurringorder.model.RecurringMode;
import rs.raf.trading.recurringorder.model.RecurringOrder;
import rs.raf.trading.recurringorder.repository.RecurringOrderRepository;
import rs.raf.trading.recurringorder.scheduler.RecurringOrderScheduler;
import rs.raf.trading.security.TradingPermissionResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * N1 (broken-feature) e2e dokaz: DCA cron STVARNO kreira Market BUY order kroz
 * PRAVI {@code OrderServiceImpl.createOrder} put — nije mock.
 *
 * <p><b>Zasto je ovaj test kljucan:</b> {@link RecurringOrderSchedulerCycleIntegrationTest}
 * i {@link RecurringOrderServiceTest} mock-uju {@code OrderService}, pa je bug bio
 * strukturno NEVIDLJIV: scheduler thread nema Spring Security context, pa bi pravi
 * {@code createOrder} (preko {@code resolveCurrent}/{@code ensureTradingAccess})
 * bacio "Nema autentifikovanog korisnika", {@code executeOne} catch bi to progutao,
 * {@code nextRun} bi tiho napredovao i DCA NIKAD ne bi kupovao (B8/Sc49 de-facto mrtav).
 *
 * <p>Ovde je {@code OrderService} PRAVI bean (H2), pa se Order red zaista perzistira.
 * Samo banka-core HTTP seam-ovi ({@link BankaCoreClient}, {@link TradingPermissionResolver})
 * su mock-ovani jer racun/identitet/permisije zive u banka-core domenu.
 *
 * <p>Pre N1 fix-a: posle {@code runCycle()} u {@code orders} tabeli NEMA reda
 * (exception progutan). Posle fix-a: tacno jedan APPROVED Market BUY order vlasnika.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecurringOrderDcaE2ETest {

    @Autowired
    private RecurringOrderScheduler scheduler;

    @Autowired
    private RecurringOrderRepository recurringOrderRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ActuaryInfoRepository actuaryInfoRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    @MockitoBean
    private TradingPermissionResolver permissionResolver;

    @MockitoBean
    private NotificationService notificationService;

    private static final Long CLIENT_ID = 7001L;
    private static final Long ACCOUNT_ID = 9300L;
    private static final String OWNER_EMAIL = "dca.owner@example.com";

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        recurringOrderRepository.deleteAll();
        listingRepository.deleteAll();
        actuaryInfoRepository.deleteAll();
        SecurityContextHolder.clearContext();
    }

    private Listing savedRsdStock(String ticker, String price) {
        Listing l = new Listing();
        l.setTicker(ticker);
        l.setName(ticker + " d.o.o.");
        l.setListingType(ListingType.STOCK);
        l.setExchangeAcronym("BELEX");
        l.setQuoteCurrency("RSD");
        l.setPrice(new BigDecimal(price));
        l.setAsk(new BigDecimal(price));
        l.setBid(new BigDecimal(price));
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    private InternalAccountDto rsdClientAccount(BigDecimal available) {
        return new InternalAccountDto(
                ACCOUNT_ID, "RS-" + ACCOUNT_ID, "Owner",
                available, available, BigDecimal.ZERO, "RSD",
                "ACTIVE", CLIENT_ID, null, "PERSONAL");
    }

    /**
     * Sc49 / N1 e2e: dospeli BY_QUANTITY mesecni DCA nalog (klijent sa
     * TRADE_STOCKS) → runCycle() preko PRAVOG createOrder-a perzistira APPROVED
     * Market BUY order vlasnika. Pre fix-a: 0 ordera u bazi.
     */
    @Test
    @DisplayName("N1 e2e: DCA cron STVARNO kreira APPROVED Market BUY order (real OrderServiceImpl, ne mock)")
    void runCycle_dueOrder_persistsRealMarketBuyOrder() {
        Listing listing = savedRsdStock("DCAE2E", "100");
        LocalDateTime due = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        RecurringOrder ro = RecurringOrder.builder()
                .ownerId(CLIENT_ID)
                .ownerType("CLIENT")
                .listingId(listing.getId())
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("4"))
                .accountId(ACCOUNT_ID)
                .cadence(RecurringCadence.MONTHLY)
                .nextRun(due)
                .active(true)
                .build();
        recurringOrderRepository.save(ro);

        // banka-core seam: racun (balance check + reserve currency lookup), owner email,
        // permisije (klijent ima TRADE_STOCKS), reservacija sredstava.
        InternalUserDto owner = new InternalUserDto(CLIENT_ID, "CLIENT", OWNER_EMAIL,
                "Dca", "Owner", true, null);
        when(bankaCoreClient.getAccount(ACCOUNT_ID)).thenReturn(rsdClientAccount(new BigDecimal("100000")));
        // runAsOrderOwner razresava email iz ownerId/ownerType (getUserById)...
        when(bankaCoreClient.getUserById("CLIENT", CLIENT_ID)).thenReturn(owner);
        // ...a OrderServiceImpl.resolveCurrentUser razresava email -> userId (getUserByEmail).
        when(bankaCoreClient.getUserByEmail(OWNER_EMAIL)).thenReturn(owner);
        when(permissionResolver.resolvePermissions(OWNER_EMAIL)).thenReturn(List.of("TRADE_STOCKS"));
        when(bankaCoreClient.reserveFunds(anyString(), any())).thenReturn(
                new ReserveFundsResponse("res-dca-1", ACCOUNT_ID, new BigDecimal("400"),
                        new BigDecimal("99600")));

        // SANITY: pre cikla nema ordera.
        assertThat(orderRepository.count()).isZero();

        scheduler.runCycle();

        // KLJUCNA ASERCIJA: pravi Order je perzistiran (pre N1 fix-a = 0).
        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(1);
        Order created = orders.get(0);
        assertThat(created.getOrderType()).isEqualTo(OrderType.MARKET);
        assertThat(created.getDirection()).isEqualTo(OrderDirection.BUY);
        assertThat(created.getQuantity()).isEqualTo(4);
        assertThat(created.getUserId()).isEqualTo(CLIENT_ID);
        assertThat(created.getUserRole()).isEqualTo("CLIENT");
        assertThat(created.getListing().getId()).isEqualTo(listing.getId());
        // Klijentov order je odmah APPROVED (OrderStatusService).
        assertThat(created.getStatus()).isEqualTo(OrderStatus.APPROVED);

        // nextRun pomeren — nalog nije zaglavljen.
        RecurringOrder reloaded = recurringOrderRepository.findById(ro.getId()).orElseThrow();
        assertThat(reloaded.getNextRun()).isAfter(due);

        // N1: SecurityContext je ociscen posle ciklusa (ne procuri na sledeci thread/nalog).
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * N1 negativna kontrola: klijent BEZ TRADE_STOCKS permisije → ensureTradingAccess
     * baca 403 unutar pravog createOrder-a; exception se proguta u executeOne catch,
     * NIJEDAN order se ne perzistira, ali nextRun svejedno napreduje (no busy-loop).
     */
    @Test
    @DisplayName("N1 e2e: klijent bez TRADE_STOCKS → 403 u createOrder, 0 ordera, nextRun pomeren")
    void runCycle_ownerWithoutTradeStocks_noOrderButAdvances() {
        Listing listing = savedRsdStock("DCANO", "100");
        LocalDateTime due = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        RecurringOrder ro = RecurringOrder.builder()
                .ownerId(CLIENT_ID)
                .ownerType("CLIENT")
                .listingId(listing.getId())
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("4"))
                .accountId(ACCOUNT_ID)
                .cadence(RecurringCadence.MONTHLY)
                .nextRun(due)
                .active(true)
                .build();
        recurringOrderRepository.save(ro);

        InternalUserDto owner = new InternalUserDto(CLIENT_ID, "CLIENT", OWNER_EMAIL,
                "Dca", "Owner", true, null);
        when(bankaCoreClient.getAccount(ACCOUNT_ID)).thenReturn(rsdClientAccount(new BigDecimal("100000")));
        when(bankaCoreClient.getUserById("CLIENT", CLIENT_ID)).thenReturn(owner);
        when(bankaCoreClient.getUserByEmail(OWNER_EMAIL)).thenReturn(owner);
        // NEMA TRADE_STOCKS — ensureTradingAccess → 403.
        when(permissionResolver.resolvePermissions(OWNER_EMAIL)).thenReturn(List.of());

        scheduler.runCycle();

        assertThat(orderRepository.count()).isZero();
        RecurringOrder reloaded = recurringOrderRepository.findById(ro.getId()).orElseThrow();
        assertThat(reloaded.getNextRun()).isAfter(due);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static final Long EMPLOYEE_ID = 5005L;
    private static final String EMPLOYEE_EMAIL = "dca.actuar@example.com";
    private static final Long BANK_ACCOUNT_ID = 9400L;

    private InternalAccountDto rsdBankAccount(BigDecimal available) {
        // Zaposleni (agent) DCA koristi bankin trading racun (RSD). ownerClientId=null (bankin).
        return new InternalAccountDto(
                BANK_ACCOUNT_ID, "RS-" + BANK_ACCOUNT_ID, "Banka Trading",
                available, available, BigDecimal.ZERO, "RSD",
                "ACTIVE", null, null, "BANK");
    }

    /**
     * <b>Sc53 (TODO_testovi) — usedLimit aktuara pri DCA → PENDING.</b>
     *
     * <p>E2E dokaz da DCA cron, kad je vlasnik trajnog naloga AGENT-aktuar ciji bi se
     * dnevni limit PROBIO ovim nalogom, kreira PRAVI Market BUY order kroz pravi
     * {@code OrderServiceImpl.createOrder} koji prelazi u <b>PENDING</b> (ceka odobrenje
     * supervizora) — NE u APPROVED. Ovo zatvara Sc53: postojeci DCA E2E testovi pokrivaju
     * SAMO klijenta (uvek APPROVED) ili klijenta bez permisije (403); aktuar-prelazi-limit
     * grana ({@code OrderStatusService.determineStatus} usedLimit+approx > dailyLimit → PENDING)
     * nije bila vozena kroz scheduler→real-createOrder put.
     *
     * <p>Setup: agent ima dailyLimit=1000 RSD, usedLimit=900 RSD. DCA nalog kupuje 4 ×
     * 100 RSD = 400 RSD → 900 + 400 = 1300 > 1000 → PENDING. Listing je RSD (bez FX
     * konverzije limita). Posto je PENDING: BEZ rezervacije sredstava, BEZ usedLimit
     * inkrementa (oba se dese SAMO na APPROVED), nextRun svejedno pomeren.
     */
    @Test
    @DisplayName("Sc53 e2e: DCA aktuar prelazi dnevni limit → PRAVI Market BUY order u PENDING "
            + "(usedLimit netaknut, nextRun pomeren)")
    void runCycle_actuaryCrossesDailyLimit_orderGoesPending() {
        Listing listing = savedRsdStock("DCALIM", "100");
        LocalDateTime due = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        RecurringOrder ro = RecurringOrder.builder()
                .ownerId(EMPLOYEE_ID)
                .ownerType("EMPLOYEE")
                .listingId(listing.getId())
                .direction("BUY")
                .mode(RecurringMode.BY_QUANTITY)
                .value(new BigDecimal("4"))   // 4 × 100 RSD = 400 RSD
                .accountId(BANK_ACCOUNT_ID)
                .cadence(RecurringCadence.MONTHLY)
                .nextRun(due)
                .active(true)
                .build();
        recurringOrderRepository.save(ro);

        // Aktuar (AGENT): dailyLimit 1000, usedLimit 900 → +400 = 1300 > 1000 → PENDING.
        ActuaryInfo actuary = new ActuaryInfo();
        actuary.setEmployeeId(EMPLOYEE_ID);
        actuary.setActuaryType(ActuaryType.AGENT);
        actuary.setDailyLimit(new BigDecimal("1000.0000"));
        actuary.setUsedLimit(new BigDecimal("900.0000"));
        actuary.setNeedApproval(false);   // ne forsiramo PENDING preko needApproval — testiramo BAS limit granu
        actuaryInfoRepository.save(actuary);

        // banka-core seam: zaposleni owner (EMPLOYEE rola), bankin trading racun, AGENT autoritet.
        InternalUserDto owner = new InternalUserDto(EMPLOYEE_ID, "EMPLOYEE", EMPLOYEE_EMAIL,
                "Aktuar", "Agent", true, null);
        when(bankaCoreClient.getAccount(BANK_ACCOUNT_ID)).thenReturn(rsdBankAccount(new BigDecimal("1000000")));
        when(bankaCoreClient.getUserById("EMPLOYEE", EMPLOYEE_ID)).thenReturn(owner);
        when(bankaCoreClient.getUserByEmail(EMPLOYEE_EMAIL)).thenReturn(owner);
        // AGENT autoritet → prolazi ensureTradingAccess (zaposleni); actuary tip iz ActuaryInfo daje limit granu.
        when(permissionResolver.resolvePermissions(EMPLOYEE_EMAIL)).thenReturn(List.of("AGENT"));

        assertThat(orderRepository.count()).isZero();

        scheduler.runCycle();

        // KLJUC: pravi Order perzistiran u PENDING (aktuar prelazi limit).
        List<Order> orders = orderRepository.findAll();
        assertThat(orders).hasSize(1);
        Order created = orders.get(0);
        assertThat(created.getOrderType()).isEqualTo(OrderType.MARKET);
        assertThat(created.getDirection()).isEqualTo(OrderDirection.BUY);
        assertThat(created.getQuantity()).isEqualTo(4);
        assertThat(created.getUserId()).isEqualTo(EMPLOYEE_ID);
        assertThat(created.getUserRole()).isEqualTo("EMPLOYEE");
        assertThat(created.getStatus())
                .as("Sc53: aktuar prelazi dnevni limit → order ceka odobrenje (PENDING, ne APPROVED)")
                .isEqualTo(OrderStatus.PENDING);

        // usedLimit NETAKNUT — inkrement se dešava SAMO na APPROVED (PENDING ne troši limit).
        ActuaryInfo reloadedActuary = actuaryInfoRepository.findByEmployeeId(EMPLOYEE_ID).orElseThrow();
        assertThat(reloadedActuary.getUsedLimit())
                .as("PENDING ne troši usedLimit (inkrement tek na supervizorovo odobravanje)")
                .isEqualByComparingTo(new BigDecimal("900.0000"));

        // nextRun pomeren — nalog nije zaglavljen.
        RecurringOrder reloaded = recurringOrderRepository.findById(ro.getId()).orElseThrow();
        assertThat(reloaded.getNextRun()).isAfter(due);
        // SecurityContext ociscen posle ciklusa.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
