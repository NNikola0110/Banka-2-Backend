package rs.raf.trading.timeseries;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [P2-input-validation-1 / R1 540/541] Flux injection + input validacija za OHLCV upit.
 */
class ListingPriceQueryServiceTest {

    private InfluxDBClient client;
    private QueryApi queryApi;
    private ListingPriceQueryService service;

    @BeforeEach
    void setUp() {
        client = mock(InfluxDBClient.class);
        queryApi = mock(QueryApi.class);
        when(client.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString())).thenReturn(Collections.emptyList());
        service = new ListingPriceQueryService(client, "test-bucket");
    }

    @Test
    void validInput_buildsFluxAndQueries() {
        Instant from = Instant.parse("2026-05-25T00:00:00Z");
        Instant to = Instant.parse("2026-05-26T00:00:00Z");

        service.getOhlcvHistory("AAPL", from, to, "1d");

        ArgumentCaptor<String> flux = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(flux.capture());
        assertThat(flux.getValue()).contains("r.ticker == \"AAPL\"");
        assertThat(flux.getValue()).contains("every: 1d");
    }

    @Test
    void maliciousTickerWithQuote_rejected_noQuery() {
        Instant from = Instant.parse("2026-05-25T00:00:00Z");
        Instant to = Instant.parse("2026-05-26T00:00:00Z");
        // Pokusaj izlaska iz string literala u Flux-u.
        String injected = "AAPL\") |> drop(columns: [\"_value\"]) //";

        assertThatThrownBy(() -> service.getOhlcvHistory(injected, from, to, "1d"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(queryApi, never()).query(anyString());
    }

    @Test
    void maliciousWindow_rejected_noQuery() {
        Instant from = Instant.parse("2026-05-25T00:00:00Z");
        Instant to = Instant.parse("2026-05-26T00:00:00Z");

        assertThatThrownBy(() -> service.getOhlcvHistory("AAPL", from, to, "1d) |> yield("))
                .isInstanceOf(IllegalArgumentException.class);
        verify(queryApi, never()).query(anyString());
    }

    @Test
    void fromAfterTo_rejected() {
        Instant from = Instant.parse("2026-05-26T00:00:00Z");
        Instant to = Instant.parse("2026-05-25T00:00:00Z");

        assertThatThrownBy(() -> service.getOhlcvHistory("AAPL", from, to, "1d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
        verify(queryApi, never()).query(anyString());
    }

    @Test
    void nullWindow_fallsBackToDefault() {
        Instant from = Instant.parse("2026-05-25T00:00:00Z");
        Instant to = Instant.parse("2026-05-26T00:00:00Z");

        service.getOhlcvHistory("AAPL", from, to, null);

        ArgumentCaptor<String> flux = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(flux.capture());
        assertThat(flux.getValue()).contains("every: 15m");
    }

    @Test
    void nullTicker_rejected() {
        Instant from = Instant.parse("2026-05-25T00:00:00Z");
        Instant to = Instant.parse("2026-05-26T00:00:00Z");

        assertThatThrownBy(() -> service.getOhlcvHistory(null, from, to, "1d"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(queryApi, never()).query(anyString());
    }
}
