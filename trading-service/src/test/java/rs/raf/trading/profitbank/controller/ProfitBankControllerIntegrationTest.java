package rs.raf.trading.profitbank.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.investmentfund.model.ClientFundPosition;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.ClientFundPositionRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * HTTP integracioni test {@link ProfitBankController} — pun Spring kontekst (H2
 * test profil), RANDOM_PORT, realan security filter chain (JWT validacija) +
 * realan {@code ActuaryProfitService}/{@code InvestmentFundService} + JPA.
 *
 * <p>NAPOMENA (copy-first ekstrakcija, faza 2d-E): {@code profitbank} paket u
 * monolitu nije imao testove — ovo je nov test po obrascu 2d
 * {@code OptionControllerIntegrationTest}. trading-service NEMA login endpoint
 * ni {@code Employee} tabelu — JWT se generise lokalno deljenim test secret-om
 * (HS256, isto kao banka-core), {@link BankaCoreClient} je {@code @MockitoBean}
 * (razresava aktuara po roli+id-u i daje permisije; banka-klijent lookup baca
 * pa {@code listBankPositions} graceful vraca praznu listu).</p>
 *
 * <p>Authorization: {@code ProfitBankController} ima class-level
 * {@code @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ADMIN','SUPERVISOR')")}.
 * trading-service JWT filter dodeljuje samo {@code ROLE_<role>} authority —
 * ADMIN token prolazi ({@code ROLE_ADMIN}), EMPLOYEE/CLIENT dobijaju 403.
 * Isti obrazac kao kod {@code InvestmentFundController}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProfitBankControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long AGENT_ID = 6201L;
    private static final Long SUPERVISOR_ID = 6202L;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private InvestmentFundRepository investmentFundRepository;

    @Autowired
    private ClientFundPositionRepository clientFundPositionRepository;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

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
    void setUp() {
        orderRepository.deleteAll();
        listingRepository.deleteAll();
        clientFundPositionRepository.deleteAll();
        investmentFundRepository.deleteAll();
        // actuary-profit je @Cacheable; @SpringBootTest deli kontekst (i cache)
        // izmedju test metoda — ocisti da svaki test krene od svezeg izracuna.
        var cache = cacheManager.getCache("actuary-profit");
        if (cache != null) {
            cache.clear();
        }

        // banka-core razresava aktuare po roli+id-u.
        lenient().when(bankaCoreClient.getUserById("EMPLOYEE", AGENT_ID)).thenReturn(
                new InternalUserDto(AGENT_ID, "EMPLOYEE", "agent@test.com",
                        "Agent", "Test", true, "Agent"));
        lenient().when(bankaCoreClient.getUserById("EMPLOYEE", SUPERVISOR_ID)).thenReturn(
                new InternalUserDto(SUPERVISOR_ID, "EMPLOYEE", "sup@test.com",
                        "Super", "Visor", true, "Direktor"));
        lenient().when(bankaCoreClient.getUserPermissions("agent@test.com"))
                .thenReturn(List.of("AGENT"));
        lenient().when(bankaCoreClient.getUserPermissions("sup@test.com"))
                .thenReturn(List.of("SUPERVISOR"));
        // Banka-klijent nije seed-ovan -> listBankPositions graceful prazna lista.
        lenient().when(bankaCoreClient.getUserByEmail(anyString()))
                .thenThrow(new BankaCoreClientException(404, "bank owner client not seeded"));
    }

    // ── GET /profit-bank/actuary-performance ─────────────────────────────────

    @Test
    @DisplayName("GET /profit-bank/actuary-performance — 200 za ADMIN, vraca agregirani profit")
    void actuaryPerformance_okForAdmin_returnsAggregatedProfit() {
        Listing belex = createListing("ATB", new BigDecimal("100.00"), "BELEX"); // RSD
        // Agent: BUY 10 @ 100 = 1000; SELL 10 @ 150 = 1500; profit = 500 RSD
        createDoneOrder(AGENT_ID, "EMPLOYEE", belex, OrderDirection.BUY, 10, "100.00");
        createDoneOrder(AGENT_ID, "EMPLOYEE", belex, OrderDirection.SELL, 10, "150.00");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/profit-bank/actuary-performance"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"employeeId\":" + AGENT_ID);
        assertThat(response.getBody()).contains("\"name\":\"Agent Test\"");
        assertThat(response.getBody()).contains("\"position\":\"AGENT\"");
        assertThat(response.getBody()).contains("500.00");
    }

    @Test
    @DisplayName("GET /profit-bank/actuary-performance — 200 sa praznom listom kad nema ordera")
    void actuaryPerformance_okForAdmin_emptyWhenNoOrders() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/profit-bank/actuary-performance"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    @DisplayName("GET /profit-bank/actuary-performance — 403 za EMPLOYEE (nije ADMIN)")
    void actuaryPerformance_forbiddenForEmployee() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/profit-bank/actuary-performance"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /profit-bank/actuary-performance — 403 za CLIENT")
    void actuaryPerformance_forbiddenForClient() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/profit-bank/actuary-performance"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client@test.com", "CLIENT"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /profit-bank/actuary-performance — 403 bez JWT-a")
    void actuaryPerformance_forbiddenWhenMissingJwt() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/profit-bank/actuary-performance"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /profit-bank/actuary-performance — 401 za nevazeci token")
    void actuaryPerformance_unauthorizedForInvalidToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("not-a-real-jwt");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/profit-bank/actuary-performance"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── GET /profit-bank/fund-positions ──────────────────────────────────────

    @Test
    @DisplayName("GET /profit-bank/fund-positions — 200 za ADMIN (prazna lista kad banka nema klijenta)")
    void fundPositions_okForAdmin() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/profit-bank/fund-positions"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    @DisplayName("GET /profit-bank/fund-positions — 200 NON-empty kad banka ima poziciju u fondu (OT-1161)")
    void fundPositions_nonEmptyForAdmin_whenBankHasPosition() {
        // OT-1161 (TEST-tr-funds-dividends-profitbank-1): integracioni test je do sad
        // pokrivao SAMO praznu listu (banka-klijent ne seed-ovan). Ovde seed-ujemo
        // banka-klijenta (getUserByEmail za bank owner email) + InvestmentFund +
        // ClientFundPosition (userRole=CLIENT, userId=bankClientId) i verifikujemo
        // da /fund-positions vrati tu poziciju (non-empty grana listBankPositions).
        Long bankClientId = 7777L;
        // setUp() je stub-ovao getUserByEmail(anyString()) da BACI 404 (graceful prazna
        // lista za default-ne testove). Ovde resetujemo mock da bismo cisto re-stub-ovali
        // bankin owner email da VRATI klijenta (non-empty grana). Reset je bezbedan:
        // actuary-performance stubovi nisu potrebni za /fund-positions.
        reset(bankaCoreClient);
        // bankin owner email default = banka2.doo@banka.rs (bez property override-a)
        when(bankaCoreClient.getUserByEmail("banka2.doo@banka.rs")).thenReturn(
                new InternalUserDto(bankClientId, "CLIENT", "banka2.doo@banka.rs",
                        "Banka 2", "d.o.o.", true, null));

        InvestmentFund fund = new InvestmentFund();
        fund.setName("Banka 2 Stable Income");
        fund.setDescription("Test fond");
        fund.setMinimumContribution(new BigDecimal("1000.0000"));
        fund.setManagerEmployeeId(SUPERVISOR_ID);
        fund.setAccountId(99001L);
        fund.setCreatedAt(LocalDateTime.now());
        fund.setActive(true);
        fund.setReinvestDividends(false);
        InvestmentFund savedFund = investmentFundRepository.save(fund);

        ClientFundPosition position = new ClientFundPosition();
        position.setFundId(savedFund.getId());
        position.setUserId(bankClientId);
        position.setUserRole("CLIENT");
        position.setTotalInvested(new BigDecimal("250000.0000"));
        position.setLastModifiedAt(LocalDateTime.now());
        clientFundPositionRepository.save(position);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/profit-bank/fund-positions"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEqualTo("[]");
        assertThat(response.getBody()).contains("\"fundName\":\"Banka 2 Stable Income\"");
        assertThat(response.getBody()).contains("\"userId\":" + bankClientId);
        assertThat(response.getBody()).contains("250000");
    }

    @Test
    @DisplayName("GET /profit-bank/fund-positions — 403 za EMPLOYEE")
    void fundPositions_forbiddenForEmployee() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/profit-bank/fund-positions"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String buildToken(String email, String role) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + 3_600_000);
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .claim("active", true)
                .issuedAt(now)
                .expiration(exp)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private Listing createListing(String ticker, BigDecimal price, String exchange) {
        Listing listing = new Listing();
        listing.setTicker(ticker);
        listing.setName(ticker + " Inc.");
        listing.setExchangeAcronym(exchange);
        listing.setListingType(ListingType.STOCK);
        listing.setPrice(price);
        listing.setAsk(price.add(new BigDecimal("1.00")));
        listing.setBid(price.subtract(new BigDecimal("1.00")));
        listing.setVolume(1000L);
        listing.setPriceChange(new BigDecimal("0.50"));
        listing.setLastRefresh(LocalDateTime.now());
        listing.setOutstandingShares(1_000_000L);
        listing.setDividendYield(new BigDecimal("0.0100"));
        listing.setContractSize(1);
        return listingRepository.save(listing);
    }

    private void createDoneOrder(Long actuarId, String userRole, Listing listing,
                                 OrderDirection direction, int qty, String pricePerUnit) {
        Order order = new Order();
        order.setUserId(actuarId);
        order.setUserRole(userRole);
        order.setListing(listing);
        order.setOrderType(OrderType.MARKET);
        order.setQuantity(qty);
        order.setContractSize(1);
        order.setPricePerUnit(new BigDecimal(pricePerUnit));
        order.setDirection(direction);
        order.setStatus(OrderStatus.DONE);
        order.setDone(true);
        order.setAfterHours(false);
        order.setAllOrNone(false);
        order.setMargin(false);
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);
    }
}
