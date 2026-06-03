package rs.raf.trading.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N2 (money): provizija se racuna JEDNOM po celom nalogu (§308/§322) i pro-rata
 * raspodeli po fill-u. Suma provizija svih partial fill-ova MORA biti jednaka
 * provizioni celog naloga (rezervacioni cap), nikad veca.
 *
 * <p>Pre N2 fix-a {@code SingleOrderExecutor} je racunao {@code min(14%×fillPrice, $7)}
 * PO SVAKOM fill-u — kod ordera koji se popuni kroz vise partial fill-ova, Σ provizija
 * je premasivala cap CELOG naloga (rezervacija drzi cap po celom nalogu) → BUY commit >
 * rezervacija (zaglavljen order / vecni retry) ili SELL preplata banke.
 *
 * <p>{@code execute} bira random fillQuantity (1..remaining); ovde mutiramo order in-place
 * kroz vise poziva dok ne postane {@code done}, sabirajuci proviziju svakog fill-a. Tako je
 * test deterministican BEZ obzira na random split. {@link RepeatedTest} pokriva razne splitove.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SingleOrderExecutorCommissionTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private rs.raf.trading.portfolio.repository.PortfolioRepository portfolioRepository;
    @Mock private AonValidationService aonValidationService;
    @Mock private FundReservationService fundReservationService;
    @Mock private FundLiquidationService fundLiquidationService;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private NotificationService notificationService;
    @Mock private MarginOrderSettlementService marginOrderSettlementService;
    @Mock private io.micrometer.core.instrument.Counter ordersExecutedCounter;

    @InjectMocks
    private SingleOrderExecutor executor;

    private Listing listing;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(executor, "afterHoursDelaySeconds", 0L);
        ReflectionTestUtils.setField(executor, "maxFillIntervalSeconds", 0L);

        listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setName("Apple");
        listing.setAsk(new BigDecimal("10.00"));
        listing.setBid(new BigDecimal("10.00"));
        listing.setVolume(1_000_000L); // visok volume → mali/nula fill interval (brzo dovrsi)
        listing.setListingType(ListingType.STOCK);

        when(listingRepository.findById(1L)).thenReturn(Optional.of(listing));
        when(aonValidationService.checkCanExecuteAon(any(), anyInt())).thenReturn(true);
    }

    private Order clientBuyMarket(int qty, BigDecimal orderCommission) {
        Order o = new Order();
        o.setId(100L);
        o.setUserId(1L);
        o.setUserRole("CLIENT");
        o.setListing(listing);
        o.setAccountId(1L);
        o.setReservedAccountId(1L);
        o.setBankaCoreReservationId("res-1");
        o.setQuantity(qty);
        o.setRemainingPortions(qty);
        o.setContractSize(1);
        o.setOrderType(OrderType.MARKET);
        o.setDirection(OrderDirection.BUY);
        o.setStatus(OrderStatus.APPROVED);
        o.setExchangeRate(BigDecimal.ONE); // single-currency: commission listing == account
        o.setOrderCommission(orderCommission);
        // P2-concurrency-locks-1 (R6-1998): execute() re-fetch-uje order pod lockom i radi
        // nad svezim managed entitetom. Vrati ISTI mutirajuci objekat (re-fetch kroz
        // multi-fill petlju vidi sveze remainingPortions jer je ista referenca).
        when(orderRepository.findByIdForUpdate(o.getId())).thenReturn(Optional.of(o));
        return o;
    }

    /**
     * Drive-uje order kroz vise partial fill-ova (random split) dok ne postane done,
     * sabirajuci proviziju (commissionForFill) svakog BUY fill-a iz consumeForBuyFill.
     * Vraca ukupnu sumu provizija svih fill-ova (u valuti racuna == listing valuti ovde).
     */
    private BigDecimal sumCommissionOverAllFills(Order order, int maxIterations) {
        BigDecimal sum = BigDecimal.ZERO;
        int iter = 0;
        while (!order.isDone() && iter++ < maxIterations) {
            int remainingBefore = order.getRemainingPortions();
            executor.execute(order);
            if (order.getRemainingPortions() == remainingBefore && !order.isDone()) {
                // Ne bi se desilo (volume visok), ali izbegni beskonacnu petlju.
                break;
            }
            ArgumentCaptor<BigDecimal> commCap = ArgumentCaptor.forClass(BigDecimal.class);
            verify(fundReservationService, atLeastOnce()).consumeForBuyFill(
                    any(Order.class), anyInt(), any(BigDecimal.class), commCap.capture());
            // Poslednja zabelezena vrednost je provizija upravo izvrsenog fill-a.
            sum = sum.add(commCap.getValue());
            org.mockito.Mockito.clearInvocations(fundReservationService);
        }
        return sum;
    }

    @RepeatedTest(20)
    @DisplayName("N2: BUY MARKET cap ($7) — Σ provizija svih partial fill-ova == provizija celog naloga, ne premasuje")
    void buyMarket_cappedCommission_sumOfFillsEqualsWholeOrder() {
        // approxPrice = 10 × 10 × 1 = 100; commission = min(14%×100, $7) = $7 (capped).
        BigDecimal wholeOrderCommission = new BigDecimal("7.0000");
        Order order = clientBuyMarket(10, wholeOrderCommission);

        BigDecimal sum = sumCommissionOverAllFills(order, 50);

        assertThat(order.isDone()).isTrue();
        // KLJUC: Σ == provizija celog naloga (rezervacioni cap), ne premasuje.
        assertThat(sum).isEqualByComparingTo(wholeOrderCommission);
    }

    @RepeatedTest(20)
    @DisplayName("N2: BUY MARKET ne-capped (14%) — Σ provizija fill-ova == provizija celog naloga")
    void buyMarket_percentCommission_sumOfFillsEqualsWholeOrder() {
        // approxPrice = 10 × 4 × 1 = 40; commission = min(14%×40=5.6, $7) = 5.6.
        BigDecimal wholeOrderCommission = new BigDecimal("5.6000");
        Order order = clientBuyMarket(4, wholeOrderCommission);

        BigDecimal sum = sumCommissionOverAllFills(order, 50);

        assertThat(order.isDone()).isTrue();
        assertThat(sum).isEqualByComparingTo(wholeOrderCommission);
    }

    @Test
    @DisplayName("N2: per-fill provizija NIKAD ne premasuje provizioni cap celog naloga (kumulativna invarijanta)")
    void perFillCommission_neverExceedsWholeOrderCap() {
        BigDecimal wholeOrderCommission = new BigDecimal("7.0000");
        Order order = clientBuyMarket(10, wholeOrderCommission);

        BigDecimal cumulative = BigDecimal.ZERO;
        int iter = 0;
        while (!order.isDone() && iter++ < 50) {
            executor.execute(order);
            ArgumentCaptor<BigDecimal> commCap = ArgumentCaptor.forClass(BigDecimal.class);
            verify(fundReservationService, atLeastOnce()).consumeForBuyFill(
                    any(Order.class), anyInt(), any(BigDecimal.class), commCap.capture());
            cumulative = cumulative.add(commCap.getValue());
            // INVARIJANTA: kumulativna provizija nikad ne pretekne provizioni cap celog naloga.
            assertThat(cumulative.setScale(4, RoundingMode.HALF_UP))
                    .isLessThanOrEqualTo(wholeOrderCommission);
            org.mockito.Mockito.clearInvocations(fundReservationService);
        }
        assertThat(order.isDone()).isTrue();
        assertThat(cumulative).isEqualByComparingTo(wholeOrderCommission);
    }

    @Test
    @DisplayName("N2: legacy order bez orderCommission (null) → fallback na per-fill formulu (backward-compat, bez NPE)")
    void legacyOrderWithoutOrderCommission_usesFallback() {
        // orderCommission == null → fallback calculateCommission(totalPriceFill).
        Order order = clientBuyMarket(10, null);

        // Ne sme da pukne (NPE) — fill prolazi kroz fallback i order se dovrsi.
        // (sumCommissionOverAllFills interno verifikuje consumeForBuyFill na svakom fill-u.)
        BigDecimal sum = sumCommissionOverAllFills(order, 50);

        assertThat(order.isDone()).isTrue();
        // Fallback (per-fill min(14%×fillPrice,$7)) je pozvan bar jednom → Σ > 0.
        assertThat(sum).isGreaterThan(BigDecimal.ZERO);
    }
}
