package rs.raf.trading.investmentfund.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.ClientFundTransactionDto;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.InvestFundDto;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.WithdrawFundDto;
import rs.raf.trading.investmentfund.model.ClientFundPosition;
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.ClientFundTransactionStatus;
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
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P1-funds-1 (31.05) — TDD regresija za investicione fondove:
 * withdraw cap na currentValue (221/1348), decreasePosition POSLE payout (1343),
 * computeFundValue availableBalance (1344/1555), stabilan invest idempotency
 * kljuc (1553). Dividend nalazi (1346/1554, 1347, 1552) su u
 * {@code FundDividendServiceTest} + {@code FundsDividendP1BatchTest}.
 */
@ExtendWith(MockitoExtension.class)
class FundsP1BatchTest {

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
    @Mock private rs.raf.trading.audit.service.AuditLogService auditLogService;

    @InjectMocks
    private InvestmentFundService service;

    private InvestmentFund fund(Long id, Long accountId) {
        InvestmentFund f = new InvestmentFund();
        f.setId(id);
        f.setName("Test Fund " + id);
        f.setAccountId(accountId);
        f.setMinimumContribution(new BigDecimal("100.00"));
        f.setManagerEmployeeId(1L);
        f.setActive(true);
        return f;
    }

    private InternalAccountDto fundAccount(Long id, String balance, String available) {
        return new InternalAccountDto(id, "FUND-" + id, "Fond", new BigDecimal(balance),
                new BigDecimal(available), BigDecimal.ZERO, "RSD", "ACTIVE", null, null, "FUND");
    }

    private InternalAccountDto clientAccount(Long id, Long clientId, String available) {
        return new InternalAccountDto(id, "CLIENT-" + id, "Klijent", new BigDecimal(available),
                new BigDecimal(available), BigDecimal.ZERO, "RSD", "ACTIVE", clientId, null, "PERSONAL");
    }

    private ClientFundPosition position(Long fundId, Long userId, String invested) {
        ClientFundPosition p = new ClientFundPosition();
        p.setId(50L);
        p.setFundId(fundId);
        p.setUserId(userId);
        p.setUserRole(UserRole.CLIENT);
        p.setTotalInvested(new BigDecimal(invested));
        return p;
    }

    // ── 221/1348: withdraw cap na currentValue (profit se MOZE povuci) ──────────

