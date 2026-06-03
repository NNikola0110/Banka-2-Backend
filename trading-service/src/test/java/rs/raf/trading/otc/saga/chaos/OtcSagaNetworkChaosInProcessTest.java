package rs.raf.trading.otc.saga.chaos;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;
import rs.raf.trading.otc.saga.support.NetworkChaosBankaCoreClient;
import rs.raf.trading.otc.saga.support.NetworkChaosBankaCoreConfig;
import rs.raf.trading.otc.saga.support.StateSnapshot;
import rs.raf.trading.otc.saga.support.StateSnapshot.OwnerKey;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * <b>Chaos (SG-09a / SG-10) — MREZNI (transport-level) fault</b> komplement W2
 * invarijant suite-u. Dokazuje da SAGA korektno kompenzuje + ocuvava invarijante
 * kad banka-core poziv padne na <i>transportu</i> (konekcija odbijena / read-timeout),
 * a NE sa HTTP statusom — sto je tacno kako se Toxiproxy {@code down}/{@code latency}
 * i {@code docker pause backend} (docs/chaos-testing.md) manifestuju na nivou klijenta.
 *
 * <p><b>Sta dopunjuje:</b> {@code OtcSagaInvariantIntegrationTest} pokriva HTTP-level
 * (X-Saga-Force-Fail → {@code SagaFaultException}) i business-level
 * ({@code failCreditFor} → {@code BankaCoreClientException} 503) mane. Ovde se
 * forsira {@link org.springframework.web.client.ResourceAccessException} (transport)
 * preko {@link NetworkChaosBankaCoreClient} — taj put nijedan postojeci automatski
 * test nije pokrivao. Pravi mrezni chaos protiv zivog stacka ostaje MANUELAN
 * (Toxiproxy + docker kill/pause) — vidi runbook; ovde se in-process dokazuje
 * fault-handling LOGIKA bez flaky visekontejnerskog setup-a.
 *
 * <p>Identican Spring/HTTP/seed obrazac kao invarijant test (RANDOM_PORT, realan
 * security chain + OtcController + orkestrator + JPA, {@code saga.chaos.enabled=true}).
 *
 * <p>Invarijante (SAGA_test.pdf):
 * <ul>
 *   <li><b>I1</b> — ocuvanje novca (Σ balance nepromenjena posle rollback-a);</li>
 *   <li><b>I2</b> — ocuvanje akcija;</li>
 *   <li><b>I3/I4</b> — bez висеће rezervacije koju je ova saga kreirala;</li>
 *   <li><b>I6</b> — status ugovora vracen na ACTIVE; SAGA u terminalu COMPENSATED.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NetworkChaosBankaCoreConfig.class)
