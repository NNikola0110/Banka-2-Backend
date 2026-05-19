package rs.raf.banka2.contracts.internal;

import java.math.BigDecimal;

/**
 * Citanje listinga (hartije od vrednosti) po ticker-u za inter-bank sloj.
 *
 * <p>Posle 2f cutover-a {@code listings} tabela zivi samo u trading-service-u;
 * banka-core {@code interbank} paket cita listing metapodatke preko ovog DTO-a
 * ({@code TransactionExecutorService} validacija postojanja hartije,
 * {@code InterbankOtcWrapperService} obogacivanje DTO-a imenom/cenom/valutom).
 *
 * <p>{@code quoteCurrency}/{@code baseCurrency} su FOREX-specificna polja
 * (mogu biti {@code null} za STOCK/FUTURES) — wrapper ih koristi za razresavanje
 * valute prikaza.
 */
public record InternalListingDto(Long id, String ticker, String name,
                                 String listingType, BigDecimal price,
                                 String quoteCurrency, String baseCurrency) {
}
