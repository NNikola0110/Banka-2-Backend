package rs.raf.trading.investmentfund.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.*;
import rs.raf.trading.investmentfund.model.*;
import rs.raf.trading.investmentfund.repository.*;
import rs.raf.trading.investmentfund.scheduler.FundValueSnapshotScheduler;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Core unit testovi {@link InvestmentFundService} — createFund / listDiscovery /
 * getFundDetails.
 *
 * NAPOMENA (faza 2c): monolitni {@code createFund} je gradio pun {@code Account}
 * preko {@code AccountRepository.save}; trading-service verzija provizionira
 * fond racun u banka-core domenu ({@link BankaCoreClient#provisionFundAccount}),
 * pa je {@code accountRepository.save} stub zamenjen
 * {@code bankaCoreClient.provisionFundAccount} stub-om — a asercija "racun je
 * kreiran" postaje {@code verify(bankaCoreClient).provisionFundAccount(...)}.
 * {@code getFundDetails} cita racun fonda preko {@link BankaCoreClient#getAccount}.
 * Ime managera razresava {@link TradingUserResolver} (banka-core identitet).
 * {@link ActuaryInfo} nosi soft {@code employeeId}.
 */
@ExtendWith(MockitoExtension.class)
class InvestmentFundServiceCoreTest {

    @Mock private FundValueSnapshotRepository fundValueSnapshotRepository;
    @Mock private InvestmentFundRepository investmentFundRepository;
    @Mock private ClientFundPositionRepository clientFundPositionRepository;
    @Mock private ClientFundTransactionRepository clientFundTransactionRepository;
    @Mock private BankaCoreClient bankaCoreClient;
    @Mock private TradingUserResolver tradingUserResolver;
    @Mock private ActuaryInfoRepository actuaryInfoRepository;
    @Mock private PortfolioRepository portfolioRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private FundValueCalculator fundValueCalculator;
    @Mock private FundLiquidationService fundLiquidationService;
    @Mock private CurrencyConversionService currencyConversionService;
    @Mock private FundValueSnapshotScheduler fundValueSnapshotScheduler;

    @InjectMocks
    private InvestmentFundService service;

    private ActuaryInfo buildActuaryInfo(Long employeeId, ActuaryType type) {
        ActuaryInfo ai = new ActuaryInfo();
        ai.setEmployeeId(employeeId);
        ai.setActuaryType(type);
        return ai;
    }

    private InternalAccountDto fundAccount(Long id, String number, String balance) {
        return new InternalAccountDto(id, number, "Banka 2 d.o.o.",
                new BigDecimal(balance), new BigDecimal(balance), BigDecimal.ZERO,
                "RSD", "ACTIVE", null, null, "FUND");
    }

    private InvestmentFund buildFund(Long id, String name, Long accountId, Long managerId) {
        InvestmentFund f = new InvestmentFund();
        f.setId(id);
        f.setName(name);
        f.setDescription("Opis fonda");
        f.setMinimumContribution(new BigDecimal("1000.00"));
        f.setManagerEmployeeId(managerId);
        f.setAccountId(accountId);
        f.setCreatedAt(LocalDateTime.now());
        f.setInceptionDate(LocalDate.now());
        f.setActive(true);
        return f;
    }

    @Test
    @DisplayName("createFund - happy path vraca detaljan DTO + provizionira fond racun")
    void createFund_happyPath() {
        Long supervisorId = 1L;
        CreateFundDto dto = new CreateFundDto("Test Fund", "Opis", new BigDecimal("500.00"));

        ActuaryInfo actuaryInfo = buildActuaryInfo(supervisorId, ActuaryType.SUPERVISOR);
        InternalAccountDto provisioned = fundAccount(10L, "222000100000000012", "0.00");
        InvestmentFund savedFund = buildFund(1L, dto.getName(), 10L, supervisorId);

        when(investmentFundRepository.findByName(dto.getName())).thenReturn(Optional.empty());
        when(actuaryInfoRepository.findByEmployeeId(supervisorId)).thenReturn(Optional.of(actuaryInfo));
        when(bankaCoreClient.provisionFundAccount(eq("Test Fund"), eq(supervisorId)))
                .thenReturn(provisioned);
        when(investmentFundRepository.save(any())).thenReturn(savedFund);
        when(fundValueSnapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tradingUserResolver.resolveName(supervisorId, UserRole.EMPLOYEE)).thenReturn("Marko Petrovic");

        InvestmentFundDetailDto result = service.createFund(dto, supervisorId);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("Test Fund", result.getName());
        assertEquals("222000100000000012", result.getAccountNumber());
        assertEquals("Marko Petrovic", result.getManagerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.getLiquidAmount()));
        assertTrue(result.getHoldings().isEmpty());

        // banka-core mora biti pozvan da provizionira fond racun (zamena za
        // monolitov accountRepository.save).
        ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
        verify(bankaCoreClient).provisionFundAccount(nameCap.capture(), eq(supervisorId));
        assertEquals("Test Fund", nameCap.getValue());
        verify(investmentFundRepository).save(any(InvestmentFund.class));
        verify(fundValueSnapshotRepository).save(any(FundValueSnapshot.class));
    }

    @Test
    @DisplayName("createFund - duplikat imena baca IllegalArgumentException")
    void createFund_duplicateName_throwsIllegalArgument() {
        CreateFundDto dto = new CreateFundDto("Postojeci Fond", "opis", new BigDecimal("100"));
        when(investmentFundRepository.findByName("Postojeci Fond"))
                .thenReturn(Optional.of(new InvestmentFund()));

        assertThrows(IllegalArgumentException.class, () -> service.createFund(dto, 1L));
        verify(bankaCoreClient, never()).provisionFundAccount(anyString(), anyLong());
    }

    @Test
    @DisplayName("createFund - agent (ne supervizor) baca IllegalStateException")
    void createFund_notSupervisor_throwsIllegalState() {
        Long agentId = 2L;
        CreateFundDto dto = new CreateFundDto("Novi Fond", "opis", new BigDecimal("1000"));

        ActuaryInfo agentInfo = buildActuaryInfo(agentId, ActuaryType.AGENT);

        when(investmentFundRepository.findByName(dto.getName())).thenReturn(Optional.empty());
        when(actuaryInfoRepository.findByEmployeeId(agentId)).thenReturn(Optional.of(agentInfo));

        assertThrows(IllegalStateException.class, () -> service.createFund(dto, agentId));
        verify(investmentFundRepository, never()).save(any());
        verify(bankaCoreClient, never()).provisionFundAccount(anyString(), anyLong());
    }

    @Test
    @DisplayName("createFund - ne supervizor (nema ActuaryInfo) baca IllegalStateException")
    void createFund_noActuaryInfo_throwsIllegalState() {
        Long empId = 3L;
        CreateFundDto dto = new CreateFundDto("Fund X", "opis", new BigDecimal("500"));

        when(investmentFundRepository.findByName(dto.getName())).thenReturn(Optional.empty());
        when(actuaryInfoRepository.findByEmployeeId(empId)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.createFund(dto, empId));
    }

    @Test
    @DisplayName("listDiscovery - vraca sve aktivne fondove")
    void listDiscovery_returnsAllActive() {
        InvestmentFund f1 = buildFund(1L, "Alpha Fund", 10L, 1L);
        InvestmentFund f2 = buildFund(2L, "Beta Fund", 11L, 1L);

        when(investmentFundRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(f1, f2));
        when(tradingUserResolver.resolveName(1L, UserRole.EMPLOYEE)).thenReturn("Marko Petrovic");
        when(fundValueCalculator.computeFundValue(any())).thenReturn(new BigDecimal("50000.00"));
        when(fundValueCalculator.computeProfit(any())).thenReturn(new BigDecimal("5000.00"));

        List<InvestmentFundSummaryDto> result = service.listDiscovery(null, null, null);

        assertEquals(2, result.size());
        assertEquals("Alpha Fund", result.get(0).getName());
        assertEquals(new BigDecimal("50000.00"), result.get(0).getFundValue());
        assertEquals(new BigDecimal("5000.00"), result.get(0).getProfit());
        assertEquals("Marko Petrovic", result.get(0).getManagerName());
    }

    @Test
    @DisplayName("listDiscovery - search filter vraca samo matchirajuce fondove")
    void listDiscovery_withSearch_filtersCorrectly() {
        InvestmentFund f1 = buildFund(1L, "Alpha IT Fund", 10L, 1L);
        InvestmentFund f2 = buildFund(2L, "Beta Energija", 11L, 1L);

        when(investmentFundRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(f1, f2));
        when(tradingUserResolver.resolveName(1L, UserRole.EMPLOYEE)).thenReturn("Marko Petrovic");
        when(fundValueCalculator.computeFundValue(any())).thenReturn(BigDecimal.ZERO);
        when(fundValueCalculator.computeProfit(any())).thenReturn(BigDecimal.ZERO);

        List<InvestmentFundSummaryDto> result = service.listDiscovery("alpha", null, null);

        assertEquals(1, result.size());
        assertEquals("Alpha IT Fund", result.get(0).getName());
    }

    @Test
    @DisplayName("listDiscovery - prazna lista kad nema aktivnih fondova")
    void listDiscovery_noFunds_returnsEmpty() {
        when(investmentFundRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of());

        List<InvestmentFundSummaryDto> result = service.listDiscovery(null, null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("listDiscovery - sortiranje po fundValue DESC")
    void listDiscovery_sortByFundValueDesc() {
        InvestmentFund f1 = buildFund(1L, "A Fund", 10L, 1L);
        InvestmentFund f2 = buildFund(2L, "B Fund", 11L, 1L);

        when(investmentFundRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(f1, f2));
        when(tradingUserResolver.resolveName(1L, UserRole.EMPLOYEE)).thenReturn("Marko Petrovic");
        when(fundValueCalculator.computeFundValue(f1)).thenReturn(new BigDecimal("10000"));
        when(fundValueCalculator.computeFundValue(f2)).thenReturn(new BigDecimal("50000"));
        when(fundValueCalculator.computeProfit(any())).thenReturn(BigDecimal.ZERO);

        List<InvestmentFundSummaryDto> result = service.listDiscovery(null, "fundValue", "DESC");

        assertEquals("B Fund", result.get(0).getName());
        assertEquals("A Fund", result.get(1).getName());
    }

    @Test
    @DisplayName("getFundDetails - vraca kompletan detaljan DTO")
    void getFundDetails_happyPath() {
        Long fundId = 1L;
        InvestmentFund fund = buildFund(fundId, "Test Fund", 10L, 1L);
        InternalAccountDto account = fundAccount(10L, "222000100000000012", "150000.00");

        FundValueSnapshot snap = new FundValueSnapshot();
        snap.setFundId(fundId);
        snap.setSnapshotDate(LocalDate.now().minusDays(1));
        snap.setFundValue(new BigDecimal("145000.00"));
        snap.setProfit(new BigDecimal("5000.00"));

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(10L)).thenReturn(account);
        when(fundValueCalculator.computeFundValue(fund)).thenReturn(new BigDecimal("150000.00"));
        when(fundValueCalculator.computeProfit(fund)).thenReturn(new BigDecimal("10000.00"));
        when(portfolioRepository.findByUserIdAndUserRole(fundId, UserRole.FUND)).thenReturn(List.of());
        when(fundValueSnapshotRepository.findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                eq(fundId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(snap));
        when(tradingUserResolver.resolveName(1L, UserRole.EMPLOYEE)).thenReturn("Marko Petrovic");

        InvestmentFundDetailDto result = service.getFundDetails(fundId);

        assertNotNull(result);
        assertEquals(fundId, result.getId());
        assertEquals("Test Fund", result.getName());
        assertEquals(new BigDecimal("150000.00"), result.getFundValue());
        assertEquals(0, new BigDecimal("150000.00").compareTo(result.getLiquidAmount()));
        assertEquals(new BigDecimal("10000.00"), result.getProfit());
        assertEquals("222000100000000012", result.getAccountNumber());
        assertEquals("Marko Petrovic", result.getManagerName());
        assertTrue(result.getHoldings().isEmpty());
        assertEquals(1, result.getPerformance().size());
        assertEquals(new BigDecimal("145000.00"), result.getPerformance().get(0).getFundValue());
    }

    @Test
    @DisplayName("getFundDetails - nepostojeci fond baca EntityNotFoundException")
    void getFundDetails_notFound_throwsEntityNotFound() {
        when(investmentFundRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.getFundDetails(999L));
    }

    @Test
    @DisplayName("getFundDetails - performance lista je prazna za novi fond bez snapshots")
    void getFundDetails_noSnapshots_emptyPerformance() {
        Long fundId = 2L;
        InvestmentFund fund = buildFund(fundId, "Novi Fond", 20L, 1L);
        InternalAccountDto account = fundAccount(20L, "222000200000000011", "0.00");

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(fund));
        when(bankaCoreClient.getAccount(20L)).thenReturn(account);
        when(fundValueCalculator.computeFundValue(fund)).thenReturn(BigDecimal.ZERO);
        when(fundValueCalculator.computeProfit(fund)).thenReturn(BigDecimal.ZERO);
        when(portfolioRepository.findByUserIdAndUserRole(fundId, UserRole.FUND)).thenReturn(List.of());
        when(fundValueSnapshotRepository.findByFundIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                eq(fundId), any(), any())).thenReturn(List.of());
        when(tradingUserResolver.resolveName(1L, UserRole.EMPLOYEE)).thenReturn("Marko Petrovic");

        InvestmentFundDetailDto result = service.getFundDetails(fundId);

        assertTrue(result.getPerformance().isEmpty());
    }
}
