package rs.raf.trading.stock.service.implementation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestTemplate;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.pricealert.service.PriceAlertService;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingDailyPriceInfoRepository;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.timeseries.ListingPriceRecorder;
import rs.raf.trading.berza.repository.ExchangeRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [TEST-tr-watchlist-recurring-influx-misc-1 / OT-1198] Karakterizacija
 * recorder-call-site-a u {@code fetchPricesForListings} (Influx tick wiring).
 *
 * <p>Pina dve invarijante koje su inace nepokrivene (postojeci
 * {@code ListingServiceImplTest} koristi {@code @InjectMocks} koji NE injektuje
 * {@code ObjectProvider<ListingPriceRecorder>}, pa je recorder grana null):
 * <ul>
 *   <li>kada je recorder dostupan, {@code recordTick} dobija
 *       {@code high == max(currentPrice, newPrice)} i
 *       {@code low == min(currentPrice, newPrice)} (OHLC korektnost — bez
 *       obzira da li cena raste ili pada);</li>
 *   <li>kada recorder NIJE dostupan ({@code banka2.influx.enabled=false} →
 *       {@code getIfAvailable()} vraca null), {@code fetchPricesForListings}
 *       radi normalno (trading-service ne zavisi od Influx-a).</li>
 * </ul>
 *
 * <p>Koristi test-mode berzu (simulateTick) da izbegne realan Alpha Vantage /
 * fixer.io HTTP poziv — high/low racunica je nezavisna od izvora newPrice.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ListingServiceImplRecorderTest {

    @Mock private ListingRepository listingRepository;
    @Mock private ListingDailyPriceInfoRepository dailyPriceRepository;
    @Mock private RestTemplate restTemplate;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private ExchangeRepository exchangeRepository;
    @Mock private ObjectProvider<ListingPriceRecorder> priceRecorderProvider;
    @Mock private ObjectProvider<PriceAlertService> priceAlertServiceProvider;
    @Mock private ListingPriceRecorder recorder;

    private ListingServiceImpl service() {
        return new ListingServiceImpl(
                listingRepository,
                dailyPriceRepository,
                restTemplate,
                bankaCoreClient,
                exchangeRepository,
                priceRecorderProvider,
                priceAlertServiceProvider);
    }

    private Listing stock(String ticker, BigDecimal price) {
        Listing l = new Listing();
        l.setId(1L);
        l.setTicker(ticker);
        l.setName(ticker + " Inc.");
        l.setListingType(ListingType.STOCK);
        l.setPrice(price);
        l.setExchangeAcronym("TESTX");
        return l;
    }

    @Test
    @DisplayName("OT-1198: recordTick dobija high=max(current,new), low=min(current,new) (test-mode tick)")
    void fetchPrices_recorderAvailable_recordsHighLowFromCurrentAndNew() {
        when(priceRecorderProvider.getIfAvailable()).thenReturn(recorder);

        Listing l = stock("AAPL", new BigDecimal("150.00"));
        Map<String, Boolean> testMode = new HashMap<>();
        testMode.put("TESTX", Boolean.TRUE); // test-mode -> simulateTick (bez HTTP-a)

        service().fetchPricesForListings(List.of(l), testMode);

        ArgumentCaptor<BigDecimal> openCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> highCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> lowCap = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> closeCap = ArgumentCaptor.forClass(BigDecimal.class);

        verify(recorder, times(1)).recordTick(
                eq("AAPL"), eq("TESTX"), eq("STOCK"),
                openCap.capture(), highCap.capture(), lowCap.capture(), closeCap.capture(),
                anyLong(), any(), any(), any(Instant.class));

        BigDecimal open = openCap.getValue();    // = currentPrice (150)
        BigDecimal close = closeCap.getValue();   // = newPrice (simulirano)
        BigDecimal high = highCap.getValue();
        BigDecimal low = lowCap.getValue();

        assertThat(open).isEqualByComparingTo("150.00");
        // OHLC invarijanta: high je max(open, close), low je min(open, close).
        assertThat(high).isEqualByComparingTo(open.max(close));
        assertThat(low).isEqualByComparingTo(open.min(close));
        assertThat(high).isGreaterThanOrEqualTo(low);
    }

    @Test
    @DisplayName("OT-1198: bez recorder-a (Influx disabled) fetchPricesForListings je no-op za tick (ne pukne)")
    void fetchPrices_recorderNull_skipsTickGracefully() {
        when(priceRecorderProvider.getIfAvailable()).thenReturn(null);

        Listing l = stock("MSFT", new BigDecimal("300.00"));
        Map<String, Boolean> testMode = new HashMap<>();
        testMode.put("TESTX", Boolean.TRUE);

        service().fetchPricesForListings(List.of(l), testMode);

        // Cena se i dalje osvezi (in-memory mutacija), ali nijedan tick nije zapisan.
        assertThat(l.getLastRefresh()).isNotNull();
        verify(recorder, never()).recordTick(
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), anyLong(), any(), any(), any());
    }
}