    @Test
    @DisplayName("1348: withdraw dozvoljava povlacenje profita (iznad totalInvested do currentValue)")
    void withdraw_allowsProfitAboveCostBasis() {
        Long fundId = 1L;
        Long clientId = 5L;
        InvestmentFund f = fund(fundId, 100L);
        InternalAccountDto fundAcc = fundAccount(100L, "100000", "100000"); // dovoljno cash → immediate payout
        InternalAccountDto destAcc = clientAccount(200L, clientId, "0");

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(f));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAcc);
        when(bankaCoreClient.getAccount(200L)).thenReturn(destAcc);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.of(position(fundId, clientId, "10000.0000")));
        // currentValue = 15000 (profit 5000 iznad cost-basis 10000)
        when(fundValueCalculator.computePositionValue(fundId, clientId, UserRole.CLIENT))
                .thenReturn(new BigDecimal("15000.0000"));
        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(inv -> {
                    ClientFundTransaction tx = inv.getArgument(0);
                    if (tx.getId() == null) tx.setId(700L);
                    return tx;
                });

        // Povlacimo 12000 — IZNAD cost-basis 10000 (raniji kod bi bacio
        // "veci od pozicije"); validno jer je < currentValue 15000.
        WithdrawFundDto dto = new WithdrawFundDto(new BigDecimal("12000"), 200L);
        ClientFundTransactionDto result = service.withdraw(fundId, dto, clientId, "CLIENT");

        assertEquals(ClientFundTransactionStatus.COMPLETED.name(), result.getStatus());
        verify(bankaCoreClient).transferFunds(anyString(), any(TransferFundsRequest.class));
    }

    @Test
    @DisplayName("1348: withdraw-all (amount==null) povlaci currentValue (NAV), ne cost basis")
    void withdrawAll_usesCurrentValueNotCostBasis() {
        Long fundId = 1L;
        Long clientId = 5L;
        InvestmentFund f = fund(fundId, 100L);
        InternalAccountDto fundAcc = fundAccount(100L, "100000", "100000");
        InternalAccountDto destAcc = clientAccount(200L, clientId, "0");

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(f));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAcc);
        when(bankaCoreClient.getAccount(200L)).thenReturn(destAcc);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.of(position(fundId, clientId, "10000.0000")));
        when(fundValueCalculator.computePositionValue(fundId, clientId, UserRole.CLIENT))
                .thenReturn(new BigDecimal("15000.0000")); // NAV udeo
        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(inv -> {
                    ClientFundTransaction tx = inv.getArgument(0);
                    if (tx.getId() == null) tx.setId(700L);
                    return tx;
                });

        WithdrawFundDto dto = new WithdrawFundDto(null, 200L); // withdraw-all
        service.withdraw(fundId, dto, clientId, "CLIENT");

        // Isplaceni iznos mora biti currentValue (15000), NE cost-basis (10000).
        ArgumentCaptor<TransferFundsRequest> cap = ArgumentCaptor.forClass(TransferFundsRequest.class);
        verify(bankaCoreClient).transferFunds(anyString(), cap.capture());
        assertEquals(0, new BigDecimal("15000.0000").compareTo(cap.getValue().debitAmount()));
    }

    // ── 1343: decreasePosition POSLE payout (pending NE smanjuje poziciju) ──────

    @Test
    @DisplayName("1343: pending withdraw (nedovoljno cash) NE smanjuje poziciju odmah")
    void pendingWithdraw_doesNotDecreasePositionBeforePayout() {
        Long fundId = 1L;
        Long clientId = 5L;
        InvestmentFund f = fund(fundId, 100L);
        // Fond nema dovoljno cash → PENDING + likvidacija.
        InternalAccountDto fundAcc = fundAccount(100L, "0", "0");
        InternalAccountDto destAcc = clientAccount(200L, clientId, "0");

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(f));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAcc);
        when(bankaCoreClient.getAccount(200L)).thenReturn(destAcc);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.of(position(fundId, clientId, "10000.0000")));
        when(fundValueCalculator.computePositionValue(fundId, clientId, UserRole.CLIENT))
                .thenReturn(new BigDecimal("10000.0000"));
        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(inv -> {
                    ClientFundTransaction tx = inv.getArgument(0);
                    if (tx.getId() == null) tx.setId(700L);
                    return tx;
                });

        WithdrawFundDto dto = new WithdrawFundDto(new BigDecimal("5000"), 200L);
        ClientFundTransactionDto result = service.withdraw(fundId, dto, clientId, "CLIENT");

        // PENDING (likvidacija pokrenuta).
        assertEquals(ClientFundTransactionStatus.PENDING.name(), result.getStatus());
        verify(fundLiquidationService).liquidateFor(eq(fundId), any(BigDecimal.class));
        // KRITICNO (1343): pozicija NIJE smanjena/obrisana pre isplate.
        verify(clientFundPositionRepository, never()).delete(any(ClientFundPosition.class));
        // jedini save je position-mgmt — ne sme se desiti (samo tx save-ovi).
        verify(clientFundPositionRepository, never()).save(any(ClientFundPosition.class));
        // investedDelta mora biti upisan za kasniji decrement pri payout-u.
        ArgumentCaptor<ClientFundTransaction> txCap = ArgumentCaptor.forClass(ClientFundTransaction.class);
        verify(clientFundTransactionRepository, atLeastOnce()).save(txCap.capture());
        assertNotNull(txCap.getAllValues().get(0).getInvestedDelta());
        assertTrue(txCap.getAllValues().get(0).getInvestedDelta().signum() > 0);
    }

    @Test
    @DisplayName("1343: immediate withdraw smanjuje poziciju TEK POSLE uspesnog payout-a")
    void immediateWithdraw_decreasesPositionAfterPayout() {
        Long fundId = 1L;
        Long clientId = 5L;
        InvestmentFund f = fund(fundId, 100L);
        InternalAccountDto fundAcc = fundAccount(100L, "100000", "100000");
        InternalAccountDto destAcc = clientAccount(200L, clientId, "0");

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(f));
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAcc);
        when(bankaCoreClient.getAccount(200L)).thenReturn(destAcc);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.of(position(fundId, clientId, "10000.0000")));
        when(fundValueCalculator.computePositionValue(fundId, clientId, UserRole.CLIENT))
                .thenReturn(new BigDecimal("10000.0000"));
        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(inv -> {
                    ClientFundTransaction tx = inv.getArgument(0);
                    if (tx.getId() == null) tx.setId(700L);
                    return tx;
                });

        // Povlacimo celu poziciju → delta == totalInvested → pozicija obrisana.
        WithdrawFundDto dto = new WithdrawFundDto(new BigDecimal("10000"), 200L);
        service.withdraw(fundId, dto, clientId, "CLIENT");

        // Payout je izvrsen PA pozicija obrisana (delta == cela pozicija).
        verify(bankaCoreClient).transferFunds(anyString(), any(TransferFundsRequest.class));
        verify(clientFundPositionRepository).delete(any(ClientFundPosition.class));
    }

    // ── 1553: stabilan invest idempotency kljuc (nezavisan od txId) ────────────

    @Test
    @DisplayName("1553: invest koristi stabilan idempotency kljuc (NE fund-invest-{txId})")
    void invest_usesStableIdempotencyKey() {
        Long fundId = 1L;
        Long clientId = 5L;
        InvestmentFund f = fund(fundId, 100L);
        InternalAccountDto sourceAcc = clientAccount(200L, clientId, "50000");
        InternalAccountDto fundAcc = fundAccount(100L, "0", "0");

        when(investmentFundRepository.findById(fundId)).thenReturn(Optional.of(f));
        when(bankaCoreClient.getAccount(200L)).thenReturn(sourceAcc);
        when(bankaCoreClient.getAccount(100L)).thenReturn(fundAcc);
        when(clientFundPositionRepository.findByFundIdAndUserIdAndUserRole(fundId, clientId, UserRole.CLIENT))
                .thenReturn(Optional.empty());
        when(clientFundPositionRepository.save(any(ClientFundPosition.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(clientFundPositionRepository.findByFundId(fundId)).thenReturn(java.util.List.of());
        when(clientFundTransactionRepository.save(any(ClientFundTransaction.class)))
                .thenAnswer(inv -> {
                    ClientFundTransaction tx = inv.getArgument(0);
                    if (tx.getId() == null) tx.setId(700L);
                    return tx;
                });

        InvestFundDto dto = new InvestFundDto(new BigDecimal("5000"), "RSD", 200L);
        service.invest(fundId, dto, clientId, "CLIENT");

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(bankaCoreClient).transferFunds(keyCap.capture(), any(TransferFundsRequest.class));
        String key = keyCap.getValue();
        // NE sme biti izveden iz txId (700) — mora nositi userId/fund/account/dan.
        assertFalse(key.equals("fund-invest-700"),
                "Idempotency kljuc ne sme biti fund-invest-{txId}, bio: " + key);
        assertTrue(key.contains("-" + clientId + "-"), "kljuc mora sadrzati userId: " + key);
        assertTrue(key.contains(LocalDate.now().toString()), "kljuc mora sadrzati dan: " + key);
    }
}
