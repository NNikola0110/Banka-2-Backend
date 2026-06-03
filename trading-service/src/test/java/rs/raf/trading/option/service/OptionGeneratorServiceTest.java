package rs.raf.trading.option.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.option.model.Option;
import rs.raf.trading.option.model.OptionType;
import rs.raf.trading.option.repository.OptionRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Testovi za {@link OptionGeneratorService} — copy-first ekstrakcija (faza 2d-C).
 * Generisanje opcija nema novcane operacije ni banka-core zavisnost, pa je test
 * portovan verbatim (samo package rename).
 */
@ExtendWith(MockitoExtension.class)
class OptionGeneratorServiceTest {

    @Mock
    private OptionRepository optionRepository;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private BlackScholesService blackScholesService;

    @InjectMocks
    private OptionGeneratorService optionGeneratorService;

    // ============================================================
    // generateStrikePrices() tests
    // ============================================================

    @Test
    void generateStrikePrices_returns10Strikes() {
        BigDecimal price = new BigDecimal("100.00");
        List<BigDecimal> strikes = optionGeneratorService.generateStrikePrices(price);
        assertThat(strikes).hasSize(10);
    }

    @Test
    void generateStrikePrices_5Above5Below() {
        BigDecimal price = new BigDecimal("100.00");
        List<BigDecimal> strikes = optionGeneratorService.generateStrikePrices(price);

        long above = strikes.stream().filter(s -> s.compareTo(price) > 0).count();
        long below = strikes.stream().filter(s -> s.compareTo(price) < 0).count();
        assertThat(above).isEqualTo(5);
        assertThat(below).isEqualTo(5);
    }

    @Test
    void generateStrikePrices_sortedAscending() {
        BigDecimal price = new BigDecimal("200.00");
        List<BigDecimal> strikes = optionGeneratorService.generateStrikePrices(price);

        for (int i = 1; i < strikes.size(); i++) {
            assertThat(strikes.get(i)).isGreaterThanOrEqualTo(strikes.get(i - 1));
        }
    }

    @Test
    void generateStrikePrices_correctValues() {
        BigDecimal price = new BigDecimal("100.00");
        List<BigDecimal> strikes = optionGeneratorService.generateStrikePrices(price);

        // Expected strikes: 75, 80, 85, 90, 95, 105, 110, 115, 120, 125
        assertThat(strikes).contains(
                new BigDecimal("75.00"),
                new BigDecimal("80.00"),
                new BigDecimal("85.00"),
                new BigDecimal("90.00"),
                new BigDecimal("95.00"),
                new BigDecimal("105.00"),
                new BigDecimal("110.00"),
                new BigDecimal("115.00"),
                new BigDecimal("120.00"),
                new BigDecimal("125.00")
        );
    }

    @Test
    void generateStrikePrices_withSmallPrice() {
        BigDecimal price = new BigDecimal("1.00");
        List<BigDecimal> strikes = optionGeneratorService.generateStrikePrices(price);

        assertThat(strikes).hasSize(10);
        // All strikes should be positive
        strikes.forEach(s -> assertThat(s).isGreaterThan(BigDecimal.ZERO));
    }

    @Test
    void generateStrikePrices_withLargePrice() {
        BigDecimal price = new BigDecimal("50000.00");
        List<BigDecimal> strikes = optionGeneratorService.generateStrikePrices(price);

        assertThat(strikes).hasSize(10);
        // Lowest should be 75% of price
        assertThat(strikes.get(0)).isEqualByComparingTo(
                price.multiply(BigDecimal.valueOf(0.75)).setScale(2, RoundingMode.HALF_UP));
        // Highest should be 125% of price
        assertThat(strikes.get(9)).isEqualByComparingTo(
                price.multiply(BigDecimal.valueOf(1.25)).setScale(2, RoundingMode.HALF_UP));
    }

    @Test
    void generateStrikePrices_scaleIsTwo() {
        BigDecimal price = new BigDecimal("123.456");
        List<BigDecimal> strikes = optionGeneratorService.generateStrikePrices(price);

        strikes.forEach(s -> assertThat(s.scale()).isEqualTo(2));
    }

    // ============================================================
    // generateSettlementDates() tests
    // ============================================================

