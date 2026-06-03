package rs.raf.trading.tax.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2.contracts.internal.TaxCollectRequest;
import rs.raf.banka2.contracts.internal.TaxCollectResponse;
import rs.raf.trading.tax.util.TaxRealizedGainCalculator;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.tax.model.TaxRecord;
import rs.raf.trading.tax.repository.TaxRecordBreakdownRepository;
import rs.raf.trading.tax.repository.TaxRecordRepository;
import rs.raf.trading.tax.service.TaxCalculationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-PAY-04: Unit tests za {@link TaxCalculatorProcessor} — paritet sa
 * {@code InstallmentProcessorTest} (BE-PAY-02) i {@code VariableRateProcessorTest}
 * (BE-PAY-03). Pokriva per-user obracun u izolaciji od orkestratora.
 *
 * <p><b>Kljucni test za partial-persist:</b>
 * {@link #processOnePersistsRecordIndependentlyEvenIfOtherUsersFail} simulira
 * 5 korisnika gde 3. baca {@link TaxCalculationException} pri FX konverziji →
 * orkestrator (TaxService) hvata exception i nastavlja sa ostalima → svi 4 ostali
 * persistovani. Pre BE-PAY-04: outer {@code @Transactional} na
 * {@code calculateTaxForAllUsers} bi rollback-ovao SVE preceding saves.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaxCalculatorProcessor (BE-PAY-04)")
class TaxCalculatorProcessorTest {

    @Mock private TaxRecordRepository taxRecordRepository;
    @Mock private TaxRecordBreakdownRepository taxRecordBreakdownRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private BankaCoreClient bankaCoreClient;

    @InjectMocks
    private TaxCalculatorProcessor processor;

    private InternalUserDto user(Long id, String role, String firstName, String lastName) {
        return new InternalUserDto(id, role, "u" + id + "@test.com", firstName, lastName, true, null);
    }

    private Listing listing(Long id, String quoteCurrency) {
        Listing l = new Listing();
        l.setId(id);
        l.setListingType(ListingType.STOCK);
        l.setQuoteCurrency(quoteCurrency);
        return l;
    }

    private Order order(Long userId, OrderDirection dir, Listing listing, String price, int qty) {
        return order(userId, dir, listing, price, qty, null);
    }

    private Order order(Long userId, OrderDirection dir, Listing listing, String price, int qty,
                        LocalDateTime createdAt) {
        Order o = new Order();
        o.setId((long) (Math.random() * 100000));
        o.setUserId(userId);
        o.setUserRole("CLIENT");
        o.setDirection(dir);
        o.setPricePerUnit(new BigDecimal(price));
        o.setQuantity(qty);
        o.setContractSize(1);
        o.setDone(true);
        o.setStatus(OrderStatus.DONE);
        o.setListing(listing);
        o.setCreatedAt(createdAt);
        return o;
    }

    @Test
    @DisplayName("processOne persists TaxRecord for single CLIENT user with positive profit")
    void processOnePersistsTaxRecord() {
        Listing l = listing(1L, "RSD");
        List<Order> orders = List.of(
                order(1L, OrderDirection.BUY, l, "100", 1),
                order(1L, OrderDirection.SELL, l, "1100", 1)
        );
        when(bankaCoreClient.getUserById("CLIENT", 1L))
                .thenReturn(user(1L, "CLIENT", "Marko", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankaCoreClient.collectTax(any(), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("150.0000"), true));

        processor.processOne(1L, "CLIENT", orders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                LocalDateTime.now());

        ArgumentCaptor<TaxRecord> cap = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(cap.capture());
        assertThat(cap.getValue().getTotalProfit()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(cap.getValue().getTaxOwed()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(cap.getValue().getTaxPaid()).isEqualByComparingTo(new BigDecimal("150.0000"));
    }

    @Test
    @DisplayName("processOne throws TaxCalculationException when FX rate unavailable")
    void processOneThrowsOnFxFailure() {
        Listing l = listing(1L, "USD");
        List<Order> orders = List.of(
                order(1L, OrderDirection.BUY, l, "100", 1),
                order(1L, OrderDirection.SELL, l, "200", 1)
        );
        when(currencyConversionService.convert(any(), eq("USD"), eq("RSD")))
                .thenThrow(new RuntimeException("banka-core /internal/fx/rates timeout"));

        assertThatThrownBy(() -> processor.processOne(1L, "CLIENT", orders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                LocalDateTime.now()))
                .isInstanceOf(TaxCalculationException.class)
                .hasMessageContaining("FX rate unavailable for USD");

        // TaxRecord NE sme biti persistovan (exception baca pre save)
        verify(taxRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("processOne accumulates OTC contributions into per-listing sell/buy")
    void processOneIncludesOtcContributions() {
        Listing l = listing(2L, "RSD");
        // Korisnik nema "ordinary" ordere, samo OTC EXERCISED kontribucije
        List<Order> noOrders = List.of();
        Map<Long, BigDecimal> otcSell = new HashMap<>();
        Map<Long, BigDecimal> otcBuy = new HashMap<>();
        Map<Long, String> otcCurrency = new HashMap<>();
        otcSell.put(2L, new BigDecimal("5000"));
        otcBuy.put(2L, new BigDecimal("3000"));
        otcCurrency.put(2L, "RSD");

        when(bankaCoreClient.getUserById("CLIENT", 7L))
                .thenReturn(user(7L, "CLIENT", "OTC", "User"));
        when(taxRecordRepository.findByUserIdAndUserType(7L, "CLIENT")).thenReturn(Optional.empty());
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankaCoreClient.collectTax(any(), any()))
                .thenReturn(new TaxCollectResponse(7L, new BigDecimal("300.0000"), true));

        processor.processOne(7L, "CLIENT", noOrders,
                otcSell, otcBuy, otcCurrency, LocalDateTime.now());

        ArgumentCaptor<TaxRecord> cap = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(cap.capture());
        // OTC profit = 5000 - 3000 = 2000, tax = 15% = 300
        assertThat(cap.getValue().getTotalProfit()).isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(cap.getValue().getTaxOwed()).isEqualByComparingTo(new BigDecimal("300.0000"));
    }

    @Test
    @DisplayName("processOne short-circuits collectTax for EMPLOYEE (no banka-core call)")
    void processOneEmployeeShortCircuits() {
        Listing l = listing(1L, "RSD");
        List<Order> orders = List.of(
                order(5L, OrderDirection.SELL, l, "500", 1)
        );
        when(bankaCoreClient.getUserById("EMPLOYEE", 5L))
                .thenReturn(user(5L, "EMPLOYEE", "Bank", "Actuary"));
        when(taxRecordRepository.findByUserIdAndUserType(5L, "EMPLOYEE")).thenReturn(Optional.empty());
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.processOne(5L, "EMPLOYEE", orders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                LocalDateTime.now());

        // EMPLOYEE → no banka-core collectTax (zaposleni placaju interno)
        verify(bankaCoreClient, never()).collectTax(any(), any());
        // ali TaxRecord se i dalje snima (sa taxOwed = 75.0000 i taxPaid postavljen na taxOwed)
        ArgumentCaptor<TaxRecord> cap = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(cap.capture());
        assertThat(cap.getValue().getTaxOwed()).isEqualByComparingTo(new BigDecimal("75.0000"));
    }

    @Test
    @DisplayName("processOne no tax when only buy orders (no realized profit)")
    void processOneNoTaxWithoutSell() {
        Listing l = listing(1L, "RSD");
        List<Order> orders = List.of(
                order(1L, OrderDirection.BUY, l, "100", 10)  // samo BUY, nema SELL
        );
        when(bankaCoreClient.getUserById("CLIENT", 1L))
                .thenReturn(user(1L, "CLIENT", "A", "B"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.processOne(1L, "CLIENT", orders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                LocalDateTime.now());

        ArgumentCaptor<TaxRecord> cap = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(cap.capture());
        // Spec §517: porez SAMO na realizovanu dobit. Bez SELL → totalProfit = 0, taxOwed = 0
        assertThat(cap.getValue().getTotalProfit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cap.getValue().getTaxOwed()).isEqualByComparingTo(BigDecimal.ZERO);
        // collectTax se NE poziva (unpaidTax = 0)
        verify(bankaCoreClient, never()).collectTax(any(), any());
    }

    // ─── P0-B3: FIFO cost-basis lot-matching ─────────────────────────────────────

    @Test
    @DisplayName("P0-B3 FIFO: BUY 10@100, BUY 10@200, SELL 15@300 → cost-basis = 10*100 + 5*200 (FIFO), gain = 2500")
    void processOneFifoCostBasis() {
        // Spec §517-518: porez = 15% na KAPITALNU DOBIT = proceeds(prodate kolicine)
        // - cost-basis(prodate kolicine, FIFO). Stari kod je radio sum(SELL)-sum(BUY)
        //   = 4500 - 3000 = 1500 → laznja dobit (cost-basis svih 20 akcija protiv 15 prodatih).
        // FIFO: prodato 15 = 10@100 (=1000) + 5@200 (=1000) → cost 2000; proceeds 15@300=4500;
        //   realizovana dobit = 2500.
        Listing l = listing(1L, "RSD");
        LocalDateTime t0 = LocalDateTime.of(2026, 5, 10, 9, 0);
        List<Order> orders = List.of(
                order(1L, OrderDirection.BUY, l, "100", 10, t0),
                order(1L, OrderDirection.BUY, l, "200", 10, t0.plusHours(1)),
                order(1L, OrderDirection.SELL, l, "300", 15, t0.plusHours(2))
        );
        when(bankaCoreClient.getUserById("CLIENT", 1L))
                .thenReturn(user(1L, "CLIENT", "Marko", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankaCoreClient.collectTax(any(), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("375.0000"), true));

        // settlement period = mesec SELL ordera (maj 2026)
        processor.processOne(1L, "CLIENT", orders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                LocalDateTime.of(2026, 5, 31, 23, 0));

        ArgumentCaptor<TaxRecord> cap = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(cap.capture());
        assertThat(cap.getValue().getTotalProfit()).isEqualByComparingTo(new BigDecimal("2500"));
        // tax = 2500 * 0.15 = 375
        assertThat(cap.getValue().getTaxOwed()).isEqualByComparingTo(new BigDecimal("375.0000"));
    }

    @Test
    @DisplayName("P0-B3 FIFO: partial sell ne sme da nosi cost-basis nekupljenih akcija")
    void processOneFifoPartialSell() {
        // BUY 10@100 (cost 1000), SELL 5@150 (proceeds 750). FIFO cost prodate kolicine
        // = 5@100 = 500. Dobit = 250 (NE 750-1000 = -250 kao stari kod).
        Listing l = listing(1L, "RSD");
        LocalDateTime t0 = LocalDateTime.of(2026, 5, 10, 9, 0);
        List<Order> orders = List.of(
                order(1L, OrderDirection.BUY, l, "100", 10, t0),
                order(1L, OrderDirection.SELL, l, "150", 5, t0.plusHours(1))
        );
        when(bankaCoreClient.getUserById("CLIENT", 1L))
                .thenReturn(user(1L, "CLIENT", "Marko", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankaCoreClient.collectTax(any(), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("37.5000"), true));

        processor.processOne(1L, "CLIENT", orders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                LocalDateTime.of(2026, 5, 31, 23, 0));

        ArgumentCaptor<TaxRecord> cap = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(cap.capture());
        assertThat(cap.getValue().getTotalProfit()).isEqualByComparingTo(new BigDecimal("250"));
        assertThat(cap.getValue().getTaxOwed()).isEqualByComparingTo(new BigDecimal("37.5000"));
    }

    // ─── P0-B3: mesecni period (carry-forward fix) ────────────────────────────────

    @Test
    @DisplayName("P0-B3 period: dobit realizovana u proslom mesecu NE ulazi u tekuci mesecni obracun")
    void processOnePeriodWindowExcludesPriorMonth() {
        // SELL realizovan u aprilu (gain 500) NE sme da se obracuna u maju.
        // Stari kod (lifetime-kumulativan) bi ukljucio svaki SELL ikad.
        Listing l = listing(1L, "RSD");
        List<Order> orders = List.of(
                order(1L, OrderDirection.BUY, l, "100", 10, LocalDateTime.of(2026, 4, 1, 9, 0)),
                order(1L, OrderDirection.SELL, l, "150", 10, LocalDateTime.of(2026, 4, 15, 9, 0))
        );
        when(bankaCoreClient.getUserById("CLIENT", 1L))
                .thenReturn(user(1L, "CLIENT", "Marko", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // settlement period = maj 2026 (mid-mesec ručni okidač)
        processor.processOne(1L, "CLIENT", orders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                LocalDateTime.of(2026, 5, 15, 12, 0));

        ArgumentCaptor<TaxRecord> cap = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(cap.capture());
        // Nijedan SELL u maju → tekuci mesecni porez = 0 (gain je realizovan u aprilu).
        assertThat(cap.getValue().getTotalProfit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cap.getValue().getTaxOwed()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(bankaCoreClient, never()).collectTax(any(), any());
    }

    @Test
    @DisplayName("P0-B3 period: SELL u tekucem mesecu se obracunava (cost-basis iz ranijih BUY-eva)")
    void processOnePeriodWindowIncludesCurrentMonthSell() {
        // BUY u aprilu (cost-basis lot), SELL u maju → gain pripada maju.
        Listing l = listing(1L, "RSD");
        List<Order> orders = List.of(
                order(1L, OrderDirection.BUY, l, "100", 10, LocalDateTime.of(2026, 4, 1, 9, 0)),
                order(1L, OrderDirection.SELL, l, "150", 10, LocalDateTime.of(2026, 5, 10, 9, 0))
        );
        when(bankaCoreClient.getUserById("CLIENT", 1L))
                .thenReturn(user(1L, "CLIENT", "Marko", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankaCoreClient.collectTax(any(), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("75.0000"), true));

        processor.processOne(1L, "CLIENT", orders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(),
                LocalDateTime.of(2026, 5, 15, 12, 0));

        ArgumentCaptor<TaxRecord> cap = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(cap.capture());
        // proceeds 10@150 = 1500, FIFO cost 10@100 = 1000, gain = 500, tax = 75
        assertThat(cap.getValue().getTotalProfit()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(cap.getValue().getTaxOwed()).isEqualByComparingTo(new BigDecimal("75.0000"));
    }

    // ─── KLJUCNI TEST: partial-persist preko TaxService orkestratora ─────────────

    @Test
    @DisplayName("BE-PAY-04: orkestrator iterira 5 korisnika, 3. baca FX exception → 4 ostala persistovana (NE rollback)")
    void processOnePersistsRecordIndependentlyEvenIfOtherUsersFail() {
        // Simuliramo orkestraciju kroz TaxService.calculateTaxForAllUsers koja
        // delegira na processor.processOne za svakog korisnika. Pre BE-PAY-04
        // outer @Transactional bi rollback-ovao sve previously-saved records ako
        // bi neki user pao. Sada (REQUIRES_NEW + per-user try/catch) svi
        // ostali su persistovani.
        //
        // Setup: 5 CLIENT korisnika sa po jednim SELL orderom u RSD (svi profit 1000,
        // tax 150). Korisnik #3 ima USD listing i FX baca exception.

        Listing rsd = listing(1L, "RSD");
        Listing usd = listing(2L, "USD");  // user 3 trguje USD listingom

        when(currencyConversionService.convert(any(), eq("USD"), eq("RSD")))
                .thenThrow(new RuntimeException("banka-core FX timeout"));

        // Per user: stub-uj getUserById + findByUserIdAndUserType + collectTax
        for (long uid : new long[] {1L, 2L, 4L, 5L}) {
            when(bankaCoreClient.getUserById("CLIENT", uid))
                    .thenReturn(user(uid, "CLIENT", "User", "#" + uid));
            when(taxRecordRepository.findByUserIdAndUserType(uid, "CLIENT")).thenReturn(Optional.empty());
            when(bankaCoreClient.collectTax(any(), any()))
                    .thenReturn(new TaxCollectResponse(uid, new BigDecimal("150.0000"), true));
        }
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Sad simuliramo orkestraciju: zovemo processor.processOne 5 puta sa per-user
        // try/catch (kao u TaxService.calculateTaxForAllUsers refactor-u).
        int successful = 0;
        int failed = 0;
        LocalDateTime now = LocalDateTime.now();

        for (long uid : new long[] {1L, 2L, 3L, 4L, 5L}) {
            Listing userListing = (uid == 3L) ? usd : rsd;
            List<Order> orders = List.of(
                    order(uid, OrderDirection.BUY, userListing, "100", 1),
                    order(uid, OrderDirection.SELL, userListing, "1100", 1)
            );
            try {
                processor.processOne(uid, "CLIENT", orders,
                        new HashMap<>(), new HashMap<>(), new HashMap<>(), now);
                successful++;
            } catch (TaxCalculationException ex) {
                failed++;
            }
        }

        // 4 ostala korisnika uspesno → 4 TaxRecord-a snimljena
        assertThat(successful).isEqualTo(4);
        assertThat(failed).isEqualTo(1);
        verify(taxRecordRepository, times(4)).save(any(TaxRecord.class));
        // User #3 (USD FX failure) NE snima TaxRecord
        // ali ostali users su uneli njihove records — to je KLJUCNI partial-persist
    }

    // ─── B-1 REGRESIJA: cross-month under-taxation (KLJUCNI test) ─────────────────

    @Test
    @DisplayName("B-1: isti TaxRecord kroz 2 meseca — maj naplati 150, jun MORA puPnih 300 (ne 150), jun re-run jos 30")
    void crossMonthDoesNotUnderTax() {
        // B-1 BUG (pre fix-a): taxOwed je mesecni (computePeriodGains filtrira po
        // periodu) ALI previouslyPaid = record.getTaxPaid() je lifetime-kumulativan.
        // unpaidTax = mesecni_owed − lifetime_paid → mesa dimenzije:
        //   Maj: gain 1000 → owed 150, prior 0 → naplati 150 (taxPaid=150).
        //   Jun: gain 2000 → owed 300, ALI unpaidTax = 300 − 150(lifetime) = 150
        //        → drzava dobije 150 umesto 300 (manjak 150). taxPaid pregazen na 300.
        //   Jun re-run: gain +200 → owed 330, unpaidTax = 330 − 300 = 30 ... ali u
        //        cistom B-1 scenariju (owed manji od lifetime paid) jun se nikad ne naplati.
        // FIX (mesecni paid bucket): mesecni owed i mesecni paid u istoj dimenziji →
        //   Maj naplati 150, Jun naplati PUNIH 300, Jun re-run naplati 30.
        //
        // Ovaj test je sa POSTOJECIM (stateful) TaxRecord-om kroz 3 run-a (ne svez
        // Optional.empty() kao ostali testovi) — upravo to hvata cross-month bug.
        Listing l = listing(1L, "RSD");

        // Stateful record koji processor mutira; findBy... vraca isti objekat, save
        // vraca isti objekat (mutacija se zadrzava izmedju run-ova).
        TaxRecord record = TaxRecord.builder()
                .userId(1L).userType("CLIENT").currency("RSD")
                .taxPaid(BigDecimal.ZERO).taxPaidInPeriod(BigDecimal.ZERO)
                .build();
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT"))
                .thenReturn(Optional.of(record));
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankaCoreClient.getUserById("CLIENT", 1L))
                .thenReturn(user(1L, "CLIENT", "Stefan", "J"));
        // collectTax uvek uspeva i vraca trazeni iznos (collectedAmount = request amount).
        when(bankaCoreClient.collectTax(any(), any())).thenAnswer(inv -> {
            TaxCollectRequest req = inv.getArgument(1);
            return new TaxCollectResponse(1L, req.amount(), true);
        });

        LocalDateTime mayRun = LocalDateTime.of(2026, 5, 15, 12, 0);   // period = maj 2026
        LocalDateTime juneRun = LocalDateTime.of(2026, 6, 15, 12, 0);  // period = jun 2026

        // ── Maj: BUY 10@100 (maj), SELL 10@200 (maj) → maj gain = 1000 → owed 150.
        List<Order> mayOrders = new ArrayList<>(List.of(
                order(1L, OrderDirection.BUY, l, "100", 10, LocalDateTime.of(2026, 5, 1, 9, 0)),
                order(1L, OrderDirection.SELL, l, "200", 10, LocalDateTime.of(2026, 5, 10, 9, 0))
        ));
        processor.processOne(1L, "CLIENT", mayOrders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), mayRun);

        assertThat(record.getTaxOwed()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(record.getTaxPaid()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(record.getTaxPaidInPeriod()).isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(record.getTaxPaidPeriod()).isEqualTo("2026-05");

        // ── Jun run 1: dodaj BUY 10@100 (jun), SELL 10@300 (jun) → jun gain = 2000 → owed 300.
        // Maj SELL je van jun-perioda (ne broji se), maj BUY lot je vec potrosen FIFO-om.
        List<Order> juneOrders = new ArrayList<>(mayOrders);
        juneOrders.add(order(1L, OrderDirection.BUY, l, "100", 10, LocalDateTime.of(2026, 6, 1, 9, 0)));
        juneOrders.add(order(1L, OrderDirection.SELL, l, "300", 10, LocalDateTime.of(2026, 6, 10, 9, 0)));
        processor.processOne(1L, "CLIENT", juneOrders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), juneRun);

        // KLJUCNA ASERCIJA (pada na B-1 kodu): jun mesecni owed = 300, mesecni paid = 0
        // → naplaceno PUNIH 300 (ne 150). B-1 kod bi naplatio 300−150 = 150.
        assertThat(record.getTaxOwed()).isEqualByComparingTo(new BigDecimal("300.0000"));
        assertThat(record.getTaxPaidInPeriod()).isEqualByComparingTo(new BigDecimal("300.0000"));
        assertThat(record.getTaxPaidPeriod()).isEqualTo("2026-06");
        // godisnji kumulativ = 150 (maj) + 300 (jun) = 450.
        assertThat(record.getTaxPaid()).isEqualByComparingTo(new BigDecimal("450.0000"));

        // ── Jun run 2: dodaj BUY 10@100 (jun), SELL 10@120 (jun) → +200 jun gain →
        // jun owed = 330, mesecni paid 300 → naplati JOS 30 (ne preskoci).
        List<Order> juneOrders2 = new ArrayList<>(juneOrders);
        juneOrders2.add(order(1L, OrderDirection.BUY, l, "100", 10, LocalDateTime.of(2026, 6, 12, 9, 0)));
        juneOrders2.add(order(1L, OrderDirection.SELL, l, "120", 10, LocalDateTime.of(2026, 6, 13, 9, 0)));
        processor.processOne(1L, "CLIENT", juneOrders2,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), juneRun);

        assertThat(record.getTaxOwed()).isEqualByComparingTo(new BigDecimal("330.0000"));
        assertThat(record.getTaxPaidInPeriod()).isEqualByComparingTo(new BigDecimal("330.0000"));
        assertThat(record.getTaxPaid()).isEqualByComparingTo(new BigDecimal("480.0000")); // 150+300+30

        // Verifikuj per-run naplacene iznose: 150 (maj), 300 (jun r1), 30 (jun r2).
        ArgumentCaptor<TaxCollectRequest> reqCap = ArgumentCaptor.forClass(TaxCollectRequest.class);
        verify(bankaCoreClient, times(3)).collectTax(any(), reqCap.capture());
        assertThat(reqCap.getAllValues().get(0).amount()).isEqualByComparingTo("150.0000");
        assertThat(reqCap.getAllValues().get(1).amount()).isEqualByComparingTo("300.0000");
        assertThat(reqCap.getAllValues().get(2).amount()).isEqualByComparingTo("30.0000");

        // Idempotency key: maj i jun moraju imati RAZLICIT period u kljucu.
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(bankaCoreClient, times(3)).collectTax(keyCap.capture(), any());
        assertThat(keyCap.getAllValues().get(0)).contains("2026-05");
        assertThat(keyCap.getAllValues().get(1)).contains("2026-06");
    }

    @Test
    @DisplayName("B-1 null-timestamp: legacy SELL bez timestamp-a se ne re-naplacuje u cron prethodni-mesec run")
    void nullTimestampSellNotReTaxedInBackdatedCronRun() {
        // Null-timestamp SELL (legacy/test order) je in-period SAMO kad period == nowMonth
        // (tekuci-mesec run), NE u backdated cron run-u prethodnog meseca. Pre B-1
        // sprege bi "uvek in-period" + mesecni reset → re-naplata svaki mesec.
        Listing l = listing(1L, "RSD");
        // SELL bez createdAt/lastModification (null timestamp).
        List<Order> orders = List.of(
                order(1L, OrderDirection.BUY, l, "100", 10, null),
                order(1L, OrderDirection.SELL, l, "200", 10, null)
        );
        TaxRecord record = TaxRecord.builder()
                .userId(1L).userType("CLIENT").currency("RSD")
                .taxPaid(BigDecimal.ZERO).taxPaidInPeriod(BigDecimal.ZERO)
                .build();
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT"))
                .thenReturn(Optional.of(record));
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bankaCoreClient.getUserById("CLIENT", 1L))
                .thenReturn(user(1L, "CLIENT", "Legacy", "User"));

        // Cron run: 1. jun 2026 u 00:00 → settlementPeriod = MAJ (prethodni mesec),
        // nowMonth = JUN. period(maj) != nowMonth(jun) → null-timestamp SELL je
        // van-perioda → gain 0 → nema naplate.
        LocalDateTime cronRun = LocalDateTime.of(2026, 6, 1, 0, 0);
        assertThat(TaxRealizedGainCalculator.settlementPeriod(cronRun))
                .isEqualTo(YearMonth.of(2026, 5));  // sanity: cron settle-uje maj
        processor.processOne(1L, "CLIENT", orders,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), cronRun);

        assertThat(record.getTaxOwed()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(bankaCoreClient, never()).collectTax(any(), any());
    }
}
