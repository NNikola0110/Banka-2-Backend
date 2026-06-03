package rs.raf.trading.investmentfund;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.model.ClientFundPosition;
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.ClientFundTransactionStatus;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.ClientFundPositionRepository;
import rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository;
import rs.raf.trading.investmentfund.repository.FundDividendDistributionLedgerRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.investmentfund.service.FundDividendService;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.otc.saga.support.FakeBankaCoreClient;
import rs.raf.trading.otc.saga.support.FakeBankaCoreConfig;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integracioni test za fondovsku raspodelu/reinvestiranje dividendi
 * (B11, TODO_final #14, Sc70/Sc71). Podize {@code @SpringBootTest} sa H2 +
 * verodostojnim {@link FakeBankaCoreClient}, pa novcane noge (transfer/reserve)
 * zaista pomeraju saldo i mogu se proveriti konzervaciono. Stvarni
 * {@link FundDividendDistributionLedgerRepository} guard se vrti na H2 (P1-2
 * idempotentnost — bez mock-a).
 *
 * <p>Za razliku od {@link FundDividendServiceTest} (cisti unit), ovde se proverava
 * EGZAKTAN proporcionalni razrez nad pravom bazom, konzervacija (zbir = priliv),
 * i da PONOVNO pokretanje raspodele NE plati klijente dvaput.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeBankaCoreConfig.class)
class FundDividendDistributionCycleIntegrationTest {

    private static final Long FUND_ACCOUNT_ID = 9300L;
    private static final Long CLIENT_A = 6001L;
    private static final Long CLIENT_B = 6002L;
    private static final Long CLIENT_A_ACCOUNT = 9301L;
    private static final Long CLIENT_B_ACCOUNT = 9302L;
    private static final BigDecimal FUND_START_BALANCE = new BigDecimal("50000.0000");

    @Autowired private FundDividendService fundDividendService;
    @Autowired private InvestmentFundRepository investmentFundRepository;
    @Autowired private ClientFundPositionRepository clientFundPositionRepository;
    @Autowired private ClientFundTransactionRepository clientFundTransactionRepository;
    @Autowired private FundDividendDistributionLedgerRepository ledgerRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private BankaCoreClient bankaCoreClient;

    private FakeBankaCoreClient fake;

    @BeforeEach
    void setUp() {
        fake = (FakeBankaCoreClient) bankaCoreClient;
        cleanDb();

        fake.seedAccount(FUND_ACCOUNT_ID, "RSD", FUND_START_BALANCE);
        fake.seedAccount(CLIENT_A_ACCOUNT, "RSD", BigDecimal.ZERO);
        fake.seedAccount(CLIENT_B_ACCOUNT, "RSD", BigDecimal.ZERO);
        fake.mapPreferredAccount(UserRole.CLIENT, CLIENT_A, CLIENT_A_ACCOUNT);
        fake.mapPreferredAccount(UserRole.CLIENT, CLIENT_B, CLIENT_B_ACCOUNT);
    }

    @AfterEach
    void tearDown() {
        cleanDb();
    }

    private void cleanDb() {
        // Orders pre listings — orders.listing_id ima FK na listings (reinvest test pravi BUY order).
        orderRepository.deleteAll();
        ledgerRepository.deleteAll();
        clientFundTransactionRepository.deleteAll();
        clientFundPositionRepository.deleteAll();
        listingRepository.deleteAll();
        investmentFundRepository.deleteAll();
    }

    private InvestmentFund savedFund(boolean reinvest) {
        InvestmentFund f = new InvestmentFund();
        f.setName("Test Fund " + System.nanoTime());
        f.setDescription("integration");
        f.setMinimumContribution(new BigDecimal("100.0000"));
        f.setManagerEmployeeId(1L);
        f.setAccountId(FUND_ACCOUNT_ID);
        f.setCreatedAt(LocalDateTime.now());
        f.setActive(true);
        f.setReinvestDividends(reinvest);
        return investmentFundRepository.save(f);
    }

