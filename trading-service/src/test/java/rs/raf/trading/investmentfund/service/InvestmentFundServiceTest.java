package rs.raf.trading.investmentfund.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos;
import rs.raf.trading.investmentfund.model.FundValueSnapshot;
import rs.raf.trading.investmentfund.repository.FundValueSnapshotRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure-logic test {@code InvestmentFundService.getPerformance} (granularity
 * agregacija dnevnih snimaka).
 *
 * NAPOMENA (faza 2c): {@code getPerformance} cita samo {@code FundValueSnapshot}
 * (trading-service entitet) — nema money seam-a, pa je adaptacija mehanicka
 * (samo package rename). Ostale {@code InvestmentFundService} zavisnosti su
 * {@code @Mock}-ovane samo da {@code @InjectMocks} moze da instancira servis;
 * {@code getPerformance} ih ne dira.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class InvestmentFundServiceTest {

    @Mock
    private FundValueSnapshotRepository fundValueSnapshotRepository;
    @Mock
    private rs.raf.trading.investmentfund.repository.InvestmentFundRepository investmentFundRepository;
    @Mock
    private rs.raf.trading.investmentfund.repository.ClientFundPositionRepository clientFundPositionRepository;
    @Mock
    private rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository clientFundTransactionRepository;
    @Mock
    private rs.raf.trading.client.BankaCoreClient bankaCoreClient;
    @Mock
    private rs.raf.trading.security.TradingUserResolver tradingUserResolver;
    @Mock
    private rs.raf.trading.actuary.repository.ActuaryInfoRepository actuaryInfoRepository;
    @Mock
    private rs.raf.trading.portfolio.repository.PortfolioRepository portfolioRepository;
    @Mock
    private rs.raf.trading.stock.repository.ListingRepository listingRepository;
    @Mock
    private FundValueCalculator fundValueCalculator;
    @Mock
    private FundLiquidationService fundLiquidationService;
    @Mock
    private rs.raf.trading.order.service.CurrencyConversionService currencyConversionService;
    @Mock
    private rs.raf.trading.investmentfund.scheduler.FundValueSnapshotScheduler fundValueSnapshotScheduler;
    @Mock
    private rs.raf.trading.audit.service.AuditLogService auditLogService;
    @Mock
    private rs.raf.trading.notification.service.NotificationService notificationService;

    private InvestmentFundService investmentFundService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        investmentFundService = new InvestmentFundService(
                fundValueSnapshotRepository,
                investmentFundRepository,
                clientFundPositionRepository,
                clientFundTransactionRepository,
                bankaCoreClient,
                tradingUserResolver,
                actuaryInfoRepository,
                portfolioRepository,
                listingRepository,
                fundValueCalculator,
                fundLiquidationService,
                currencyConversionService,
                fundValueSnapshotScheduler,
                auditLogService,
                notificationService);
    }

    @Test
    void testGetPerformanceDayGranularity() {
        LocalDate start = LocalDate.of(2023, 1, 1);
        List<FundValueSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            snapshots.add(new FundValueSnapshot((long) i, 1L, start.plusDays(i), BigDecimal.valueOf(100 + i), BigDecimal.valueOf(50), BigDecimal.valueOf(80), BigDecimal.valueOf(20 + i)));
        }
        when(fundValueSnapshotRepository.findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(1L, start, start.plusDays(4))).thenReturn(snapshots);
        var result = investmentFundService.getPerformance(1L, start, start.plusDays(4), InvestmentFundDtos.Granularity.DAY);
        assertEquals(5, result.size());
        assertEquals(start, result.get(0).getDate());
        assertEquals(BigDecimal.valueOf(100), result.get(0).getFundValue());
        assertEquals(BigDecimal.valueOf(20), result.get(0).getProfit());
    }

    @Test
    void testGetPerformanceWeekGranularity() {
        LocalDate start = LocalDate.of(2023, 1, 1);
        List<FundValueSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            snapshots.add(new FundValueSnapshot((long) i, 1L, start.plusDays(i), BigDecimal.valueOf(100 + i), BigDecimal.valueOf(50), BigDecimal.valueOf(80), BigDecimal.valueOf(20 + i)));
        }
        when(fundValueSnapshotRepository.findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(1L, start, start.plusDays(14))).thenReturn(snapshots);
        var result = investmentFundService.getPerformance(1L, start, start.plusDays(14), InvestmentFundDtos.Granularity.WEEK);
        assertTrue(result.size() >= 2 && result.size() <= 3); // 2 or 3 weeks
    }

    @Test
    void testGetPerformanceMonthGranularity() {
        LocalDate start = LocalDate.of(2023, 1, 1);
        List<FundValueSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            snapshots.add(new FundValueSnapshot((long) i, 1L, start.plusDays(i), BigDecimal.valueOf(100 + i), BigDecimal.valueOf(50), BigDecimal.valueOf(80), BigDecimal.valueOf(20 + i)));
        }
        when(fundValueSnapshotRepository.findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(1L, start, start.plusDays(59))).thenReturn(snapshots);
        var result = investmentFundService.getPerformance(1L, start, start.plusDays(59), InvestmentFundDtos.Granularity.MONTH);
        assertTrue(result.size() >= 2 && result.size() <= 3); // 2 or 3 months
    }

    @Test
    void testGetPerformanceQuarterGranularity() {
        LocalDate start = LocalDate.of(2023, 1, 1);
        List<FundValueSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < 180; i++) {
            snapshots.add(new FundValueSnapshot((long) i, 1L, start.plusDays(i), BigDecimal.valueOf(100 + i), BigDecimal.valueOf(50), BigDecimal.valueOf(80), BigDecimal.valueOf(20 + i)));
        }
        when(fundValueSnapshotRepository.findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(1L, start, start.plusDays(179))).thenReturn(snapshots);
        var result = investmentFundService.getPerformance(1L, start, start.plusDays(179), InvestmentFundDtos.Granularity.QUARTER);
        assertTrue(result.size() >= 2 && result.size() <= 3); // 2 or 3 quarters
    }

    @Test
    void testGetPerformanceYearGranularity() {
        LocalDate start = LocalDate.of(2020, 1, 1);
        List<FundValueSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < 730; i++) {
            snapshots.add(new FundValueSnapshot((long) i, 1L, start.plusDays(i), BigDecimal.valueOf(100 + i), BigDecimal.valueOf(50), BigDecimal.valueOf(80), BigDecimal.valueOf(20 + i)));
        }
        when(fundValueSnapshotRepository.findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(1L, start, start.plusDays(729))).thenReturn(snapshots);
        var result = investmentFundService.getPerformance(1L, start, start.plusDays(729), InvestmentFundDtos.Granularity.YEAR);
        assertTrue(result.size() >= 2 && result.size() <= 3); // 2 or 3 years
    }

    @Test
    void testGetPerformanceEmptySnapshots_returnsEmptyList() {
        // OT-1140 (TEST-tr-funds-dividends-profitbank-1): kad nema nijednog
        // snapshot-a u opsegu, getPerformance vraca PRAZNU listu bez NPE-a
        // (rana grana `if (snapshots.isEmpty()) return List.of();`). Bilo koja
        // granularnost mora pre-empt-ovati agregaciju.
        LocalDate start = LocalDate.of(2023, 1, 1);
        when(fundValueSnapshotRepository
                .findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(1L, start, start.plusDays(30)))
                .thenReturn(new ArrayList<>());

        for (InvestmentFundDtos.Granularity g : InvestmentFundDtos.Granularity.values()) {
            var result = investmentFundService.getPerformance(1L, start, start.plusDays(30), g);
            assertNotNull(result);
            assertTrue(result.isEmpty(), "granularnost " + g + " mora vratiti praznu listu");
        }
    }

    @Test
    void testGetPerformanceMonthIntegration() {
        LocalDate start = LocalDate.of(2023, 1, 1);
        List<FundValueSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            snapshots.add(new FundValueSnapshot((long) i, 1L, start.plusDays(i), BigDecimal.valueOf(100 + i), BigDecimal.valueOf(50), BigDecimal.valueOf(80), BigDecimal.valueOf(20 + i)));
        }
        when(fundValueSnapshotRepository.findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(1L, start, start.plusDays(89))).thenReturn(snapshots);
        var result = investmentFundService.getPerformance(1L, start, start.plusDays(89), InvestmentFundDtos.Granularity.MONTH);
        assertEquals(3, result.size());
    }
}
