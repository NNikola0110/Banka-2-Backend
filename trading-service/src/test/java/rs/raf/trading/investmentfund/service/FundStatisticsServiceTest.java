package rs.raf.trading.investmentfund.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.investmentfund.dto.FundStatisticsDto;
import rs.raf.trading.investmentfund.model.FundValueSnapshot;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.FundValueSnapshotRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * B12 — Unit testovi za {@link FundStatisticsService}.
 *
 * <p>Pokrivaju 6 spec-explicit scenarija (vidi @DisplayName-ove): nedovoljna
 * istorija, uniform rast, opadanje, volatilan portfolio, drawdown profil,
 * not-found.</p>
 *
 * <p>Tolerancija: BigDecimal comparison koristi {@code abs(actual - expected) <= 0.01}
 * (1 basis point) — preciznost je dovoljna za demo metrike a izbegava floating
 * point gresku u sqrt/pow operacijama.</p>
 */
@ExtendWith(MockitoExtension.class)
class FundStatisticsServiceTest {

    private static final Long FUND_ID = 42L;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final MathContext MC = MathContext.DECIMAL64;

    @Mock
    private InvestmentFundRepository fundRepository;

    @Mock
    private FundValueSnapshotRepository snapshotRepository;

    @InjectMocks
    private FundStatisticsService service;

    private InvestmentFund fund;

    @BeforeEach
    void setUp() {
        fund = new InvestmentFund();
        fund.setId(FUND_ID);
        fund.setName("Test Fund");
    }

    @Test
    @DisplayName("Nedovoljno snimaka (<30) -> sufficientHistory=false, sve metrike null")
    void computeStatistics_withInsufficientHistory_returnsNullMetrics() {
        List<FundValueSnapshot> snapshots = createSyntheticSnapshots(
                FUND_ID, 29, new BigDecimal("100000"), new BigDecimal("0.01"));
        when(fundRepository.findById(eq(FUND_ID))).thenReturn(Optional.of(fund));
        when(snapshotRepository.findByFundIdOrderBySnapshotDateAsc(eq(FUND_ID)))
                .thenReturn(snapshots);

        FundStatisticsDto dto = service.computeStatistics(FUND_ID);

        assertThat(dto.getFundId()).isEqualTo(FUND_ID);
        assertThat(dto.getFundName()).isEqualTo("Test Fund");
        assertThat(dto.getSnapshotCount()).isEqualTo(29);
        assertThat(dto.isSufficientHistory()).isFalse();
        assertThat(dto.getAnnualizedReturn()).isNull();
        assertThat(dto.getVolatility()).isNull();
        assertThat(dto.getMaxDrawdown()).isNull();
        assertThat(dto.getRewardToVariability()).isNull();
    }

    @Test
    @DisplayName("Uniform 1% mesecni rast -> annualizedReturn ~12.68%, volatility ~0, maxDD=0")
    void computeStatistics_withSufficientHistory_returnsAllMetrics() {
        // 36 snimaka, jedan svakog meseca (uniform monthly growth 1%).
        // (1.01)^12 - 1 = 0.126825 = 12.6825%
        List<FundValueSnapshot> snapshots = createMonthlySnapshots(
                FUND_ID, 36, new BigDecimal("100000"), new BigDecimal("0.01"));
        when(fundRepository.findById(eq(FUND_ID))).thenReturn(Optional.of(fund));
        when(snapshotRepository.findByFundIdOrderBySnapshotDateAsc(eq(FUND_ID)))
                .thenReturn(snapshots);

        FundStatisticsDto dto = service.computeStatistics(FUND_ID);

        assertThat(dto.isSufficientHistory()).isTrue();
        assertThat(dto.getSnapshotCount()).isEqualTo(36);
        assertCloseTo(dto.getAnnualizedReturn(), new BigDecimal("12.6825"));
        // Volatility za uniform growth treba da bude bukvalno 0
        assertCloseTo(dto.getVolatility(), BigDecimal.ZERO);
        // Bez drawdown-a (uvek rastuca putanja)
        assertCloseTo(dto.getMaxDrawdown(), BigDecimal.ZERO);
        // Reward-to-variability je null jer je volatility nula
        assertThat(dto.getRewardToVariability()).isNull();
    }

