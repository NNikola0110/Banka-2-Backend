package rs.raf.trading.investmentfund.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.InvestmentFundDetailDto;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.ClientFundPositionRepository;
import rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository;
import rs.raf.trading.investmentfund.repository.FundValueSnapshotRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.investmentfund.scheduler.FundValueSnapshotScheduler;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pokriva ad-hoc reassign single-fund flow + bulk reassignManager (jos uvek
 * koristen iz {@code EmployeeServiceImpl.updateEmployee} kad admin oduzme
 * isSupervisor permisiju).
 *
 * NAPOMENA (faza 2c): {@code reassignSingleFundManager} interno zove
 * {@code getFundDetails}, koji u trading-service-u cita racun fonda preko
 * banka-core internog API-ja ({@link BankaCoreClient#getAccount}) umesto
 * {@code AccountRepository}-ja. {@link ActuaryInfo} u trading-service-u nosi
 * soft {@code employeeId} (banka-core {@code Employee} entitet ne postoji u
 * ovom servisu), pa se {@code ActuaryInfo} gradi sa {@code setEmployeeId(...)}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvestmentFundService — reassign manager")
class InvestmentFundServiceReassignTest {

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

    private InvestmentFund fund;

    @BeforeEach
    void setUp() {
        fund = new InvestmentFund();
        fund.setId(101L);
        fund.setName("Alpha Growth");
        fund.setDescription("Tech-fokusiran fond");
        fund.setManagerEmployeeId(10L);
        fund.setAccountId(5001L);
    }

    private InternalAccountDto fundAccount() {
        return new InternalAccountDto(5001L, "222000100000050010", "Banka 2 d.o.o.",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "RSD", "ACTIVE", null, null, "FUND");
    }

    private ActuaryInfo supervisor(Long employeeId) {
        ActuaryInfo info = new ActuaryInfo();
        info.setEmployeeId(employeeId);
        info.setActuaryType(ActuaryType.SUPERVISOR);
        return info;
    }

    private ActuaryInfo agent(Long employeeId) {
        ActuaryInfo info = new ActuaryInfo();
        info.setEmployeeId(employeeId);
        info.setActuaryType(ActuaryType.AGENT);
        return info;
    }

    @Test
    @DisplayName("reassignSingleFundManager: validan supervizor — fund.managerEmployeeId azuriran")
    void reassignSingleFund_validSupervisor_updatesManager() {
        when(investmentFundRepository.findById(101L)).thenReturn(Optional.of(fund));
        when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisor(20L)));
        // getFundDetails poziv u service-u — mockovi za sve sto fund-details cita
        when(bankaCoreClient.getAccount(5001L)).thenReturn(fundAccount());
        when(portfolioRepository.findByUserIdAndUserRole(101L, UserRole.FUND))
                .thenReturn(java.util.Collections.emptyList());
        when(fundValueCalculator.computeFundValue(any())).thenReturn(BigDecimal.ZERO);
        when(fundValueCalculator.computeProfit(any())).thenReturn(BigDecimal.ZERO);

        InvestmentFundDetailDto result = service.reassignSingleFundManager(101L, 20L);

        ArgumentCaptor<InvestmentFund> savedCaptor = ArgumentCaptor.forClass(InvestmentFund.class);
        verify(investmentFundRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getManagerEmployeeId()).isEqualTo(20L);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("reassignSingleFundManager: novi manager je agent — IllegalArgumentException")
    void reassignSingleFund_newManagerIsAgent_throws() {
        when(investmentFundRepository.findById(101L)).thenReturn(Optional.of(fund));
        when(actuaryInfoRepository.findByEmployeeId(30L)).thenReturn(Optional.of(agent(30L)));

        assertThatThrownBy(() -> service.reassignSingleFundManager(101L, 30L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supervisor");

        verify(investmentFundRepository, never()).save(any());
    }

    @Test
    @DisplayName("reassignSingleFundManager: novi manager ne postoji u ActuaryInfo — IllegalArgumentException")
    void reassignSingleFund_newManagerNotActuary_throws() {
        when(investmentFundRepository.findById(101L)).thenReturn(Optional.of(fund));
        when(actuaryInfoRepository.findByEmployeeId(40L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reassignSingleFundManager(101L, 40L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supervisor");

        verify(investmentFundRepository, never()).save(any());
    }

    @Test
    @DisplayName("reassignSingleFundManager: nepostojeci fund — EntityNotFoundException")
    void reassignSingleFund_fundMissing_throws() {
        when(investmentFundRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reassignSingleFundManager(999L, 20L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("999");

        verify(actuaryInfoRepository, never()).findByEmployeeId(any());
        verify(investmentFundRepository, never()).save(any());
    }

    @Test
    @DisplayName("reassignSingleFundManager: novi == stari manager — no-op (bez save)")
    void reassignSingleFund_sameManager_noOp() {
        when(investmentFundRepository.findById(101L)).thenReturn(Optional.of(fund));
        when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(supervisor(10L)));
        when(bankaCoreClient.getAccount(5001L)).thenReturn(fundAccount());
        when(portfolioRepository.findByUserIdAndUserRole(101L, UserRole.FUND))
                .thenReturn(java.util.Collections.emptyList());
        when(fundValueCalculator.computeFundValue(any())).thenReturn(BigDecimal.ZERO);
        when(fundValueCalculator.computeProfit(any())).thenReturn(BigDecimal.ZERO);

        InvestmentFundDetailDto result = service.reassignSingleFundManager(101L, 10L);

        assertThat(result).isNotNull();
        verify(investmentFundRepository, never()).save(any());
    }

    @Test
    @DisplayName("reassignSingleFundManager: null fundId — IllegalArgumentException")
    void reassignSingleFund_nullFundId_throws() {
        assertThatThrownBy(() -> service.reassignSingleFundManager(null, 20L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fund id");
    }

    @Test
    @DisplayName("reassignSingleFundManager: null newManagerId — IllegalArgumentException")
    void reassignSingleFund_nullNewManagerId_throws() {
        assertThatThrownBy(() -> service.reassignSingleFundManager(101L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manager");
    }

    @Test
    @DisplayName("reassignFundManager (bulk): poziva repository.reassignManager i loguje broj")
    void reassignFundManager_bulk_callsRepository() {
        when(investmentFundRepository.reassignManager(10L, 20L)).thenReturn(3);

        int result = service.reassignFundManager(10L, 20L);

        assertThat(result).isEqualTo(3);
        verify(investmentFundRepository).reassignManager(10L, 20L);
    }

    @Test
    @DisplayName("reassignFundManager (bulk): null oldId — vraca 0 bez poziva repo")
    void reassignFundManager_bulk_nullOld_returnsZero() {
        int result = service.reassignFundManager(null, 20L);

        assertThat(result).isEqualTo(0);
        verify(investmentFundRepository, never()).reassignManager(any(), any());
    }

    @Test
    @DisplayName("reassignFundManager (bulk): isti oldId i newId — vraca 0 bez poziva repo")
    void reassignFundManager_bulk_sameIds_returnsZero() {
        int result = service.reassignFundManager(10L, 10L);

        assertThat(result).isEqualTo(0);
        verify(investmentFundRepository, never()).reassignManager(any(), any());
    }
}
