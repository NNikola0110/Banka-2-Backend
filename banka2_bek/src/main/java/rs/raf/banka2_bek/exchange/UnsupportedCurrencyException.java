package rs.raf.banka2_bek.exchange;

/**
 * Baca se kada {@link CurrencyConversionService} nema kurs za trazeni par valuta.
 *
 * <p>Faza 2f: {@code CurrencyConversionService} je u 2f-1 premesten u
 * {@code exchange} (monolit, genericki FX) paket; ova greska zivi uz njega tako
 * da {@code exchange} paket vise ne import-uje trgovinski {@code order} paket
 * (priprema za 2f-5 cutover — brisanje {@code order} paketa iz monolita).
 */
public class UnsupportedCurrencyException extends RuntimeException {
    public UnsupportedCurrencyException(String message) {
        super(message);
    }
}