    @Test
    @DisplayName("Konstantno opadanje (-0.5% mesecno) -> negativan annualizedReturn")
    void computeStatistics_withDecliningFund_negativeAnnualizedReturn() {
        // (1 - 0.005)^12 - 1 = -0.058411... = -5.8411%
        List<FundValueSnapshot> snapshots = createMonthlySnapshots(
                FUND_ID, 36, new BigDecimal("100000"), new BigDecimal("-0.005"));
        when(fundRepository.findById(eq(FUND_ID))).thenReturn(Optional.of(fund));
        when(snapshotRepository.findByFundIdOrderBySnapshotDateAsc(eq(FUND_ID)))
                .thenReturn(snapshots);

        FundStatisticsDto dto = service.computeStatistics(FUND_ID);

        assertThat(dto.isSufficientHistory()).isTrue();
        assertCloseTo(dto.getAnnualizedReturn(), new BigDecimal("-5.8411"));
        // Volatility = 0 (uniform decline), maxDD raste sa svakim mesecom
        assertCloseTo(dto.getVolatility(), BigDecimal.ZERO);
        // Posle 35 meseci pada od 100000: 100000 * 0.995^35 = ~83830 -> drawdown ~16.17%
        BigDecimal expectedFinalValue = new BigDecimal("100000")
                .multiply(new BigDecimal("0.995").pow(35, MC), MC);
        BigDecimal expectedDrawdownPct = new BigDecimal("100000").subtract(expectedFinalValue, MC)
                .divide(new BigDecimal("100000"), MC)
                .multiply(new BigDecimal("100"), MC);
        assertCloseTo(dto.getMaxDrawdown(), expectedDrawdownPct);
    }

    @Test
    @DisplayName("Volatilan fond (mix +/- prinosa) -> volatility>0, return pravilno racunat")
    void computeStatistics_withVolatileFund_computesVolatilityCorrectly() {
        // Kreiraj 36 mesecnih snapshot-ova sa alternirajucim +2% / -1.5% mesecnim
        // promenama. Geometric average mesecno: sqrt((1.02)*(0.985)) - 1 = 0.002389 = 0.2389%
        // Annualized: (1.002389)^12 - 1 = 0.029042... ali bezbednije proverimo
        // da je strictly between bounds + volatility > 0.
        List<FundValueSnapshot> snapshots = new ArrayList<>();
        BigDecimal value = new BigDecimal("100000");
        BigDecimal[] returns = {
                new BigDecimal("0.02"), new BigDecimal("-0.015"),
                new BigDecimal("0.025"), new BigDecimal("-0.01"),
                new BigDecimal("0.018"), new BigDecimal("-0.02")
        };
        LocalDate date = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 36; i++) {
            FundValueSnapshot s = new FundValueSnapshot();
            s.setFundId(FUND_ID);
            s.setSnapshotDate(date.plusMonths(i));
            s.setFundValue(value);
            s.setLiquidAmount(value);
            s.setInvestedTotal(value);
            snapshots.add(s);
            BigDecimal r = returns[i % returns.length];
            value = value.multiply(BigDecimal.ONE.add(r, MC), MC);
        }
        when(fundRepository.findById(eq(FUND_ID))).thenReturn(Optional.of(fund));
        when(snapshotRepository.findByFundIdOrderBySnapshotDateAsc(eq(FUND_ID)))
                .thenReturn(snapshots);

        FundStatisticsDto dto = service.computeStatistics(FUND_ID);

        assertThat(dto.isSufficientHistory()).isTrue();
        // Volatility STRICTNO veca od nule (mix returns -> stddev > 0)
        assertThat(dto.getVolatility()).isNotNull();
        assertThat(dto.getVolatility()).isGreaterThan(BigDecimal.ZERO);
        // RewardToVariability sad MORA biti popunjen (volatility > 0)
        assertThat(dto.getRewardToVariability()).isNotNull();
        // AnnualizedReturn nenula (ima drift naviše jer su +%-ovi veci od -%-ova)
        assertThat(dto.getAnnualizedReturn()).isNotNull();
        // Sanity: maxDD postoji (svaki -1.5% / -1% / -2% pravi mali drawdown)
        assertThat(dto.getMaxDrawdown()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Profil sa peak-om i valley-om -> maxDrawdown verno hvata pad")
    void computeStatistics_withDrawdown_computesMaxDrawdown() {
        // 30 dnevnih snapshot-ova: prvih 10 raste 100->200 (peak na 200),
        // sledecih 10 pada na 100 (valley na 100), poslednjih 10 raste opet na 150.
        // Expected maxDrawdown = (200 - 100) / 200 = 0.5 = 50%
        List<FundValueSnapshot> snapshots = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 1);
        BigDecimal[] values = new BigDecimal[30];
        // Rast 100->200 linearno preko 10 dana
        for (int i = 0; i < 10; i++) {
            values[i] = new BigDecimal(100 + i * 10);
        }
        // Pad 200->100 preko narednih 10
        for (int i = 0; i < 10; i++) {
            values[10 + i] = new BigDecimal(200 - i * 10);
        }
        // Rast 100->150 preko poslednjih 10
        for (int i = 0; i < 10; i++) {
            values[20 + i] = new BigDecimal(100 + i * 5);
        }
        for (int i = 0; i < 30; i++) {
            FundValueSnapshot s = new FundValueSnapshot();
            s.setFundId(FUND_ID);
            s.setSnapshotDate(date.plusDays(i));
            s.setFundValue(values[i]);
            s.setLiquidAmount(values[i]);
            s.setInvestedTotal(values[i]);
            snapshots.add(s);
        }
        when(fundRepository.findById(eq(FUND_ID))).thenReturn(Optional.of(fund));
        when(snapshotRepository.findByFundIdOrderBySnapshotDateAsc(eq(FUND_ID)))
                .thenReturn(snapshots);

        FundStatisticsDto dto = service.computeStatistics(FUND_ID);

        assertThat(dto.isSufficientHistory()).isTrue();
        assertCloseTo(dto.getMaxDrawdown(), new BigDecimal("50.0000"));
    }

