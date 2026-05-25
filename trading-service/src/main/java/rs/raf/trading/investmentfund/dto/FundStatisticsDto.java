package rs.raf.trading.investmentfund.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * DTO sa statistickim metrikama performansi jednog investicionog fonda.
 *
 * <p>Vraca se kao odgovor na {@code GET /funds/{id}/statistics}.</p>
 *
 * <p>Metrike ({@code annualizedReturn}, {@code volatility}, {@code maxDrawdown},
 * {@code rewardToVariability}) su {@code null} kada nema dovoljno istorije
 * ({@code snapshotCount < 30}). U tom slucaju {@code sufficientHistory == false}
 * i klijent treba da prikaze "premalo podataka" poruku.</p>
 *
 * <p>Sve procentualne vrednosti su izrazene kao "celi procenat" (npr. {@code 12.5}
 * znaci 12.5%) zaokruzeno na 4 decimale (HALF_UP). Spec: TODO_final C4 #15,
 * Zadaci_Backend.pdf B12.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundStatisticsDto {

    /** ID fonda na koji se statistike odnose. */
    private Long fundId;

    /** Ime fonda (radi citljivosti odgovora, bez dodatnog poziva). */
    private String fundName;

    /** Ukupan broj dnevnih snimaka korisceni za racunanje. */
    private int snapshotCount;

    /** {@code true} ako je snapshotCount &gt;= 30 (minimum za smislene metrike). */
    private boolean sufficientHistory;

    /**
     * Annualizovani prinos u procentima (geometric mean mesecnih prinosa anualizovan).
     * {@code null} ako nema dovoljno istorije.
     */
    private BigDecimal annualizedReturn;

    /**
     * Volatilnost (anualizovana standardna devijacija mesecnih prinosa) u procentima.
     * {@code null} ako nema dovoljno mesecnih tacaka.
     */
    private BigDecimal volatility;

    /**
     * Maksimalni drawdown (najveci pad od high-water mark) u procentima.
     * Vraca se kao pozitivan broj (npr. {@code 8.25} znaci 8.25% pad).
     * {@code null} ako nema dovoljno istorije.
     */
    private BigDecimal maxDrawdown;

    /**
     * Sharpe-like racio: {@code annualizedReturn / volatility} (risk-free rate = 0).
     * {@code null} ako je volatilnost null ili nula, ili annualizedReturn null.
     */
    private BigDecimal rewardToVariability;
}
