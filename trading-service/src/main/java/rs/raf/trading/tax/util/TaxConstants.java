package rs.raf.trading.tax.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Konstante vezane za porez na kapitalnu dobit.
 *
 * Spec (Celina 3 - Porez u nasem sistemu): "Iznos poreza je 15%".
 * Pre je bio duplikovan u {@code TaxService.TAX_RATE} i inline u
 * {@code PortfolioService#getSummary}.
 */
public final class TaxConstants {

    private TaxConstants() {}

    /** Stopa poreza na kapitalnu dobit — 15%. */
    public static final BigDecimal TAX_RATE = new BigDecimal("0.15");

    /**
     * Skala (broj decimala) za obracun poreske obaveze. R1-737: pre je bio
     * inline {@code setScale(4, HALF_UP)} na 4+ mesta ({@code TaxService} +
     * {@code TaxCalculatorProcessor}); centralizovano da bi promena skale/zaokr.
     * bila na jednom mestu i da bi byte-identicnost inline/processor putanje bila
     * garantovana konstantom (ne kopijom literala).
     */
    public static final int TAX_SCALE = 4;

    /** Nacin zaokruzivanja poreske obaveze — HALF_UP (R1-737, vidi {@link #TAX_SCALE}). */
    public static final RoundingMode TAX_ROUNDING = RoundingMode.HALF_UP;

    /**
     * Pomocnik: pomnozi {@code profit} sa {@link #TAX_RATE} i zaokruzi na
     * {@link #TAX_SCALE} decimala ({@link #TAX_ROUNDING}). Centralizuje
     * {@code profit.multiply(TAX_RATE).setScale(4, HALF_UP)} obrazac.
     */
    public static BigDecimal computeTax(BigDecimal profit) {
        return profit.multiply(TAX_RATE).setScale(TAX_SCALE, TAX_ROUNDING);
    }
}
