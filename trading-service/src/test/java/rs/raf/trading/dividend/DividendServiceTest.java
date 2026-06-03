package rs.raf.trading.dividend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.dividend.dto.DividendPayoutDto;
import rs.raf.trading.dividend.model.DividendPayout;
import rs.raf.trading.dividend.repository.DividendPayoutRepository;
import rs.raf.trading.dividend.service.DividendService;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.investmentfund.service.FundDividendService;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/**
 * Unit testovi za {@link DividendService} — B9 (dividende na akcije).
 *
 * <p>Svi pozivi ka banka-core su mock-ovani preko {@link BankaCoreClient}.
 * Portfolio i Listing su lokalni (trading_db) — mock-ovani direktno.
 */
@ExtendWith(MockitoExtension.class)
class DividendServiceTest {

    @InjectMocks
    private DividendService dividendService;

    @Mock
    private DividendPayoutRepository dividendPayoutRepository;

    @Mock
    private PortfolioRepository portfolioRepository;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private BankaCoreClient bankaCoreClient;

    @Mock
    private TradingUserResolver userResolver;

    @Mock
    private CurrencyConversionService currencyConversionService;

    @Mock
    private FundDividendService fundDividendService;

    @Mock
    private InvestmentFundRepository investmentFundRepository;

    /**
     * BE-FND-03 fix: {@code DividendService} ima self-injected proxy field
     * (@code self}) preko kog ide poziv {@code payDividendForOwner} iz
     * {@code processQuarterlyDividends} (bez toga intra-class self-invoke
     * zaobilazi {@code @Transactional} AOP). Mockito {@code @InjectMocks} ne
     * popunjava ovaj field — pa ga rucno setujemo na sam SUT instance u
     * {@link #setUpSelfProxy()}. U test okruzenju nemamo Spring kontekst,
     * pa nema pravog proxy-ja — ali za potrebe verify-ja poziva ovo je
     * dovoljno (test pokrivenost transakcionog ponasanja je odvojena).
     */
    @BeforeEach
    void setUpSelfProxy() {
        ReflectionTestUtils.setField(dividendService, "self", dividendService);
    }

    // ── Pomocni byggeri ───────────────────────────────────────────────────────

    private Portfolio buildPortfolio(Long userId, String userRole, Long listingId, int qty) {
        Portfolio p = new Portfolio();
        p.setUserId(userId);
        p.setUserRole(userRole);
        p.setListingId(listingId);
        p.setQuantity(qty);
        p.setListingType("STOCK");
        p.setListingTicker("AAPL");
        return p;
    }

    private Listing buildListing(BigDecimal price, BigDecimal annualYield, String baseCurrency) {
        Listing l = new Listing();
        l.setId(1L);
        l.setTicker("AAPL");
        l.setPrice(price);
        l.setDividendYield(annualYield);
        l.setBaseCurrency(baseCurrency);
        return l;
    }

    private InternalAccountDto stubAccount(Long id, String currency) {
        return new InternalAccountDto(id, "222000100000000001", "Test Owner",
                BigDecimal.valueOf(10000), BigDecimal.valueOf(10000), BigDecimal.ZERO,
                currency, "ACTIVE", null, null, "PERSONAL");
    }

    // ── Test 1: Preskace poziciju ako dividenda za taj datum vec postoji ──────

    @Test
    void processQuarterlyDividends_skipsAlreadyPaid() {
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        Portfolio position = buildPortfolio(1L, "CLIENT", 10L, 5);

        when(portfolioRepository.findAllStockPositionsWithQuantity())
                .thenReturn(List.of(position));
        DividendPayout alreadyPaid = new DividendPayout();
        alreadyPaid.setOwnerId(1L);
        alreadyPaid.setOwnerType("CLIENT");
        when(dividendPayoutRepository.findByStockListingIdAndPaymentDate(10L, paymentDate))
                .thenReturn(List.of(alreadyPaid));

        dividendService.processQuarterlyDividends(paymentDate);

        verify(dividendPayoutRepository, never()).save(any());
        verify(bankaCoreClient, never()).creditFunds(anyString(), any());
    }

    // ── Test 2: Zaposleni (EMPLOYEE) je porezno oslobodjen ───────────────────

