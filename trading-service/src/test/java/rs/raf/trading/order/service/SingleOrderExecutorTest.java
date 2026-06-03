package rs.raf.trading.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.investmentfund.service.FundLiquidationService;
import rs.raf.trading.margin.service.MarginOrderSettlementService;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
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
 * P2-3/P2-4: Unit testovi za {@link SingleOrderExecutor} — per-order fill flow
 * (REQUIRES_NEW tx). Ovo je novi dom za fill-flow asercije koje su ranije bile u
 * {@code OrderExecutionServiceTest} (pre nego sto je per-order logika izdvojena u
 * zaseban bean radi tx izolacije). Asercija novcane noge cilja banka-core seam:
 * BUY fill -&gt; {@code FundReservationService.consumeForBuyFill}, SELL prihod -&gt;
 * {@code BankaCoreClient.creditFunds}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SingleOrderExecutorTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private AonValidationService aonValidationService;
    @Mock private FundReservationService fundReservationService;
    @Mock private FundLiquidationService fundLiquidationService;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private rs.raf.trading.notification.service.NotificationService notificationService;
    @Mock private MarginOrderSettlementService marginOrderSettlementService;
    @Mock private io.micrometer.core.instrument.Counter ordersExecutedCounter;

    @InjectMocks
    private SingleOrderExecutor singleOrderExecutor;

    private Order testOrder;
    private Listing testListing;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(singleOrderExecutor, "afterHoursDelaySeconds", 0L);
        ReflectionTestUtils.setField(singleOrderExecutor, "maxFillIntervalSeconds", 600L);

        testListing = new Listing();
        testListing.setId(1L);
        testListing.setTicker("AAPL");
        testListing.setName("Apple Inc");
        testListing.setAsk(new BigDecimal("100.00"));
        testListing.setBid(new BigDecimal("95.00"));
        testListing.setVolume(10000L);
        testListing.setListingType(ListingType.STOCK);

        testOrder = new Order();
        testOrder.setId(100L);
        testOrder.setUserId(1L);
        testOrder.setListing(testListing);
        testOrder.setAccountId(1L);
        testOrder.setReservedAccountId(1L);
        testOrder.setQuantity(10);
        testOrder.setRemainingPortions(10);
        testOrder.setContractSize(1);
        testOrder.setUserRole("CLIENT");
        testOrder.setStatus(OrderStatus.APPROVED);

        // P2-concurrency-locks-1 (R6-1998): execute() sada re-fetch-uje order pod
        // pessimistic lockom (findByIdForUpdate) i radi nad svezim managed entitetom.
        // Default stub vraca BAS testOrder (managed == test objekat) tako da postojece
        // asercije nad testOrder-om i dalje vaze. Pojedinacni testovi mogu da override-uju.
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(testOrder));
    }

    @Test
    @DisplayName("1. Market Buy - uspesno izvrsavanje preko banka-core commit seam-a")
    void testExecuteMarketBuy() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        singleOrderExecutor.execute(testOrder);

        verify(fundReservationService, atLeastOnce())
                .consumeForBuyFill(eq(testOrder), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
        verify(orderRepository, atLeastOnce()).save(testOrder);
    }

    @Test
    @DisplayName("2. Limit Sell - ne izvrsava se ako je cena na berzi preniska")
    void testLimitSell_PriceTooLow() {
        testOrder.setOrderType(OrderType.LIMIT);
        testOrder.setDirection(OrderDirection.SELL);
        testOrder.setLimitValue(new BigDecimal("110.00")); // Zelim 110, a Bid je 95

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));

        singleOrderExecutor.execute(testOrder);

        assertFalse(testOrder.isDone());
        verify(bankaCoreClient, never()).creditFunds(anyString(), any());
        verify(fundReservationService, never()).consumeForSellFill(any(), any(), anyInt());
    }

    @Test
    @DisplayName("3. All-Or-None (AON) - ne izvrsava se ako nije pun fill")
    void testAonOrder_NoPartialFill() {
        testOrder.setAllOrNone(true);
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(eq(testOrder), anyInt())).thenReturn(false);

        singleOrderExecutor.execute(testOrder);

        assertEquals(10, testOrder.getRemainingPortions());
        assertFalse(testOrder.isDone());
    }

    /**
     * Sc60 (Celina 3, TestoviCelina3 §60): "AON order - ne izvrsava se bez pune
     * dostupne kolicine". Given aktuar kreira AON BUY za 20; And dostupno 15; When
     * sistem pokusa da izvrsi; Then order se NE izvrsava; And order ostaje u "Pending"
     * statusu dok se ne skupi puna kolicina.
     *
     * <p>PRIHVACENA DEVIACIJA (CLAUDE.md AON): bez orderbook-a, fill je random-
     * simulacija; AON-waiting order ostaje {@code APPROVED} (a NE prebacen u
     * {@code PENDING}) jer order engine ({@code OrderExecutionService}/{@code
     * SingleOrderExecutor}) RETRY-uje iskljucivo {@code APPROVED} ordere — prebacivanje
     * u {@code PENDING} bi ga trajno izbacilo iz ciklusa i prekrsilo bas spec-zahtev
     * "dok se ne skupi puna kolicina". {@code APPROVED}-waiting je dakle funkcionalni
     * ekvivalent spec "Pending"-a (order CEKA, nije izvrsen, nije terminalan). Ovaj
     * test EKSPLICITNO pinuje to ponasanje: posle neuspelog AON tick-a order nije
     * fill-ovan (remaining nepromenjen, NE done), status je i dalje izvrsiv (APPROVED,
     * NE DECLINED) → sledeci ciklus ce pokusati ponovo.
     */
    @Test
    @DisplayName("Sc60: AON bez pune kolicine -> ostaje u CEKANJU (APPROVED-waiting ≡ spec 'Pending'), bez fill-a, retry-abilan")
    void aonInsufficientQuantity_remainsWaitingNotExecuted_Sc60() {
        testOrder.setAllOrNone(true);
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testOrder.setQuantity(20);
        testOrder.setRemainingPortions(20);
        testOrder.setStatus(OrderStatus.APPROVED);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        // Dostupno (15) < traziti (20) -> AON ne moze da se izvrsi.
        when(aonValidationService.checkCanExecuteAon(eq(testOrder), anyInt())).thenReturn(false);

        singleOrderExecutor.execute(testOrder);

        // Then: order se NE izvrsava — nista fill-ovano, remaining nepromenjen.
        assertEquals(20, testOrder.getRemainingPortions());
        assertFalse(testOrder.isDone());
        verify(fundReservationService, never())
                .consumeForBuyFill(any(), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
        // And: order ostaje u CEKANJU — status je i dalje izvrsiv (APPROVED-waiting,
        // NIJE DONE ni DECLINED) pa ga sledeci ciklus retry-uje "dok se ne skupi puna kolicina".
        assertEquals(OrderStatus.APPROVED, testOrder.getStatus());
        assertNotEquals(OrderStatus.DONE, testOrder.getStatus());
        assertNotEquals(OrderStatus.DECLINED, testOrder.getStatus());
    }

    @Test
    @DisplayName("4. Portfolio Update - provera kreiranja novog portfolija na prvom BUY")
    void testPortfolioCreationOnFirstBuy() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(portfolioRepository.findByUserIdAndUserRole(any(), any())).thenReturn(List.of()); // Prazan portfolio
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        singleOrderExecutor.execute(testOrder);

        verify(portfolioRepository, atLeastOnce()).save(any(Portfolio.class));
    }

    @Test
    @DisplayName("5. Employee Role - BUY fill provizija je nula")
    void testCommissionForEmployeeIsZero() {
        testOrder.setUserRole("EMPLOYEE");
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testOrder.setAllOrNone(true); // deterministican fill = 10

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        singleOrderExecutor.execute(testOrder);

        var commCap = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        verify(fundReservationService).consumeForBuyFill(eq(testOrder), eq(10),
                any(BigDecimal.class), commCap.capture());
        assertEquals(0, commCap.getValue().compareTo(BigDecimal.ZERO));
    }

    // ── P2-4: margin SELL/BUY na BLOCKED racunu se declime (bez retry-a) ─────

    @Test
    @DisplayName("P2-4: margin SELL na BLOCKED racunu -> order DECLINED + done (bez settle)")
    void marginSellOnBlockedAccount_declinesOrder() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.SELL);
        testOrder.setMargin(true);
        testOrder.setReservationReleased(true); // izbegni portfolio release lookup u testu

        when(marginOrderSettlementService.isMarginAccountBlocked(testOrder)).thenReturn(true);

        singleOrderExecutor.execute(testOrder);

        // Order je declime-ovan + done, bez settleMarginSellFill / fill seam poziva.
        assertEquals(OrderStatus.DECLINED, testOrder.getStatus());
        assertTrue(testOrder.isDone());
        verify(marginOrderSettlementService, never())
                .settleMarginSellFill(any(), anyInt(), any(BigDecimal.class));
        verify(fundReservationService, never()).consumeForSellFill(any(), any(), anyInt());
        verify(orderRepository).save(testOrder);
        verify(notificationService).notify(eq(1L), eq("CLIENT"), any(), anyString(), anyString(),
                eq("ORDER"), eq(100L));
    }

    @Test
    @DisplayName("P2-4: margin BUY na BLOCKED racunu -> order DECLINED + done (bez settle)")
    void marginBuyOnBlockedAccount_declinesOrder() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testOrder.setMargin(true);
        testOrder.setReservationReleased(true);

        when(marginOrderSettlementService.isMarginAccountBlocked(testOrder)).thenReturn(true);

        singleOrderExecutor.execute(testOrder);

        assertEquals(OrderStatus.DECLINED, testOrder.getStatus());
        assertTrue(testOrder.isDone());
        verify(marginOrderSettlementService, never())
                .settleMarginBuyFill(any(), anyInt(), anyInt(), any(BigDecimal.class));
        verify(fundReservationService, never())
                .consumeForBuyFill(any(), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    @DisplayName("P2-4: margin order na ACTIVE racunu se normalno izvrsava (regression)")
    void marginBuyOnActiveAccount_settlesNormally() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testOrder.setMargin(true);
        testOrder.setApproximatePrice(new BigDecimal("1000"));

        when(marginOrderSettlementService.isMarginAccountBlocked(testOrder)).thenReturn(false);
        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        singleOrderExecutor.execute(testOrder);

        // settleMarginBuyFill je pozvan (margin settle seam), order NIJE declime-ovan.
        verify(marginOrderSettlementService, atLeastOnce())
                .settleMarginBuyFill(eq(testOrder), anyInt(), anyInt(), any(BigDecimal.class));
        assertNotEquals(OrderStatus.DECLINED, testOrder.getStatus());
    }

    // ── settlement-date auto-decline (preneto iz orkestratora) ──────────────

    // ── P1-dividends-order-1 (1545): null/zero ask-bid guard u fill putanji ──

    @Test
    @DisplayName("1545: MARKET BUY sa null ask -> nema fill (NPE/STUCK) i nema commit-a")
    void marketBuy_nullAsk_skipsFillNoCommit() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testListing.setAsk(null); // listing nije refreshovan / opcija bez upstream-a

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        // Pre fix-a: NPE na listing.getAsk().multiply(...) -> order STUCK + retry zauvek.
        singleOrderExecutor.execute(testOrder);

        // Posle fix-a: cisto preskoci (nema commit-a, order ostaje za naredni tick kad cena bude validna).
        verify(fundReservationService, never())
                .consumeForBuyFill(any(), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
        assertEquals(10, testOrder.getRemainingPortions());
        assertFalse(testOrder.isDone());
    }

    @Test
    @DisplayName("1545: MARKET BUY sa ask=0 -> nema fill (besplatna kupovina, krsi konzervaciju)")
    void marketBuy_zeroAsk_skipsFillNoFreeBuy() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testListing.setAsk(BigDecimal.ZERO);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        singleOrderExecutor.execute(testOrder);

        // Cena 0 = besplatna kupovina; mora se preskociti, nikad ne sme commit-ovati fill po 0.
        verify(fundReservationService, never())
                .consumeForBuyFill(any(), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
        verify(portfolioRepository, never()).save(any(Portfolio.class));
        assertEquals(10, testOrder.getRemainingPortions());
    }

    @Test
    @DisplayName("1545: MARKET SELL sa null bid -> nema fill, nema creditFunds")
    void marketSell_nullBid_skipsFillNoCredit() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.SELL);
        testOrder.setReservationReleased(true); // izbegni portfolio release lookup
        testListing.setBid(null);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));

        singleOrderExecutor.execute(testOrder);

        verify(bankaCoreClient, never()).creditFunds(anyString(), any());
        verify(fundReservationService, never()).consumeForSellFill(any(), any(), anyInt());
        assertEquals(10, testOrder.getRemainingPortions());
    }

    // ── P1-dividends-order-1 (1320): AON parcijalni cancel over-fill ──────────

    @Test
    @DisplayName("1320: AON posle parcijalnog cancel-a (remaining<quantity) NE over-fill-uje na getQuantity()")
    void aon_afterPartialCancel_fillsRemainingNotFullQuantity() {
        // AON order originalno qty=10, ali je supervizor parcijalno skratio na
        // remaining=6 (quantity ostaje 10 jer setRemainingPortions ne menja quantity).
        // Pre fix-a: fillQuantity = order.getQuantity() = 10 -> over-fill (remaining ide na -4,
        //   portfolio dobija 10 umesto 6, rezervacija po 6 a kupovina po 10 -> money break).
        // Posle fix-a: fillQuantity = remaining = 6.
        testOrder.setAllOrNone(true);
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testOrder.setQuantity(10);
        testOrder.setRemainingPortions(6); // posle parcijalnog cancel-a

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        singleOrderExecutor.execute(testOrder);

        // Fill je tacno preostalih 6 -> order DONE (remaining 0), bez over-fill-a.
        var qtyCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(fundReservationService).consumeForBuyFill(eq(testOrder), qtyCaptor.capture(),
                any(BigDecimal.class), any(BigDecimal.class));
        assertEquals(6, qtyCaptor.getValue());
        assertEquals(0, testOrder.getRemainingPortions());
        assertTrue(testOrder.isDone());
    }

    // ── P2-concurrency-locks-1 (R6-1998): re-fetch + status guard pod lockom ──

    @Test
    @DisplayName("R6-1998: order declime-ovan izmedju load-a i tick-a (re-fetch=DECLINED) -> NEMA fill-a, NEMA rezervacije")
    void declineVsFill_refetchedDeclined_skipsTick() {
        // Scheduler je ucitao APPROVED snapshot, ali je supervizor u medjuvremenu
        // declime-ovao order (oslobodio rezervaciju). Re-fetch pod lockom vraca
        // DECLINED red -> tick mora da preskoci (bez prepisa DECLINED->DONE, bez
        // ponovne rezervacije/naplate).
        Order stale = new Order();
        stale.setId(100L);
        stale.setStatus(OrderStatus.APPROVED); // detached snapshot jos kaze APPROVED

        Order locked = new Order();
        locked.setId(100L);
        locked.setStatus(OrderStatus.DECLINED); // svez red pod lockom
        locked.setDone(true);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(locked));

        singleOrderExecutor.execute(stale);

        // Nikakav fill / commit / save / notify — tick je odbacen pre obrade.
        verify(listingRepository, never()).findById(any());
        verify(fundReservationService, never())
                .consumeForBuyFill(any(), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
        verify(bankaCoreClient, never()).creditFunds(anyString(), any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("R6-1998: order vec DONE pod lockom (drugi tick ga je fill-ovao) -> NEMA double-fill-a")
    void alreadyDone_refetchedDone_skipsTick() {
        Order stale = new Order();
        stale.setId(100L);
        stale.setStatus(OrderStatus.APPROVED);

        Order locked = new Order();
        locked.setId(100L);
        locked.setStatus(OrderStatus.APPROVED);
        locked.setDone(true); // drugi tick ga je vec zavrsio
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(locked));

        singleOrderExecutor.execute(stale);

        verify(fundReservationService, never())
                .consumeForBuyFill(any(), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("R6-1998: order nestao pod lockom (re-fetch empty) -> cist no-op")
    void orderVanished_refetchEmpty_skipsTick() {
        Order stale = new Order();
        stale.setId(100L);
        stale.setStatus(OrderStatus.APPROVED);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.empty());

        singleOrderExecutor.execute(stale);

        verify(listingRepository, never()).findById(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("settlement-date prosao -> order DECLINED + done + release")
    void settlementDatePassed_declinesOrder() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testListing.setSettlementDate(java.time.LocalDate.now().minusDays(1));
        testOrder.setReservationReleased(true);

        singleOrderExecutor.execute(testOrder);

        assertEquals(OrderStatus.DECLINED, testOrder.getStatus());
        assertTrue(testOrder.isDone());
        verify(orderRepository).save(testOrder);
        // Nije ni dosao do fill-a.
        verify(fundReservationService, never())
                .consumeForBuyFill(any(), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
    }

    // ── OT-1034: zatvorena/after-hours berza +30min sporiji fill ─────────────
    //
    // §404 / R1-190: kad je order afterHours=true (berza zatvorena ILI u
    // after-hours prozoru pri kreiranju), svaki interval izmedju fill-ova dobija
    // dodatni `afterHoursDelaySeconds` bonus (spec: 30 min). Ovo je per-order
    // "slow-fill" — postojeci testovi su afterHoursDelaySeconds postavili na 0
    // (deterministicnost), pa +30min bonus grana NIJE bila pinned.
    @Test
    @DisplayName("OT-1034: afterHours=true order -> computeNextFillDelay dodaje +1800s (30min) bonus")
    void computeNextFillDelay_afterHours_adds30MinBonus() {
        // 1800s bonus (spec 30min) — override setUp-ovog 0L.
        ReflectionTestUtils.setField(singleOrderExecutor, "afterHoursDelaySeconds", 1800L);

        Order order = new Order();
        order.setQuantity(10);
        order.setRemainingPortions(10);
        order.setAfterHours(true);

        Listing listing = new Listing();
        // volume=0 → spec interval fallback je maxFillIntervalSeconds (600), pa je
        // ukupno = 600 + 1800 = 2400 (deterministicno, bez random komponente).
        listing.setVolume(0L);

        long delay = singleOrderExecutor.computeNextFillDelay(order, listing);

        // maxFillIntervalSeconds (600) + afterHoursBonus (1800) = 2400.
        assertEquals(2400L, delay);
    }

    @Test
    @DisplayName("OT-1034: afterHours=false order -> NEMA +1800s bonusa (kontrolni slucaj)")
    void computeNextFillDelay_notAfterHours_noBonus() {
        ReflectionTestUtils.setField(singleOrderExecutor, "afterHoursDelaySeconds", 1800L);

        Order order = new Order();
        order.setQuantity(10);
        order.setRemainingPortions(10);
        order.setAfterHours(false);

        Listing listing = new Listing();
        listing.setVolume(0L); // fallback = maxFillIntervalSeconds (600), bez bonusa

        long delay = singleOrderExecutor.computeNextFillDelay(order, listing);

        // Samo maxFillIntervalSeconds (600), bez after-hours bonusa.
        assertEquals(600L, delay);
    }

    // ════════════════════════════════════════════════════════════════════
    //  C-notif-email (02.06) — Sc23/Sc24: EMAIL na izvrsenje + anti-flood
    //  invarijanta (no-op tick → 0 notifikacija; DONE → tacno 1 ORDER_EXECUTED;
    //  stvarni partial → tacno 1 ORDER_PARTIAL_FILL sa "Izvrseno/Preostalo").
    //  NotificationType.ORDER_EXECUTED/PARTIAL_FILL sad imaju sendsEmail=true
    //  (NotificationServiceImpl onda okine email kanal) — ali kljuc je da se
    //  notify() pozove TACNO JEDNOM po smislenom eventu, NE po no-op tick-u.
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Sc23: pun fill (DONE) -> TACNO 1 ORDER_EXECUTED notif, NIJEDAN partial")
    void fullFill_emitsExactlyOneExecutedNotification_Sc23() {
        // qty=remaining=1 -> nextInt(1,2)=1 -> fill==remaining -> DONE (deterministicno).
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testOrder.setQuantity(1);
        testOrder.setRemainingPortions(1);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        singleOrderExecutor.execute(testOrder);

        assertTrue(testOrder.isDone());
        assertEquals(OrderStatus.DONE, testOrder.getStatus());
        // TACNO jedan ORDER_EXECUTED (terminalni event), bez per-tick flood-a.
        verify(notificationService, times(1)).notify(eq(1L), eq("CLIENT"),
                eq(rs.raf.trading.notification.model.NotificationType.ORDER_EXECUTED),
                anyString(), anyString(), eq("ORDER"), eq(100L));
        // NIJEDAN partial-fill notif na punom fill-u.
        verify(notificationService, never()).notify(any(), any(),
                eq(rs.raf.trading.notification.model.NotificationType.ORDER_PARTIAL_FILL),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Sc24: stvarni partial fill -> TACNO 1 ORDER_PARTIAL_FILL sa 'Izvrseno: Y / Preostalo: X'")
    void partialFill_emitsOnePartialNotificationWithExecutedAndRemaining_Sc24() {
        // qty=10, remaining=10. Fill je random 1..10. Ovaj test je ROBUSTAN na random:
        // grana se po stvarnom ishodu. U OBA slucaja se notify() poziva TACNO JEDNOM
        // (nema flood-a) i poruka tacno odrazava izvrseno/preostalo.
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        testOrder.setQuantity(10);
        testOrder.setRemainingPortions(10);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);

        singleOrderExecutor.execute(testOrder);

        org.mockito.ArgumentCaptor<rs.raf.trading.notification.model.NotificationType> typeCaptor =
                org.mockito.ArgumentCaptor.forClass(rs.raf.trading.notification.model.NotificationType.class);
        org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        // Tacno JEDAN notify poziv po execute()-u (smisleni event, bez flood-a).
        verify(notificationService, times(1)).notify(eq(1L), eq("CLIENT"),
                typeCaptor.capture(), anyString(), bodyCaptor.capture(), eq("ORDER"), eq(100L));

        int executed = 10 - testOrder.getRemainingPortions();
        if (testOrder.isDone()) {
            // Random je popunio svih 10 -> terminalni ORDER_EXECUTED.
            assertEquals(rs.raf.trading.notification.model.NotificationType.ORDER_EXECUTED,
                    typeCaptor.getValue());
            assertEquals(10, executed);
        } else {
            // Stvarni parcijalni fill -> ORDER_PARTIAL_FILL sa Izvrseno/Preostalo.
            assertEquals(rs.raf.trading.notification.model.NotificationType.ORDER_PARTIAL_FILL,
                    typeCaptor.getValue());
            assertTrue(executed > 0 && executed < 10);
            String body = bodyCaptor.getValue();
            // Sc24: telo MORA navesti i izvrseno i preostalo.
            assertTrue(body.contains("Izvršeno: " + executed),
                    "Telo treba da sadrzi 'Izvršeno: " + executed + "', a bilo je: " + body);
            assertTrue(body.contains("Preostalo: " + testOrder.getRemainingPortions()),
                    "Telo treba da sadrzi 'Preostalo: " + testOrder.getRemainingPortions() + "', a bilo je: " + body);
        }
    }

    @Test
    @DisplayName("Anti-flood: no-op tick (LIMIT cena previsoka, bez fill-a) -> 0 notifikacija")
    void noFillTick_emitsZeroNotifications_antiFlood() {
        // LIMIT BUY sa limitom ISPOD trzisne ask cene -> cena previsoka -> return PRE
        // bilo kakvog fill-a/notifikacije. Ovo je "tick" iz reviewer zahteva: 0 emailova.
        testOrder.setOrderType(OrderType.LIMIT);
        testOrder.setDirection(OrderDirection.BUY);
        testOrder.setQuantity(10);
        testOrder.setRemainingPortions(10);
        testOrder.setLimitValue(new BigDecimal("50.00")); // < ask 100 -> ne fill-uje

        when(listingRepository.findById(1L)).thenReturn(Optional.of(testListing));

        singleOrderExecutor.execute(testOrder);

        // Nijedan fill, order ostaje APPROVED, NIJEDNA notifikacija (anti-flood).
        assertFalse(testOrder.isDone());
        assertEquals(10, testOrder.getRemainingPortions());
        verify(notificationService, never()).notify(any(), any(), any(),
                anyString(), anyString(), any(), any());
        verify(fundReservationService, never())
                .consumeForBuyFill(any(), anyInt(), any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    @DisplayName("Sc25: auto-otkaz (settlement prosao) -> TACNO 1 ORDER_CANCELLED notif")
    void autoCancelOnExpiredSettlement_emitsOneCancelledNotification_Sc25() {
        testOrder.setOrderType(OrderType.MARKET);
        testOrder.setDirection(OrderDirection.BUY);
        // Settlement u proslosti -> auto-decline + ORDER_CANCELLED + release.
        testListing.setSettlementDate(java.time.LocalDate.now().minusDays(1));

        singleOrderExecutor.execute(testOrder);

        assertEquals(OrderStatus.DECLINED, testOrder.getStatus());
        assertTrue(testOrder.isDone());
        verify(notificationService, times(1)).notify(eq(1L), eq("CLIENT"),
                eq(rs.raf.trading.notification.model.NotificationType.ORDER_CANCELLED),
                anyString(), anyString(), eq("ORDER"), eq(100L));
    }
}
