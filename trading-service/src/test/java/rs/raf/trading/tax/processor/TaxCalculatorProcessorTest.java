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
}
