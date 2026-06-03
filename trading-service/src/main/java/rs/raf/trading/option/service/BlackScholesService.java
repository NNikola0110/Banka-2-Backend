package rs.raf.trading.option.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Servis za izracunavanje cene opcija koristeci Black-Scholes model.
 *
 * Black-Scholes formula je standardni matematicki model za odredjivanje
 * teorijske cene evropskih opcija (call i put).
 *
 * FORMULA:
 * ========
 *
 * Pomocne promenljive:
 *   d1 = [ln(S/K) + (r + sigma^2 / 2) * T] / (sigma * sqrt(T))
 *   d2 = d1 - sigma * sqrt(T)
 *
 * Call cena:
 *   C = S * N(d1) - K * e^(-r*T) * N(d2)
 *
 * Put cena:
 *   P = K * e^(-r*T) * N(-d2) - S * N(-d1)
 *
 * Gde je:
 *   S     = trenutna cena akcije (spot price)
 *   K     = strike cena opcije
 *   T     = vreme do isteka u godinama (daysToExpiry / 365.0)
 *   r     = risk-free kamatna stopa (koristiti 0.05 = 5% kao default)
 *   sigma = implied volatility (npr. 0.25 = 25%)
 *   N(x)  = kumulativna funkcija normalne distribucije (CDF)
 *   ln    = prirodni logaritam
 *   e     = Ojlerova konstanta (Math.E)
 *
 * IMPLEMENTACIONE NAPOMENE:
 * =========================
 * - Koristiti Math.log() za ln, Math.exp() za e^x, Math.sqrt() za sqrt
 * - Za N(x) implementirati normalCDF() metodu (videti dole)
 * - Sve racunanje raditi u double, konvertovati u BigDecimal na kraju
 * - Ako je T <= 0 (opcija istekla), cena je max(0, intrinsicValue):
 *     CALL intrinsic = max(0, S - K)
 *     PUT intrinsic  = max(0, K - S)
 * - Ako je sigma <= 0, baciti IllegalArgumentException
 * - Rezultat zaokruziti na 4 decimale (scale = 4, HALF_UP)
 *
 * RISK-FREE RATE:
 * ===============
 * Podrazumevana vrednost: 0.05 (5% godisnje)
 * Moze se konfigurisati preko application.properties:
 *   option.risk-free-rate=0.05
 * Koristiti @Value("${option.risk-free-rate:0.05}") za injectovanje.
 */
@Service
public class BlackScholesService {

    /** Default risk-free kamatna stopa (5% godisnje). */
    private static final double DEFAULT_RISK_FREE_RATE = 0.05;

