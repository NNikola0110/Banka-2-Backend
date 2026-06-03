package rs.raf.trading.timeseries;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.InfluxDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end roundtrip: {@link ListingPriceRecorder#recordTick(String, String,
 * String, BigDecimal, BigDecimal, BigDecimal, BigDecimal, Long, BigDecimal,
 * BigDecimal, Instant)} → InfluxDB (Testcontainers) →
 * {@link ListingPriceQueryService#getOhlcvHistory(String, Instant, Instant, String)}.
 *
 * <p>Tezi 30-60s na prvom run-u (Docker pull {@code influxdb:2.7-alpine}),
 * potom je u kesu. Verifikuje da Recorder/Query rade preko realnog InfluxDB-a
 * (ne samo unit mock-a).
 */
@Testcontainers
@EnabledIf("dockerAvailable")
class OhlcvRecorderIntegrationTest {

    /**
     * JUnit5 ExecutionCondition (evaluira se PRE Testcontainers @BeforeAll-a koji
     * pokrece kontejner) — ceo test se cisto preskace ako Docker daemon nije
     * dostupan, umesto da puca sa container-startup greskom (CI / lokalno bez Docker-a).
     */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final String ORG = "banka2";
    private static final String BUCKET = "tick-listings";
    private static final String TOKEN = "test-token-32-bytes-minimum-for-influx-init";

    @Container
    static InfluxDBContainer<?> influx = new InfluxDBContainer<>(DockerImageName.parse("influxdb:2.7-alpine"))
            .withOrganization(ORG)
            .withBucket(BUCKET)
            .withAdminToken(TOKEN)
            .withUsername("admin")
            .withPassword("adminpass");

    @Test
    void recordTick_thenQuery_roundtrip() {
        try (InfluxDBClient client = InfluxDBClientFactory.create(
                influx.getUrl(),
                TOKEN.toCharArray(),
                ORG,
                BUCKET)) {

            WriteApiBlocking writeApi = client.getWriteApiBlocking();
            ListingPriceRecorder recorder = new ListingPriceRecorder(writeApi, BUCKET, ORG);
            ListingPriceQueryService queryService = new ListingPriceQueryService(client, BUCKET);

            Instant now = Instant.now();
            recorder.recordTick(
                    "AAPL", "NASDAQ", "STOCK",
                    new BigDecimal("185.0"),
                    new BigDecimal("190.0"),
                    new BigDecimal("184.0"),
                    new BigDecimal("188.5"),
                    10_000L,
                    new BigDecimal("188.0"),
                    new BigDecimal("189.0"),
                    now);

            // InfluxDB write je async-friendly cak i sa WriteApiBlocking;
            // dajemo malo prostora kontejneru da indeksira tacku pre query-ja.
            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> {
                        List<OhlcvCandle> candles = queryService.getOhlcvHistory(
                                "AAPL",
                                now.minusSeconds(60),
                                now.plusSeconds(60),
                                "1m");
                        assertThat(candles).isNotEmpty();
                        assertThat(candles.get(0).close()).isEqualTo(188.5);
                        assertThat(candles.get(0).volume()).isEqualTo(10_000L);
                    });
        }
    }
}
