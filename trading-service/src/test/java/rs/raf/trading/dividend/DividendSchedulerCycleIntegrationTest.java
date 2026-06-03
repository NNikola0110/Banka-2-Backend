package rs.raf.trading.dividend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.dividend.model.DividendPayout;
import rs.raf.trading.dividend.repository.DividendPayoutRepository;
import rs.raf.trading.dividend.service.DividendService;
import rs.raf.trading.otc.saga.support.FakeBankaCoreClient;
import rs.raf.trading.otc.saga.support.FakeBankaCoreConfig;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scheduler-cycle integracioni test za kvartalnu isplatu dividendi na akcije
 * (B9, TODO_final #22, Sc54). Za razliku od {@link DividendServiceTest} (unit,
 * sve mock-ovano) i {@link DividendFundIntegrationTest} (FUND grana, mock-ovan
 * FundDividendService), ovde se podize {@code @SpringBootTest} sa H2 +
 * verodostojnim {@link FakeBankaCoreClient} dvojnikom banke, pa novcana noga
 * (credit na racun) zaista pomera saldo i moze se proveriti konzervaciono.
 *
 * <p>Poziva se STVARNI {@link DividendService#processQuarterlyDividends} (entry
 * point koji {@code DividendScheduler} cron pozove) nad realnim
 * {@code portfolios}/{@code listings}/{@code dividend_payouts} tabelama u H2.
 *
 * <p>Dokazuje formulu i poreski tretman po spec-u:
 * <ul>
 *   <li>bruto = Q × P × (godisnji prinos / 4);</li>
 *   <li>CLIENT: porez 15% na bruto, neto = bruto − porez, neto kreditiran na
 *       preferiran RSD racun (saldo se uveca tacno za neto);</li>
 *   <li>EMPLOYEE: oslobodjen poreza (porez 0, neto = bruto), kreditiran na
 *       bankin trading racun;</li>
 *   <li>idempotentnost: drugi ciklus za isti datum NE knjizi dvaput.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeBankaCoreConfig.class)
class DividendSchedulerCycleIntegrationTest {

    private static final Long CLIENT_ID = 5001L;
    private static final Long EMPLOYEE_ID = 5002L;
    private static final Long CLIENT_ACCOUNT_ID = 9201L;
    private static final Long BANK_TRADING_ACCOUNT_ID = 9202L;
    private static final BigDecimal START_BALANCE = new BigDecimal("100000.0000");

    @Autowired private DividendService dividendService;
    @Autowired private DividendPayoutRepository dividendPayoutRepository;
    @Autowired private PortfolioRepository portfolioRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private BankaCoreClient bankaCoreClient;

    private FakeBankaCoreClient fake;

    @BeforeEach
    void setUp() {
        fake = (FakeBankaCoreClient) bankaCoreClient;
        dividendPayoutRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();

        // Klijent preferiran RSD racun + bankin RSD trading racun (za EMPLOYEE granu).
        fake.seedAccount(CLIENT_ACCOUNT_ID, "RSD", START_BALANCE);
        fake.seedAccount(BANK_TRADING_ACCOUNT_ID, "RSD", START_BALANCE);
        fake.mapPreferredAccount("CLIENT", CLIENT_ID, CLIENT_ACCOUNT_ID);
        fake.mapPreferredAccount("EMPLOYEE", EMPLOYEE_ID, BANK_TRADING_ACCOUNT_ID);
        fake.mapBankTradingAccount("RSD", BANK_TRADING_ACCOUNT_ID);
    }

    @AfterEach
    void tearDown() {
        dividendPayoutRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();
    }

