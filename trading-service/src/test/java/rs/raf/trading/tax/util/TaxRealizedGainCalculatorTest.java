package rs.raf.trading.tax.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression coverage za {@link TaxRealizedGainCalculator}, fokus na null-safety
 * {@code listingId} kljuca.
 *
 * <p><b>REGRESIJA (31.05):</b> {@code hasOtc(listingId, otcSell)} je radio
 * {@code otcSell.containsKey(listingId)}. Kada je {@code listingId == null}
 * (transient/detached {@link Listing} bez perzistovanog id-a, ili legacy/test
 * order) a {@code otcSell} je immutable prazna mapa ({@code Map.of()} —
 * {@code TaxService.calculateTaxForAllUsers} prosledjuje
 * {@code getOrDefault(key, Map.of())}), {@code Map.of().containsKey(null)} baca
 * {@link NullPointerException} (immutable mape zabranjuju null kljuc). To je
 * rusilo ceo tax obracun za korisnika. Stara (pre-B3) implementacija je koristila
 * {@code HashMap}/{@code HashSet} koji tolerisu null kljuc, pa je null listingId
 * prolazio bezopasno. Fix: {@code hasOtc} kratko spaja na {@code false} za null
 * listingId pre {@code containsKey} poziva.
 */
@DisplayName("TaxRealizedGainCalculator")
class TaxRealizedGainCalculatorTest {

    private Order stockOrder(OrderDirection dir, String price, int qty, Long listingId) {
        Order o = new Order();
        o.setId((long) (Math.random() * 10000));
        o.setDirection(dir);
        o.setPricePerUnit(new BigDecimal(price));
        o.setQuantity(qty);
        o.setContractSize(1);
        Listing listing = new Listing();
        listing.setId(listingId); // moze biti null — reprodukcija regresije
        listing.setListingType(ListingType.STOCK);
        listing.setQuoteCurrency("RSD");
        o.setListing(listing);
        return o;
    }

    @Test
    @DisplayName("REGRESIJA: null listingId + immutable prazna OTC mapa NE baca NPE u hasOtc")
    void nullListingIdWithImmutableEmptyOtcMapDoesNotThrow() {
        // Order sa null listingId (transient Listing bez id-a) — egzaktan scenario
        // koji ruse stari hasOtc na Map.of().containsKey(null).
        Order buy = stockOrder(OrderDirection.BUY, "100", 10, null);
        Order sell = stockOrder(OrderDirection.SELL, "150", 10, null);

        YearMonth period = YearMonth.from(LocalDateTime.now());

        assertThatCode(() ->
                TaxRealizedGainCalculator.computePeriodGains(
                        List.of(buy, sell),
                        Map.of(),   // immutable prazna OTC sell mapa — getOrDefault(key, Map.of())
                        Map.of(),   // immutable prazna OTC buy mapa
                        Map.of(),   // immutable prazna OTC currency mapa
                        period,
                        period))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null listingId order se i dalje racuna u realizovanu dobit (FIFO ocuvan)")
    void nullListingIdStillComputesGain() {
        // BUY 10 @100, SELL 10 @150 → realizovana dobit = (150-100)*10 = 500 RSD.
        Order buy = stockOrder(OrderDirection.BUY, "100", 10, null);
        Order sell = stockOrder(OrderDirection.SELL, "150", 10, null);

        YearMonth period = YearMonth.from(LocalDateTime.now());

        List<TaxRealizedGainCalculator.ListingRealizedGain> gains =
                TaxRealizedGainCalculator.computePeriodGains(
                        List.of(buy, sell), Map.of(), Map.of(), Map.of(), period, period);

        assertThat(gains).hasSize(1);
        TaxRealizedGainCalculator.ListingRealizedGain g = gains.get(0);
        assertThat(g.listingId()).isNull();
        assertThat(g.gainNative()).isEqualByComparingTo(new BigDecimal("500"));
        assertThat(g.listingCurrency()).isEqualTo("RSD");
    }

    @Test
    @DisplayName("FIFO cost-basis lot-matching: BUY 20 / SELL 15 nosi cost samo 15 prodatih")
    void fifoMatchesOnlySoldQuantity() {
        // B3 semantika: BUY 20 @100, SELL 15 @150 → dobit = 15*150 - 15*100 = 750,
        // NE 15*150 - 20*100 (= 250, stari sum(SELL)-sum(BUY) bug).
        Order buy = stockOrder(OrderDirection.BUY, "100", 20, 7L);
        Order sell = stockOrder(OrderDirection.SELL, "150", 15, 7L);

        YearMonth period = YearMonth.from(LocalDateTime.now());

        List<TaxRealizedGainCalculator.ListingRealizedGain> gains =
                TaxRealizedGainCalculator.computePeriodGains(
                        List.of(buy, sell), Map.of(), Map.of(), Map.of(), period, period);

        assertThat(gains).hasSize(1);
        assertThat(gains.get(0).gainNative()).isEqualByComparingTo(new BigDecimal("750"));
    }
}