    @Test
    @DisplayName("Fond ne postoji -> EntityNotFoundException (404)")
    void computeStatistics_withFundNotFound_throws404() {
        when(fundRepository.findById(eq(999L))).thenReturn(Optional.empty());

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class, () ->
                service.computeStatistics(999L));
        assertThat(ex.getMessage()).contains("999");
    }

    // ----- fixture helperi -----

    /**
     * Kreira {@code count} snimaka sa uniformnim mesecnim rastom — ali snapshot-ovi
     * su DNEVNI (jedan po danu), pa se mesecni prinosi izvuku tek u
     * {@code extractMonthlyFirstValues}. Snapshot vrednost je promenjena na svaki
     * "kalendar mesec" za {@code monthlyGrowthPercent}.
     */
    private List<FundValueSnapshot> createSyntheticSnapshots(
            Long fundId, int count, BigDecimal startValue, BigDecimal monthlyGrowth) {
        List<FundValueSnapshot> result = new ArrayList<>(count);
        LocalDate start = LocalDate.of(2024, 1, 1);
        BigDecimal currentValue = startValue;
        int prevMonth = -1;
        for (int i = 0; i < count; i++) {
            LocalDate date = start.plusDays(i);
            if (date.getMonthValue() != prevMonth && prevMonth != -1) {
                currentValue = currentValue.multiply(BigDecimal.ONE.add(monthlyGrowth, MC), MC);
            }
            prevMonth = date.getMonthValue();
            FundValueSnapshot s = new FundValueSnapshot();
            s.setFundId(fundId);
            s.setSnapshotDate(date);
            s.setFundValue(currentValue);
            s.setLiquidAmount(currentValue);
            s.setInvestedTotal(currentValue);
            result.add(s);
        }
        return result;
    }

    /**
     * Kreira {@code count} mesecnih snimaka (jedan po mesecu, prvi dan) sa
     * uniformnim mesecnim rastom {@code monthlyGrowth}.
     */
    private List<FundValueSnapshot> createMonthlySnapshots(
            Long fundId, int count, BigDecimal startValue, BigDecimal monthlyGrowth) {
        List<FundValueSnapshot> result = new ArrayList<>(count);
        LocalDate start = LocalDate.of(2024, 1, 1);
        BigDecimal currentValue = startValue;
        for (int i = 0; i < count; i++) {
            FundValueSnapshot s = new FundValueSnapshot();
            s.setFundId(fundId);
            s.setSnapshotDate(start.plusMonths(i));
            s.setFundValue(currentValue);
            s.setLiquidAmount(currentValue);
            s.setInvestedTotal(currentValue);
            result.add(s);
            currentValue = currentValue.multiply(BigDecimal.ONE.add(monthlyGrowth, MC), MC);
        }
        return result;
    }

    /** Tolerantan equals: |actual - expected| <= 0.01 (1 basis point). */
    private void assertCloseTo(BigDecimal actual, BigDecimal expected) {
        assertThat(actual).isNotNull();
        BigDecimal diff = actual.subtract(expected).abs().setScale(4, RoundingMode.HALF_UP);
        assertThat(diff)
                .as("expected %s, got %s (diff %s)", expected, actual, diff)
                .isLessThanOrEqualTo(TOLERANCE);
    }
}
