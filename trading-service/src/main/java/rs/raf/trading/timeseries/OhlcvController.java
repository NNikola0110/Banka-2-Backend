package rs.raf.trading.timeseries;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * REST kontroler za OHLCV time-series podatke (FE chart komponente).
 * <p>
 * Aktivira se sa {@code banka2.influx.enabled=true} (W1-T6) — paritet sa
 * {@link ListingPriceQueryService} koji je takodje gejtovan istim property-jem,
 * pa kontroler bez njega ne bi imao dependency da injekuje.
 *
 * <p>Endpoint: {@code GET /listings/{symbol}/ohlcv} sa query parametrima
 * {@code from}, {@code to} (ISO-8601 instant) i {@code window} (npr. 1m, 15m,
 * 1h, 1d). Vraca {@link OhlcvCandle} listu agregovanu Flux query-jem na
 * InfluxDB-u. Prazna lista je validan rezultat (npr. simbol jos nema tickove).
 */
@Tag(name = "OHLCV", description = "Time-series OHLCV svece za FE chart komponente")
@RestController
@RequestMapping("/listings")
@ConditionalOnProperty(name = "banka2.influx.enabled", havingValue = "true")
public class OhlcvController {

    private final ListingPriceQueryService queryService;

    public OhlcvController(ListingPriceQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Vraca OHLCV istoriju za simbol u zadatom vremenskom opsegu, agregovano
     * po prozoru {@code window} (Flux {@code aggregateWindow}). Podrzane
     * vrednosti window-a: {@code 1m}, {@code 5m}, {@code 15m}, {@code 1h},
     * {@code 4h}, {@code 1d}, {@code 1w}.
     */
    @Operation(
            summary = "OHLCV istorija za simbol",
            description = "Agregovani open/high/low/close/volume za FE chart. "
                    + "Prazna lista ako InfluxDB nema tickove u opsegu — nije greska."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OHLCV svece (mozda prazna lista)"),
            @ApiResponse(responseCode = "400", description = "Neispravan format from/to ili window")
    })
    @GetMapping("/{symbol}/ohlcv")
    public ResponseEntity<List<OhlcvCandle>> getOhlcvHistory(
            @Parameter(description = "Ticker (npr. AAPL, MSFT)", example = "AAPL")
            @PathVariable String symbol,
            @Parameter(description = "Pocetak opsega (ISO-8601 instant)", example = "2026-05-25T00:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Kraj opsega (ekskluzivno, ISO-8601 instant)", example = "2026-05-26T23:59:59Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Velicina agregacionog prozora", example = "15m")
            @RequestParam(defaultValue = "15m") String window) {
        List<OhlcvCandle> candles = queryService.getOhlcvHistory(symbol, from, to, window);
        return ResponseEntity.ok(candles);
    }
}