    private ClientFundPosition savedPosition(Long fundId, Long clientId, String totalInvested) {
        ClientFundPosition p = new ClientFundPosition();
        p.setFundId(fundId);
        p.setUserId(clientId);
        p.setUserRole(UserRole.CLIENT);
        p.setTotalInvested(new BigDecimal(totalInvested));
        p.setLastModifiedAt(LocalDateTime.now());
        return clientFundPositionRepository.save(p);
    }

    private ClientFundTransaction savedDividendInflow(Long fundId, Long listingId, String amount) {
        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setFundId(fundId);
        tx.setUserId(fundId);
        tx.setUserRole(UserRole.FUND);
        tx.setAmountRsd(new BigDecimal(amount));
        tx.setSourceAccountId(FUND_ACCOUNT_ID);
        tx.setInflow(true);
        tx.setStatus(ClientFundTransactionStatus.DIVIDEND_INFLOW);
        tx.setCreatedAt(LocalDateTime.now());
        tx.setFailureReason("DIVIDEND_INFLOW listingId=" + listingId);
        return clientFundTransactionRepository.save(tx);
    }

    private Listing savedRsdStock(String ticker, String price) {
        Listing l = new Listing();
        l.setTicker(ticker);
        l.setName(ticker + " d.o.o.");
        l.setListingType(ListingType.STOCK);
        l.setExchangeAcronym("BELEX");
        l.setQuoteCurrency("RSD");
        l.setPrice(new BigDecimal(price));
        l.setAsk(new BigDecimal(price));
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    /**
     * Sc71: fond sa dve klijentske pozicije (A 30%, B 70% od totalInvested) i
     * 10000 RSD DIVIDEND_INFLOW. {@code distributeDividendsToClients} razdeljuje
     * TACNO A=3000, B=7000; zbir = priliv (konzervacija, bez rounding leak-a);
     * fond racun umanjen za 10000, klijentski racuni uvecani za svoj udeo.
     */
    @Test
    @DisplayName("distributeDividendsToClients: 30/70 razrez inflow=10000 → A=3000, B=7000 (zbir=priliv, konzervacija)")
    void distributesProportionallyAndConserves() {
        InvestmentFund fund = savedFund(false);
        savedPosition(fund.getId(), CLIENT_A, "3000.0000"); // 30%
        savedPosition(fund.getId(), CLIENT_B, "7000.0000"); // 70%
        savedDividendInflow(fund.getId(), 10L, "10000.0000");

        BigDecimal totalMoneyBefore = fake.totalMoney();

        List<ClientFundTransaction> distributions =
                fundDividendService.distributeDividendsToClients(fund.getId());

        assertThat(distributions).hasSize(2);

        // Egzaktan razrez na klijentske racune.
        assertThat(fake.balanceOf(CLIENT_A_ACCOUNT)).isEqualByComparingTo("3000.0000");
        assertThat(fake.balanceOf(CLIENT_B_ACCOUNT)).isEqualByComparingTo("7000.0000");

        // Konzervacija: fond racun umanjen za tacno 10000; ukupna masa novca nepromenjena
        // (transfer je interni — ni stvara ni unistava novac).
        assertThat(fake.balanceOf(FUND_ACCOUNT_ID))
                .isEqualByComparingTo(FUND_START_BALANCE.subtract(new BigDecimal("10000.0000")));
        assertThat(fake.totalMoney()).isEqualByComparingTo(totalMoneyBefore);

        // Zbir isplata == priliv (nema leak-a).
        BigDecimal sumPaid = fake.balanceOf(CLIENT_A_ACCOUNT).add(fake.balanceOf(CLIENT_B_ACCOUNT));
        assertThat(sumPaid).isEqualByComparingTo("10000.0000");

        // Priliv oznacen DISTRIBUTED + trajni ledger ima tacno 1 red po klijentu.
        assertThat(ledgerRepository.findAll()).hasSize(2);
    }

    /**
     * Sc71 P1-2 idempotentnost: PONOVNO pokretanje raspodele za isti (vec
     * raspodeljen) priliv NE placa klijente dvaput. Posle prvog (potpunog) run-a
     * priliv je DISTRIBUTED → {@code listPendingDividends} prazna → drugi run je
     * no-op; klijentski saldi i ledger ostaju isti (svaki klijent placen jednom).
     */
    @Test
    @DisplayName("distributeDividendsToClients ponovo: vec raspodeljen priliv → no double-pay (svaki klijent placen jednom)")
    void rerunDoesNotDoublePay() {
        InvestmentFund fund = savedFund(false);
        savedPosition(fund.getId(), CLIENT_A, "3000.0000");
        savedPosition(fund.getId(), CLIENT_B, "7000.0000");
        savedDividendInflow(fund.getId(), 10L, "10000.0000");

        fundDividendService.distributeDividendsToClients(fund.getId());

        BigDecimal aAfterFirst = fake.balanceOf(CLIENT_A_ACCOUNT);
        BigDecimal bAfterFirst = fake.balanceOf(CLIENT_B_ACCOUNT);
        BigDecimal fundAfterFirst = fake.balanceOf(FUND_ACCOUNT_ID);
        int ledgerAfterFirst = ledgerRepository.findAll().size();

        // Drugi run (simulacija cron retry-a) — mora biti no-op.
        List<ClientFundTransaction> secondRun =
                fundDividendService.distributeDividendsToClients(fund.getId());

        assertThat(secondRun).isEmpty();
        assertThat(fake.balanceOf(CLIENT_A_ACCOUNT)).isEqualByComparingTo(aAfterFirst);
        assertThat(fake.balanceOf(CLIENT_B_ACCOUNT)).isEqualByComparingTo(bAfterFirst);
        assertThat(fake.balanceOf(FUND_ACCOUNT_ID)).isEqualByComparingTo(fundAfterFirst);
        // Klijent A placen tacno jednom (3000), B tacno jednom (7000).
        assertThat(aAfterFirst).isEqualByComparingTo("3000.0000");
        assertThat(bAfterFirst).isEqualByComparingTo("7000.0000");
        // Ledger nije narastao u drugom run-u.
        assertThat(ledgerRepository.findAll()).hasSize(ledgerAfterFirst);
        assertThat(ledgerAfterFirst).isEqualTo(2);
    }

    /**
     * Sc70: fond sa politikom reinvestiranja. {@code reinvestDividends} za pending
     * DIVIDEND_INFLOW (1000 RSD) kreira interni MARKET BUY order za iznos
     * dividende (1000 / 200 = 5 komada), priliv prelazi u DIVIDEND_REINVESTED.
     */
    @Test
    @DisplayName("reinvestDividends (Sc70): pending priliv → MARKET BUY order za iznos dividende (5 kom), priliv REINVESTED")
    void reinvestCreatesBuyOrderForDividendAmount() {
        InvestmentFund fund = savedFund(true);
        Listing listing = savedRsdStock("REI1", "200"); // ASK 200 RSD/kom
        ClientFundTransaction inflow =
                savedDividendInflow(fund.getId(), listing.getId(), "1000.0000");

        List<Order> orders = fundDividendService.reinvestDividends(fund.getId());

        assertThat(orders).hasSize(1);
        Order order = orders.get(0);
        assertThat(order.getDirection()).isEqualTo(OrderDirection.BUY);
        assertThat(order.getOrderType()).isEqualTo(OrderType.MARKET);
        assertThat(order.getFundId()).isEqualTo(fund.getId());
        assertThat(order.getListing().getId()).isEqualTo(listing.getId());
        assertThat(order.getQuantity()).isEqualTo(5);                 // floor(1000 / 200)
        assertThat(order.getReservedAmount()).isEqualByComparingTo("1000.0000"); // 5 × 200

        // Priliv prebacen u DIVIDEND_REINVESTED (ne placa se dvaput).
        ClientFundTransaction reloaded =
                clientFundTransactionRepository.findById(inflow.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ClientFundTransactionStatus.DIVIDEND_REINVESTED);

        // Rezervacija sredstava za BUY je presla kroz banka-core (fond racun ima hold 1000).
        assertThat(fake.reservedOf(FUND_ACCOUNT_ID)).isEqualByComparingTo("1000.0000");
    }
}