@TestPropertySource(properties = "saga.chaos.enabled=true")
class OtcSagaNetworkChaosInProcessTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long BUYER_ID = 8101L;
    private static final Long SELLER_ID = 8102L;
    private static final Long BUYER_ACCOUNT_ID = 9101L;
    private static final Long SELLER_ACCOUNT_ID = 9102L;

    private static final String CCY = "USD";
    private static final int QTY = 10;
    private static final BigDecimal STRIKE = new BigDecimal("160.0000");
    /** strike × qty = 1600 — iznos koji bi F3 premestio buyer→seller. */
    private static final BigDecimal COST = new BigDecimal("1600.0000");
    private static final BigDecimal START_BALANCE = new BigDecimal("100000.0000");

    @Value("${local.server.port}")
    private int port;

    @Autowired private OtcContractRepository contractRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private PortfolioRepository portfolioRepository;
    @Autowired private SagaLogRepository sagaLogRepository;
    @Autowired private EntityManager entityManager;

    /** @Primary chaos dvojnik iz NetworkChaosBankaCoreConfig. */
    @Autowired private BankaCoreClient bankaCoreClient;
    private NetworkChaosBankaCoreClient chaos;

    @MockitoBean private TradingUserResolver tradingUserResolver;

    private final RestTemplate restTemplate = createRestTemplate();
    private final SecretKey secretKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    private static RestTemplate createRestTemplate() {
        RestTemplate rt = new RestTemplate(new JdkClientHttpRequestFactory());
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return rt;
    }

    @BeforeEach
    void setUp() {
        chaos = (NetworkChaosBankaCoreClient) bankaCoreClient;
        sagaLogRepository.deleteAll();
        contractRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();

        lenient().when(tradingUserResolver.resolveCurrent())
                .thenReturn(new UserContext(BUYER_ID, UserRole.CLIENT));

        chaos.mapPreferredAccount(UserRole.CLIENT, BUYER_ID, BUYER_ACCOUNT_ID);
        chaos.mapPreferredAccount(UserRole.CLIENT, SELLER_ID, SELLER_ACCOUNT_ID);
    }

    @AfterEach
    void tearDown() {
        sagaLogRepository.deleteAll();
        contractRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();
    }

    // ─────────────────────────── seed helpers ───────────────────────────────

    private Listing savedStockListing() {
        Listing l = new Listing();
        l.setTicker("AAPL");
        l.setName("Apple Inc.");
        l.setListingType(ListingType.STOCK);
        l.setQuoteCurrency(CCY);
        l.setExchangeAcronym("NASDAQ");
        l.setPrice(BigDecimal.valueOf(150));
        l.setAsk(BigDecimal.valueOf(151));
        l.setBid(BigDecimal.valueOf(149));
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    /**
     * ACTIVE ugovor BEZ accept-time rezervacije → F1 MORA da pozove
     * {@code reserveFunds} (taj poziv "obaramo" u SG-09a, ili pustamo da prodje pa
     * obaramo {@code commitFunds} u SG-10). Reserved-reuse put nikad ne pozove
     * reserveFunds/commit nogu istim redom — zato chaos koristi unreserved ugovor.
     */
    private OtcContract savedUnreservedContract(Listing listing) {
        OtcContract c = baseContract(listing);
        c.setBuyerReservedAccountId(null);
        c.setBuyerReservedAmount(BigDecimal.ZERO);
        c.setBankaCoreReservationId(null);
        return contractRepository.save(c);
    }

    private OtcContract baseContract(Listing listing) {
        OtcContract c = new OtcContract();
        c.setSourceOfferId(1L);
        c.setBuyerId(BUYER_ID);
        c.setBuyerRole(UserRole.CLIENT);
        c.setSellerId(SELLER_ID);
        c.setSellerRole(UserRole.CLIENT);
        c.setListing(listing);
        c.setQuantity(QTY);
        c.setStrikePrice(STRIKE);
        c.setPremium(new BigDecimal("50.0000"));
        c.setSettlementDate(LocalDate.now().plusDays(30));
        c.setStatus(OtcContractStatus.ACTIVE);
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    /** Seller portfolio: 20 akcija, 10 javnih, 10 rezervisanih (pokriva ugovor). */
    private Portfolio savedSellerPortfolio(Listing listing) {
        Portfolio p = new Portfolio();
        p.setUserId(SELLER_ID);
        p.setUserRole(UserRole.CLIENT);
        p.setListingId(listing.getId());
        p.setListingTicker("AAPL");
        p.setListingName("Apple Inc.");
        p.setListingType("STOCK");
        p.setQuantity(20);
        p.setAverageBuyPrice(new BigDecimal("100.0000"));
        p.setPublicQuantity(10);
        p.setReservedQuantity(10);
        return portfolioRepository.save(p);
    }

    private List<OwnerKey> owners(Listing listing) {
        List<OwnerKey> owners = new ArrayList<>();
        owners.add(new OwnerKey(BUYER_ID, UserRole.CLIENT, listing.getId()));
        owners.add(new OwnerKey(SELLER_ID, UserRole.CLIENT, listing.getId()));
        return owners;
    }

    private StateSnapshot snapshot(OtcContract contract, Listing listing) {
        return StateSnapshot.capture(entityManager, chaos, portfolioRepository, contractRepository,
                contract.getId(), owners(listing), List.of(BUYER_ACCOUNT_ID, SELLER_ACCOUNT_ID));
    }

    // ─────────────────────────── HTTP helpers ───────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> exercise(Long contractId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(buildToken("buyer@test.com", "CLIENT"));
        return restTemplate.exchange(
                url("/otc/contracts/" + contractId + "/exercise?buyerAccountId=" + BUYER_ACCOUNT_ID),
                HttpMethod.POST, new HttpEntity<>(h), String.class);
    }

    private ResponseEntity<String> getSaga(String sagaId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(buildToken("buyer@test.com", "CLIENT"));
        return restTemplate.exchange(url("/otc/saga/" + sagaId), HttpMethod.GET,
                new HttpEntity<>(h), String.class);
    }

    private String buildToken(String subject, String role) {
        return Jwts.builder()
                .subject(subject)
                .claim("role", role)
                .claim("active", true)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000L))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    private static String extractSagaId(String body) {
        String key = "\"sagaId\":\"";
        int start = body.indexOf(key);
        if (start < 0) {
            return "";
        }
        start += key.length();
        int end = body.indexOf('"', start);
        return end < 0 ? "" : body.substring(start, end);
    }

    // ─────────────────────────── Tests ──────────────────────────────────────

    /**
     * <b>SG-09a — banka-core nedostupan na F1 (transport fault, connection refused).</b>
     *
     * <p>{@code reserveFunds} (prva banka-core noga u F1) puca kao
     * {@link org.springframework.web.client.ResourceAccessException} — kao da je
     * Toxiproxy {@code down} ili {@code docker pause backend} u trenutku poziva.
     * Pad je PRE bilo kog bocnog efekta (rezervacija nikad nije nastala), pa SAGA
     * kompenzuje (C1 no-op) i zavrsi COMPENSATED bez ijedne promene stanja.
     *
     * <p>Asercije: 200 + {@code sagaStatus=COMPENSATED} u koraku 1; {@code post == pre}
     * (novac + akcije + reserved netaknuti); ugovor ostaje ACTIVE.
     */
    @Test
    @DisplayName("SG-09a F1 transport-fail (connection refused): COMPENSATED step1, post==pre, ugovor ACTIVE")
    void sg09a_bankaCoreDown_onF1Reserve_compensatesNoSideEffects() {
        Listing listing = savedStockListing();
        OtcContract contract = savedUnreservedContract(listing);
        savedSellerPortfolio(listing);
        chaos.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE);
        chaos.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);

        StateSnapshot pre = snapshot(contract, listing);

        // Naoruzaj transport fault na F1 reserveFunds (banka-core "down").
        chaos.armReserveFundsTransportFail();

        ResponseEntity<String> resp = exercise(contract.getId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"sagaStatus\":\"COMPENSATED\"");
        assertThat(resp.getBody()).contains("\"currentStep\":1");
        assertThat(resp.getBody()).contains("\"status\":\"ACTIVE\"");

        StateSnapshot post = snapshot(contract, listing);

        // I1/I2: nikakvih bocnih efekata — transport fault pao pre prve mutacije.
        assertThat(post.totalMoney()).isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID));
        assertThat(post.balances().get(SELLER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(SELLER_ACCOUNT_ID));
        assertThat(chaos.reservedOf(BUYER_ACCOUNT_ID)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(post.totalShares()).isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser()).isEqualTo(pre.sharesByUser());

        // I6: ugovor netaknut + SAGA u durable terminalu.
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.ACTIVE);
        String sagaId = extractSagaId(resp.getBody());
        assertThat(getSaga(sagaId).getBody()).contains("\"status\":\"COMPENSATED\"");
    }

    /**
     * <b>SG-10 — banka-core latency &gt; read-timeout na F3 (transport read-timeout).</b>
     *
     * <p>F1 {@code reserveFunds} uspesno KREIRA rezervaciju (1600 USD hold na kupcu),
     * pa F3 {@code commitFunds} puca kao {@link org.springframework.web.client.ResourceAccessException}
     * (read-timeout) — kao da je Toxiproxy {@code latency > 30s} na tom pozivu. SAGA
     * kompenzuje: C1 {@code releaseFunds} oslobadja F1-kreiranu rezervaciju (jer je
     * {@code buyerReservationCreatedHere=true}), C2 vraca seller akcije.
     *
     * <p><b>KLJUC (I1):</b> novac nikad nije premesten (commit pao na transportu pre
     * promene salda u fake banci), a rezervacija je oslobodjena → {@code post.totalMoney
     * == pre} i {@code reservedOf(buyer) == 0} (bez висеће rezervacije). Ugovor ACTIVE.
     */
    @Test
    @DisplayName("SG-10 F3 transport-timeout (latency>read-timeout): COMPENSATED step3, I1 ocuvan, reserved oslobodjen, ACTIVE")
    void sg10_latencyTimeout_onF3Commit_compensatesAndReleasesReservation() {
        Listing listing = savedStockListing();
        OtcContract contract = savedUnreservedContract(listing);
        savedSellerPortfolio(listing);
        chaos.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE);
        chaos.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);

        StateSnapshot pre = snapshot(contract, listing);

        // Pusti F1 reserve da prodje (rezervacija nastaje), pa obori F3 commit na transportu.
        chaos.armCommitFundsTransportFail();

        ResponseEntity<String> resp = exercise(contract.getId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"sagaStatus\":\"COMPENSATED\"");
        assertThat(resp.getBody()).contains("\"currentStep\":3");
        assertThat(resp.getBody()).contains("\"status\":\"ACTIVE\"");

        StateSnapshot post = snapshot(contract, listing);

        // I1 KLJUC: masa novca ocuvana (commit pao na transportu pre premestaja) +
        // C1 oslobodio F1-kreiranu rezervaciju → nema висеће rezervacije.
        assertThat(post.totalMoney())
                .as("SG-10: F3 commit read-timeout ne sme da promeni masu novca")
                .isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID));
        assertThat(post.balances().get(SELLER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(SELLER_ACCOUNT_ID));
        assertThat(chaos.reservedOf(BUYER_ACCOUNT_ID))
                .as("C1 oslobodio F1-kreiranu rezervaciju (buyerReservationCreatedHere=true)")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // I2: akcije ocuvane (F4 nikad dosegnut; C2 vratio seller reservedQuantity).
        assertThat(post.totalShares()).isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser()).isEqualTo(pre.sharesByUser());

        // I6: ugovor vracen na ACTIVE + SAGA terminal COMPENSATED.
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.ACTIVE);
        String sagaId = extractSagaId(resp.getBody());
        assertThat(getSaga(sagaId).getBody()).contains("\"status\":\"COMPENSATED\"");
    }
}
