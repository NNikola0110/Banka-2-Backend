package rs.raf.trading.option.mapper;

import org.junit.jupiter.api.Test;
import rs.raf.trading.option.dto.OptionDto;
import rs.raf.trading.option.model.Option;
import rs.raf.trading.option.model.OptionType;
import rs.raf.trading.stock.model.Listing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testovi za {@link OptionMapper} — copy-first ekstrakcija (faza 2d-C).
 * Cista logika mapiranja, portovano verbatim (samo package rename).
 */
class OptionMapperTest {

    @Test
    void toDto_null_returnsNull() {
        assertThat(OptionMapper.toDto(null, BigDecimal.TEN)).isNull();
    }

    @Test
    void toDto_callOption_inTheMoney() {
        Listing stock = new Listing();
        stock.setId(50L);
        stock.setTicker("AAPL");
        stock.setName("Apple Inc.");

        Option option = new Option();
        option.setId(1L);
        option.setTicker("AAPL260402C00185000");
        option.setOptionType(OptionType.CALL);
        option.setStrikePrice(BigDecimal.valueOf(185));
        option.setPrice(BigDecimal.valueOf(5.50));
        option.setAsk(BigDecimal.valueOf(5.60));
        option.setBid(BigDecimal.valueOf(5.40));
        option.setImpliedVolatility(0.35);
        option.setOpenInterest(1200);
        option.setVolume(500L);
        option.setSettlementDate(LocalDate.of(2026, 4, 2));
        option.setContractSize(100);
        option.setMaintenanceMargin(BigDecimal.valueOf(9500));
        option.setStockListing(stock);
        option.setCreatedAt(LocalDateTime.of(2026, 3, 1, 10, 0));

        BigDecimal currentPrice = BigDecimal.valueOf(190); // > strike 185 => ITM for CALL

        OptionDto dto = OptionMapper.toDto(option, currentPrice);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getTicker()).isEqualTo("AAPL260402C00185000");
        assertThat(dto.getOptionType()).isEqualTo("CALL");
        assertThat(dto.getStrikePrice()).isEqualByComparingTo(BigDecimal.valueOf(185));
        assertThat(dto.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(5.50));
        assertThat(dto.getAsk()).isEqualByComparingTo(BigDecimal.valueOf(5.60));
        assertThat(dto.getBid()).isEqualByComparingTo(BigDecimal.valueOf(5.40));
        assertThat(dto.getImpliedVolatility()).isEqualTo(0.35);
        assertThat(dto.getOpenInterest()).isEqualTo(1200);
        assertThat(dto.getVolume()).isEqualTo(500L);
        assertThat(dto.getSettlementDate()).isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(dto.getContractSize()).isEqualTo(100);
        assertThat(dto.getMaintenanceMargin()).isEqualByComparingTo(BigDecimal.valueOf(9500));
        assertThat(dto.getStockTicker()).isEqualTo("AAPL");
        assertThat(dto.getStockName()).isEqualTo("Apple Inc.");
        assertThat(dto.getStockListingId()).isEqualTo(50L);
        assertThat(dto.getCurrentStockPrice()).isEqualByComparingTo(BigDecimal.valueOf(190));
        assertThat(dto.isInTheMoney()).isTrue();
    }

    @Test
    void toDto_nullMaintenanceMargin_dtoFieldIsNull() {
        Option option = new Option();
        option.setId(8L);
        option.setTicker("TEST");
        option.setOptionType(OptionType.CALL);
        option.setStrikePrice(BigDecimal.valueOf(100));
        option.setMaintenanceMargin(null);

        OptionDto dto = OptionMapper.toDto(option, BigDecimal.TEN);

        assertThat(dto.getMaintenanceMargin()).isNull();
    }

    @Test
    void toDto_callOption_outOfTheMoney() {
        Option option = new Option();
        option.setId(2L);
        option.setTicker("AAPL260402C00200000");
        option.setOptionType(OptionType.CALL);
        option.setStrikePrice(BigDecimal.valueOf(200));

        BigDecimal currentPrice = BigDecimal.valueOf(190); // < strike 200 => OTM for CALL

        OptionDto dto = OptionMapper.toDto(option, currentPrice);

        assertThat(dto.isInTheMoney()).isFalse();
    }

    @Test
    void toDto_putOption_inTheMoney() {
        Option option = new Option();
        option.setId(3L);
        option.setTicker("AAPL260402P00200000");
        option.setOptionType(OptionType.PUT);
        option.setStrikePrice(BigDecimal.valueOf(200));

        BigDecimal currentPrice = BigDecimal.valueOf(190); // < strike 200 => ITM for PUT

        OptionDto dto = OptionMapper.toDto(option, currentPrice);

        assertThat(dto.isInTheMoney()).isTrue();
    }

    @Test
    void toDto_putOption_outOfTheMoney() {
        Option option = new Option();
        option.setId(4L);
        option.setTicker("AAPL260402P00185000");
        option.setOptionType(OptionType.PUT);
        option.setStrikePrice(BigDecimal.valueOf(185));

        BigDecimal currentPrice = BigDecimal.valueOf(190); // > strike 185 => OTM for PUT

        OptionDto dto = OptionMapper.toDto(option, currentPrice);

        assertThat(dto.isInTheMoney()).isFalse();
    }

    @Test
    void toDto_callOption_atTheMoney_notInTheMoney() {
        // OT-1099: ATM granica — current == strike. Za CALL je ITM samo kad je
        // current STRIKTNO vece od strike-a (compareTo > 0). Na granici (jednako)
        // → NIJE in-the-money. Postojeci testovi pokrivaju > i < ali ne == granicu.
        Option option = new Option();
        option.setId(20L);
        option.setTicker("AAPL260402C00185000");
        option.setOptionType(OptionType.CALL);
        option.setStrikePrice(BigDecimal.valueOf(185));

        BigDecimal currentPrice = BigDecimal.valueOf(185); // == strike => ATM

        OptionDto dto = OptionMapper.toDto(option, currentPrice);

        assertThat(dto.isInTheMoney()).isFalse();
    }

    @Test
    void toDto_putOption_atTheMoney_notInTheMoney() {
        // OT-1099: ATM granica za PUT — ITM samo kad je current STRIKTNO manje od
        // strike-a (compareTo < 0). Na granici (jednako) → NIJE in-the-money.
        Option option = new Option();
        option.setId(21L);
        option.setTicker("AAPL260402P00185000");
        option.setOptionType(OptionType.PUT);
        option.setStrikePrice(BigDecimal.valueOf(185));

        BigDecimal currentPrice = BigDecimal.valueOf(185); // == strike => ATM

        OptionDto dto = OptionMapper.toDto(option, currentPrice);

        assertThat(dto.isInTheMoney()).isFalse();
    }

    @Test
    void toDto_atTheMoney_scaleDifference_stillNotInTheMoney() {
        // OT-1099: ATM granica robustna na razliku skale (compareTo, ne equals):
        // 185 vs 185.00 su numericki jednaki → ATM → ni CALL ni PUT nije ITM.
        Option call = new Option();
        call.setId(22L);
        call.setTicker("X");
        call.setOptionType(OptionType.CALL);
        call.setStrikePrice(new BigDecimal("185.00"));

        OptionDto callDto = OptionMapper.toDto(call, new BigDecimal("185"));
        assertThat(callDto.isInTheMoney()).isFalse();

        Option put = new Option();
        put.setId(23L);
        put.setTicker("Y");
        put.setOptionType(OptionType.PUT);
        put.setStrikePrice(new BigDecimal("185"));

        OptionDto putDto = OptionMapper.toDto(put, new BigDecimal("185.00"));
        assertThat(putDto.isInTheMoney()).isFalse();
    }

    @Test
    void toDto_nullCurrentPrice_inTheMoneyNotSet() {
        Option option = new Option();
        option.setId(5L);
        option.setTicker("TEST");
        option.setOptionType(OptionType.CALL);
        option.setStrikePrice(BigDecimal.valueOf(100));

        OptionDto dto = OptionMapper.toDto(option, null);

        assertThat(dto.isInTheMoney()).isFalse(); // default false
        assertThat(dto.getCurrentStockPrice()).isNull();
    }

    @Test
    void toDto_nullStrikePrice_inTheMoneyNotSet() {
        Option option = new Option();
        option.setId(6L);
        option.setTicker("TEST");
        option.setOptionType(OptionType.CALL);
        option.setStrikePrice(null);

        OptionDto dto = OptionMapper.toDto(option, BigDecimal.valueOf(100));

        assertThat(dto.isInTheMoney()).isFalse();
    }

    @Test
    void toDto_nullStockListing_stockFieldsNotSet() {
        Option option = new Option();
        option.setId(7L);
        option.setTicker("TEST");
        option.setOptionType(OptionType.CALL);
        option.setStockListing(null);

        OptionDto dto = OptionMapper.toDto(option, BigDecimal.TEN);

        assertThat(dto.getStockTicker()).isNull();
        assertThat(dto.getStockName()).isNull();
        assertThat(dto.getStockListingId()).isNull();
    }
}
