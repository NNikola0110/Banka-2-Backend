package rs.raf.trading.investmentfund.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.investmentfund.dto.InvestmentFundDtos.ClientFundPositionDto;
import rs.raf.trading.investmentfund.model.ClientFundPosition;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T12 — Mockito unit testovi za listMyPositions / listBankPositions.
 *
 * Spec ref:
 *   - Celina 4 (Nova) "Moj portfolio -> Moji fondovi" (listMyPositions)
 *   - Celina 4 (Nova) §4406-4435 Napomena 1+2 "Banka kao klijent fonda" (listBankPositions)
 *   - Profit Banke "Pozicije u fondovima" tab (consumer od listBankPositions)
 *
 * NAPOMENA (faza 2c): monolitna verzija je razresavala banka client_id preko
 * {@code ClientRepository.findByEmail}. trading-service NEMA korisnicku bazu —
 * {@code listBankPositions} razresava banka klijenta preko banka-core internog
 * API-ja ({@link BankaCoreClient#getUserByEmail}). Stub-ovi su pomereni: umesto
 * {@code clientRepository.findByEmail} se stubuje {@code bankaCoreClient.getUserByEmail};
 * graceful fallback putanja stubuje {@link BankaCoreClientException} (banka
 * klijent nije seed-ovan / banka-core greska). Ostatak pokrivenosti
 * (DTO mapiranje, prazni listovi, defensive null/blank) je doslovan.
 */
@ExtendWith(MockitoExtension.class)
class InvestmentFundServicePositionsTest {

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

    private static final String BANK_EMAIL = "banka2.doo@banka.rs";

    @BeforeEach
    void setUp() {
        // @Value se ne resolvuje sa MockitoExtension-om — koristimo
        // ReflectionTestUtils da postavimo polje rucno. Inace bi
        // bankOwnerClientEmail bio null i lookup bi se srusio.
        ReflectionTestUtils.setField(service, "bankOwnerClientEmail", BANK_EMAIL);
    }

    private ClientFundPosition position(Long id, Long fundId, Long userId, String role, String invested) {
        ClientFundPosition p = new ClientFundPosition();
        p.setId(id);
        p.setFundId(fundId);
        p.setUserId(userId);
        p.setUserRole(role);
        p.setTotalInvested(new BigDecimal(invested));
        p.setLastModifiedAt(LocalDateTime.now());
        return p;
    }

    private InvestmentFund fund(Long id, String name) {
        InvestmentFund f = new InvestmentFund();
        f.setId(id);
        f.setName(name);
        return f;
    }

    private InternalUserDto bankUser(Long id) {
        return new InternalUserDto(id, "CLIENT", BANK_EMAIL, "Banka 2", "d.o.o.", true, null);
    }

    // ─── listMyPositions ──────────────────────────────────────────────────

    @Test
    @DisplayName("listMyPositions — vraca pozicije korisnika sa popunjenim fundName-om")
    void listMyPositions_happyPath() {
        ClientFundPosition p1 = position(101L, 1L, 5L, "CLIENT", "10000.00");
        ClientFundPosition p2 = position(102L, 2L, 5L, "CLIENT", "25000.00");
        when(clientFundPositionRepository.findByUserIdAndUserRole(5L, "CLIENT"))
                .thenReturn(List.of(p1, p2));
        when(investmentFundRepository.findAllById(any()))
                .thenReturn(List.of(fund(1L, "Stable Income"), fund(2L, "Tech Growth")));

        List<ClientFundPositionDto> result = service.listMyPositions(5L, "CLIENT");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ClientFundPositionDto::getFundName)
                .containsExactlyInAnyOrder("Stable Income", "Tech Growth");
        assertThat(result).extracting(ClientFundPositionDto::getUserId)
                .containsOnly(5L);
        assertThat(result).extracting(ClientFundPositionDto::getUserRole)
                .containsOnly("CLIENT");
        // Izvedena polja su BigDecimal (ne null). fundValueCalculator je @Mock,
        // computeFundValue vraca 0 (default); clientFundPositionRepository.findByFundId
        // nije stubovan pa Mockito vraca prazan list i sumInvested = 0.
        // Posledica: currentValue = 0, percentOfFund = 0, profit = 0 - totalInvested.
        assertThat(result).allMatch(d -> d.getCurrentValue() != null
                && d.getCurrentValue().signum() == 0
                && d.getPercentOfFund() != null
                && d.getPercentOfFund().signum() == 0
                && d.getProfit() != null
                && d.getProfit().signum() < 0);
    }

    @Test
    @DisplayName("listMyPositions — vraca prazan list kad korisnik nema pozicija")
    void listMyPositions_emptyForUserWithoutPositions() {
        when(clientFundPositionRepository.findByUserIdAndUserRole(99L, "CLIENT"))
                .thenReturn(List.of());

        List<ClientFundPositionDto> result = service.listMyPositions(99L, "CLIENT");

        assertThat(result).isEmpty();
        // Ne sme zvati findAllById ako nema pozicija — fail fast.
        verify(investmentFundRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("listMyPositions — null userId vraca prazan list (defensive)")
    void listMyPositions_nullUserId() {
        List<ClientFundPositionDto> result = service.listMyPositions(null, "CLIENT");

        assertThat(result).isEmpty();
        verify(clientFundPositionRepository, never()).findByUserIdAndUserRole(any(), anyString());
    }

    @Test
    @DisplayName("listMyPositions — blank userRole vraca prazan list (defensive)")
    void listMyPositions_blankRole() {
        List<ClientFundPositionDto> result = service.listMyPositions(5L, "  ");

        assertThat(result).isEmpty();
        verify(clientFundPositionRepository, never()).findByUserIdAndUserRole(any(), anyString());
    }

    // ─── listBankPositions ────────────────────────────────────────────────

    @Test
    @DisplayName("listBankPositions — resolvuje banka client_id po email-u i vraca njegove pozicije")
    void listBankPositions_happyPath() {
        when(bankaCoreClient.getUserByEmail(BANK_EMAIL)).thenReturn(bankUser(10L));

        ClientFundPosition p = position(201L, 1L, 10L, "CLIENT", "250000.00");
        when(clientFundPositionRepository.findByUserIdAndUserRole(10L, "CLIENT"))
                .thenReturn(List.of(p));
        when(investmentFundRepository.findAllById(any()))
                .thenReturn(List.of(fund(1L, "Stable Income")));

        List<ClientFundPositionDto> result = service.listBankPositions();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFundName()).isEqualTo("Stable Income");
        assertThat(result.get(0).getUserId()).isEqualTo(10L);
        assertThat(result.get(0).getTotalInvested()).isEqualByComparingTo("250000.00");
    }

    @Test
    @DisplayName("listBankPositions — graceful fallback kad banka-core baci gresku")
    void listBankPositions_bankaCoreError() {
        when(bankaCoreClient.getUserByEmail(BANK_EMAIL))
                .thenThrow(new BankaCoreClientException(404, "banka klijent nije seed-ovan"));

        List<ClientFundPositionDto> result = service.listBankPositions();

        assertThat(result).isEmpty();
        // Ne sme dalje zvati pozicije ako banka klijent nije razresiv.
        verify(clientFundPositionRepository, never()).findByUserIdAndUserRole(any(), anyString());
    }

    @Test
    @DisplayName("listBankPositions — graceful fallback kad banka-core vrati null userId")
    void listBankPositions_nullBankClientId() {
        when(bankaCoreClient.getUserByEmail(BANK_EMAIL))
                .thenReturn(new InternalUserDto(null, "CLIENT", BANK_EMAIL, "Banka 2", "d.o.o.", true, null));

        List<ClientFundPositionDto> result = service.listBankPositions();

        assertThat(result).isEmpty();
        verify(clientFundPositionRepository, never()).findByUserIdAndUserRole(any(), anyString());
    }

    @Test
    @DisplayName("listBankPositions — vraca prazan list kad banka nema pozicija u fondovima")
    void listBankPositions_bankExistsButNoPositions() {
        when(bankaCoreClient.getUserByEmail(BANK_EMAIL)).thenReturn(bankUser(10L));
        when(clientFundPositionRepository.findByUserIdAndUserRole(10L, "CLIENT"))
                .thenReturn(List.of());

        List<ClientFundPositionDto> result = service.listBankPositions();

        assertThat(result).isEmpty();
    }
}
