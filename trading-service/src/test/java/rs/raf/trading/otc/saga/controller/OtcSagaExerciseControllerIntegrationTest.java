package rs.raf.trading.otc.saga.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CommitFundsResponse;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.otc.saga.model.SagaStatus;
import rs.raf.trading.otc.saga.repository.SagaLogRepository;
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
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * HTTP integracioni test Model-B SAGA exercise putanje (W1.10) — pun Spring
 * kontekst (H2 test profil), RANDOM_PORT, realan security filter chain (lokalna
 * JWT validacija) + realan {@code OtcController} + {@code OtcExerciseSagaOrchestrator}
 * + JPA persistencija. Bootstrap je kloniran iz {@code OrderControllerIntegrationTest}.
 *
 * <p>{@code BankaCoreClient} i {@code TradingUserResolver} su {@code @MockitoBean}
 * (trading-service nema korisnicku bazu — JWT izdaje banka-core; novcane noge ka
 * banka-core /internal/funds API-ju se mock-uju). {@code Listing} / {@code OtcContract}
 * / {@code Portfolio} su trading-service entiteti pa se seeduju direktno preko
 * repozitorijuma.
 *
 * <p>Pokriva:
 * <ul>
 *   <li>happy path -> 200, sagaStatus=COMPLETED, currentStep=5, status=EXERCISED,
 *       5 log zapisa, ugovor EXERCISED u H2, saga_logs red postoji</li>
 *   <li>pre-saga 403 (ne-kupac) -> NEMA saga_logs reda</li>
 *   <li>rollback smoke: commitFunds baca -> 200, sagaStatus=COMPENSATED, ugovor ACTIVE</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OtcSagaExerciseControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long BUYER_ID = 8001L;
    private static final Long SELLER_ID = 8002L;
    private static final Long BUYER_ACCOUNT_ID = 9001L;
    private static final Long SELLER_ACCOUNT_ID = 9002L;

    @Value("${local.server.port}")
    private int port;

    @Autowired private OtcContractRepository contractRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private PortfolioRepository portfolioRepository;
    @Autowired private SagaLogRepository sagaLogRepository;
    // P1-authz-idor-1: drugi IT-ovi (OtcNegotiationHistoryControllerIntegrationTest) u
    // istom shared H2 contextu seeduju otc_offers (FK→listings). Cistimo ih pre listings
    // da listingRepository.deleteAll() ne padne na referential-integrity FK.
    @Autowired private rs.raf.trading.otc.repository.OtcOfferRepository offerRepository;

    @MockitoBean private BankaCoreClient bankaCoreClient;
    @MockitoBean private TradingUserResolver tradingUserResolver;

    private final RestTemplate restTemplate = createRestTemplate();
    private final SecretKey secretKey =
            Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

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
    void clean() {
        sagaLogRepository.deleteAll();
        contractRepository.deleteAll();
        offerRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();

        // Default identitet: kupac (CLIENT). Pojedinacni testovi prekrivaju.
        lenient().when(tradingUserResolver.resolveCurrent())
                .thenReturn(new UserContext(BUYER_ID, UserRole.CLIENT));

        // P2-7: exercise sad forsira OTC access gate (TradingPermissionResolver razresava
        // TRADE_STOCKS preko banka-core getUserPermissions). Default: SVI klijenti imaju
        // TRADE_STOCKS (smeju OTC) — test za 403 gate prekriva za svoj specifican email.
        lenient().when(bankaCoreClient.getUserPermissions(anyString()))
                .thenReturn(java.util.List.of("TRADE_STOCKS"));

        // Default novcane noge — racuni + commit/credit ok (rollback test prekriva commit).
        lenient().when(bankaCoreClient.getAccount(BUYER_ACCOUNT_ID))
                .thenReturn(account(BUYER_ACCOUNT_ID, "111", "Buyer", "USD", BUYER_ID));
        lenient().when(bankaCoreClient.getPreferredAccount(UserRole.CLIENT, SELLER_ID, "USD"))
                .thenReturn(account(SELLER_ACCOUNT_ID, "222", "Seller", "USD", SELLER_ID));
        lenient().when(bankaCoreClient.commitFunds(anyString(), anyString(), any(CommitFundsRequest.class)))
                .thenReturn(new CommitFundsResponse("RES-1010", new BigDecimal("1600.0000"),
                        BigDecimal.ZERO, BigDecimal.ZERO));
        lenient().when(bankaCoreClient.creditFunds(anyString(), any(CreditFundsRequest.class)))
                .thenReturn(new CreditFundsResponse(SELLER_ACCOUNT_ID, new BigDecimal("1600.0000"),
                        new BigDecimal("101600.0000")));
    }

    @AfterEach
    void tearDown() {
        sagaLogRepository.deleteAll();
        contractRepository.deleteAll();
        offerRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
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

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private InternalAccountDto account(long id, String number, String owner, String ccy, Long ownerClientId) {
        return new InternalAccountDto(id, number, owner,
                new BigDecimal("100000.00"), new BigDecimal("100000.00"), BigDecimal.ZERO,
                ccy, "ACTIVE", ownerClientId, null, "CHECKING");
    }

    private Listing savedStockListing() {
        Listing l = new Listing();
        l.setTicker("AAPL");
        l.setName("Apple Inc.");
        l.setListingType(ListingType.STOCK);
        l.setQuoteCurrency("USD");
        l.setExchangeAcronym("NASDAQ");
        l.setPrice(BigDecimal.valueOf(150));
        l.setAsk(BigDecimal.valueOf(151));
        l.setBid(BigDecimal.valueOf(149));
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    /**
     * ACTIVE ugovor sa vec postavljenom rezervacijom (accept-time) tako da F1
     * REUSE-uje postojeci {@code bankaCoreReservationId} umesto {@code reserveFunds} poziva.
     */
    private OtcContract savedReservedContract(Listing listing) {
        OtcContract c = new OtcContract();
        c.setSourceOfferId(1L);
        c.setBuyerId(BUYER_ID);
        c.setBuyerRole(UserRole.CLIENT);
        c.setSellerId(SELLER_ID);
        c.setSellerRole(UserRole.CLIENT);
        c.setListing(listing);
        c.setQuantity(10);
        c.setStrikePrice(new BigDecimal("160.0000"));
        c.setPremium(new BigDecimal("50.0000"));
        c.setSettlementDate(LocalDate.now().plusDays(30));
        c.setStatus(OtcContractStatus.ACTIVE);
        c.setBuyerReservedAccountId(BUYER_ACCOUNT_ID);
        c.setBuyerReservedAmount(new BigDecimal("1600.0000"));
        c.setBankaCoreReservationId("RES-1010");
        c.setCreatedAt(LocalDateTime.now());
        return contractRepository.save(c);
    }

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

    @Test
    @DisplayName("Happy path: POST exercise -> 200 COMPLETED/EXERCISED, GET saga -> 5 zapisa, "
            + "ugovor EXERCISED u H2, saga_logs red postoji")
    void exercise_happyPath_completed() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/contracts/" + contract.getId() + "/exercise?buyerAccountId=" + BUYER_ACCOUNT_ID),
                HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(buildToken("buyer@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"sagaStatus\":\"COMPLETED\"");
        assertThat(body).contains("\"currentStep\":5");
        assertThat(body).contains("\"status\":\"EXERCISED\"");

        // izvuci sagaId iz odgovora za poll
        String sagaId = extractSagaId(body);
        assertThat(sagaId).isNotBlank();

        // GET /otc/saga/{id} -> 200, 5 log zapisa
        ResponseEntity<String> sagaResp = restTemplate.exchange(
                url("/otc/saga/" + sagaId),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(buildToken("buyer@test.com", "CLIENT"))),
                String.class);
        assertThat(sagaResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sagaBody = sagaResp.getBody();
        assertThat(sagaBody).isNotNull();
        assertThat(sagaBody).contains("\"status\":\"COMPLETED\"");
        // 5 forward "ok" zapisa (phase 1..5)
        assertThat(countOccurrences(sagaBody, "\"outcome\":\"ok\"")).isEqualTo(5);

        // H2 stanje: ugovor EXERCISED + saga_logs red
        OtcContract reloaded = contractRepository.findById(contract.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OtcContractStatus.EXERCISED);
        assertThat(sagaLogRepository.findBySagaId(sagaId)).isPresent();
        assertThat(sagaLogRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Pre-saga 403: ne-kupac POST exercise -> 403 i NEMA saga_logs reda")
    void exercise_nonBuyer_forbidden_noLog() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);

        // resolver vraca razlicit identitet (nije kupac BUYER_ID)
        when(tradingUserResolver.resolveCurrent())
                .thenReturn(new UserContext(99999L, UserRole.CLIENT));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/contracts/" + contract.getId() + "/exercise?buyerAccountId=" + BUYER_ACCOUNT_ID),
                HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(buildToken("intruder@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // nema bocnih efekata: nijedan saga_logs red nije kreiran
        assertThat(sagaLogRepository.count()).isEqualTo(0);
        // ugovor i dalje ACTIVE
        OtcContract reloaded = contractRepository.findById(contract.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);
    }

    @Test
    @DisplayName("P2-7 access gate: CLIENT kupac BEZ TRADE_STOCKS -> 403 i NEMA saga_logs reda")
    void exercise_clientWithoutTradeStocks_forbidden_noLog() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);

        // Klijent JESTE kupac (BUYER_ID), ali nema TRADE_STOCKS permisiju → access gate 403.
        // Jedinstven email da Caffeine kes TradingPermissionResolver-a ne procuri TRADE_STOCKS
        // iz default stub-a drugih testova.
        String noTradeEmail = "notrade-buyer@test.com";
        when(bankaCoreClient.getUserPermissions(noTradeEmail)).thenReturn(java.util.List.of());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/contracts/" + contract.getId() + "/exercise?buyerAccountId=" + BUYER_ACCOUNT_ID),
                HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(buildToken(noTradeEmail, "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // access gate je PRE kreiranja loga: nijedan saga_logs red nije kreiran
        assertThat(sagaLogRepository.count()).isEqualTo(0);
        // ugovor i dalje ACTIVE (nikakvi bocni efekti)
        OtcContract reloaded = contractRepository.findById(contract.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);
    }

    @Test
    @DisplayName("Rollback smoke: commitFunds baca -> 200 COMPENSATED, ugovor ostaje ACTIVE")
    void exercise_commitFails_compensated() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);

        // F3 commitFunds baca banka-core 500 -> SAGA pada u F3, kompenzuje C2/C1 -> COMPENSATED
        when(bankaCoreClient.commitFunds(anyString(), anyString(), any(CommitFundsRequest.class)))
                .thenThrow(new BankaCoreClientException(500, "banka-core commit nedostupan"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/contracts/" + contract.getId() + "/exercise?buyerAccountId=" + BUYER_ACCOUNT_ID),
                HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(buildToken("buyer@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"sagaStatus\":\"COMPENSATED\"");
        assertThat(body).contains("\"status\":\"ACTIVE\"");

        // ugovor ostaje ACTIVE (F5 nikad dostignut)
        OtcContract reloaded = contractRepository.findById(contract.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OtcContractStatus.ACTIVE);

        // saga_logs red postoji sa COMPENSATED statusom
        String sagaId = extractSagaId(body);
        assertThat(sagaLogRepository.findBySagaId(sagaId))
                .get()
                .extracting(s -> s.getStatus())
                .isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("P1-authz-idor-1 (R1 217): ne-ucesnik GET /otc/saga/{id} -> 403; ucesnik -> 200")
    void getSagaStatus_nonParticipant_forbidden() {
        Listing listing = savedStockListing();
        OtcContract contract = savedReservedContract(listing);
        savedSellerPortfolio(listing);

        // 1) Kupac iskoristi ugovor — kreira saga_logs red.
        ResponseEntity<String> exResp = restTemplate.exchange(
                url("/otc/contracts/" + contract.getId() + "/exercise?buyerAccountId=" + BUYER_ACCOUNT_ID),
                HttpMethod.POST,
                new HttpEntity<>(bearerHeaders(buildToken("buyer@test.com", "CLIENT"))),
                String.class);
        assertThat(exResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sagaId = extractSagaId(exResp.getBody());
        assertThat(sagaId).isNotBlank();

        // 2) Ne-ucesnik (drugi klijent, nije buyer ni seller) poll-uje saga -> 403.
        when(tradingUserResolver.resolveCurrent())
                .thenReturn(new UserContext(99999L, UserRole.CLIENT));
        ResponseEntity<String> intruderResp = restTemplate.exchange(
                url("/otc/saga/" + sagaId),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(buildToken("intruder@test.com", "CLIENT"))),
                String.class);
        assertThat(intruderResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 3) Seller (ucesnik) poll-uje saga -> 200.
        when(tradingUserResolver.resolveCurrent())
                .thenReturn(new UserContext(SELLER_ID, UserRole.CLIENT));
        ResponseEntity<String> sellerResp = restTemplate.exchange(
                url("/otc/saga/" + sagaId),
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(buildToken("seller@test.com", "CLIENT"))),
                String.class);
        assertThat(sellerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
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
}
