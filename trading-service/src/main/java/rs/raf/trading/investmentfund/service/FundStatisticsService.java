package rs.raf.trading.investmentfund.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.investmentfund.dto.FundStatisticsDto;
import rs.raf.trading.investmentfund.model.FundValueSnapshot;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.FundValueSnapshotRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B12 — Statistika investicionih fondova (spec Celina 4).
 *
 * <p>Iz dnevnih snapshot-ova fonda ({@link FundValueSnapshot}) racuna 4
 * standardne metrike performansi:</p>
 *
 * <ul>
 *   <li><b>Annualized Return</b> — geometric mean mesecnih prinosa anualizovan na 12 meseci.</li>
 *   <li><b>Volatility</b> — sample stddev mesecnih prinosa, anualizovan ({@code * sqrt(12)}).</li>
 *   <li><b>Max Drawdown</b> — najveci pad od high-water mark, izracunat nad svim dnevnim
 *       snapshot-ovima (ne mesecnim — preciznije hvata pikove i doline).</li>
 *   <li><b>Reward-to-Variability</b> — Sharpe-like racio {@code annualizedReturn / volatility}
 *       (risk-free rate = 0).</li>
 * </ul>
 *
 * <p>Sve metrike su procenti (npr. {@code 12.5} znaci 12.5%). {@code maxDrawdown} je
 * pozitivan broj. Sve procente su zaokruzene na 4 decimale (HALF_UP).</p>
 *
 * <p>Minimalan broj snapshot-ova za smisleno racunanje je
 * {@value #MIN_SNAPSHOTS_REQUIRED}. Ako ih ima manje, sve metrike su {@code null}
 * i {@code sufficientHistory == false}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FundStatisticsService {

    /** Minimum broj dnevnih snapshot-ova ispod kog su metrike nepouzdane. */
    public static final int MIN_SNAPSHOTS_REQUIRED = 30;

    /** MathContext za internu aritmetiku (16 cifara preciznosti). */
    private static final MathContext MC = MathContext.DECIMAL64;

    /** Konstante za anualizaciju mesecnih prinosa. */
    private static final BigDecimal MONTHS_IN_YEAR = BigDecimal.valueOf(12);
    private static final BigDecimal SQRT_12 = BigDecimal.valueOf(12).sqrt(MC);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final InvestmentFundRepository fundRepository;
    private final FundValueSnapshotRepository snapshotRepository;

    /**
     * Glavni ulazni point za {@code GET /funds/{id}/statistics}.
     *
     * @throws EntityNotFoundException ako fond ne postoji
     *         ({@link rs.raf.trading.investmentfund.controller.exception_handler.InvestmentFundExceptionHandler}
     *         ga mapira u HTTP 404).
     */
    @Transactional(readOnly = true)
    public FundStatisticsDto computeStatistics(Long fundId) {
        InvestmentFund fund = fundRepository.findById(fundId).orElseThrow(() ->
                new EntityNotFoundException("Fond sa ID " + fundId + " ne postoji."));

        List<FundValueSnapshot> snapshots = snapshotRepository
                .findByFundIdOrderBySnapshotDateAsc(fundId);

        int snapshotCount = snapshots.size();
        boolean sufficient = snapshotCount >= MIN_SNAPSHOTS_REQUIRED;

        FundStatisticsDto.FundStatisticsDtoBuilder builder = FundStatisticsDto.builder()
                .fundId(fund.getId())
                .fundName(fund.getName())
                .snapshotCount(snapshotCount)
                .sufficientHistory(sufficient);

        if (!sufficient) {
            return builder.build();
        }

        // Max drawdown radi nad RAW dnevnim snapshot-ovima — ne treba mesecne podatke.
        BigDecimal maxDrawdown = computeMaxDrawdown(snapshots);
        builder.maxDrawdown(scale4(maxDrawdown));

        List<BigDecimal> monthlyValues = extractMonthlyFirstValues(snapshots);
        List<BigDecimal> monthlyReturns = computeMonthlyReturns(monthlyValues);

        if (monthlyReturns.size() < 2) {
            // Imamo dovoljno DANA (>=30), ali ne dovoljno meseci za annualized return /
            // volatility / Sharpe. Vrati DTO sa maxDrawdown popunjenim a ostatak null.
            return builder.build();
        }

        BigDecimal averageMonthlyReturn = mean(monthlyReturns);
        BigDecimal annualizedReturn = computeAnnualizedReturn(averageMonthlyReturn);
        BigDecimal volatility = computeVolatility(monthlyReturns, averageMonthlyReturn);
        // Zaokruzi volatility PRE Sharpe racuna — ako je rounded volatility 0,
        // Sharpe je nedefinisan (uniform-growth slucaj). Sprecava BigDecimal precision
        // drift gde stddev bude ~1e-14 pa Sharpe eksplodira na nesmislene 1e14 %.
        BigDecimal volatilityScaled = scale4(volatility);
        BigDecimal rewardToVariability = computeRewardToVariability(annualizedReturn, volatilityScaled);

        return builder
                .annualizedReturn(scale4(annualizedReturn))
                .volatility(volatilityScaled)
                .rewardToVariability(rewardToVariability != null
                        ? scale4(rewardToVariability) : null)
                .build();
    }

    // ----- helper metode (package-private za testabilnost) -----

    /**
     * Iz sortiranih snapshot-ova vraca vrednost prvog snapshot-a u svakom
     * kalendarskom mesecu (proxy za "value na pocetku meseca"). Ocekuje da je
     * lista sortirana po {@code snapshotDate ASC}.
     */
    List<BigDecimal> extractMonthlyFirstValues(List<FundValueSnapshot> sorted) {
        Map<YearMonth, FundValueSnapshot> firstPerMonth = new LinkedHashMap<>();
        for (FundValueSnapshot s : sorted) {
            YearMonth ym = YearMonth.from(s.getSnapshotDate());
            firstPerMonth.merge(ym, s, (existing, candidate) ->
                    candidate.getSnapshotDate().isBefore(existing.getSnapshotDate())
                            ? candidate : existing);
        }
        List<FundValueSnapshot> ordered = new ArrayList<>(firstPerMonth.values());
        ordered.sort(Comparator.comparing(FundValueSnapshot::getSnapshotDate));
        List<BigDecimal> values = new ArrayList<>(ordered.size());
        for (FundValueSnapshot s : ordered) {
            values.add(s.getFundValue());
        }
        return values;
    }

    /**
     * Iz mesecnih vrednosti racuna listu mesecnih prinosa kao raw decimale
     * (npr. {@code 0.012} znaci 1.2%). {@code returns[i] = (v[i+1] - v[i]) / v[i]}.
     */
    List<BigDecimal> computeMonthlyReturns(List<BigDecimal> monthlyValues) {
        List<BigDecimal> returns = new ArrayList<>(Math.max(0, monthlyValues.size() - 1));
        for (int i = 1; i < monthlyValues.size(); i++) {
            BigDecimal prev = monthlyValues.get(i - 1);
            BigDecimal curr = monthlyValues.get(i);
            if (prev.signum() == 0) {
                // izbegni deljenje nulom; treat as 0% promena
                returns.add(BigDecimal.ZERO);
            } else {
                returns.add(curr.subtract(prev, MC).divide(prev, MC));
            }
        }
        return returns;
    }

    /**
     * Annualized return iz prosecnog mesecnog prinosa (geometric):
     * {@code ((1 + avgMonthly)^12 - 1) * 100}. Vraca procenat.
     */
    BigDecimal computeAnnualizedReturn(BigDecimal averageMonthlyReturn) {
        BigDecimal base = ONE.add(averageMonthlyReturn, MC);
        BigDecimal annualized;
        if (base.signum() <= 0) {
            // pow sa BigDecimal radi za negative base + int exponent — ali da budemo
            // safe, fallback na Math.pow (preciznost dovoljna za demo metrike)
            annualized = BigDecimal.valueOf(Math.pow(base.doubleValue(), 12.0));
        } else {
            annualized = base.pow(12, MC);
        }
        return annualized.subtract(ONE, MC).multiply(HUNDRED, MC);
    }

    /**
     * Annualized volatility iz mesecnih prinosa: sample stddev anualizovan sa
     * {@code sqrt(12)} i izrazen kao procenat.
     */
    BigDecimal computeVolatility(List<BigDecimal> monthlyReturns, BigDecimal meanReturn) {
        BigDecimal stddev = sampleStddev(monthlyReturns, meanReturn);
        return stddev.multiply(SQRT_12, MC).multiply(HUNDRED, MC);
    }

    /**
     * Max drawdown nad dnevnim snapshot-ovima. Vraca pozitivan procenat
     * (najveci pad od high-water mark). 0 ako nema drawdown-a.
     */
    BigDecimal computeMaxDrawdown(List<FundValueSnapshot> snapshots) {
        BigDecimal peak = snapshots.get(0).getFundValue();
        BigDecimal maxDD = BigDecimal.ZERO;
        for (FundValueSnapshot s : snapshots) {
            BigDecimal v = s.getFundValue();
            if (v.compareTo(peak) > 0) {
                peak = v;
            }
            if (peak.signum() > 0) {
                BigDecimal dd = peak.subtract(v, MC).divide(peak, MC);
                if (dd.compareTo(maxDD) > 0) {
                    maxDD = dd;
                }
            }
        }
        return maxDD.multiply(HUNDRED, MC);
    }

    /**
     * Sharpe-like racio: {@code annualizedReturn / volatility}.
     * Vraca {@code null} kad nema smisla (volatility nula ili null).
     */
    BigDecimal computeRewardToVariability(BigDecimal annualizedReturn, BigDecimal volatility) {
        if (annualizedReturn == null || volatility == null || volatility.signum() == 0) {
            return null;
        }
        return annualizedReturn.divide(volatility, MC);
    }

    /** Aritmeticki prosek. */
    BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v, MC);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), MC);
    }

    /**
     * Sample standardna devijacija: {@code sqrt(sum((x - mean)^2) / (n - 1))}.
     * Za {@code n < 2} vraca 0.
     */
    BigDecimal sampleStddev(List<BigDecimal> values, BigDecimal mean) {
        int n = values.size();
        if (n < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal sumSquaredDeviations = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal diff = v.subtract(mean, MC);
            sumSquaredDeviations = sumSquaredDeviations.add(diff.multiply(diff, MC), MC);
        }
        BigDecimal variance = sumSquaredDeviations.divide(BigDecimal.valueOf(n - 1L), MC);
        return variance.sqrt(MC);
    }

    /** Zaokruzi na 4 decimale (HALF_UP). */
    private BigDecimal scale4(BigDecimal v) {
        if (v == null) {
            return null;
        }
        return v.setScale(4, RoundingMode.HALF_UP);
    }
}