    @Test
    void generateSettlementDates_returns9Dates() {
        List<LocalDate> dates = optionGeneratorService.generateSettlementDates();
        assertThat(dates).hasSize(9);
    }

    @Test
    void generateSettlementDates_allInFuture() {
        List<LocalDate> dates = optionGeneratorService.generateSettlementDates();
        LocalDate today = LocalDate.now();
        dates.forEach(d -> assertThat(d).isAfter(today));
    }

    @Test
    void generateSettlementDates_first6HaveSixDaySpacing() {
        List<LocalDate> dates = optionGeneratorService.generateSettlementDates();
        LocalDate today = LocalDate.now();

        for (int i = 0; i < 6; i++) {
            assertThat(dates.get(i)).isEqualTo(today.plusDays((i + 1) * 6L));
        }
    }

    @Test
    void generateSettlementDates_last3Have30DaySpacingAfterDay36() {
        List<LocalDate> dates = optionGeneratorService.generateSettlementDates();
        LocalDate today = LocalDate.now();

        assertThat(dates.get(6)).isEqualTo(today.plusDays(66));
        assertThat(dates.get(7)).isEqualTo(today.plusDays(96));
        assertThat(dates.get(8)).isEqualTo(today.plusDays(126));
    }

    @Test
    void generateSettlementDates_sortedChronologically() {
        List<LocalDate> dates = optionGeneratorService.generateSettlementDates();
        for (int i = 1; i < dates.size(); i++) {
            assertThat(dates.get(i)).isAfter(dates.get(i - 1));
        }
    }

    // ============================================================
    // generateTicker() tests
    // ============================================================

    @Test
    void generateTicker_callFormat() {
        LocalDate date = LocalDate.of(2026, 4, 8);
        BigDecimal strike = new BigDecimal("185.00");

        String ticker = optionGeneratorService.generateTicker("AAPL", date, OptionType.CALL, strike);

        assertThat(ticker).isEqualTo("AAPL260408C00185000");
    }

    @Test
    void generateTicker_putFormat() {
        LocalDate date = LocalDate.of(2026, 4, 8);
        BigDecimal strike = new BigDecimal("185.00");

        String ticker = optionGeneratorService.generateTicker("AAPL", date, OptionType.PUT, strike);

        assertThat(ticker).isEqualTo("AAPL260408P00185000");
    }

    @Test
    void generateTicker_highStrikePrice() {
        LocalDate date = LocalDate.of(2026, 12, 31);
        BigDecimal strike = new BigDecimal("5000.00");

        String ticker = optionGeneratorService.generateTicker("MSFT", date, OptionType.CALL, strike);

        assertThat(ticker).isEqualTo("MSFT261231C05000000");
    }

    @Test
    void generateTicker_lowStrikePrice() {
        LocalDate date = LocalDate.of(2026, 1, 15);
        BigDecimal strike = new BigDecimal("0.50");

        String ticker = optionGeneratorService.generateTicker("XYZ", date, OptionType.PUT, strike);

        assertThat(ticker).isEqualTo("XYZ260115P00000500");
    }

    @Test
    void generateTicker_strikePriceZeroPadding() {
        LocalDate date = LocalDate.of(2026, 6, 1);
        BigDecimal strike = new BigDecimal("1.00");

        String ticker = optionGeneratorService.generateTicker("A", date, OptionType.CALL, strike);

        // strike * 1000 = 1000, padded to 8 digits = 00001000
        assertThat(ticker).isEqualTo("A260601C00001000");
    }

    // ============================================================
    // generateOptionsForListing() tests
    // ============================================================

    @Test
    void generateOptionsForListing_nullStock_doesNothing() {
        optionGeneratorService.generateOptionsForListing(null);

        verifyNoInteractions(optionRepository);
        verifyNoInteractions(blackScholesService);
    }

    @Test
    void generateOptionsForListing_nonStockType_doesNothing() {
        Listing forex = createListing(1L, "EURUSD", ListingType.FOREX, new BigDecimal("1.10"));

        optionGeneratorService.generateOptionsForListing(forex);

        verifyNoInteractions(optionRepository);
    }

