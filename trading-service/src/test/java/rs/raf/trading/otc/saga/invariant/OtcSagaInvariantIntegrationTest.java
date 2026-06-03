package rs.raf.trading.otc.saga.invariant;

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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.otc.saga.model.SagaLog;
import rs.raf.trading.otc.saga.model.SagaLogEntry;
import rs.raf.trading.otc.saga.model.SagaPhase;
import rs.raf.trading.otc.saga.model.SagaStatus;
import rs.raf.trading.otc.saga.model.SagaStepKind;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;
import rs.raf.trading.otc.saga.scheduler.SagaRecoveryService;
import rs.raf.trading.otc.saga.support.FakeBankaCoreClient;
import rs.raf.trading.otc.saga.support.FakeBankaCoreConfig;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * <b>W2</b> integracioni invarijant test suite za Model-B OTC-exercise SAGA-u.
 * Dokazuje korektnost invarijanti SAGA-e protiv prave baze (H2) + verodostojnog
 * in-memory dvojnika banke ({@link FakeBankaCoreClient}, Docker nedostupan →
 * bez Testcontainers banka-core).
 *
 * <p>Pun Spring kontekst (RANDOM_PORT, test profil, realan security chain +
 * realan {@code OtcController} + {@code OtcExerciseSagaOrchestrator} + JPA).
 * {@code saga.chaos.enabled=true} aktivira {@code HeaderSagaFaultInjector} pa
 * {@code X-Saga-Force-Fail: F{n}} forsira pad odredjene faze PRE bocnih efekata
 * (kompenzacija C{n-1}..C1, stanje vraceno).
 *
 * <p>Snimanje pre/posle stanja ide preko {@link StateSnapshot#capture} koje radi
 * SVEZE citanje (flush+clear) — SAGA komituje u svojoj server-side transakciji,
 * pa test mora odbaciti stale persistence-context entitete.
 *
 * <p>Invarijante (SAGA_test.pdf / Marzni semantika):
 * <ul>
 *   <li>I1 — ocuvanje novca (Σ balance konstantna kroz interne reserve/commit/
 *       transfer; menja se samo eksternim credit/debit — kojih u OTC-u nema)</li>
 *   <li>I2 — ocuvanje akcija (qty se samo premesta seller→buyer)</li>
 *   <li>I3 — atomičnost: posle rollback-a nikakvih delimicnih efekata, reserved=0</li>
 *   <li>I4 — bez "висećih" rezervacija (buyer reserved + seller reservedQuantity oslobodjeni)</li>
 *   <li>I5 — idempotentnost dvostrukog exercise-a (drugi → 409, bez dvostruke naplate)</li>
 *   <li>I6 — fidelnost rollback-a (status ugovora vracen na ACTIVE, balance/qty tacno vraceni)</li>
 *   <li>I8 — crash recovery (zaglavljena SAGA dovedena do terminalnog stanja, invarijante drze)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(FakeBankaCoreConfig.class)
@TestPropertySource(properties = "saga.chaos.enabled=true")
class OtcSagaInvariantIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long BUYER_ID = 8001L;
    private static final Long SELLER_ID = 8002L;
    private static final Long BUYER_ACCOUNT_ID = 9001L;
    private static final Long SELLER_ACCOUNT_ID = 9002L;

    private static final String CCY = "USD";
    private static final int QTY = 10;
    private static final BigDecimal STRIKE = new BigDecimal("160.0000");
    /** strike × qty = 1600 — iznos koji F3 premesta buyer→seller. */
    private static final BigDecimal COST = new BigDecimal("1600.0000");
    private static final BigDecimal START_BALANCE = new BigDecimal("100000.0000");

    @Value("${local.server.port}")
    private int port;

    @Autowired private OtcContractRepository contractRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private PortfolioRepository portfolioRepository;
    @Autowired private SagaLogRepository sagaLogRepository;
    @Autowired private SagaRecoveryService sagaRecoveryService;
    @Autowired private EntityManager entityManager;

    /** @Primary fake iz FakeBankaCoreConfig; cast-ujemo na konkretan tip za seed/introspekciju. */
    @Autowired private BankaCoreClient bankaCoreClient;
    private FakeBankaCoreClient fake;

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
        fake = (FakeBankaCoreClient) bankaCoreClient;
        sagaLogRepository.deleteAll();
        contractRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();

        // Default identitet: kupac (CLIENT BUYER_ID). Test 403/seller scenariji prekrivaju.
        lenient().when(tradingUserResolver.resolveCurrent())
                .thenReturn(new UserContext(BUYER_ID, UserRole.CLIENT));

        // (role,userId) -> account za getPreferredAccount + ownerClientId derivaciju (verifyOwnership).
        fake.mapPreferredAccount(UserRole.CLIENT, BUYER_ID, BUYER_ACCOUNT_ID);
        fake.mapPreferredAccount(UserRole.CLIENT, SELLER_ID, SELLER_ACCOUNT_ID);
    }

    @AfterEach
    void tearDown() {
        sagaLogRepository.deleteAll();
        contractRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();
    }

    // ─────────────────────────── seed helpers ───────────────────────────────

    /**
     * Seed za REUSE-rezervacije put (accept-time rezervacija postoji): oba racuna
     * 100000 USD, buyer-u 1600 vec drzano kao reserved, RES-1010 registrovana.
     */
    private void seedAccountsWithReservation() {
        fake.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE, COST);   // reserved=1600 hold
        fake.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);
        fake.seedReservation("RES-1010", BUYER_ACCOUNT_ID, COST);
    }

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

    /** ACTIVE ugovor sa accept-time rezervacijom (F1 REUSE-uje RES-1010). */
    private OtcContract savedReservedContract(Listing listing) {
        OtcContract c = baseContract(listing);
        c.setBuyerReservedAccountId(BUYER_ACCOUNT_ID);
        c.setBuyerReservedAmount(COST);
        c.setBankaCoreReservationId("RES-1010");
        return contractRepository.save(c);
    }

    /** ACTIVE ugovor BEZ rezervacije (F1 mora da reserve-uje na exercise — SG-03 put). */
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

    /**
     * Seller portfolio sa NEDOVOLJNO raspolozivih akcija za SG-04 (F2 neuspeh).
     * {@code quantity} akcija, 0 rezervisanih, 0 javnih → {@code availableQuantity = quantity}.
     * Za ugovor koji trazi {@link #QTY} (10) seedujemo manje (npr. 3) → F2 baca.
     */
    private Portfolio savedSellerPortfolioWithShares(Listing listing, int quantity) {
        Portfolio p = new Portfolio();
        p.setUserId(SELLER_ID);
        p.setUserRole(UserRole.CLIENT);
        p.setListingId(listing.getId());
        p.setListingTicker("AAPL");
        p.setListingName("Apple Inc.");
        p.setListingType("STOCK");
        p.setQuantity(quantity);
        p.setAverageBuyPrice(new BigDecimal("100.0000"));
        p.setPublicQuantity(0);
        p.setReservedQuantity(0);
        return portfolioRepository.save(p);
    }

    private List<OwnerKey> owners(Listing listing) {
        List<OwnerKey> owners = new ArrayList<>();
        owners.add(new OwnerKey(BUYER_ID, UserRole.CLIENT, listing.getId()));
        owners.add(new OwnerKey(SELLER_ID, UserRole.CLIENT, listing.getId()));
        return owners;
    }

    private StateSnapshot snapshot(OtcContract contract, Listing listing) {
        return StateSnapshot.capture(entityManager, fake, portfolioRepository, contractRepository,
                contract.getId(), owners(listing), List.of(BUYER_ACCOUNT_ID, SELLER_ACCOUNT_ID));
    }

    // ─────────────────────────── HTTP helpers ───────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> exercise(Long contractId, String forceFailHeader) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(buildToken("buyer@test.com", "CLIENT"));
        if (forceFailHeader != null) {
            h.add("X-Saga-Force-Fail", forceFailHeader);
        }
        return restTemplate.exchange(
                url("/otc/contracts/" + contractId + "/exercise?buyerAccountId=" + BUYER_ACCOUNT_ID),
                HttpMethod.POST, new HttpEntity<>(h), String.class);
    }

    /**
     * Exercise sa MID-phase fault header-om ({@code X-Saga-Force-Fail-Mid}) — forsira pad
     * U SREDINI date forward faze (npr. F4: posle seller dekrementa, pre buyer credit-a).
     * Koristi se za testove delimicnog forward pada (P0-1).
     */
    private ResponseEntity<String> exerciseMidFail(Long contractId, String midFailHeader) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(buildToken("buyer@test.com", "CLIENT"));
        h.add("X-Saga-Force-Fail-Mid", midFailHeader);
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

    /**
     * Exercise sa adversarnim chaos header-ima iz SAGA_test.pdf "Fault injection headeri" sekcije:
     * {@code X-Saga-Force-Fail: F{n}} (obori forward fazu), {@code X-Saga-Compensate-Fail: C{n}}
     * (obori dati kompenzator) + {@code X-Saga-Compensate-Fail-Times: N} (broj uzastopnih neuspeha
     * pre nego sto kompenzator pocne da uspeva). Koristi se za SG-08 (kompenzator pao N puta pa uspeo).
     */
    private ResponseEntity<String> exerciseWithChaos(Long contractId, String forceFail,
                                                     String compensateFail, int compensateFailTimes) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(buildToken("buyer@test.com", "CLIENT"));
        if (forceFail != null) {
            h.add("X-Saga-Force-Fail", forceFail);
        }
        if (compensateFail != null) {
            h.add("X-Saga-Compensate-Fail", compensateFail);
            h.add("X-Saga-Compensate-Fail-Times", Integer.toString(compensateFailTimes));
        }
        return restTemplate.exchange(
                url("/otc/contracts/" + contractId + "/exercise?buyerAccountId=" + BUYER_ACCOUNT_ID),
                HttpMethod.POST, new HttpEntity<>(h), String.class);
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ─────────────────────────── Tests ──────────────────────────────────────

    /**
     * <b>Test 1 — Happy path: I1 + I2 + I3/I4 + I6.</b>
     * Σ novca ocuvana; 1600 ide buyer→seller; 10 akcija ide seller→buyer; sve
     * rezervacije (novac + akcije) na 0; ugovor EXERCISED.
     */
    @Test
    @DisplayName("Happy path COMPLETED: I1 novac ocuvan, I2 akcije ocuvane, I3/I4 reserved=0, I6 EXERCISED")
    void happyPath_conservesMoneyAndShares() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);
        seedAccountsWithReservation();

        StateSnapshot pre = snapshot(contract, listing);

        ResponseEntity<String> resp = exercise(contract.getId(), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"sagaStatus\":\"COMPLETED\"");
        assertThat(resp.getBody()).contains("\"status\":\"EXERCISED\"");

        StateSnapshot post = snapshot(contract, listing);

        // I1: ukupna masa novca ocuvana + tacan smer (buyer -1600, seller +1600)
        assertThat(post.totalMoney()).isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID).subtract(COST));
        assertThat(post.balances().get(SELLER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(SELLER_ACCOUNT_ID).add(COST));

        // I2: ukupan broj akcija ocuvan, 10 premesteno seller→buyer
        assertThat(post.totalShares()).isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser().get("CLIENT|" + BUYER_ID))
                .isEqualTo(pre.sharesByUser().get("CLIENT|" + BUYER_ID) + QTY);
        assertThat(post.sharesByUser().get("CLIENT|" + SELLER_ID))
                .isEqualTo(pre.sharesByUser().get("CLIENT|" + SELLER_ID) - QTY);

        // I3/I4: nikakvih висećih rezervacija (novac + akcije)
        assertThat(fake.reservedOf(BUYER_ACCOUNT_ID)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(post.reservedShares().get("CLIENT|" + SELLER_ID)).isZero();

        // I6: ugovor EXERCISED u bazi
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.EXERCISED);

        // log: 5 forward "ok" zapisa
        String sagaId = extractSagaId(resp.getBody());
        assertThat(countOccurrences(getSaga(sagaId).getBody(), "\"outcome\":\"ok\"")).isEqualTo(5);
    }

    /**
     * <b>Test 2 — F3 forced-fail rollback (accept-time ugovor): I1 + I2 + I6 + BUG-W2-01 FIX.</b>
     * F3 pada PRE bocnih efekata → kompenzacija C2/C1 → COMPENSATED.
     *
     * <p>Invarijante koje DRZE i strogo se asertuju: I1 (novac netaknut — F3 pao
     * pre commit-a), I2 (akcije netaknute), I6 (status ugovora vracen na ACTIVE),
     * vlasnistvo akcija (quantity) netaknuto.
     *
     * <p><b>BUG-W2-01 FIX (kompenzacija postuje create/reuse model):</b>
     * F1/F2 su "ensure-reserved" no-op kad accept-time rezervacija vec postoji
     * (ugovor je seedovan sa {@code bankaCoreReservationId} + seller
     * {@code reservedQuantity=10}), pa C1/C2 NE oslobadjaju tu rezervaciju (jer je
     * nisu kreirali). Posle rollback-a ugovor je ACTIVE, a buyer-ova novcana
     * rezervacija (1600) i seller-ova {@code reservedQuantity} (10) su OCUVANE —
     * sto zadovoljava spec Celina 4 ("Prodavceva javna kolicina je zakljucana dok
     * ugovor ne istekne ili ne bude iskoriscen").
     */
    @Test
    @DisplayName("F3 forced-fail COMPENSATED (accept-time): I1/I2/I6 vraceni; accept-time rezervacije OCUVANE")
    void f3ForcedFail_fullRollback() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);
        seedAccountsWithReservation();

        StateSnapshot pre = snapshot(contract, listing);
        assertThat(pre.reservedShares().get("CLIENT|" + SELLER_ID)).isEqualTo(10);
        assertThat(fake.reservedOf(BUYER_ACCOUNT_ID)).isEqualByComparingTo(COST);  // accept-time hold

        ResponseEntity<String> resp = exercise(contract.getId(), "F3");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"sagaStatus\":\"COMPENSATED\"");
        assertThat(resp.getBody()).contains("\"currentStep\":3");
        assertThat(resp.getBody()).contains("\"status\":\"ACTIVE\"");

        StateSnapshot post = snapshot(contract, listing);

        // I1: novac netaknut (F3 pao pre commit-a/credit-a) — masa + per-racun balance
        assertThat(post.totalMoney()).isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID));
        assertThat(post.balances().get(SELLER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(SELLER_ACCOUNT_ID));

        // I2/I6: vlasnistvo akcija + status vraceni na pocetno
        assertThat(post.totalShares()).isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser()).isEqualTo(pre.sharesByUser());
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.ACTIVE);

        // BUG-W2-01 FIX: ugovor ostaje ACTIVE → accept-time rezervacije OCUVANE (C1/C2 nisu ih kreirali).
        // Celina 4 lock invarijanta: seller javna kolicina zakljucana dok ugovor ne istekne/iskoristi.
        assertThat(post.reservedShares().get("CLIENT|" + SELLER_ID))
                .as("BUG-W2-01 FIX: C2 cuva accept-time seller rezervaciju na ACTIVE ugovoru")
                .isEqualTo(10);
        assertThat(fake.reservedOf(BUYER_ACCOUNT_ID))
                .as("BUG-W2-01 FIX: C1 cuva accept-time buyer rezervaciju na ACTIVE ugovoru")
                .isEqualByComparingTo(COST);
    }

    /**
     * <b>Test 3 — F4 forced-fail rollback: KLJUČNI I1 dokaz (C3 reverzni transfer).</b>
     * F3 je VEC premestio novac buyer→seller; C3 mora da ga vrati. Ako bi C3 bio
     * samo credit kupcu (a ne reverzni transfer), totalMoney bi bio VECI od pre —
     * pa asertujemo da je TACNO jednak (oba balance-a tacno vracena).
     */
    @Test
    @DisplayName("F4 forced-fail COMPENSATED: C3 reverzni transfer vraca novac (I1 — totalMoney TACNO jednak)")
    void f4ForcedFail_c3ReverseTransferRestoresMoney() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);
        seedAccountsWithReservation();

        StateSnapshot pre = snapshot(contract, listing);

        ResponseEntity<String> resp = exercise(contract.getId(), "F4");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"sagaStatus\":\"COMPENSATED\"");
        assertThat(resp.getBody()).contains("\"currentStep\":4");
        assertThat(resp.getBody()).contains("\"status\":\"ACTIVE\"");

        StateSnapshot post = snapshot(contract, listing);

        // I1 KLJUC: totalMoney TACNO jednak (ne veci) — C3 reverzni transfer ponistio F3
        assertThat(post.totalMoney()).isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID));
        assertThat(post.balances().get(SELLER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(SELLER_ACCOUNT_ID));

        // akcije: vlasnistvo nikad trajno premesteno (F4 pao pre prenosa, C4 vratio) + ugovor ACTIVE
        assertThat(post.totalShares()).isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser()).isEqualTo(pre.sharesByUser());
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.ACTIVE);
        // BUG-W2-01 FIX: seller accept-time rezervacija akcija OCUVANA — F2 je bio no-op (accept-time
        // pokrice), F4 pao PRE prenosa akcija, C2 no-op → seller reservedQuantity OSTAJE 10
        // (Celina 4 lock invarijanta: javna kolicina zakljucana dok ugovor ne istekne/iskoristi).
        assertThat(post.reservedShares().get("CLIENT|" + SELLER_ID))
                .as("BUG-W2-01 FIX: seller accept-time reservedQuantity ocuvan na ACTIVE ugovoru")
                .isEqualTo(10);
        // NB (buyer novcani hold): za razliku od seller akcija, buyer-ova accept-time NOVCANA
        // rezervacija je VEC POTROSENA u F3 (commitFunds konzumira RES-1010), pa je C3 vratio novac
        // kao BALANCE transfer (ne kao re-hold). Posle rollback-a buyer reserved=0 ali je balance
        // tacno vracen — I1 (ocuvanje novca) DRZI; "hold" vise ne postoji jer ga je F3 zatvorio.
        // C1 je no-op (F1 REUSE-ovao accept-time hold, nije kreirao nov), pa ne re-rezervise.
        // (Ovo je posledica nepromenjene F3/C3 money-leg logike — van scope-a gating fix-a.)
        assertThat(fake.reservedOf(BUYER_ACCOUNT_ID))
                .as("buyer accept-time hold potrosen u F3 commit-u; C3 vratio novac kao balance (I1)")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * <b>Test 4 — Insufficient funds F1 (SG-03).</b>
     * Ugovor BEZ accept-time rezervacije + buyer balance < strike×qty → F1
     * reserveFunds vrati 409 → COMPENSATED currentStep 1, bez bocnih efekata.
     *
     * <p><b>P0-1 (loop-inclusive):</b> kompenzaciona petlja sad ukljucuje failed step,
     * pa se i C1 izvrsi — ali kao NO-OP (F1 je pao u reserveFunds PRE kreiranja
     * rezervacije → {@code buyerReservationCreatedHere=false}, {@code reservationId=null}
     * → C1 ne poziva releaseFunds). Log dakle ima 1 "err" (F1 fwd) + 1 "ok" (C1 no-op
     * compensate). Kljucna invarijanta (nema bocnih efekata na novac/akcije/status)
     * je netaknuta — samo se belezi idempotentan no-op C1.
     */
    @Test
    @DisplayName("Insufficient funds F1 (SG-03): COMPENSATED step1, post==pre, C1 no-op compensate")
    void insufficientFunds_f1_noSideEffects() {
        Listing listing = savedStockListing();
        OtcContract contract = savedUnreservedContract(listing);
        savedSellerPortfolio(listing);
        // buyer ima MANJE od 1600 → reserveFunds vrati 409
        fake.seedAccount(BUYER_ACCOUNT_ID, CCY, new BigDecimal("500.0000"));
        fake.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);

        StateSnapshot pre = snapshot(contract, listing);

        ResponseEntity<String> resp = exercise(contract.getId(), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"sagaStatus\":\"COMPENSATED\"");
        assertThat(resp.getBody()).contains("\"currentStep\":1");

        StateSnapshot post = snapshot(contract, listing);

        // bez bocnih efekata
        assertThat(post.totalMoney()).isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID));
        assertThat(fake.reservedOf(BUYER_ACCOUNT_ID)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(post.totalShares()).isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser()).isEqualTo(pre.sharesByUser());
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.ACTIVE);

        // log: F1 forward "err" + C1 no-op compensate "ok" (loop-inclusive ukljucuje failed step;
        // C1 je no-op jer F1 nije kreirao rezervaciju → bez stvarnog releaseFunds bocnog efekta).
        String sagaId = extractSagaId(resp.getBody());
        String sagaBody = getSaga(sagaId).getBody();
        assertThat(countOccurrences(sagaBody, "\"outcome\":\"err\"")).isEqualTo(1);
        assertThat(countOccurrences(sagaBody, "\"outcome\":\"ok\"")).isEqualTo(1);
    }

    /**
     * <b>Test 5 — Idempotentnost / dvostruki exercise (I5).</b>
     * Prvi exercise → EXERCISED. Drugi → 409 (ugovor nije ACTIVE), bez dvostruke
     * naplate (totalMoney nepromenjen u odnosu na stanje posle prvog), ugovor
     * ostaje EXERCISED jednom.
     */
    @Test
    @DisplayName("Dvostruki exercise (I5): drugi → 409, bez dvostruke naplate, ugovor EXERCISED jednom")
    void doubleExercise_secondReturns409_noDoubleCharge() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);
        seedAccountsWithReservation();

        ResponseEntity<String> first = exercise(contract.getId(), null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("\"sagaStatus\":\"COMPLETED\"");

        StateSnapshot afterFirst = snapshot(contract, listing);

        ResponseEntity<String> second = exercise(contract.getId(), null);

        // pre-saga validacija: ugovor nije ACTIVE → IllegalStateException → 409 (OtcExceptionHandler)
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        StateSnapshot afterSecond = snapshot(contract, listing);

        // bez dvostruke naplate: novac + akcije + status nepromenjeni od posle-prvog
        assertThat(afterSecond.totalMoney()).isEqualByComparingTo(afterFirst.totalMoney());
        assertThat(afterSecond.balances().get(BUYER_ACCOUNT_ID))
                .isEqualByComparingTo(afterFirst.balances().get(BUYER_ACCOUNT_ID));
        assertThat(afterSecond.balances().get(SELLER_ACCOUNT_ID))
                .isEqualByComparingTo(afterFirst.balances().get(SELLER_ACCOUNT_ID));
        assertThat(afterSecond.sharesByUser()).isEqualTo(afterFirst.sharesByUser());
        assertThat(afterSecond.contractStatus()).isEqualTo(OtcContractStatus.EXERCISED);

        // tacno jedan COMPLETED saga_logs red (drugi pokusaj nije ni kreirao log)
        assertThat(sagaLogRepository.findByContractId(contract.getId())).hasSize(1);
    }

    /**
     * <b>Test 5b (P2-6) — KONKURENTNI dvostruki exercise: pesimisticki lock serijalizuje.</b>
     *
     * <p>Dva paralelna {@code POST .../exercise} nad ISTIM ACTIVE ugovorom (start gate
     * sinhronizuje race). Pre fix-a (pre-saga {@code findById} BEZ lock-a) oba bi prosla
     * ACTIVE check, oba usla u F1 REUSE granu sa istim {@code bankaCoreReservationId} i
     * oba commit-ovala RES-1010 u F3 → dvostruka naplata / lazni C3 refund (stvaranje novca).
     * Posle FIX 1 ({@code findByIdForUpdate} PESSIMISTIC_WRITE) drugi exercise blokira dok
     * prvi ne commit-uje, pa vidi ugovor vec EXERCISED → 409 (IllegalState). Ishod: TACNO
     * jedan uspeh + jedan 409, novac premesten TACNO jednom (I1/I5 — konzervacija).
     *
     * <p>SecurityContext se NE propagira rucno — svaki HTTP zahtev nosi svoj Bearer JWT,
     * pa filter chain postavlja kontekst per-request u svom worker thread-u.
     *
     * <p><b>Napomena o pouzdanosti:</b> H2 sa {@code @Lock(PESSIMISTIC_WRITE)} serijalizuje
     * konkurentne write-lock-ove na istom redu; test je deterministicki (start gate +
     * Future.get timeout). Komplementaran je sekvencijalnom Test 5 (isti 409/konzervacija
     * dokaz bez race-a) i unit verifikaciji da pre-saga citanje ide kroz {@code findByIdForUpdate}.
     */
    @Test
    @DisplayName("P2-6 konkurentni dvostruki exercise: pesimisticki lock → TACNO jedan uspeh + jedan 409, "
            + "novac premesten jednom (I1/I5)")
    void concurrentDoubleExercise_pessimisticLock_oneSuccessOne409_conserves() throws Exception {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);
        seedAccountsWithReservation();

        StateSnapshot pre = snapshot(contract, listing);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch bothReady = new CountDownLatch(2);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        Callable<ResponseEntity<String>> attempt = () -> {
            bothReady.countDown();
            startGate.await(5, TimeUnit.SECONDS);     // sinhronizovan race start
            return exercise(contract.getId(), null);
        };
        Future<ResponseEntity<String>> f1 = exec.submit(attempt);
        Future<ResponseEntity<String>> f2 = exec.submit(attempt);

        assertThat(bothReady.await(5, TimeUnit.SECONDS))
                .as("oba exercise thread-a moraju startovati pre release-a").isTrue();
        startGate.countDown();

        ResponseEntity<String> r1 = f1.get(20, TimeUnit.SECONDS);
        ResponseEntity<String> r2 = f2.get(20, TimeUnit.SECONDS);
        exec.shutdownNow();

        // TACNO jedan 200 COMPLETED i jedan 409 (drugi je serijalizovan i video EXERCISED ugovor).
        int completed = 0;
        int conflicts = 0;
        for (ResponseEntity<String> r : List.of(r1, r2)) {
            if (r.getStatusCode() == HttpStatus.OK && r.getBody() != null
                    && r.getBody().contains("\"sagaStatus\":\"COMPLETED\"")) {
                completed++;
            } else if (r.getStatusCode() == HttpStatus.CONFLICT) {
                conflicts++;
            }
        }
        assertThat(completed).as("tacno jedan exercise uspeva (COMPLETED)").isEqualTo(1);
        assertThat(conflicts).as("tacno jedan exercise odbijen sa 409 (ugovor vec EXERCISED)")
                .isEqualTo(1);

        StateSnapshot post = snapshot(contract, listing);

        // I1/I5: novac premesten TACNO jednom (buyer -1600, seller +1600), masa ocuvana.
        assertThat(post.totalMoney()).isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .as("kupac debitovan TACNO jednom (nema dvostruke naplate)")
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID).subtract(COST));
        assertThat(post.balances().get(SELLER_ACCOUNT_ID))
                .as("prodavac kreditiran TACNO jednom")
                .isEqualByComparingTo(pre.balances().get(SELLER_ACCOUNT_ID).add(COST));

        // I2: akcije premestene tacno jednom; ugovor EXERCISED jednom; tacno jedan COMPLETED log.
        assertThat(post.totalShares()).isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser().get("CLIENT|" + BUYER_ID))
                .isEqualTo(pre.sharesByUser().get("CLIENT|" + BUYER_ID) + QTY);
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.EXERCISED);
        assertThat(sagaLogRepository.findByContractId(contract.getId()))
                .as("samo prvi (uspeli) exercise je kreirao saga_logs red; drugi odbijen u pre-saga gate-u")
                .hasSize(1);
    }

    /**
     * <b>Test 6 — Crash recovery (I8), accept-time ugovor.</b>
     * Perzistuje se zaglavljen COMPENSATING SagaLog (F1+F2 primenjeni, F3 err);
     * stanje sveta je "posle F2" (buyer reserved 1600, seller reservedQuantity 10).
     * SagaLog flag-ovi {@code createdHere=false} (accept-time REUSE) → {@code
     * recoverOnce()} gura SAGA-u do COMPENSATED ali CUVA accept-time rezervacije.
     *
     * <p><b>BUG-W2-01 FIX:</b> recovery kompenzacija postuje perzistovane
     * create/reuse flag-ove tacno kao live put — ugovor ostaje ACTIVE pa
     * rezervacije (novac + akcije) ostaju zakljucane (Celina 4). I1 (ocuvanje
     * novca) drzi nezavisno od reservation-preservation odluke.
     */
    @Test
    @DisplayName("Crash recovery (I8) accept-time: COMPENSATING SagaLog → COMPENSATED, accept-time rezervacije OCUVANE")
    @Transactional
    void recovery_compensatingSaga_reachesCompensated() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);
        seedAccountsWithReservation();

        // Svet je "posle F2": buyer ima hold 1600 (RES-1010 RESERVED), seller reservedQuantity=10.
        // (seedAccountsWithReservation vec drzi buyer reserved=1600; seller reservedQuantity=10 iz portfolija)

        // Perzistuj zaglavljen COMPENSATING SagaLog: F1+F2 forward ok, F3 forward err.
        // createdHere=false: F1/F2 su REUSE-ovali accept-time rezervaciju → C1/C2 je cuvaju.
        SagaLog saga = new SagaLog();
        saga.setSagaId("crash-recovery-1");
        saga.setContractId(contract.getId());
        saga.setStatus(SagaStatus.COMPENSATING);
        saga.setCurrentStep(3);
        saga.setBankaCoreReservationId("RES-1010");
        saga.setBuyerReservationCreatedHere(false);
        saga.setSellerSharesReservedHere(false);
        saga.setSellerSharesReservedAmount(0);
        saga.append(SagaLogEntry.ok(SagaPhase.F1.step(), SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.ok(SagaPhase.F2.step(), SagaStepKind.FORWARD));
        saga.append(SagaLogEntry.err(SagaPhase.F3.step(), SagaStepKind.FORWARD, "crash pre F3 commit"));
        sagaLogRepository.saveAndFlush(saga);

        BigDecimal totalBefore = fake.totalMoney();

        sagaRecoveryService.recoverOnce();

        entityManager.flush();
        entityManager.clear();

        SagaLog recovered = sagaLogRepository.findBySagaId("crash-recovery-1").orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(SagaStatus.COMPENSATED);

        // I1: novac ocuvan (recovery ne pravi/unistava novac)
        assertThat(fake.totalMoney()).isEqualByComparingTo(totalBefore);
        // BUG-W2-01 FIX: accept-time hold ocuvan (C1 ne oslobadja) + seller reservedQuantity ocuvan (C2 no-op)
        assertThat(fake.reservedOf(BUYER_ACCOUNT_ID)).isEqualByComparingTo(COST);
        Portfolio sellerAfter = portfolioRepository
                .findByUserIdAndUserRoleAndListingId(SELLER_ID, UserRole.CLIENT, listing.getId())
                .orElseThrow();
        assertThat(sellerAfter.getReservedQuantity()).isEqualTo(10);
        // ugovor i dalje ACTIVE (F5 nikad dosegnut)
        OtcContract contractAfter = contractRepository.findById(contract.getId()).orElseThrow();
        assertThat(contractAfter.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);
    }

    /**
     * <b>Test 7 (P0-1, Bug A) — F3 DELIMICAN pad: commit OK, credit FAIL → I1 ocuvan.</b>
     *
     * <p>F3 nije atoman: {@code commitFunds} (debit kupca + zatvaranje rezervacije) pa
     * {@code creditFunds} (isplata prodavcu) su DVA poziva. Forsiramo banka-core 5xx na
     * credit-u POSLE uspelog commit-a → saga pada u koraku 3 sa VEC debitovanim kupcem i
     * NIKAD kreditiranim prodavcem. Pre fix-a {@code compensate(failedStep-1)} preskace C3
     * pa novac nestaje (I1 prekrsen). Posle fix-a {@code compensate(failedStep)} pokrece C3,
     * koji (jer je samo commit obavljen) kreditira kupca nazad commit-ovani iznos.
     *
     * <p>Asercije: saga COMPENSATED; {@code post.totalMoney == pre.totalMoney} (byte-identicno);
     * buyer balance vracen na pre; seller balance NETAKNUT (nikad kreditiran); ugovor ACTIVE.
     */
    @Test
    @DisplayName("F3 delimican (commit OK, credit FAIL) COMPENSATED: I1 ocuvan (C3 vraca commit kupcu)")
    void f3PartialFail_commitOk_creditFails_conservesMoney() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);   // accept-time: F3 commit konzumira RES-1010
        savedSellerPortfolio(listing);
        seedAccountsWithReservation();

        StateSnapshot pre = snapshot(contract, listing);
        // forsiraj fail na credit-u ka prodavcu (prvi creditFunds poziv u sagi je bas F3 seller credit)
        fake.failCreditFor(SELLER_ACCOUNT_ID);

        ResponseEntity<String> resp = exercise(contract.getId(), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"sagaStatus\":\"COMPENSATED\"");
        assertThat(resp.getBody()).contains("\"currentStep\":3");
        assertThat(resp.getBody()).contains("\"status\":\"ACTIVE\"");

        StateSnapshot post = snapshot(contract, listing);

        // I1 KLJUC: masa novca ocuvana iako je F3 commit debitovao kupca a credit pao —
        // C3 je vratio commit-ovani iznos kupcu (nema debit prodavca jer nije ni kreditiran).
        assertThat(post.totalMoney())
                .as("Bug A: F3 commit-OK/credit-FAIL ne sme da unisti novac — C3 vraca kupcu")
                .isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .as("kupcu vracen ceo commit-ovani iznos")
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID));
        assertThat(post.balances().get(SELLER_ACCOUNT_ID))
                .as("prodavac nikad kreditiran → balance netaknut")
                .isEqualByComparingTo(pre.balances().get(SELLER_ACCOUNT_ID));

        // akcije + status netaknuti (F4/F5 nikad dosegnuti)
        assertThat(post.totalShares()).isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser()).isEqualTo(pre.sharesByUser());
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.ACTIVE);
    }

    /**
     * <b>Test 8 (P0-1, Bug B) — F4 DELIMICAN pad: seller dekrementovan, buyer credit FAIL → I2 ocuvan.</b>
     *
     * <p>F4 prvo dekrementuje seller poziciju (save/delete) pa kreditira buyer poziciju. Forsiramo
     * pad U SREDINI ({@code X-Saga-Force-Fail-Mid: F4}) — posle seller dekrementa, pre buyer credit-a.
     * Saga pada u koraku 4 sa VEC umanjenom seller pozicijom i bez buyer pozicije. Pre fix-a
     * {@code compensate(failedStep-1)} preskace C4 pa su akcije UNISTENE (I2 prekrsen). Posle fix-a
     * {@code compensate(failedStep)} pokrece C4 (partial-safe: vraca seller akcije jer je
     * {@code f4SellerApplied=true}, ne dira buyer jer blok nije ni izvrsen).
     *
     * <p>Dodatno: F3 je VEC premestio novac → C3 reverzni transfer ga vraca (I1). Asercije:
     * saga COMPENSATED; ukupne akcije ocuvane (seller vracen, buyer netaknut);
     * {@code post.totalMoney == pre.totalMoney}; ugovor ACTIVE.
     */
    @Test
    @DisplayName("F4 delimican (seller dekrement OK, buyer credit FAIL) COMPENSATED: I2 ocuvan (C4 vraca seller akcije)")
    void f4PartialFail_sellerDecrementedBuyerFails_conservesShares() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);
        seedAccountsWithReservation();

        StateSnapshot pre = snapshot(contract, listing);

        ResponseEntity<String> resp = exerciseMidFail(contract.getId(), "F4");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"sagaStatus\":\"COMPENSATED\"");
        assertThat(resp.getBody()).contains("\"currentStep\":4");
        assertThat(resp.getBody()).contains("\"status\":\"ACTIVE\"");

        StateSnapshot post = snapshot(contract, listing);

        // I2 KLJUC: ukupne akcije ocuvane iako je F4 dekrementovao prodavca pre nego sto je pao —
        // C4 (partial-safe) vratio prodavcu akcije; kupac nikad nije dobio (buyer blok nije izvrsen).
        assertThat(post.totalShares())
                .as("Bug B: F4 partial pad ne sme da unisti akcije — C4 vraca prodavcu")
                .isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser().get("CLIENT|" + SELLER_ID))
                .as("seller akcije vracene na pre vrednost")
                .isEqualTo(pre.sharesByUser().get("CLIENT|" + SELLER_ID));
        assertThat(post.sharesByUser().get("CLIENT|" + BUYER_ID))
                .as("buyer pozicija netaknuta (credit nikad nije izvrsen)")
                .isEqualTo(pre.sharesByUser().get("CLIENT|" + BUYER_ID));

        // I1: F3 vec premestio novac → C3 reverzni transfer ga vratio (totalMoney TACNO jednak)
        assertThat(post.totalMoney())
                .as("F3 izvrsen pre F4 pada → C3 reverzni transfer ponistio money-leg")
                .isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID));
        assertThat(post.balances().get(SELLER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(SELLER_ACCOUNT_ID));

        // status vracen + seller accept-time reservedQuantity ocuvan (C2 no-op)
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.ACTIVE);
        assertThat(post.reservedShares().get("CLIENT|" + SELLER_ID))
                .as("BUG-W2-01 FIX: seller accept-time reservedQuantity ocuvan na ACTIVE ugovoru")
                .isEqualTo(10);
    }

    /**
     * <b>SG-04 (SAGA_test.pdf) — Nedovoljno hartija (F2 neuspeh).</b>
     *
     * <p>Setup: ugovor trazi {@link #QTY} (10) hartija, prodavac ima samo 3
     * (raspolozivih). Reserve-at-exercise put (ugovor BEZ accept-time rezervacije),
     * pa F1 STVARNO rezervise buyer-ova sredstva (kreira rezervaciju). F2 nailazi na
     * {@code availableQuantity (3) < need (10)} → baca → saga pada u koraku 2.
     *
     * <p>Spec ocekivano: {@code Compensated, current_step=2}. Log: F1 ok, F2 greska,
     * C1 ok (rezervacija sa F1 OTPUSTENA u C1 — jer ju je OVA saga kreirala,
     * {@code buyerReservationCreatedHere=true}). Stanje racuna i hartija nepromenjeno
     * (I1/I2/I3/I6): novac ocuvan, buyer reserved natrag na 0, hartije netaknute,
     * ugovor ACTIVE.
     */
    @Test
    @DisplayName("SG-04 nedovoljno hartija (F2 neuspeh): COMPENSATED step2, F1 ok/F2 err/C1 ok, "
            + "F1 rezervacija OTPUSTENA u C1, stanje nepromenjeno (I1/I2/I3/I6)")
    void sg04_insufficientShares_f2Fails_compensatedStep2_releasesF1Reservation() {
        Listing listing = savedStockListing();
        // Ugovor BEZ accept-time rezervacije → F1 reserveFunds STVARNO kreira rezervaciju (C1 je oslobadja).
        OtcContract contract = savedUnreservedContract(listing);
        // Prodavac ima 3 raspolozive hartije, ugovor trazi 10 → F2 baca (SG-04).
        savedSellerPortfolioWithShares(listing, 3);
        // Buyer ima dovoljno novca (F1 reserveFunds prolazi), seller racun postoji.
        fake.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE);
        fake.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);

        StateSnapshot pre = snapshot(contract, listing);
        assertThat(fake.reservedOf(BUYER_ACCOUNT_ID))
                .as("pre-uslov: buyer nema nikakvu rezervaciju (reserve-at-exercise put)")
                .isEqualByComparingTo(BigDecimal.ZERO);

        ResponseEntity<String> resp = exercise(contract.getId(), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"sagaStatus\":\"COMPENSATED\"");
        assertThat(resp.getBody()).contains("\"currentStep\":2");
        assertThat(resp.getBody()).contains("\"status\":\"ACTIVE\"");

        StateSnapshot post = snapshot(contract, listing);

        // I1: novac ocuvan + buyer balance netaknut (F1 samo rezervisao, F2 pao pre commit-a)
        assertThat(post.totalMoney()).isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID));
        assertThat(post.balances().get(SELLER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(SELLER_ACCOUNT_ID));

        // I3 KLJUC: F1 rezervacija OTPUSTENA u C1 → buyer reserved natrag na 0 (nema висеће rezervacije).
        assertThat(fake.reservedOf(BUYER_ACCOUNT_ID))
                .as("SG-04: rezervacija sa F1 OTPUSTENA u C1 (buyerReservationCreatedHere=true)")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // I2/I6: hartije netaknute (F2 pao pre rezervacije) + ugovor ACTIVE
        assertThat(post.totalShares()).isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser()).isEqualTo(pre.sharesByUser());
        assertThat(post.reservedShares().get("CLIENT|" + SELLER_ID))
                .as("seller reservedQuantity netaknut (F2 pao pre setReservedQuantity)")
                .isZero();
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.ACTIVE);

        // log: F1 forward "ok" + F2 forward "err" + C1 compensate "ok"
        // (loop-inclusive ukljucuje failed step F2; C2 je no-op jer F2 nije nista rezervisao →
        //  ne pojavljuje se kao zaseban side-effect, ali je tehnicki "ok" no-op zapis za C2 + C1).
        String sagaId = extractSagaId(resp.getBody());
        String sagaBody = getSaga(sagaId).getBody();
        // tacno 1 forward err (F2)
        assertThat(countOccurrences(sagaBody, "\"outcome\":\"err\""))
                .as("tacno jedan forward err — F2")
                .isEqualTo(1);
        // F1 forward ok je prisutan
        assertThat(sagaBody).contains("\"phase\":1,\"kind\":\"FORWARD\",\"outcome\":\"ok\"");
        // F2 forward err je prisutan
        assertThat(sagaBody).contains("\"phase\":2,\"kind\":\"FORWARD\",\"outcome\":\"err\"");
        // C1 compensate ok je prisutan (rezervacija oslobodjena)
        assertThat(sagaBody).contains("\"phase\":1,\"kind\":\"COMPENSATE\",\"outcome\":\"ok\"");
    }

    /**
     * <b>SG-08 (SAGA_test.pdf) — Kompenzator pao jednom, pa uspeo (IN-LINE compensate retry).</b>
     *
     * <p>Action: {@code X-Saga-Force-Fail: F3 + X-Saga-Compensate-Fail: C2 +
     * X-Saga-Compensate-Fail-Times: 1}. F3 pada pre bocnih efekata → kompenzacija krece
     * obrnutim redom: C2 (prvi pokusaj BACA — forsiran fault, drugi pokusaj USPEVA), pa C1.
     * Ovo vozi INLINE compensate retry-loop u {@code OtcExerciseSagaOrchestrator.compensate}
     * (a NE recovery-scheduler put), koji retry-uje idempotentni kompenzator do uspeha.
     *
     * <p>Spec ocekivano: {@code Compensated}. Log UKLJUCUJE DVA zapisa za C2 (prvi sa greskom,
     * drugi ok). Stanje identicno prethodnom (invarijante I1/I2/I6 drze).
     *
     * <p>Reserve-at-exercise put (ugovor BEZ accept-time rezervacije) tako da F2 STVARNO
     * rezervise seller hartije ({@code sellerSharesReservedHere=true}) → C2 ima pravi
     * side-effect koji se prvo forsirano obori pa retry-uje (ne degeneriše u no-op).
     */
    @Test
    @DisplayName("SG-08 inline compensate retry (F3 fail + C2 fail x1): COMPENSATED, DVA C2 zapisa "
            + "(err pa ok) u istom sagaId, stanje nepromenjeno (I1/I2/I6)")
    void sg08_inlineCompensateRetry_c2FailsOncePasses_compensatedTwoC2Entries() {
        Listing listing = savedStockListing();
        // Reserve-at-exercise put: F1 reserve-uje novac, F2 reserve-uje seller hartije (C2 ima side-effect).
        OtcContract contract = savedUnreservedContract(listing);
        // Prodavac ima 20 hartija, 0 rezervisanih → F2 rezervise 10 (sellerSharesReservedHere=true).
        savedSellerPortfolioWithShares(listing, 20);
        fake.seedAccount(BUYER_ACCOUNT_ID, CCY, START_BALANCE);
        fake.seedAccount(SELLER_ACCOUNT_ID, CCY, START_BALANCE);

        StateSnapshot pre = snapshot(contract, listing);

        // F3 force-fail + C2 fail once then pass → inline retry loop.
        ResponseEntity<String> resp = exerciseWithChaos(contract.getId(), "F3", "C2", 1);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .as("SG-08: terminalni status COMPENSATED (inline retry dovrsio C2)")
                .contains("\"sagaStatus\":\"COMPENSATED\"");
        assertThat(resp.getBody()).contains("\"currentStep\":3");
        assertThat(resp.getBody()).contains("\"status\":\"ACTIVE\"");

        String sagaId = extractSagaId(resp.getBody());
        String sagaBody = getSaga(sagaId).getBody();

        // KLJUC: log UKLJUCUJE DVA C2 zapisa (phase=2, kind=COMPENSATE) — prvi err, drugi ok.
        assertThat(countOccurrences(sagaBody, "\"phase\":2,\"kind\":\"COMPENSATE\""))
                .as("SG-08: dva C2 zapisa (prvi pad, drugi uspeh) u istom sagaId")
                .isEqualTo(2);
        assertThat(sagaBody)
                .as("prvi C2 pokusaj zabelezen kao err")
                .contains("\"phase\":2,\"kind\":\"COMPENSATE\",\"outcome\":\"err\"");
        assertThat(sagaBody)
                .as("drugi C2 pokusaj zabelezen kao ok")
                .contains("\"phase\":2,\"kind\":\"COMPENSATE\",\"outcome\":\"ok\"");
        // C1 takodje prosao (kompenzacija nastavila posle C2 uspeha)
        assertThat(sagaBody).contains("\"phase\":1,\"kind\":\"COMPENSATE\",\"outcome\":\"ok\"");

        StateSnapshot post = snapshot(contract, listing);

        // Stanje identicno prethodnom: novac ocuvan, buyer reserved natrag na 0 (C1 oslobodio),
        // seller reservedQuantity natrag na 0 (C2 oslobodio posle retry-ja), ugovor ACTIVE.
        assertThat(post.totalMoney()).isEqualByComparingTo(pre.totalMoney());
        assertThat(post.balances().get(BUYER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(BUYER_ACCOUNT_ID));
        assertThat(post.balances().get(SELLER_ACCOUNT_ID))
                .isEqualByComparingTo(pre.balances().get(SELLER_ACCOUNT_ID));
        assertThat(fake.reservedOf(BUYER_ACCOUNT_ID))
                .as("C1 oslobodio buyer rezervaciju posle C2 uspeha")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(post.totalShares()).isEqualTo(pre.totalShares());
        assertThat(post.sharesByUser()).isEqualTo(pre.sharesByUser());
        assertThat(post.reservedShares().get("CLIENT|" + SELLER_ID))
                .as("C2 oslobodio seller rezervaciju (retry do uspeha)")
                .isZero();
        assertThat(post.contractStatus()).isEqualTo(OtcContractStatus.ACTIVE);
    }
}