    /**
     * Izracunava cenu CALL opcije koristeci Black-Scholes formulu.
     *
     * @param spotPrice         S - trenutna cena akcije
     * @param strikePrice       K - strike cena opcije
     * @param timeToExpiryYears T - vreme do isteka u godinama
     * @param riskFreeRate      r - risk-free kamatna stopa
     * @param volatility        sigma - implied volatility
     * @return cena CALL opcije kao BigDecimal
     */
    public BigDecimal calculateCallPrice(double spotPrice, double strikePrice,
                                         double timeToExpiryYears, double riskFreeRate,
                                         double volatility) {
        if (volatility <= 0) throw new IllegalArgumentException("Volatility must be > 0");
        if (timeToExpiryYears <= 0) {
            return BigDecimal.valueOf(Math.max(0, spotPrice - strikePrice))
                    .setScale(4, java.math.RoundingMode.HALF_UP);
        }

        double[] d = computeD1D2(spotPrice, strikePrice, timeToExpiryYears, riskFreeRate, volatility);
        double d1 = d[0];
        double d2 = d[1];

        double callPrice = spotPrice * normalCDF(d1)
                - strikePrice * Math.exp(-riskFreeRate * timeToExpiryYears) * normalCDF(d2);

        // [P2-input-validation-1 / R1 459] clamp na 0 — za deep-OTM opcije
        // (ili numericku gresku N(x) aproksimacije) formula moze vratiti
        // marginalno negativnu cenu; opcija nema negativnu vrednost. Paritet sa
        // intrinsic-value granom (T<=0) koja vec radi max(0,...).
        return BigDecimal.valueOf(Math.max(0.0, callPrice)).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Izracunava cenu PUT opcije koristeci Black-Scholes formulu.
     *
     * @param spotPrice         S - trenutna cena akcije
     * @param strikePrice       K - strike cena opcije
     * @param timeToExpiryYears T - vreme do isteka u godinama
     * @param riskFreeRate      r - risk-free kamatna stopa
     * @param volatility        sigma - implied volatility
     * @return cena PUT opcije kao BigDecimal
     */
    public BigDecimal calculatePutPrice(double spotPrice, double strikePrice,
                                        double timeToExpiryYears, double riskFreeRate,
                                        double volatility) {
        if (volatility <= 0) throw new IllegalArgumentException("Volatility must be > 0");
        if (timeToExpiryYears <= 0) {
            return BigDecimal.valueOf(Math.max(0, strikePrice - spotPrice))
                    .setScale(4, java.math.RoundingMode.HALF_UP);
        }

        double[] d = computeD1D2(spotPrice, strikePrice, timeToExpiryYears, riskFreeRate, volatility);
        double d1 = d[0];
        double d2 = d[1];

        double putPrice = strikePrice * Math.exp(-riskFreeRate * timeToExpiryYears) * normalCDF(-d2)
                - spotPrice * normalCDF(-d1);

        // [P2-input-validation-1 / R1 459] clamp na 0 (vidi calculateCallPrice).
        return BigDecimal.valueOf(Math.max(0.0, putPrice)).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** Convenience metoda sa default risk-free rate-om. */
    public BigDecimal calculateCallPrice(double spotPrice, double strikePrice,
                                         double timeToExpiryYears, double volatility) {
        return calculateCallPrice(spotPrice, strikePrice, timeToExpiryYears,
                DEFAULT_RISK_FREE_RATE, volatility);
    }

    /** Convenience metoda sa default risk-free rate-om. */
    public BigDecimal calculatePutPrice(double spotPrice, double strikePrice,
                                        double timeToExpiryYears, double volatility) {
        return calculatePutPrice(spotPrice, strikePrice, timeToExpiryYears,
                DEFAULT_RISK_FREE_RATE, volatility);
    }

    /**
     * R1 762: zajednicki proracun Black-Scholes pomocnih promenljivih d1 i d2 —
     * identican za CALL i PUT cenu (ranije kopiran verbatim u obe metode).
     *
     * <pre>
     *   d1 = [ln(S/K) + (r + sigma^2/2)·T] / (sigma·sqrt(T))
     *   d2 = d1 − sigma·sqrt(T)
     * </pre>
     *
     * Pretpostavlja {@code timeToExpiryYears > 0} i {@code volatility > 0}
     * (pozivaoci to vec validiraju pre poziva).
     *
     * @return dvoelementni niz {@code {d1, d2}}
     */
    protected double[] computeD1D2(double spotPrice, double strikePrice,
                                   double timeToExpiryYears, double riskFreeRate,
                                   double volatility) {
        double sqrtT = Math.sqrt(timeToExpiryYears);
        double d1 = (Math.log(spotPrice / strikePrice)
                + (riskFreeRate + volatility * volatility / 2.0) * timeToExpiryYears)
                / (volatility * sqrtT);
        double d2 = d1 - volatility * sqrtT;
        return new double[] {d1, d2};
    }

    /**
     * Kumulativna funkcija normalne distribucije (CDF) - N(x).
     * Koristi Abramowitz & Stegun aproksimaciju (greska < 1.5e-7).
     *
     * @param x vrednost za koju se racuna CDF
     * @return N(x) - verovatnoca P(Z <= x) za standardnu normalnu distribuciju
     */
    protected double normalCDF(double x) {
        // Horner approximation (Abramowitz & Stegun, error < 1.5e-7)
        if (x < 0) return 1.0 - normalCDF(-x);
        double t = 1.0 / (1.0 + 0.2316419 * x);
        double d = 0.3989422804014327; // 1/sqrt(2*PI)
        double prob = d * Math.exp(-x * x / 2.0)
                * t * (0.3193815 + t * (-0.3565638 + t * (1.7814779
                + t * (-1.8212560 + t * 1.3302744))));
        return 1.0 - prob;
    }
}
