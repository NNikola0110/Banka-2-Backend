package rs.raf.trading.tax.service;

import org.junit.jupiter.api.BeforeEach;
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
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.tax.dto.TaxRecordDto;
import rs.raf.trading.tax.model.TaxRecord;
import rs.raf.trading.tax.repository.TaxRecordBreakdownRepository;
import rs.raf.trading.tax.repository.TaxRecordRepository;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Additional coverage for TaxService targeting uncovered branches:
 * - collectTaxFromUser (CLIENT collectTax path, collected=false, EMPLOYEE short-circuit)
 * - resolveOrderCurrency fallback (null listing, blank currency, exception)
 * - convertToRsd (null amount, zero amount, conversion exception fallback)
 * - resolveUserName fallback ("Zaposleni #", "Klijent #")
 * - previouslyPaid null branch on update
 *
 * NAPOMENA (faza 2c): monolitni test je naplacivao porez direktno mutirajuci
 * {@code Account} (asercije nad {@code Account.getBalance()}) preko
 * {@code AccountRepository}. U trading-service-u racuni/novac pripadaju banka-core
 * domenu — CLIENT grana zove {@link BankaCoreClient#collectTax}. Asercije nad
 * mutacijom racuna su zamenjene verifikacijom da je {@code collectTax} pozvan
 * sa ispravnim {@link TaxCollectRequest} (payerClientId, iznos) i deterministickim
 * idempotency key-em; "nema RSD racuna / nedovoljno sredstava" putanje stubuju
 * {@code collectTax} da vrati {@code collected=false} (banka-core sam razresava
 * klijentov racun — to je pokriveno banka-core {@code internalapi} testovima).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaxServiceCoverage")
class TaxServiceCoverageTest {

    @Mock private TaxRecordRepository taxRecordRepository;
    @Mock private TaxRecordBreakdownRepository taxRecordBreakdownRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private OtcContractRepository otcContractRepository;
    @Mock private rs.raf.trading.stock.repository.ListingRepository listingRepository;
    @Mock private BankaCoreClient bankaCoreClient;

    @InjectMocks
    private TaxService taxService;

    /**
     * Idempotency key koji {@code collectTaxFromUser} koristi: ukljucuje userId,
     * settlement {@code period} I iznos naplate ({@code unpaidTax.toPlainString()}).
     * B-1: key se vezuje za settlement {@code period} (NE run-mesec) — koristimo
     * istu {@code settlementPeriod} heuristiku kao produkcija da izbegnemo flaky
     * mismatch na granici meseca (cron 1. u mesecu naplacuje prethodni mesec).
     */
    private static String taxKey(long userId, String unpaidAmount) {
        YearMonth period = rs.raf.trading.tax.util.TaxRealizedGainCalculator
                .settlementPeriod(java.time.LocalDateTime.now());
        return "tax-" + userId + "-" + period + "-" + unpaidAmount;
    }

    @BeforeEach
    void setUp() {
        when(taxRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private Listing listing(Long id, String quote) {
        Listing l = new Listing();
        l.setId(id);
        l.setQuoteCurrency(quote);
        // Porez se obracunava samo na STOCK (Celina 3 spec)
        l.setListingType(ListingType.STOCK);
        return l;
    }

    private Order order(Long userId, String role, Listing listing, OrderDirection dir,
                        String price, int qty) {
        Order o = new Order();
        o.setId((long) (Math.random() * 100000));
        o.setUserId(userId);
        o.setUserRole(role);
        o.setDirection(dir);
        o.setPricePerUnit(new BigDecimal(price));
        o.setQuantity(qty);
        o.setContractSize(1);
        o.setDone(true);
        o.setStatus(OrderStatus.DONE);
        o.setListing(listing);
        return o;
    }

    private InternalUserDto user(Long id, String role, String firstName, String lastName) {
        return new InternalUserDto(id, role, "u" + id + "@test.com", firstName, lastName, true, null);
    }

    // ─── collectTaxFromUser paths ───────────────────────────────────────────────

    @Test
    @DisplayName("CLIENT tax is collected via banka-core collectTax (debit RSD, credit state)")
    void collectClientTaxViaBankaCore() {
        Listing l = listing(1L, "RSD");
        Order buy = order(1L, "CLIENT", l, OrderDirection.BUY, "100", 1);
        Order sell = order(1L, "CLIENT", l, OrderDirection.SELL, "1100", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(buy, sell));
        when(bankaCoreClient.getUserById("CLIENT", 1L)).thenReturn(user(1L, "CLIENT", "Marko", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        // banka-core uspesno naplatio
        when(bankaCoreClient.collectTax(eq(taxKey(1L, "150.0000")), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("150.0000"), true));

        taxService.calculateTaxForAllUsers();

        // profit = 1000 RSD, tax = 150 → record placen
        ArgumentCaptor<TaxRecord> recCap = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(recCap.capture());
        assertThat(recCap.getValue().getTaxOwed()).isEqualByComparingTo("150.0000");
        assertThat(recCap.getValue().getTaxPaid()).isEqualByComparingTo("150.0000");

        // banka-core collectTax pozvan sa ispravnim zahtevom (payerClientId, neplaceni iznos)
        verify(bankaCoreClient).collectTax(eq(taxKey(1L, "150.0000")), argThat((TaxCollectRequest req) ->
                req.payerClientId().equals(1L)
                        && req.amount().compareTo(new BigDecimal("150.0000")) == 0));
    }

    @Test
    @DisplayName("CLIENT with no RSD account: collected=false, tax record saved but taxPaid stays 0")
    void clientWithoutRsdAccount() {
        Listing l = listing(1L, "RSD");
        Order sell = order(1L, "CLIENT", l, OrderDirection.SELL, "1000", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
        when(bankaCoreClient.getUserById("CLIENT", 1L)).thenReturn(user(1L, "CLIENT", "M", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        // banka-core: klijent nema RSD racun → collected=false (collectedAmount=0)
        when(bankaCoreClient.collectTax(eq(taxKey(1L, "150.0000")), any()))
                .thenReturn(new TaxCollectResponse(1L, BigDecimal.ZERO, false));

        taxService.calculateTaxForAllUsers();

        ArgumentCaptor<TaxRecord> rc = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(rc.capture());
        assertThat(rc.getValue().getTaxOwed()).isEqualByComparingTo("150.0000");
        // collected=false → TaxRecord ostaje neplacen
        assertThat(rc.getValue().getTaxPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(bankaCoreClient).collectTax(eq(taxKey(1L, "150.0000")), any());
    }

    @Test
    @DisplayName("CLIENT with insufficient funds: collected=false, no taxPaid update")
    void clientInsufficientFunds() {
        Listing l = listing(1L, "RSD");
        Order sell = order(1L, "CLIENT", l, OrderDirection.SELL, "10000", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
        when(bankaCoreClient.getUserById("CLIENT", 1L)).thenReturn(user(1L, "CLIENT", "M", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        // banka-core: RSD racun ali nedovoljno sredstava → collected=false
        // profit = 10000 → taxOwed = 1500.0000
        when(bankaCoreClient.collectTax(eq(taxKey(1L, "1500.0000")), any()))
                .thenReturn(new TaxCollectResponse(1L, BigDecimal.ZERO, false));

        taxService.calculateTaxForAllUsers();

        ArgumentCaptor<TaxRecord> rc = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(rc.capture());
        assertThat(rc.getValue().getTaxPaid()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("banka-core collectTax throws: collection skipped, tax record stays unpaid")
    void collectTaxThrows() {
        Listing l = listing(1L, "RSD");
        Order sell = order(1L, "CLIENT", l, OrderDirection.SELL, "1000", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
        when(bankaCoreClient.getUserById("CLIENT", 1L)).thenReturn(user(1L, "CLIENT", "M", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        // banka-core HTTP greska → collectTaxFromUser hvata, vraca false
        when(bankaCoreClient.collectTax(eq(taxKey(1L, "150.0000")), any()))
                .thenThrow(new BankaCoreClientException(500, "banka-core down"));

        taxService.calculateTaxForAllUsers();

        ArgumentCaptor<TaxRecord> rc = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(rc.capture());
        assertThat(rc.getValue().getTaxPaid()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("BE-ORD-06: EMPLOYEE orderi se preskacu (bank actuaries ne placaju licni porez)")
    void employeeTaxCollectedInternally() {
        // BE-ORD-06: spec Celina 3 Sc 58 — bank actuaries trade off bank accounts
        // and dont pay personal capital gains tax. Pre fix-a su EMPLOYEE orderi
        // padali u TaxRecord. Sad se filtriraju u TaxService.
        Listing l = listing(1L, "RSD");
        Order buy = order(5L, "EMPLOYEE", l, OrderDirection.BUY, "100", 1);
        Order sell = order(5L, "EMPLOYEE", l, OrderDirection.SELL, "500", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(buy, sell));

        taxService.calculateTaxForAllUsers();

        // EMPLOYEE orderi su preskoceni — nijedan TaxRecord ne snima se.
        verify(taxRecordRepository, never()).save(any());
        verify(bankaCoreClient, never()).collectTax(any(), any());
    }

    @Test
    @DisplayName("updates existing record with previously paid tax - only incremental charge")
    void incrementalTaxOnExistingRecord() {
        Listing l = listing(1L, "RSD");
        Order buy = order(1L, "CLIENT", l, OrderDirection.BUY, "100", 1);
        Order sell = order(1L, "CLIENT", l, OrderDirection.SELL, "1100", 1);

        // B-1: incremental charge je smislen SAMO unutar istog meseca — pa record
        // nosi mesecni paid bucket (taxPaidPeriod = tekuci mesec, taxPaidInPeriod=75).
        // Bez ovih polja (legacy semantika) 75 bi se tretiralo kao "placeno bilo kad"
        // i mesalo bi lifetime sa mesecnim (upravo B-1 bug).
        YearMonth currentPeriod = YearMonth.now();
        TaxRecord existing = TaxRecord.builder()
                .id(7L).userId(1L).userType("CLIENT")
                .totalProfit(new BigDecimal("500"))
                .taxOwed(new BigDecimal("75"))
                .taxPaid(new BigDecimal("75")) // godisnji kumulativ
                .taxPaidYear(currentPeriod.getYear())
                .taxPaidPeriod(currentPeriod.toString())
                .taxPaidInPeriod(new BigDecimal("75")) // placeno u tekucem mesecu
                .currency("RSD")
                .build();

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(buy, sell));
        when(bankaCoreClient.getUserById("CLIENT", 1L)).thenReturn(user(1L, "CLIENT", "M", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.of(existing));
        // novi tax = 150, placeno 75 => unpaid = 75.0000 → collectTax sa 75
        when(bankaCoreClient.collectTax(eq(taxKey(1L, "75.0000")), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("75.0000"), true));

        taxService.calculateTaxForAllUsers();

        // taxPaid postavljen na pun taxOwed posle uspesne naplate
        assertThat(existing.getTaxPaid()).isEqualByComparingTo("150.0000");
        // collectTax pozvan sa SAMO inkrementalnim iznosom (75), ne punim 150
        verify(bankaCoreClient).collectTax(eq(taxKey(1L, "75.0000")), argThat((TaxCollectRequest req) ->
                req.amount().compareTo(new BigDecimal("75")) == 0));
    }

    // ─── intra-mesecni re-run: idempotency key sa iznosom (holisticki review I-2) ──

    @Test
    @DisplayName("dva re-run-a u istom mesecu sa rastucim porezom → razliciti idempotency kljucevi, drugi nosi inkrement")
    void intraMonthRerun_distinctIdempotencyKeys_secondCarriesIncrement() {
        // Run 1: profit 1000 → taxOwed 150.0000, prior 0 → unpaidTax 150.0000.
        // Run 2 (isti mesec, nove trgovine): profit 3000 → taxOwed 450.0000,
        // prior placeno 150 → unpaidTax 300.0000.
        // Bez fix-a I-2: oba run-a koriste isti kljuc tax-1-{ym} → banka-core
        // replay-uje prvu naplatu i drugi (veci) porez se laznja obelezi placen.
        // Sa fix-om: razlicit unpaidTax → razlicit kljuc → drugi run stvarno naplati 300.
        Listing l = listing(1L, "RSD");

        Order buy1 = order(1L, "CLIENT", l, OrderDirection.BUY, "100", 1);
        Order sell1 = order(1L, "CLIENT", l, OrderDirection.SELL, "1100", 1); // profit 1000
        Order sell2 = order(1L, "CLIENT", l, OrderDirection.SELL, "2000", 1); // jos 2000 → ukupno 3000

        when(bankaCoreClient.getUserById("CLIENT", 1L)).thenReturn(user(1L, "CLIENT", "M", "P"));

        // Run 1 vidi samo buy1+sell1; Run 2 vidi sve (buy1+sell1+sell2).
        when(orderRepository.findByIsDoneTrue())
                .thenReturn(List.of(buy1, sell1))
                .thenReturn(List.of(buy1, sell1, sell2));

        // Run 1: nema postojeceg record-a. Run 2: record sa mesecnim paid bucket-om
        // (taxPaidInPeriod=150 u tekucem mesecu) — B-1: drugi run u ISTOM mesecu
        // racuna unpaidTax = mesecni owed (450) − mesecni paid (150) = 300.
        YearMonth run1Period = YearMonth.now();
        TaxRecord afterRun1 = TaxRecord.builder()
                .id(1L).userId(1L).userType("CLIENT")
                .totalProfit(new BigDecimal("1000"))
                .taxOwed(new BigDecimal("150.0000"))
                .taxPaid(new BigDecimal("150.0000"))
                .taxPaidYear(run1Period.getYear())
                .taxPaidPeriod(run1Period.toString())
                .taxPaidInPeriod(new BigDecimal("150.0000"))
                .currency("RSD")
                .build();
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(afterRun1));

        when(bankaCoreClient.collectTax(eq(taxKey(1L, "150.0000")), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("150.0000"), true));
        when(bankaCoreClient.collectTax(eq(taxKey(1L, "300.0000")), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("300.0000"), true));

        taxService.calculateTaxForAllUsers(); // run 1
        taxService.calculateTaxForAllUsers(); // run 2 (intra-mesecni re-run)

        // collectTax pozvan tacno dvaput, sa RAZLICITIM kljucevima.
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(bankaCoreClient, times(2)).collectTax(keyCap.capture(), any());
        assertThat(keyCap.getAllValues())
                .containsExactly(taxKey(1L, "150.0000"), taxKey(1L, "300.0000"));
        assertThat(keyCap.getAllValues().get(0))
                .isNotEqualTo(keyCap.getAllValues().get(1));

        // Drugi zahtev nosi tacno inkrementalni neplaceni porez (300, ne 450 ni 150).
        ArgumentCaptor<TaxCollectRequest> reqCap = ArgumentCaptor.forClass(TaxCollectRequest.class);
        verify(bankaCoreClient, times(2)).collectTax(any(), reqCap.capture());
        assertThat(reqCap.getAllValues().get(0).amount()).isEqualByComparingTo("150.0000");
        assertThat(reqCap.getAllValues().get(1).amount()).isEqualByComparingTo("300.0000");
    }

    @Test
    @DisplayName("existing record with null taxPaid treated as zero")
    void existingRecordNullTaxPaid() {
        Listing l = listing(1L, "RSD");
        Order sell = order(1L, "CLIENT", l, OrderDirection.SELL, "1000", 1);

        TaxRecord existing = TaxRecord.builder()
                .id(8L).userId(1L).userType("CLIENT")
                .taxPaid(null)
                .currency("RSD")
                .build();

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
        when(bankaCoreClient.getUserById("CLIENT", 1L)).thenReturn(user(1L, "CLIENT", "M", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.of(existing));
        when(bankaCoreClient.collectTax(eq(taxKey(1L, "150.0000")), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("150.0000"), true));

        taxService.calculateTaxForAllUsers();

        assertThat(existing.getTaxPaid()).isEqualByComparingTo("150.0000");
    }

    // ─── resolveOrderCurrency branches ──────────────────────────────────────────

    @Test
    @DisplayName("order with null listing quote currency falls back to RSD")
    void nullQuoteCurrencyFallback() {
        Listing l = new Listing();
        l.setId(1L);
        l.setQuoteCurrency(null); // explicit null
        l.setListingType(ListingType.STOCK);
        l.setExchangeAcronym("BELEX"); // BELEX → RSD, izbegava USD konverziju
        Order sell = order(1L, "CLIENT", l, OrderDirection.SELL, "500", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
        when(bankaCoreClient.getUserById("CLIENT", 1L)).thenReturn(user(1L, "CLIENT", "M", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        when(bankaCoreClient.collectTax(any(), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("75"), true));

        taxService.calculateTaxForAllUsers();

        // treba da koristi RSD (bez konverzije)
        verify(currencyConversionService, never()).convert(any(), any(), any());
    }

    @Test
    @DisplayName("order with blank listing quote currency falls back to RSD")
    void blankQuoteCurrencyFallback() {
        Listing l = new Listing();
        l.setId(1L);
        l.setQuoteCurrency("   ");
        l.setListingType(ListingType.STOCK);
        l.setExchangeAcronym("BELEX");
        Order sell = order(1L, "CLIENT", l, OrderDirection.SELL, "500", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
        when(bankaCoreClient.getUserById("CLIENT", 1L)).thenReturn(user(1L, "CLIENT", "M", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());
        when(bankaCoreClient.collectTax(any(), any()))
                .thenReturn(new TaxCollectResponse(1L, new BigDecimal("75"), true));

        taxService.calculateTaxForAllUsers();

        verify(currencyConversionService, never()).convert(any(), any(), any());
    }

    // ─── convertToRsd branches ──────────────────────────────────────────────────

    @Test
    @DisplayName("BE-ORD-08: FX failure baca TaxCalculationException (NE fallback na raw amount)")
    void conversionExceptionFallback() {
        // Pre BE-ORD-08 fix-a: FX failure je tisko fallback-ovao na sirovi iznos
        // (USD 1000 → 1000 RSD = severe under-taxation). Sad propagira
        // TaxCalculationException; TaxRecord se NE snima sa pogresnim podatkom.
        Listing l = listing(1L, "USD");
        Order buy = order(1L, "CLIENT", l, OrderDirection.BUY, "100", 1);
        Order sell = order(1L, "CLIENT", l, OrderDirection.SELL, "300", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(buy, sell));
        when(currencyConversionService.convert(any(), eq("USD"), eq("RSD")))
                .thenThrow(new RuntimeException("rate unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> taxService.calculateTaxForAllUsers())
                .isInstanceOf(rs.raf.trading.tax.service.TaxCalculationException.class)
                .hasMessageContaining("FX rate unavailable for USD");

        // KRITICNO: TaxRecord se NE snima (raw amount NE tretira kao RSD).
        verify(taxRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("zero profit in foreign currency short-circuits conversion")
    void zeroProfitSkipsConversion() {
        Listing l = listing(1L, "USD");
        Order buy = order(1L, "CLIENT", l, OrderDirection.BUY, "100", 1);
        Order sell = order(1L, "CLIENT", l, OrderDirection.SELL, "100", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(buy, sell));
        when(bankaCoreClient.getUserById("CLIENT", 1L)).thenReturn(user(1L, "CLIENT", "M", "P"));
        when(taxRecordRepository.findByUserIdAndUserType(1L, "CLIENT")).thenReturn(Optional.empty());

        taxService.calculateTaxForAllUsers();

        // convertToRsd sa amount=0 treba da vrati ZERO bez poziva servisa
        verify(currencyConversionService, never()).convert(any(), any(), any());
        ArgumentCaptor<TaxRecord> rc = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(rc.capture());
        assertThat(rc.getValue().getTotalProfit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rc.getValue().getTaxOwed()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─── resolveUserName fallbacks ──────────────────────────────────────────────

    @Test
    @DisplayName("unknown CLIENT user id falls back to 'Klijent #id' label")
    void unknownClientUserNameFallback() {
        Listing l = listing(1L, "RSD");
        Order sell = order(99L, "CLIENT", l, OrderDirection.SELL, "100", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
        // banka-core ne moze da razresi korisnika → BankaCoreClientException
        when(bankaCoreClient.getUserById("CLIENT", 99L))
                .thenThrow(new BankaCoreClientException(404, "not found"));
        when(taxRecordRepository.findByUserIdAndUserType(99L, "CLIENT")).thenReturn(Optional.empty());
        when(bankaCoreClient.collectTax(any(), any()))
                .thenReturn(new TaxCollectResponse(99L, new BigDecimal("15"), true));

        taxService.calculateTaxForAllUsers();

        ArgumentCaptor<TaxRecord> rc = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(rc.capture());
        assertThat(rc.getValue().getUserName()).isEqualTo("Klijent #99");
    }

    @Test
    @DisplayName("unknown CLIENT user id falls back to 'Klijent #id' label")
    void unknownEmployeeUserNameFallback() {
        // BE-ORD-06: EMPLOYEE orderi se preskacu pa testiramo CLIENT fallback put.
        // Pre fix-a testirao se EMPLOYEE 'Zaposleni #N' label; sad CLIENT 'Klijent #N'.
        Listing l = listing(1L, "RSD");
        Order sell = order(42L, "CLIENT", l, OrderDirection.SELL, "100", 1);

        when(orderRepository.findByIsDoneTrue()).thenReturn(List.of(sell));
        when(bankaCoreClient.getUserById("CLIENT", 42L))
                .thenThrow(new BankaCoreClientException(404, "not found"));
        when(taxRecordRepository.findByUserIdAndUserType(42L, "CLIENT")).thenReturn(Optional.empty());

        taxService.calculateTaxForAllUsers();

        ArgumentCaptor<TaxRecord> rc = ArgumentCaptor.forClass(TaxRecord.class);
        verify(taxRecordRepository).save(rc.capture());
        assertThat(rc.getValue().getUserName()).isEqualTo("Klijent #42");
    }

    // ─── getTaxRecords explicit filter path ─────────────────────────────────────

    @Test
    @DisplayName("explicit non-blank filters pass through to repository")
    void explicitFiltersPassThrough() {
        when(taxRecordRepository.findByFilters("Marko", "CLIENT"))
                .thenReturn(Collections.emptyList());

        List<TaxRecordDto> out = taxService.getTaxRecords("Marko", "CLIENT");

        assertThat(out).isEmpty();
        verify(taxRecordRepository).findByFilters("Marko", "CLIENT");
    }

    @Test
    @DisplayName("null filters normalized to null")
    void nullFiltersNormalized() {
        when(taxRecordRepository.findByFilters(null, null))
                .thenReturn(Collections.emptyList());

        taxService.getTaxRecords(null, null);

        verify(taxRecordRepository).findByFilters(null, null);
    }

    // ─── getMyTaxRecord client without record (covers orElseGet branch) ─────────

    @Test
    @DisplayName("getMyTaxRecord: CLIENT without record returns empty DTO with full name")
    void getMyTaxRecord_clientWithoutRecord_returnsEmptyDto() {
        when(bankaCoreClient.getUserByEmail("marko@x.com"))
                .thenReturn(new InternalUserDto(77L, "CLIENT", "marko@x.com",
                        "Marko", "Petrovic", true, null));
        when(taxRecordRepository.findByUserIdAndUserType(77L, "CLIENT")).thenReturn(Optional.empty());

        TaxRecordDto dto = taxService.getMyTaxRecord("marko@x.com");

        assertThat(dto.getUserId()).isEqualTo(77L);
        assertThat(dto.getUserName()).isEqualTo("Marko Petrovic");
        assertThat(dto.getUserType()).isEqualTo("CLIENT");
        assertThat(dto.getTaxOwed()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ─── convertToRsd: amount == null short-circuit ────────────────────────────

    @Test
    @DisplayName("convertToRsd reflectively: null amount returns ZERO")
    void convertToRsd_nullAmount_returnsZero() throws Exception {
        java.lang.reflect.Method m = TaxService.class.getDeclaredMethod(
                "convertToRsd", BigDecimal.class, String.class);
        m.setAccessible(true);
        Object out = m.invoke(taxService, (BigDecimal) null, "USD");
        assertThat((BigDecimal) out).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("convertToRsd reflectively: null fromCurrency returns amount as-is")
    void convertToRsd_nullFromCurrency_returnsAmount() throws Exception {
        java.lang.reflect.Method m = TaxService.class.getDeclaredMethod(
                "convertToRsd", BigDecimal.class, String.class);
        m.setAccessible(true);
        Object out = m.invoke(taxService, new BigDecimal("123.45"), (String) null);
        assertThat((BigDecimal) out).isEqualByComparingTo("123.45");
        verify(currencyConversionService, never()).convert(any(), any(), any());
    }

    // ─── resolveOrderCurrency: listing.getQuoteCurrency() throws ───────────────

    @Test
    @DisplayName("resolveOrderCurrency reflectively: getQuoteCurrency throws → fallback RSD")
    void resolveOrderCurrency_listingThrows_fallsBackToRsd() throws Exception {
        Listing throwingListing = mock(Listing.class);
        when(throwingListing.getQuoteCurrency()).thenThrow(new RuntimeException("lazy init boom"));

        Order o = new Order();
        o.setListing(throwingListing);

        java.lang.reflect.Method m = TaxService.class.getDeclaredMethod(
                "resolveOrderCurrency", Order.class);
        m.setAccessible(true);
        Object out = m.invoke(taxService, o);

        assertThat((String) out).isEqualTo("RSD");
    }

    // ─── resolveOrderCurrency: order.getListing() == null branch ────────────────

    @Test
    @DisplayName("resolveOrderCurrency reflectively: null listing → fallback RSD")
    void resolveOrderCurrency_nullListing_fallsBackToRsd() throws Exception {
        Order o = new Order();
        o.setListing(null);

        java.lang.reflect.Method m = TaxService.class.getDeclaredMethod(
                "resolveOrderCurrency", Order.class);
        m.setAccessible(true);
        Object out = m.invoke(taxService, o);

        assertThat((String) out).isEqualTo("RSD");
    }
}
