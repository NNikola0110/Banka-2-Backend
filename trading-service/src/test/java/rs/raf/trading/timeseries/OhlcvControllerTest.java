package rs.raf.trading.timeseries;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito unit testovi za {@link OhlcvController} — verifikuju da kontroler
 * deleguje na {@link ListingPriceQueryService} i wrap-uje result u 200 OK
 * response. InfluxDB i Spring context nisu pokrenuti.
 */
@ExtendWith(MockitoExtension.class)
class OhlcvControllerTest {

    @Mock
    private ListingPriceQueryService queryService;

    @InjectMocks
    private OhlcvController controller;

    @Test
    void getOhlcvHistory_returnsCandles() {
        Instant t0 = Instant.parse("2026-05-26T10:00:00Z");
        List<OhlcvCandle> mockCandles = List.of(
                new OhlcvCandle(t0, 100.0, 105.0, 99.0, 102.0, 10_000L)
        );
        when(queryService.getOhlcvHistory(anyString(), any(Instant.class), any(Instant.class), anyString()))
                .thenReturn(mockCandles);

        ResponseEntity<List<OhlcvCandle>> response = controller.getOhlcvHistory(
                "AAPL",
                Instant.parse("2026-05-25T00:00:00Z"),
                Instant.parse("2026-05-26T23:59:59Z"),
                "15m"
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).close()).isEqualTo(102.0);
        assertThat(response.getBody().get(0).volume()).isEqualTo(10_000L);
        verify(queryService).getOhlcvHistory(
                "AAPL",
                Instant.parse("2026-05-25T00:00:00Z"),
                Instant.parse("2026-05-26T23:59:59Z"),
                "15m"
        );
    }

    @Test
    void getOhlcvHistory_emptyResultsReturnsEmptyList() {
        when(queryService.getOhlcvHistory(anyString(), any(Instant.class), any(Instant.class), anyString()))
                .thenReturn(List.of());

        Instant now = Instant.now();
        ResponseEntity<List<OhlcvCandle>> response = controller.getOhlcvHistory(
                "UNKNOWN",
                now.minusSeconds(86_400),
                now,
                "1h"
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEmpty();
    }
}