    private Listing savedRsdStock(String ticker, String price, String annualYield) {
        Listing l = new Listing();
        l.setTicker(ticker);
        l.setName(ticker + " d.o.o.");
        l.setListingType(ListingType.STOCK);
        l.setExchangeAcronym("BELEX");
        l.setQuoteCurrency("RSD");
        l.setPrice(new BigDecimal(price));
        l.setDividendYield(new BigDecimal(annualYield));
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    private Portfolio savedPosition(Long ownerId, String ownerRole, Listing listing, int qty) {
        Portfolio p = Portfolio.builder()
                .userId(ownerId)
                .userRole(ownerRole)
                .listingId(listing.getId())
                .listingTicker(listing.getTicker())
                .listingName(listing.getName())
                .listingType("STOCK")
                .quantity(qty)
                .averageBuyPrice(new BigDecimal("90.0000"))
                .publicQuantity(0)
                .reservedQuantity(0)
                .build();
        return portfolioRepository.save(p);
    }

    /**
     * Sc54 CLIENT grana: Q=10, P=100, godisnji prinos=8% → kvartalni prinos=2%
     * → bruto = 10 × 100 × 0.02 = 20.0000; porez 15% = 3.0000; neto = 17.0000.
     * Klijentov RSD racun se uveca TACNO za neto (17), a DividendPayout zapis
     * nosi tacne iznose + poreski tretman.
     */
    @Test
    @DisplayName("processQuarterlyDividends CLIENT: bruto=Q×P×(yield/4)=20, porez 15%=3, neto 17 kreditiran")
    void clientDividend_appliesFormulaAndTaxAndCredits() {
        Listing listing = savedRsdStock("DIVA", "100", "0.0800");
        savedPosition(CLIENT_ID, "CLIENT", listing, 10);
        LocalDate paymentDate = LocalDate.of(2026, 3, 31);

        BigDecimal balanceBefore = fake.balanceOf(CLIENT_ACCOUNT_ID);
        BigDecimal totalMoneyBefore = fake.totalMoney();

        dividendService.processQuarterlyDividends(paymentDate);

        List<DividendPayout> payouts = dividendPayoutRepository
                .findByOwnerIdAndOwnerTypeOrderByPaymentDateDesc(CLIENT_ID, "CLIENT");
        assertThat(payouts).hasSize(1);
        DividendPayout dp = payouts.get(0);

        // Formula + porez (spec Sc54).
        assertThat(dp.getQuantity()).isEqualTo(10);
        assertThat(dp.getDividendYieldRate()).isEqualByComparingTo("0.020000"); // 8% / 4
        assertThat(dp.getGrossAmount()).isEqualByComparingTo("20.0000");
        assertThat(dp.getTax()).isEqualByComparingTo("3.0000");                 // 15% × 20
        assertThat(dp.getNetAmount()).isEqualByComparingTo("17.0000");
        assertThat(dp.getTaxExempt()).isFalse();
        assertThat(dp.getCurrencyCode()).isEqualTo("RSD");
        assertThat(dp.getCreditedAccountId()).isEqualTo(CLIENT_ACCOUNT_ID);

        // Novcana noga: racun klijenta uvecan TACNO za neto (17), masa novca raste za 17
        // (credit je jednostrani eksterni priliv u dividend modelu).
        assertThat(fake.balanceOf(CLIENT_ACCOUNT_ID))
                .isEqualByComparingTo(balanceBefore.add(new BigDecimal("17.0000")));
        assertThat(fake.totalMoney())
                .isEqualByComparingTo(totalMoneyBefore.add(new BigDecimal("17.0000")));
    }

    /**
     * Sc54 EMPLOYEE (aktuar) grana: porez OSLOBODJEN (tax=0, neto=bruto). Bruto =
     * 5 × 200 × (0.04/4=0.01) = 10.0000; ceo iznos (10) kreditiran na bankin
     * trading racun.
     */
    @Test
    @DisplayName("processQuarterlyDividends EMPLOYEE: porez oslobodjen (tax=0), ceo bruto kreditiran na bankin racun")
    void employeeDividend_isTaxExemptAndCreditsBankTradingAccount() {
        Listing listing = savedRsdStock("DIVB", "200", "0.0400");
        savedPosition(EMPLOYEE_ID, "EMPLOYEE", listing, 5);
        LocalDate paymentDate = LocalDate.of(2026, 6, 30);

        BigDecimal bankBalanceBefore = fake.balanceOf(BANK_TRADING_ACCOUNT_ID);

        dividendService.processQuarterlyDividends(paymentDate);

        List<DividendPayout> payouts = dividendPayoutRepository
                .findByOwnerIdAndOwnerTypeOrderByPaymentDateDesc(EMPLOYEE_ID, "EMPLOYEE");
        assertThat(payouts).hasSize(1);
        DividendPayout dp = payouts.get(0);

        assertThat(dp.getGrossAmount()).isEqualByComparingTo("10.0000");
        assertThat(dp.getTax()).isEqualByComparingTo("0.0000");
        assertThat(dp.getNetAmount()).isEqualByComparingTo("10.0000");
        assertThat(dp.getTaxExempt()).isTrue();
        assertThat(dp.getCreditedAccountId()).isEqualTo(BANK_TRADING_ACCOUNT_ID);

        assertThat(fake.balanceOf(BANK_TRADING_ACCOUNT_ID))
                .isEqualByComparingTo(bankBalanceBefore.add(new BigDecimal("10.0000")));
    }

    /**
     * Idempotentnost ciklusa: drugi {@code processQuarterlyDividends} za isti
     * (owner, listing, datum) NE knjizi dvaput — tacno jedan DividendPayout, saldo
     * uvecan samo jednom (spec: kron sme da se ponovi bez dvostruke isplate).
     */
    @Test
    @DisplayName("processQuarterlyDividends idempotentno: drugi ciklus istog datuma ne knjizi dvaput")
    void secondCycleSameDate_isIdempotent_noDoubleCredit() {
        Listing listing = savedRsdStock("DIVC", "100", "0.0800");
        savedPosition(CLIENT_ID, "CLIENT", listing, 10);
        LocalDate paymentDate = LocalDate.of(2026, 9, 30);

        dividendService.processQuarterlyDividends(paymentDate);
        BigDecimal balanceAfterFirst = fake.balanceOf(CLIENT_ACCOUNT_ID);

        // Drugi ciklus istog datuma — mora biti no-op (vec isplaceno).
        dividendService.processQuarterlyDividends(paymentDate);

        assertThat(dividendPayoutRepository
                .findByOwnerIdAndOwnerTypeOrderByPaymentDateDesc(CLIENT_ID, "CLIENT"))
                .hasSize(1);
        assertThat(fake.balanceOf(CLIENT_ACCOUNT_ID)).isEqualByComparingTo(balanceAfterFirst);
    }
}