    @Test
    void processQuarterlyDividends_taxExemptForEmployee() {
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        Portfolio position = buildPortfolio(5L, "EMPLOYEE", 10L, 10);
        Listing listing = buildListing(new BigDecimal("100.00"), new BigDecimal("0.08"), "USD");

        when(portfolioRepository.findAllStockPositionsWithQuantity())
                .thenReturn(List.of(position));
        when(dividendPayoutRepository.findByStockListingIdAndPaymentDate(10L, paymentDate))
                .thenReturn(List.of());
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenReturn(stubAccount(99L, "USD"));
        when(dividendPayoutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dividendService.processQuarterlyDividends(paymentDate);

        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(dividendPayoutRepository).save(captor.capture());

        DividendPayout saved = captor.getValue();
        assertThat(saved.getTaxExempt()).isTrue();
        assertThat(saved.getTax()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Test 3: Klijent placa 15% poreza ─────────────────────────────────────

    @Test
    void processQuarterlyDividends_appliesTax15PercentForClient() {
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        Portfolio position = buildPortfolio(2L, "CLIENT", 10L, 10);
        Listing listing = buildListing(new BigDecimal("100.00"), new BigDecimal("0.08"), "USD");

        when(portfolioRepository.findAllStockPositionsWithQuantity())
                .thenReturn(List.of(position));
        when(dividendPayoutRepository.findByStockListingIdAndPaymentDate(10L, paymentDate))
                .thenReturn(List.of());
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getPreferredAccount("CLIENT", 2L, "USD"))
                .thenReturn(stubAccount(20L, "USD"));
        when(dividendPayoutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dividendService.processQuarterlyDividends(paymentDate);

        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(dividendPayoutRepository).save(captor.capture());

        DividendPayout saved = captor.getValue();
        assertThat(saved.getTaxExempt()).isFalse();
        // gross = 10 * 100.00 * (0.08/4) = 20.00; tax = 20.00 * 0.15 = 3.00
        assertThat(saved.getTax()).isEqualByComparingTo(new BigDecimal("3.0000"));
        assertThat(saved.getNetAmount()).isEqualByComparingTo(new BigDecimal("17.0000"));
    }

    // ── Test 4: Provjera ispravnosti izracuna bruto iznosa ───────────────────

    @Test
    void processQuarterlyDividends_calculatesGrossCorrectly() {
        // qty=10, price=100.00, annualYield=0.08 → quarterly=0.02, gross=20.0000
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        Portfolio position = buildPortfolio(3L, "CLIENT", 10L, 10);
        Listing listing = buildListing(new BigDecimal("100.00"), new BigDecimal("0.08"), "USD");

        when(portfolioRepository.findAllStockPositionsWithQuantity())
                .thenReturn(List.of(position));
        when(dividendPayoutRepository.findByStockListingIdAndPaymentDate(10L, paymentDate))
                .thenReturn(List.of());
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getPreferredAccount("CLIENT", 3L, "USD"))
                .thenReturn(stubAccount(30L, "USD"));
        when(dividendPayoutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dividendService.processQuarterlyDividends(paymentDate);

        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(dividendPayoutRepository).save(captor.capture());

        DividendPayout saved = captor.getValue();
        assertThat(saved.getGrossAmount()).isEqualByComparingTo(new BigDecimal("20.0000"));
        // quarterly yield = 0.08 / 4 = 0.020000
        assertThat(saved.getDividendYieldRate()).isEqualByComparingTo(new BigDecimal("0.020000"));
    }

    // ── Test 5: creditFunds se poziva sa netAmount ────────────────────────────

    @Test
    void processQuarterlyDividends_creditsAccountWithNetAmount() {
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        Portfolio position = buildPortfolio(4L, "CLIENT", 10L, 10);
        Listing listing = buildListing(new BigDecimal("100.00"), new BigDecimal("0.08"), "USD");

        when(portfolioRepository.findAllStockPositionsWithQuantity())
                .thenReturn(List.of(position));
        when(dividendPayoutRepository.findByStockListingIdAndPaymentDate(10L, paymentDate))
                .thenReturn(List.of());
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getPreferredAccount("CLIENT", 4L, "USD"))
                .thenReturn(stubAccount(40L, "USD"));
        when(dividendPayoutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dividendService.processQuarterlyDividends(paymentDate);

        ArgumentCaptor<CreditFundsRequest> reqCaptor =
                ArgumentCaptor.forClass(CreditFundsRequest.class);
        verify(bankaCoreClient).creditFunds(anyString(), reqCaptor.capture());

        CreditFundsRequest req = reqCaptor.getValue();
        // gross = 20.0000, tax = 3.0000, net = 17.0000
        assertThat(req.amount()).isEqualByComparingTo(new BigDecimal("17.0000"));
        assertThat(req.accountId()).isEqualTo(40L);
        assertThat(req.currencyCode()).isEqualTo("USD");
        assertThat(req.commission()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Test 6: RSD fallback kad preferiran racun u valuti listinga ne postoji

    @Test
    void processQuarterlyDividends_fallsBackToRsdAccountWhenCurrencyMismatch() {
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        Portfolio position = buildPortfolio(6L, "CLIENT", 10L, 5);
        Listing listing = buildListing(new BigDecimal("200.00"), new BigDecimal("0.04"), "USD");

        when(portfolioRepository.findAllStockPositionsWithQuantity())
                .thenReturn(List.of(position));
        when(dividendPayoutRepository.findByStockListingIdAndPaymentDate(10L, paymentDate))
                .thenReturn(List.of());
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));

        // Klijent nema USD racun → BankaCoreClientException → RSD fallback
        when(bankaCoreClient.getPreferredAccount("CLIENT", 6L, "USD"))
                .thenThrow(new BankaCoreClientException(404, "No USD account"));

        BigDecimal convertedRsd = new BigDecimal("850.0000");
        when(currencyConversionService.convert(any(), eq("USD"), eq("RSD")))
                .thenReturn(convertedRsd);
        when(bankaCoreClient.getPreferredAccount("CLIENT", 6L, "RSD"))
                .thenReturn(stubAccount(60L, "RSD"));
        when(dividendPayoutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dividendService.processQuarterlyDividends(paymentDate);

        // Mora biti pozvano: convert(netAmount, USD, RSD) + getPreferredAccount(..., RSD)
        verify(currencyConversionService).convert(any(), eq("USD"), eq("RSD"));
        verify(bankaCoreClient).getPreferredAccount("CLIENT", 6L, "RSD");

        ArgumentCaptor<CreditFundsRequest> reqCaptor =
                ArgumentCaptor.forClass(CreditFundsRequest.class);
        verify(bankaCoreClient).creditFunds(anyString(), reqCaptor.capture());

        CreditFundsRequest req = reqCaptor.getValue();
        assertThat(req.accountId()).isEqualTo(60L);
        assertThat(req.currencyCode()).isEqualTo("RSD");
        assertThat(req.amount()).isEqualByComparingTo(convertedRsd);
    }

    // ── Test 7: getMyDividendHistory vraca isplate trenutnog korisnika ────────

    @Test
    void getMyDividendHistory_returnsOnlyCurrentUserPayouts() {
        UserContext ctx = new UserContext(99L, "CLIENT");
        when(userResolver.resolveCurrent()).thenReturn(ctx);

        DividendPayout payout = new DividendPayout();
        payout.setOwnerId(99L);
        payout.setOwnerType("CLIENT");
        payout.setStockListingId(10L);
        payout.setStockTicker("AAPL");
        payout.setQuantity(3);
        payout.setPriceOnDate(new BigDecimal("150.00"));
        payout.setDividendYieldRate(new BigDecimal("0.020000"));
        payout.setGrossAmount(new BigDecimal("9.0000"));
        payout.setTax(new BigDecimal("1.3500"));
        payout.setNetAmount(new BigDecimal("7.6500"));
        payout.setCreditedAccountId(50L);
        payout.setCurrencyCode("USD");
        payout.setPaymentDate(LocalDate.of(2025, 9, 30));
        payout.setTaxExempt(false);

        when(dividendPayoutRepository.findByOwnerIdAndOwnerTypeOrderByPaymentDateDesc(99L, "CLIENT"))
                .thenReturn(List.of(payout));

        List<DividendPayoutDto> result = dividendService.getMyDividendHistory();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOwnerId()).isEqualTo(99L);
        assertThat(result.get(0).getOwnerType()).isEqualTo("CLIENT");
        verify(dividendPayoutRepository)
                .findByOwnerIdAndOwnerTypeOrderByPaymentDateDesc(99L, "CLIENT");
    }

    // ── Test 8: getDividendHistoryByPosition baca AccessDenied ako nije vlasnik

    @Test
    void getDividendHistoryByPosition_throwsAccessDeniedIfNotOwner() {
        // Korisnik je userId=1, ali pozicija pripada userId=2
        UserContext ctx = new UserContext(1L, "CLIENT");
        when(userResolver.resolveCurrent()).thenReturn(ctx);

        Portfolio portfolio = buildPortfolio(2L, "CLIENT", 10L, 5);
        portfolio.setId(77L);
        when(portfolioRepository.findById(77L)).thenReturn(Optional.of(portfolio));

        assertThatThrownBy(() -> dividendService.getDividendHistoryByPosition(77L))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── Test 9: getAdminDividendHistory — samo 'from' defaultuje 'to' na danas ─

    @Test
    void getAdminDividendHistory_fromOnlyDefaultsTodayAsTo() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Pageable pageable = PageRequest.of(0, 20);

        when(dividendPayoutRepository.findByPaymentDateBetween(eq(from), eq(today), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        dividendService.getAdminDividendHistory(from, null, pageable);

        verify(dividendPayoutRepository, times(1))
                .findByPaymentDateBetween(eq(from), eq(today), eq(pageable));
        verify(dividendPayoutRepository, never()).findAllByOrderByPaymentDateDesc(any());
    }

    // ── OT-1150: getAdminDividendHistory — samo 'to' defaultuje 'from' na epoch ─

    @Test
    void getAdminDividendHistory_toOnlyDefaultsEpochAsFrom() {
        // OT-1150 (TEST-tr-funds-dividends-profitbank-1): kad je zadan SAMO 'to',
        // 'from' se defaultuje na 1970-01-01 (epoch) — pokriva drugu granu (else if
        // to != null) koju Test 9 (from-only) ne dira.
        LocalDate to = LocalDate.of(2025, 6, 30);
        LocalDate epoch = LocalDate.of(1970, 1, 1);
        Pageable pageable = PageRequest.of(0, 20);

        when(dividendPayoutRepository.findByPaymentDateBetween(eq(epoch), eq(to), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        dividendService.getAdminDividendHistory(null, to, pageable);

        verify(dividendPayoutRepository, times(1))
                .findByPaymentDateBetween(eq(epoch), eq(to), eq(pageable));
        verify(dividendPayoutRepository, never()).findAllByOrderByPaymentDateDesc(any());
    }

    @Test
    void getAdminDividendHistory_bothFromAndTo_usesProvidedRange() {
        // OT-1150: kad su zadati i 'from' i 'to', koristi tacno taj opseg (bez
        // defaultovanja nijedne granice).
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        Pageable pageable = PageRequest.of(0, 50);

        when(dividendPayoutRepository.findByPaymentDateBetween(eq(from), eq(to), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        dividendService.getAdminDividendHistory(from, to, pageable);

        verify(dividendPayoutRepository, times(1))
                .findByPaymentDateBetween(eq(from), eq(to), eq(pageable));
        verify(dividendPayoutRepository, never()).findAllByOrderByPaymentDateDesc(any());
    }

    @Test
    void getAdminDividendHistory_noFilters_returnsFullHistory() {
        // OT-1150: kad nijedan datum nije zadat, vraca kompletnu istoriju
        // (findAllByOrderByPaymentDateDesc), bez between filtera.
        Pageable pageable = PageRequest.of(0, 20);
        when(dividendPayoutRepository.findAllByOrderByPaymentDateDesc(eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        dividendService.getAdminDividendHistory(null, null, pageable);

        verify(dividendPayoutRepository, times(1)).findAllByOrderByPaymentDateDesc(eq(pageable));
        verify(dividendPayoutRepository, never()).findByPaymentDateBetween(any(), any(), any());
    }

    // ── OT-1151: EMPLOYEE (tax-exempt) RSD fallback kad bankin valutni racun fali

    @Test
    void payDividend_employeeBankTradingAccountMissing_fallsBackToRsd() {
        // OT-1151 (TEST-tr-funds-dividends-profitbank-1): za EMPLOYEE (aktuar,
        // tax-exempt) ciljni je bankin trading racun u valuti listinga. Ako taj
        // racun ne postoji (banka-core 4xx -> resolveBankTradingAccount vraca null),
        // dividenda se konvertuje u RSD i kreditira bankin RSD trading racun.
        // Test 2 pokriva SAMO happy USD putanju; ovo pokriva RSD fallback granu.
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        Portfolio position = buildPortfolio(15L, "EMPLOYEE", 10L, 10);
        Listing listing = buildListing(new BigDecimal("100.00"), new BigDecimal("0.08"), "USD");

        when(portfolioRepository.findAllStockPositionsWithQuantity())
                .thenReturn(List.of(position));
        when(dividendPayoutRepository.findByStockListingIdAndPaymentDate(10L, paymentDate))
                .thenReturn(List.of());
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        // bankin USD trading racun ne postoji -> 404 -> RSD fallback
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenThrow(new BankaCoreClientException(404, "no USD bank trading account"));
        BigDecimal convertedRsd = new BigDecimal("2360.0000"); // gross 20 USD -> ~2360 RSD
        when(currencyConversionService.convert(any(), eq("USD"), eq("RSD")))
                .thenReturn(convertedRsd);
        when(bankaCoreClient.getBankTradingAccount("RSD"))
                .thenReturn(stubAccount(900L, "RSD"));
        when(dividendPayoutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dividendService.processQuarterlyDividends(paymentDate);

        // konverzija USD->RSD i RSD bankin racun moraju biti korisceni
        verify(currencyConversionService).convert(any(), eq("USD"), eq("RSD"));
        verify(bankaCoreClient).getBankTradingAccount("RSD");

        ArgumentCaptor<CreditFundsRequest> reqCaptor =
                ArgumentCaptor.forClass(CreditFundsRequest.class);
        verify(bankaCoreClient).creditFunds(anyString(), reqCaptor.capture());
        CreditFundsRequest req = reqCaptor.getValue();
        assertThat(req.accountId()).isEqualTo(900L);
        assertThat(req.currencyCode()).isEqualTo("RSD");
        assertThat(req.amount()).isEqualByComparingTo(convertedRsd);

        ArgumentCaptor<DividendPayout> payoutCaptor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(dividendPayoutRepository).save(payoutCaptor.capture());
        DividendPayout saved = payoutCaptor.getValue();
        assertThat(saved.getTaxExempt()).isTrue();
        assertThat(saved.getTax()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(saved.getCurrencyCode()).isEqualTo("RSD");
    }

    @Test
    void payDividend_employeeBankTradingAccount500_propagatesNotSwallowed() {
        // OT-1151: 5xx od banka-core (banka-core dole) NE sme tiho na RSD fallback —
        // propagira se (resolveBankTradingAccount baca za >=500).
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        Portfolio position = buildPortfolio(16L, "EMPLOYEE", 10L, 10);
        Listing listing = buildListing(new BigDecimal("100.00"), new BigDecimal("0.08"), "USD");

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getBankTradingAccount("USD"))
                .thenThrow(new BankaCoreClientException(503, "banka-core down"));

        // payDividendForOwner direktno (zaobilazi catch u processQuarterlyDividends koji
        // svaku gresku guta) — 5xx mora da izleti, ne tihi RSD fallback.
        assertThatThrownBy(() -> dividendService.payDividendForOwner(position, paymentDate))
                .isInstanceOf(BankaCoreClientException.class);
        verify(bankaCoreClient, never()).getBankTradingAccount("RSD");
        verify(bankaCoreClient, never()).creditFunds(anyString(), any());
    }

    // ── Test 10a: TODO_final C4 #14 / Sc 70 — dispatch po politici fonda ──────

    /**
     * Posle DIVIDEND_INFLOW kreditiranja, scheduler treba da iterira aktivne
     * fondove i pozove odgovarajuci handler na osnovu {@code reinvestDividends}
     * flag-a. Fond sa reinvest=true ide na {@code reinvestDividends(fundId)},
     * fond sa reinvest=false ide na {@code distributeDividendsToClients(fundId)}.
     */
    @Test
    void dispatchFundDividendsByPolicy_branchesBasedOnReinvestFlag() {
        InvestmentFund reinvestFund = new InvestmentFund();
        reinvestFund.setId(1L);
        reinvestFund.setName("Reinvest Fund");
        reinvestFund.setReinvestDividends(true);
        reinvestFund.setActive(true);

        InvestmentFund distributeFund = new InvestmentFund();
        distributeFund.setId(2L);
        distributeFund.setName("Distribute Fund");
        distributeFund.setReinvestDividends(false);
        distributeFund.setActive(true);

        when(investmentFundRepository.findByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(reinvestFund, distributeFund));

        dividendService.dispatchFundDividendsByPolicy();

        // R1 796: per-fund reinvest-vs-distribute switch zivi sad u
        // FundDividendService.dispatchByPolicy(fund); DividendService samo delegira.
        // Granjanje po reinvest flagu pokriva FundDividendServiceTest.
        verify(fundDividendService, times(1)).dispatchByPolicy(reinvestFund);
        verify(fundDividendService, times(1)).dispatchByPolicy(distributeFund);
    }

    /**
     * Greska u jednom fondu ne sme da prekine dispatch ostalih fondova.
     */
    @Test
    void dispatchFundDividendsByPolicy_continuesOnFailure() {
        InvestmentFund failingFund = new InvestmentFund();
        failingFund.setId(1L);
        failingFund.setName("Bad");
        failingFund.setReinvestDividends(true);
        failingFund.setActive(true);

        InvestmentFund okFund = new InvestmentFund();
        okFund.setId(2L);
        okFund.setName("Good");
        okFund.setReinvestDividends(false);
        okFund.setActive(true);

        when(investmentFundRepository.findByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(failingFund, okFund));
        doThrow(new RuntimeException("boom"))
                .when(fundDividendService).dispatchByPolicy(failingFund);

        dividendService.dispatchFundDividendsByPolicy();

        verify(fundDividendService).dispatchByPolicy(failingFund);
        // I dalje pokusava drugi fond uprkos gresci u prvom.
        verify(fundDividendService).dispatchByPolicy(okFund);
    }

    /**
     * Null {@code reinvestDividends} (legacy fund pre uvodjenja polja) tretira
     * se kao false (distribute). Backward-compat.
     */
    @Test
    void dispatchFundDividendsByPolicy_nullReinvestFlagDefaultsToDistribute() {
        InvestmentFund legacyFund = new InvestmentFund();
        legacyFund.setId(7L);
        legacyFund.setName("Legacy");
        legacyFund.setReinvestDividends(null);
        legacyFund.setActive(true);

        when(investmentFundRepository.findByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(legacyFund));

        dividendService.dispatchFundDividendsByPolicy();

        // Delegira na dispatchByPolicy; null->distribute granjanje pokriva FundDividendServiceTest.
        verify(fundDividendService).dispatchByPolicy(legacyFund);
    }

    // ── P1-dividends-order-1 (R1 227): underpay — sumira sve Portfolio redove ──

    /**
     * Vlasnik sa VISE Portfolio redova za istu hartiju (parcijalni fillovi)
     * mora dobiti dividendu na UKUPNU kolicinu, ne samo na prvi red.
     * Pre fix-a: grupisalo se po (owner,role,listing) ali se placalo samo
     * {@code group.get(0).getQuantity()} -> underpay.
     */
    @Test
    void processQuarterlyDividends_sumsAllPortfolioRowsForSameSecurity() {
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        // Isti vlasnik (CLIENT #8), ista hartija (listing 10), dva reda: 7 + 3 = 10 kom.
        Portfolio row1 = buildPortfolio(8L, "CLIENT", 10L, 7);
        Portfolio row2 = buildPortfolio(8L, "CLIENT", 10L, 3);
        Listing listing = buildListing(new BigDecimal("100.00"), new BigDecimal("0.08"), "USD");

        when(portfolioRepository.findAllStockPositionsWithQuantity())
                .thenReturn(List.of(row1, row2));
        when(dividendPayoutRepository.findByStockListingIdAndPaymentDate(10L, paymentDate))
                .thenReturn(List.of());
        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getPreferredAccount("CLIENT", 8L, "USD"))
                .thenReturn(stubAccount(80L, "USD"));
        when(dividendPayoutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        dividendService.processQuarterlyDividends(paymentDate);

        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(dividendPayoutRepository).save(captor.capture());
        DividendPayout saved = captor.getValue();

        // Kvantitet = 7+3 = 10; gross = 10 * 100 * (0.08/4) = 20.0000 (ne 14.0000 za samo 7 kom).
        assertThat(saved.getQuantity()).isEqualTo(10);
        assertThat(saved.getGrossAmount()).isEqualByComparingTo(new BigDecimal("20.0000"));
    }

    // ── P1-dividends-order-1 (R5 1843): truncate-pre-mnozenja ─────────────────

    /**
     * Za veliki quantity, zaokruzivanje quarterlyYield-a na scale-6 PRE mnozenja
     * sa (qty×price) akumulira gresku do celih jedinica valute. Fix racuna
     * gross u jednom lancu (qty×price×annualYield/4), zaokruzuje tek na kraju.
     */
    @Test
    void payDividendForOwner_largeQuantity_noTruncationLoss() {
        LocalDate paymentDate = LocalDate.of(2025, 12, 31);
        // annualYield 0.0333 -> /4 = 0.008325 (egzaktno na 6 decimala u ovom slucaju
        // nije problem), pa biramo yield koji se NE deli cisto na 6 decimala:
        // 0.0001 / 4 = 0.000025 — ali da bismo videli truncation, koristimo yield
        // ciji /4 ima 7+ znacajnih decimala: 0.01 / 4 = 0.0025 (cisto). Uzmimo
        // annualYield = 0.001 -> /4 = 0.00025 (cisto). Da iznudimo truncation, treba
        // /4 da ima vise od 6 decimala: annualYield = 0.0000004 -> /4 = 0.0000001.
        // Realnije: koristimo yield koji u scale-6 gubi rep. annualYield = 0.013 ->
        // /4 = 0.00325 (cisto). annualYield = 0.0133 -> /4 = 0.003325 (cisto, 6 dec).
        // Truncation se vidi tek kad /4 ima 7. decimalu: annualYield = 0.00001 ->
        // /4 = 0.0000025 -> scale6 round = 0.000003 (HALF_UP). Sa qty=1_000_000 i
        // price=100: tacno = 1_000_000*100*0.00001/4 = 250.0000;
        // truncate-pre = 1_000_000*100*round6(0.0000025=>0.000003) = 300.0000 -> greska 50.
        int qty = 1_000_000;
        Portfolio position = buildPortfolio(9L, "EMPLOYEE", 10L, qty); // EMPLOYEE = tax-exempt, gross==net
        Listing listing = buildListing(new BigDecimal("100.00"), new BigDecimal("0.00001"), "USD");

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getBankTradingAccount("USD")).thenReturn(stubAccount(90L, "USD"));
        when(dividendPayoutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DividendPayout result = dividendService.payDividendForOwner(position, paymentDate);

        // Egzaktno: 1_000_000 * 100 * 0.00001 / 4 = 250.0000 (NE 300.0000).
        assertThat(result.getGrossAmount()).isEqualByComparingTo(new BigDecimal("250.0000"));
    }

    // ── Test 10: payDividendForOwner cuva prosledjeni paymentDate nepromenjeno ─
    // Weekend shifting je odgovornost DividendScheduler-a, ne servisa.

    @Test
    void payDividendForOwner_storesProvidedPaymentDateAsIs() {
        // Prosledi subotu direktno servisu — on je samo cuva.
        LocalDate saturday = LocalDate.of(2025, 12, 27); // subota
        Portfolio position = buildPortfolio(7L, "CLIENT", 10L, 2);
        // R1 797: baseCurrency je relevantan SAMO za FOREX listinge (vidi
        // ListingCurrencyResolver.resolve). Ova akcija nije FOREX, pa valuta pada
        // na "USD" fallback — zato je i account stub ispod za "USD". Ranije je
        // stajalo "EUR" sto je zavaravalo (no-op, nikad nije menjalo razresenu valutu).
        Listing listing = buildListing(new BigDecimal("50.00"), new BigDecimal("0.04"), "USD");

        when(listingRepository.findById(10L)).thenReturn(Optional.of(listing));
        when(bankaCoreClient.getPreferredAccount("CLIENT", 7L, "USD"))
                .thenReturn(stubAccount(70L, "USD"));
        when(dividendPayoutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DividendPayout result = dividendService.payDividendForOwner(position, saturday);

        assertThat(result.getPaymentDate()).isEqualTo(saturday);
        verify(dividendPayoutRepository).save(any());
    }
}
