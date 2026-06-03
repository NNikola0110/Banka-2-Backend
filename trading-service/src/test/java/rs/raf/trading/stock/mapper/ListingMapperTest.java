package rs.raf.trading.stock.mapper;

import org.junit.jupiter.api.Test;
import rs.raf.trading.stock.dto.ListingDailyPriceDto;
import rs.raf.trading.stock.dto.ListingDto;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingDailyPriceInfo;
import rs.raf.trading.stock.model.ListingType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ListingMapperTest {

    @Test
    void toDto_null_returnsNull() {
        assertThat(ListingMapper.toDto(null)).isNull();
    }

    @Test
    void toDto_stock_mapsAllFieldsAndCalculations() {
        Listing listing = new Listing();
        listing.setId(1L);
        listing.setTicker("AAPL");
        listing.setName("Apple Inc.");
        listing.setExchangeAcronym("NASDAQ");
        listing.setListingType(ListingType.STOCK);
        listing.setPrice(BigDecimal.valueOf(150));
        listing.setAsk(BigDecimal.valueOf(151));
        listing.setBid(BigDecimal.valueOf(149));
        listing.setVolume(1000000L);
        listing.setPriceChange(BigDecimal.valueOf(5));
        listing.setOutstandingShares(1000000L);
        listing.setDividendYield(BigDecimal.valueOf(0.015));

        ListingDto dto = ListingMapper.toDto(listing);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getTicker()).isEqualTo("AAPL");
        assertThat(dto.getName()).isEqualTo("Apple Inc.");
        assertThat(dto.getListingType()).isEqualTo("STOCK");
        assertThat(dto.getChangePercent()).isEqualByComparingTo(
                BigDecimal.valueOf(500).divide(BigDecimal.valueOf(145), 2, RoundingMode.HALF_UP));
        assertThat(dto.getMaintenanceMargin()).isEqualByComparingTo(BigDecimal.valueOf(75));
        assertThat(dto.getInitialMarginCost()).isEqualByComparingTo(BigDecimal.valueOf(82.5));
        assertThat(dto.getMarketCap()).isEqualByComparingTo(BigDecimal.valueOf(150000000));
    }

    @Test
    void toDto_forex_calculatesForexMargin() {
        Listing listing = new Listing();
        listing.setListingType(ListingType.FOREX);
        listing.setPrice(BigDecimal.valueOf(1.10));
        listing.setContractSize(100000);
        ListingDto dto = ListingMapper.toDto(listing);
        assertThat(dto.getMaintenanceMargin()).isEqualByComparingTo(BigDecimal.valueOf(11000));
        assertThat(dto.getInitialMarginCost()).isEqualByComparingTo(BigDecimal.valueOf(12100));
        assertThat(dto.getMarketCap()).isNull();
    }

    @Test
    void toDto_futures_calculatesFuturesMargin() {
        Listing listing = new Listing();
        listing.setListingType(ListingType.FUTURES);
        listing.setPrice(BigDecimal.valueOf(75));
        listing.setContractSize(1000);
        listing.setContractUnit("barrel");
        listing.setSettlementDate(LocalDate.of(2026, 6, 1));
        ListingDto dto = ListingMapper.toDto(listing);
        assertThat(dto.getMaintenanceMargin()).isEqualByComparingTo(BigDecimal.valueOf(7500));
        assertThat(dto.getContractUnit()).isEqualTo("barrel");
        assertThat(dto.getSettlementDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void toDto_forexNullContractSize_defaultsTo1000_perSpec_OT1048() {
        // OT-1048 [FIXED 02.06]: spec §162 "ForexPair Contract Size = standardno 1000".
        // Ranije je ListingMapper (i ceo codebase: OrderMapper/OrderServiceImpl/
        // TaxRealizedGainCalculator) slepo default-ovao null contractSize na 1 →
        // FOREX margin 1×1.10×10% = 0.11 (mis-priced za faktor 1000). Sad se default
        // razresava po tipu hartije preko ContractSize.resolve → FOREX → 1000:
        //   maintenanceMargin = 1000 × 1.10 × 10% = 110.00.
        // Isti resolver koristi i order-engine (OrderServiceImpl) i porez, pa display
        // margin NE divergira od stvarno rezervisanog iznosa.
        Listing listing = new Listing();
        listing.setListingType(ListingType.FOREX);
        listing.setPrice(BigDecimal.valueOf(1.10));
        listing.setContractSize(null);
        ListingDto dto = ListingMapper.toDto(listing);
        assertThat(dto.getMaintenanceMargin()).isEqualByComparingTo(BigDecimal.valueOf(110));
        // initialMarginCost = maintenance × 1.1 = 121.00
        assertThat(dto.getInitialMarginCost()).isEqualByComparingTo(BigDecimal.valueOf(121));
    }

    @Test
    void toDto_futuresNullContractSize_defaultsTo1_OT1048() {
        // OT-1048: FUTURES nema univerzalni spec default (contract size dolazi sa API-ja
        // po hartiji — CME Crude=1000, Gold=100), pa fallback ostaje neutralno 1 kad
        // izostane. FOREX-specific 1000 default se NE primenjuje na FUTURES.
        Listing listing = new Listing();
        listing.setListingType(ListingType.FUTURES);
        listing.setPrice(BigDecimal.valueOf(75));
        listing.setContractSize(null);
        ListingDto dto = ListingMapper.toDto(listing);
        // 1 × 75 × 10% = 7.5
        assertThat(dto.getMaintenanceMargin()).isEqualByComparingTo(BigDecimal.valueOf(7.5));
    }

    @Test
    void toDto_nullListingType_calculatedFieldsNull() {
        Listing listing = new Listing();
        listing.setListingType(null);
        listing.setPrice(BigDecimal.valueOf(100));
        ListingDto dto = ListingMapper.toDto(listing);
        assertThat(dto.getListingType()).isNull();
        assertThat(dto.getMaintenanceMargin()).isNull();
        assertThat(dto.getInitialMarginCost()).isNull();
        assertThat(dto.getMarketCap()).isNull();
    }

    @Test
    void calculateChangePercent_nullPrice_returnsNull() {
        Listing l = new Listing();
        l.setPrice(null);
        l.setPriceChange(BigDecimal.ONE);
        assertThat(ListingMapper.calculateChangePercent(l)).isNull();
    }

    @Test
    void calculateChangePercent_nullChange_returnsNull() {
        Listing l = new Listing();
        l.setPrice(BigDecimal.valueOf(100));
        l.setPriceChange(null);
        assertThat(ListingMapper.calculateChangePercent(l)).isNull();
    }

    @Test
    void calculateChangePercent_previousPriceZero_returnsNull() {
        Listing l = new Listing();
        l.setPrice(BigDecimal.valueOf(5));
        l.setPriceChange(BigDecimal.valueOf(5));
        assertThat(ListingMapper.calculateChangePercent(l)).isNull();
    }

    @Test
    void calculateChangePercent_negativePreviousPrice_returnsNull_R1_731() {
        // R1-731: price=5, change=10 → previousPrice=-5 (negativna baza). % promena
        // u odnosu na ne-pozitivnu bazu je besmislena → null (umesto matematicki
        // validnog ali zavaravajuceg negativnog rezultata).
        Listing l = new Listing();
        l.setPrice(BigDecimal.valueOf(5));
        l.setPriceChange(BigDecimal.valueOf(10));
        assertThat(ListingMapper.calculateChangePercent(l)).isNull();
    }

    @Test
    void calculateMaintenanceMargin_nullPrice_returnsNull() {
        Listing l = new Listing();
        l.setListingType(ListingType.STOCK);
        l.setPrice(null);
        assertThat(ListingMapper.calculateMaintenanceMargin(l)).isNull();
    }

    @Test
    void calculateMaintenanceMargin_nullType_returnsNull() {
        Listing l = new Listing();
        l.setListingType(null);
        l.setPrice(BigDecimal.valueOf(100));
        assertThat(ListingMapper.calculateMaintenanceMargin(l)).isNull();
    }

    @Test
    void calculateInitialMarginCost_whenMaintenanceNull_returnsNull() {
        Listing l = new Listing();
        l.setListingType(null);
        l.setPrice(BigDecimal.valueOf(100));
        assertThat(ListingMapper.calculateInitialMarginCost(l)).isNull();
    }

    @Test
    void calculateMarketCap_nonStock_returnsNull() {
        Listing l = new Listing();
        l.setListingType(ListingType.FOREX);
        l.setOutstandingShares(1000L);
        l.setPrice(BigDecimal.valueOf(100));
        assertThat(ListingMapper.calculateMarketCap(l)).isNull();
    }

    @Test
    void calculateMarketCap_nullShares_returnsNull() {
        Listing l = new Listing();
        l.setListingType(ListingType.STOCK);
        l.setOutstandingShares(null);
        l.setPrice(BigDecimal.valueOf(100));
        assertThat(ListingMapper.calculateMarketCap(l)).isNull();
    }

    @Test
    void calculateMarketCap_nullPrice_returnsNull() {
        Listing l = new Listing();
        l.setListingType(ListingType.STOCK);
        l.setOutstandingShares(1000L);
        l.setPrice(null);
        assertThat(ListingMapper.calculateMarketCap(l)).isNull();
    }

    @Test
    void toDailyPriceDto_null_returnsNull() {
        assertThat(ListingMapper.toDailyPriceDto(null)).isNull();
    }

    @Test
    void toDailyPriceDto_mapsAllFields() {
        ListingDailyPriceInfo info = new ListingDailyPriceInfo();
        info.setDate(LocalDate.of(2026, 4, 1));
        info.setPrice(BigDecimal.valueOf(150));
        info.setHigh(BigDecimal.valueOf(155));
        info.setLow(BigDecimal.valueOf(148));
        info.setChange(BigDecimal.valueOf(3));
        info.setVolume(2000000L);

        ListingDailyPriceDto dto = ListingMapper.toDailyPriceDto(info);

        assertThat(dto.getDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(dto.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(150));
        assertThat(dto.getHigh()).isEqualByComparingTo(BigDecimal.valueOf(155));
        assertThat(dto.getLow()).isEqualByComparingTo(BigDecimal.valueOf(148));
        assertThat(dto.getChange()).isEqualByComparingTo(BigDecimal.valueOf(3));
        assertThat(dto.getVolume()).isEqualTo(2000000L);
    }
}
