package rs.raf.trading.timeseries;

import java.time.Instant;

/**
 * OHLCV candle za jedan vremenski prozor.
 * <p>
 * Top-level record (extract iz {@link ListingPriceQueryService} u W1-T6) da bi
 * {@link OhlcvController} mogao da ga referencira bez kvalifikovanog imena
 * u JSON response body-ju. Vraca se iz GET /listings/{symbol}/ohlcv ka FE
 * chart komponentama.
 *
 * @param timestamp pocetak vremenskog prozora (UTC)
 * @param open      cena na pocetku prozora
 * @param high      maksimum u prozoru
 * @param low       minimum u prozoru
 * @param close     cena na kraju prozora
 * @param volume    suma volumena u prozoru
 */
public record OhlcvCandle(
        Instant timestamp,
        Double open,
        Double high,
        Double low,
        Double close,
        Long volume
) {}
