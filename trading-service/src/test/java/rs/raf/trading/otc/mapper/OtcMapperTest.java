package rs.raf.trading.otc.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import rs.raf.trading.otc.dto.OtcContractDto;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.stock.model.Listing;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pokriva izvedenu {@code profit} kolonu na {@link OtcContractDto} (Celina 4
 * "Sklopljeni ugovori"): {@code profit = (currentPrice − strikePrice) × quantity − premium}
 * za call opciju (neto dobit ako se ugovor odmah iskoristi). Spec primeri:
 * Celina 4 (AAPL current 250, strike 200, qty 50, premium 1150 → 1350) i
 * Celina 5 Primer 1 (12500 − 10000 − 1150 = 1350). Polje je derived (ne cuva
 * se u bazi) i null-safe kad trenutna cena nije poznata; null premium = 0.
 */
class OtcMapperTest {

    private OtcContract contract(int quantity, String strike) {
        OtcContract c = new OtcContract();
        c.setId(7L);
        c.setBuyerId(1L);
        c.setBuyerRole("CLIENT");
        c.setSellerId(2L);
        c.setSellerRole("CLIENT");
        c.setQuantity(quantity);
        c.setStrikePrice(new BigDecimal(strike));
        c.setPremium(new BigDecimal("1150.00"));
        c.setSettlementDate(LocalDate.now().plusDays(30));
        Listing listing = new Listing();
        listing.setId(100L);
        listing.setTicker("AAPL");
        listing.setName("Apple Inc.");
        c.setListing(listing);
        return c;
    }

    @Nested
    @DisplayName("profit izvedeno polje")
    class Profit {

        @Test
        @DisplayName("ITM ugovor — spec primer (Celina 4/5): (250−200)×50 − 1150 = 1350")
        void profit_itm_positive() {
            // current 250, strike 200, qty 50, premium 1150 -> (250-200)*50 - 1150 = 1350
            OtcContract c = contract(50, "200");
            OtcContractDto dto = OtcMapper.toDto(c, "Kupac", "Prodavac", "USD",
                    new BigDecimal("250"));

            assertThat(dto.getProfit()).isEqualByComparingTo("1350.0000");
            assertThat(dto.getProfit().signum()).isPositive();
        }

        @Test
        @DisplayName("OTM ugovor — profit ≤ 0 (current ispod strike, umanjen za premiju)")
        void profit_otm_negativeOrZero() {
            // current 180, strike 200, qty 50, premium 1150 -> (180-200)*50 - 1150 = -2150
            OtcContract c = contract(50, "200");
            OtcContractDto dto = OtcMapper.toDto(c, "Kupac", "Prodavac", "USD",
                    new BigDecimal("180"));

            assertThat(dto.getProfit()).isEqualByComparingTo("-2150.0000");
            assertThat(dto.getProfit().signum()).isNotPositive();
        }

        @Test
        @DisplayName("at-the-money — intrinsicna 0, profit = −premium (−1150)")
        void profit_atTheMoney_zero() {
            // current 200, strike 200, qty 50, premium 1150 -> 0 - 1150 = -1150
            OtcContract c = contract(50, "200");
            OtcContractDto dto = OtcMapper.toDto(c, "Kupac", "Prodavac", "USD",
                    new BigDecimal("200"));

            assertThat(dto.getProfit()).isEqualByComparingTo("-1150.0000");
        }

        @Test
        @DisplayName("nepoznata trenutna cena (null) — profit null (isto kao currentPrice)")
        void profit_nullCurrentPrice_nullProfit() {
            OtcContract c = contract(50, "200");
            OtcContractDto dto = OtcMapper.toDto(c, "Kupac", "Prodavac", "USD", null);

            assertThat(dto.getCurrentPrice()).isNull();
            assertThat(dto.getProfit()).isNull();
        }

        @Test
        @DisplayName("scale 4 (HALF_UP) — ista konvencija kao strike/premium kolone")
        void profit_scale4() {
            // current 250.5, strike 200, qty 3, premium 1150 -> (250.5-200)*3 - 1150 = -998.5 -> -998.5000
            OtcContract c = contract(3, "200");
            OtcContractDto dto = OtcMapper.toDto(c, "Kupac", "Prodavac", "USD",
                    new BigDecimal("250.5"));

            assertThat(dto.getProfit()).isEqualByComparingTo("-998.5000");
            assertThat(dto.getProfit().scale()).isEqualTo(4);
        }

        @Test
        @DisplayName("null premija se tretira kao 0 — profit = (current − strike) × qty")
        void profit_nullPremium_treatedAsZero() {
            // current 250, strike 200, qty 50, premium null -> (250-200)*50 - 0 = 2500
            OtcContract c = contract(50, "200");
            c.setPremium(null);
            OtcContractDto dto = OtcMapper.toDto(c, "Kupac", "Prodavac", "USD",
                    new BigDecimal("250"));

            assertThat(dto.getProfit()).isEqualByComparingTo("2500.0000");
        }
    }
}
