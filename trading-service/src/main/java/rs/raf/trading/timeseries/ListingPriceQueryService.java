package rs.raf.trading.timeseries;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cita agregirane OHLCV podatke iz InfluxDB-a za chart prikaz na FE-u.
 * <p>
 * Flux query agregira tickove u prozore (npr. 1d, 1h) — kalkulisuci open
 * (prvi tick u prozoru), high (max), low (min), close (poslednji), volume
 * (sum). Ovo zameni dnevne PG snapshot-e.
 */
@Service
@ConditionalOnProperty(name = "banka2.influx.enabled", havingValue = "true")
public class ListingPriceQueryService {

    private static final Logger log = LoggerFactory.getLogger(ListingPriceQueryService.class);

    /**
     * [P2-input-validation-1 / R1 541] Whitelist dozvoljenih agregacionih prozora.
     * Flux query string-interpolira {@code windowEvery} direktno (npr.
     * {@code aggregateWindow(every: 1d, ...)}) — proizvoljan input bi omogucio
     * Flux injection. Samo poznate vrednosti se propustaju.
     */
    private static final Set<String> ALLOWED_WINDOWS =
            Set.of("1m", "5m", "15m", "1h", "4h", "1d", "1w");

    /** Default prozor kad je prosledjeni nepoznat/null. */
    private static final String DEFAULT_WINDOW = "15m";

    /**
     * [P2-input-validation-1 / R1 541] Ticker je u Flux query-ju unutar string
     * literala ({@code r.ticker == "..."}). Dozvoljavamo samo bezopasne ticker
     * znake (slova, cifre, tacka, crta, donja crta) — sve ostalo (navodnici,
     * backslash, CR/LF) bi omogucilo izlazak iz literala i Flux injection.
     */
    private static final Pattern SAFE_TICKER = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");

    private final InfluxDBClient client;
    private final String bucket;

    public ListingPriceQueryService(
            InfluxDBClient client,
            @Value("${banka2.influx.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * Vraca OHLCV agregat za zadati ticker u zadatom prozoru.
     *
     * @param ticker        npr. "AAPL"
     * @param from          pocetak vremenskog opsega (inkluzivno)
     * @param to            kraj (ekskluzivno)
     * @param windowEvery   velicina agregacionog prozora (npr. "1d" za dnevni)
     */
    public List<OhlcvCandle> getOhlcvHistory(String ticker, Instant from, Instant to, String windowEvery) {
        // [P2-input-validation-1 / R1 540/541] validacija ulaza PRE Flux interpolacije.
        if (ticker == null || !SAFE_TICKER.matcher(ticker).matches()) {
            throw new IllegalArgumentException(
                    "Neispravan ticker (dozvoljeni su slova, cifre, '.', '-', '_' do 32 znaka).");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("from i to su obavezni.");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from mora biti <= to.");
        }
        String safeWindow = (windowEvery != null && ALLOWED_WINDOWS.contains(windowEvery))
                ? windowEvery
                : DEFAULT_WINDOW;
        if (windowEvery != null && !ALLOWED_WINDOWS.contains(windowEvery)) {
            throw new IllegalArgumentException(
                    "Nepodrzan window. Dozvoljeni: " + ALLOWED_WINDOWS);
        }

        // Flux query koristi `aggregateWindow` za grupisanje tickova u prozore,
        // pa pivot za rekonstrukciju OHLC strukture iz field-ova u redove.
        // Sve interpolirane vrednosti su prethodno validirane (whitelist) — nema
        // Flux injection povrsine (R1 541).
        String flux = """
                from(bucket: "%s")
                  |> range(start: %s, stop: %s)
                  |> filter(fn: (r) => r._measurement == "listing_price")
                  |> filter(fn: (r) => r.ticker == "%s")
                  |> filter(fn: (r) => r._field == "open" or r._field == "high"
                                       or r._field == "low" or r._field == "close" or r._field == "volume")
                  |> aggregateWindow(every: %s, fn: last, createEmpty: false)
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                  |> sort(columns: ["_time"])
                """.formatted(bucket, from.toString(), to.toString(), ticker, safeWindow);

        List<OhlcvCandle> candles = new ArrayList<>();
        try {
            List<FluxTable> tables = client.getQueryApi().query(flux);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    candles.add(new OhlcvCandle(
                            record.getTime(),
                            asDouble(record.getValueByKey("open")),
                            asDouble(record.getValueByKey("high")),
                            asDouble(record.getValueByKey("low")),
                            asDouble(record.getValueByKey("close")),
                            asLong(record.getValueByKey("volume"))
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("InfluxDB query nije uspeo za ticker={}: {}", ticker, e.getMessage());
        }
        return candles;
    }

    private static Double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
