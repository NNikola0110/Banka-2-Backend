package rs.raf.trading.timeseries;

import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [TEST-tr-watchlist-recurring-influx-misc-1 / OT-1196] Karakterizacioni unit
 * test za {@link ListingPriceRecorder#recordTick}.
 *
 * <p>Recorder je bio "0-test" baseline (postojao je samo Testcontainers
 * roundtrip IT koji se preskace bez Docker-a). Ovaj test ne trazi Influx —
 * {@link WriteApiBlocking} je mockovan — pa pina cetiri invarijante koje su
 * inace nepokrivene lokalno:
 * <ul>
 *   <li>svi-prisutni-field-ovi se prosledjuju (write se zove TACNO jednom);</li>
 *   <li>{@code null} field-ovi se preskacu (open/high/.../bid su uslovni);</li>
 *   <li>{@code null} exchange/assetType padaju na default tag-ove
 *       ({@code "unknown"} / {@code "STOCK"});</li>
 *   <li>Influx write greska se PROGUTA (ne sme da pukne business operaciju —
 *       listing refresh / order execution rade i bez time-series persistencije).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ListingPriceRecorderTest {

    @Mock
    private WriteApiBlocking writeApi;

    private static final String BUCKET = "tick-listings";
    private static final String ORG = "banka2";

    private ListingPriceRecorder recorder() {
        return new ListingPriceRecorder(writeApi, BUCKET, ORG);
    }

    @Test
    @DisplayName("recordTick — svi field-ovi prisutni -> jedan write na (bucket, org)")
    void recordTick_allFieldsPresent_writesOnce() {
        Instant now = Instant.now();

        recorder().recordTick(
                "AAPL", "NASDAQ", "STOCK",
                new BigDecimal("185.0"),
                new BigDecimal("190.0"),
                new BigDecimal("184.0"),
                new BigDecimal("188.5"),
                10_000L,
                new BigDecimal("188.0"),
                new BigDecimal("189.0"),
                now);

        ArgumentCaptor<Point> captor = ArgumentCaptor.forClass(Point.class);
        verify(writeApi, times(1)).writePoint(eq(BUCKET), eq(ORG), captor.capture());
        // Line-protocol enkoding sadrzi measurement, tagove i sve field-ove.
        String lp = captor.getValue().toLineProtocol();
        assertThat(lp).startsWith("listing_price,");
        assertThat(lp).contains("ticker=AAPL");
        assertThat(lp).contains("exchange=NASDAQ");
        assertThat(lp).contains("asset_type=STOCK");
        assertThat(lp).contains("open=185");
        assertThat(lp).contains("close=188.5");
        assertThat(lp).contains("volume=10000");
        assertThat(lp).contains("ask=188");
        assertThat(lp).contains("bid=189");
    }

    @Test
    @DisplayName("recordTick — null field-ovi se preskacu, prisutni ostaju")
    void recordTick_nullFields_skipped() {
        recorder().recordTick(
                "MSFT", "NASDAQ", "STOCK",
                null,                       // open null -> izostavljen
                null,                       // high null
                null,                       // low null
                new BigDecimal("300.0"),    // close prisutan
                null,                       // volume null
                null,                       // ask null
                null,                       // bid null
                Instant.now());

        ArgumentCaptor<Point> captor = ArgumentCaptor.forClass(Point.class);
        verify(writeApi, times(1)).writePoint(eq(BUCKET), eq(ORG), captor.capture());
        String lp = captor.getValue().toLineProtocol();
        assertThat(lp).contains("close=300");
        // Preskoceni field-ovi NE smeju da se pojave.
        assertThat(lp).doesNotContain("open=");
        assertThat(lp).doesNotContain("high=");
        assertThat(lp).doesNotContain("low=");
        assertThat(lp).doesNotContain("volume=");
        assertThat(lp).doesNotContain("ask=");
        assertThat(lp).doesNotContain("bid=");
    }

    @Test
    @DisplayName("recordTick — null exchange/assetType -> default tag-ovi unknown/STOCK")
    void recordTick_nullExchangeAndAssetType_useDefaults() {
        recorder().recordTick(
                "GOOG", null, null,
                new BigDecimal("1.0"), new BigDecimal("1.0"),
                new BigDecimal("1.0"), new BigDecimal("1.0"),
                1L, null, null,
                Instant.now());

        ArgumentCaptor<Point> captor = ArgumentCaptor.forClass(Point.class);
        verify(writeApi, times(1)).writePoint(eq(BUCKET), eq(ORG), captor.capture());
        String lp = captor.getValue().toLineProtocol();
        assertThat(lp).contains("exchange=unknown");
        assertThat(lp).contains("asset_type=STOCK");
    }

    @Test
    @DisplayName("recordTick — Influx write greska se PROGUTA (ne propagira se pozivaocu)")
    void recordTick_influxWriteThrows_swallowed() {
        doThrow(new RuntimeException("influx down"))
                .when(writeApi).writePoint(any(), any(), any(Point.class));

        // Mora proci bez izuzetka — business operacija (listing refresh) se nastavlja.
        assertThatNoException().isThrownBy(() ->
                recorder().recordTick(
                        "AMZN", "NASDAQ", "STOCK",
                        new BigDecimal("100"), new BigDecimal("105"),
                        new BigDecimal("99"), new BigDecimal("102"),
                        500L, new BigDecimal("101"), new BigDecimal("103"),
                        Instant.now()));

        verify(writeApi, times(1)).writePoint(any(), any(), any(Point.class));
    }
}