    @Test
    void generateOptionsForListing_nullPrice_doesNothing() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, null);

        optionGeneratorService.generateOptionsForListing(stock);

        verify(optionRepository, never()).saveAll(any());
    }

    @Test
    void generateOptionsForListing_zeroPrice_doesNothing() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, BigDecimal.ZERO);

        optionGeneratorService.generateOptionsForListing(stock);

        verify(optionRepository, never()).saveAll(any());
    }

    @Test
    void generateOptionsForListing_negativePrice_doesNothing() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("-10.00"));

        optionGeneratorService.generateOptionsForListing(stock);

        verify(optionRepository, never()).saveAll(any());
    }

    @Test
    void generateOptionsForListing_validStock_generates180Options() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("150.00"));

        when(optionRepository.existsByStockListingIdAndSettlementDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("5.0000"));
        when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("3.0000"));

        optionGeneratorService.generateOptionsForListing(stock);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Option>> captor = ArgumentCaptor.forClass(List.class);
        verify(optionRepository).saveAll(captor.capture());

        List<Option> savedOptions = captor.getValue();
        // 10 strikes * 9 dates * 2 types = 180
        assertThat(savedOptions).hasSize(180);
    }

    @Test
    void generateOptionsForListing_halfCallHalfPut() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("150.00"));

        when(optionRepository.existsByStockListingIdAndSettlementDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("5.0000"));
        when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("3.0000"));

        optionGeneratorService.generateOptionsForListing(stock);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Option>> captor = ArgumentCaptor.forClass(List.class);
        verify(optionRepository).saveAll(captor.capture());

        List<Option> options = captor.getValue();
        long calls = options.stream().filter(o -> o.getOptionType() == OptionType.CALL).count();
        long puts = options.stream().filter(o -> o.getOptionType() == OptionType.PUT).count();
        assertThat(calls).isEqualTo(90);
        assertThat(puts).isEqualTo(90);
    }

    @Test
    void generateOptionsForListing_skipsExistingDates() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("150.00"));

        // First 3 dates already exist
        when(optionRepository.existsByStockListingIdAndSettlementDate(eq(1L), any(LocalDate.class)))
                .thenReturn(true, true, true, false, false, false, false, false, false);
        when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("5.0000"));
        when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("3.0000"));

        optionGeneratorService.generateOptionsForListing(stock);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Option>> captor = ArgumentCaptor.forClass(List.class);
        verify(optionRepository).saveAll(captor.capture());

        // 6 remaining dates * 10 strikes * 2 types = 120
        assertThat(captor.getValue()).hasSize(120);
    }

    @Test
    void generateOptionsForListing_allDatesExist_doesNotSave() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("150.00"));

        when(optionRepository.existsByStockListingIdAndSettlementDate(anyLong(), any(LocalDate.class)))
                .thenReturn(true);

        optionGeneratorService.generateOptionsForListing(stock);

        verify(optionRepository, never()).saveAll(any());
    }

    @Test
    void generateOptionsForListing_optionHasCorrectFields() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("100.00"));

        when(optionRepository.existsByStockListingIdAndSettlementDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("10.0000"));
        when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("8.0000"));

        optionGeneratorService.generateOptionsForListing(stock);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Option>> captor = ArgumentCaptor.forClass(List.class);
        verify(optionRepository).saveAll(captor.capture());

        Option firstOption = captor.getValue().get(0);
        assertThat(firstOption.getStockListing()).isEqualTo(stock);
        assertThat(firstOption.getContractSize()).isEqualTo(100);
        assertThat(firstOption.getOpenInterest()).isEqualTo(0);
        assertThat(firstOption.getTicker()).isNotBlank();
        assertThat(firstOption.getVolume()).isBetween(100L, 10000L);
    }

    @Test
    void generateOptionsForListing_askAndBidSpread() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("100.00"));

        BigDecimal callPrice = new BigDecimal("10.0000");
        when(optionRepository.existsByStockListingIdAndSettlementDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(callPrice);
        when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("8.0000"));

        optionGeneratorService.generateOptionsForListing(stock);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Option>> captor = ArgumentCaptor.forClass(List.class);
        verify(optionRepository).saveAll(captor.capture());

        // Find a CALL option and verify ask/bid
        Option callOption = captor.getValue().stream()
                .filter(o -> o.getOptionType() == OptionType.CALL)
                .findFirst()
                .orElseThrow();

        // ask = price * 1.05, bid = price * 0.95
        BigDecimal expectedAsk = callPrice.multiply(BigDecimal.valueOf(1.05)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal expectedBid = callPrice.multiply(BigDecimal.valueOf(0.95)).setScale(4, RoundingMode.HALF_UP);
        assertThat(callOption.getAsk()).isEqualByComparingTo(expectedAsk);
        assertThat(callOption.getBid()).isEqualByComparingTo(expectedBid);
    }

    // ============================================================
    // generateAllOptions() tests
    // ============================================================

    @Test
    void generateAllOptions_filtersOnlyStocks() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("150.00"));
        Listing forex = createListing(2L, "EURUSD", ListingType.FOREX, new BigDecimal("1.10"));
        Listing futures = createListing(3L, "CL", ListingType.FUTURES, new BigDecimal("75.00"));

        when(listingRepository.findAll()).thenReturn(List.of(stock, forex, futures));
        when(optionRepository.existsByStockListingIdAndSettlementDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("5.0000"));
        when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("3.0000"));

        optionGeneratorService.generateAllOptions();

        // saveAll should be called only once (for the single STOCK)
        verify(optionRepository, times(1)).saveAll(any());
    }

    @Test
    void generateAllOptions_noStocks_doesNothing() {
        Listing forex = createListing(1L, "EURUSD", ListingType.FOREX, new BigDecimal("1.10"));
        when(listingRepository.findAll()).thenReturn(List.of(forex));

        optionGeneratorService.generateAllOptions();

        verify(optionRepository, never()).saveAll(any());
    }

    @Test
    void generateAllOptions_emptyListings() {
        when(listingRepository.findAll()).thenReturn(List.of());

        optionGeneratorService.generateAllOptions();

        verify(optionRepository, never()).saveAll(any());
    }

    @Test
    void generateAllOptions_multipleStocks() {
        Listing stock1 = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("150.00"));
        Listing stock2 = createListing(2L, "MSFT", ListingType.STOCK, new BigDecimal("300.00"));

        when(listingRepository.findAll()).thenReturn(List.of(stock1, stock2));
        when(optionRepository.existsByStockListingIdAndSettlementDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("5.0000"));
        when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("3.0000"));

        optionGeneratorService.generateAllOptions();

        verify(optionRepository, times(2)).saveAll(any());
    }

    @Test
    void generateAllOptions_continuesOnError() {
        Listing stock1 = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("150.00"));
        Listing stock2 = createListing(2L, "MSFT", ListingType.STOCK, new BigDecimal("300.00"));

        when(listingRepository.findAll()).thenReturn(List.of(stock1, stock2));
        // First stock throws, second succeeds
        when(optionRepository.existsByStockListingIdAndSettlementDate(eq(1L), any(LocalDate.class)))
                .thenThrow(new RuntimeException("DB error"));
        when(optionRepository.existsByStockListingIdAndSettlementDate(eq(2L), any(LocalDate.class)))
                .thenReturn(false);
        when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("5.0000"));
        when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("3.0000"));

        optionGeneratorService.generateAllOptions();

        // Should still process stock2 despite stock1 failing
        verify(optionRepository, times(1)).saveAll(any());
    }

    // ============================================================
    // Edge case: FUTURES type listing
    // ============================================================

    @Test
    void generateOptionsForListing_futuresType_doesNothing() {
        Listing futures = createListing(1L, "CL", ListingType.FUTURES, new BigDecimal("75.00"));

        optionGeneratorService.generateOptionsForListing(futures);

        verifyNoInteractions(optionRepository);
    }

    // ============================================================
    // Maintenance margin tests (Option O1 — spec Opcije.txt)
    // ============================================================

    @Test
    void computeMaintenanceMargin_standardContract_equalsFiftyTimesPrice() {
        // ContractSize=100, factor=0.5, price=150 => 100 * 0.5 * 150 = 7500.0000
        BigDecimal margin = optionGeneratorService.computeMaintenanceMargin(new BigDecimal("150.00"));

        assertThat(margin).isEqualByComparingTo(new BigDecimal("7500.0000"));
    }

    @Test
    void computeMaintenanceMargin_smallPrice_correctScale() {
        // 100 * 0.5 * 1.00 = 50.0000
        BigDecimal margin = optionGeneratorService.computeMaintenanceMargin(new BigDecimal("1.00"));

        assertThat(margin).isEqualByComparingTo(new BigDecimal("50.0000"));
        assertThat(margin.scale()).isEqualTo(4);
    }

    @Test
    void computeMaintenanceMargin_largePrice() {
        // 100 * 0.5 * 5000 = 250000.0000
        BigDecimal margin = optionGeneratorService.computeMaintenanceMargin(new BigDecimal("5000.00"));

        assertThat(margin).isEqualByComparingTo(new BigDecimal("250000.0000"));
    }

    @Test
    void computeMaintenanceMargin_nullPrice_returnsZero() {
        BigDecimal margin = optionGeneratorService.computeMaintenanceMargin(null);

        assertThat(margin).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void generateOptionsForListing_setsMaintenanceMarginOnEveryOption() {
        BigDecimal stockPrice = new BigDecimal("100.00");
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, stockPrice);

        when(optionRepository.existsByStockListingIdAndSettlementDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("5.0000"));
        when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("3.0000"));

        optionGeneratorService.generateOptionsForListing(stock);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Option>> captor = ArgumentCaptor.forClass(List.class);
        verify(optionRepository).saveAll(captor.capture());

        // Spec: ContractSize x 50% x StockPrice = 100 * 0.5 * 100 = 5000.0000
        BigDecimal expectedMargin = new BigDecimal("5000.0000");
        captor.getValue().forEach(o ->
                assertThat(o.getMaintenanceMargin()).isEqualByComparingTo(expectedMargin)
        );
    }

    // ============================================================
    // R1 760: seedovani RNG → reproducibilan IV/volume
    // ============================================================

    @Test
    void seededRandom_makesIvAndVolumeReproducible() {
        Listing stock = createListing(1L, "AAPL", ListingType.STOCK, new BigDecimal("100.00"));
        when(optionRepository.existsByStockListingIdAndSettlementDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        when(blackScholesService.calculateCallPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("5.0000"));
        when(blackScholesService.calculatePutPrice(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new BigDecimal("3.0000"));

        // Prvi run sa seed-om 42.
        optionGeneratorService.setRandom(new Random(42L));
        optionGeneratorService.generateOptionsForListing(stock);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Option>> captor1 = ArgumentCaptor.forClass(List.class);
        verify(optionRepository).saveAll(captor1.capture());
        List<Long> volumes1 = captor1.getValue().stream().map(Option::getVolume).toList();
        List<Double> ivs1 = captor1.getValue().stream().map(Option::getImpliedVolatility).toList();

        // Drugi run sa ISTIM seed-om → identicni IV/volume nizovi (reproducibilno).
        reset(optionRepository);
        when(optionRepository.existsByStockListingIdAndSettlementDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        optionGeneratorService.setRandom(new Random(42L));
        optionGeneratorService.generateOptionsForListing(stock);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Option>> captor2 = ArgumentCaptor.forClass(List.class);
        verify(optionRepository).saveAll(captor2.capture());
        List<Long> volumes2 = captor2.getValue().stream().map(Option::getVolume).toList();
        List<Double> ivs2 = captor2.getValue().stream().map(Option::getImpliedVolatility).toList();

        assertThat(volumes2).isEqualTo(volumes1);
        assertThat(ivs2).isEqualTo(ivs1);
        // Volume i IV su u ocekivanom rasponu.
        volumes1.forEach(v -> assertThat(v).isBetween(100L, 10000L));
        ivs1.forEach(iv -> assertThat(iv).isBetween(0.15, 0.60));
    }

    @Test
    void askBidSpread_usesSharedConstants() {
        // R1 761: ask/bid helperi koriste centralizovane ±5% konstante.
        BigDecimal price = new BigDecimal("10.0000");
        assertThat(OptionGeneratorService.askFrom(price))
                .isEqualByComparingTo(new BigDecimal("10.5000"));
        assertThat(OptionGeneratorService.bidFrom(price))
                .isEqualByComparingTo(new BigDecimal("9.5000"));
    }

    // ============================================================
    // Helper
    // ============================================================

    private Listing createListing(Long id, String ticker, ListingType type, BigDecimal price) {
        Listing listing = new Listing();
        listing.setId(id);
        listing.setTicker(ticker);
        listing.setName(ticker + " Inc.");
        listing.setListingType(type);
        listing.setPrice(price);
        return listing;
    }
}
