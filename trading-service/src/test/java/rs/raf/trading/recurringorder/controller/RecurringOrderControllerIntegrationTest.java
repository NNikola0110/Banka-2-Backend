package rs.raf.trading.recurringorder.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.recurringorder.model.RecurringCadence;
import rs.raf.trading.recurringorder.model.RecurringMode;
import rs.raf.trading.recurringorder.model.RecurringOrder;
import rs.raf.trading.recurringorder.repository.RecurringOrderRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * HTTP integracioni test {@link RecurringOrderController} — pun Spring kontekst (H2
 * test profil), RANDOM_PORT, realan {@code TradingSecurityConfig} filter chain +
 * realan {@link rs.raf.trading.recurringorder.service.RecurringOrderService} + JPA.
 *
 * <p>Obrazac kao {@code MarginAccountControllerIntegrationTest}: JWT mintovan lokalno
 * (HS256), {@link BankaCoreClient} {@code @MockitoBean} razresava identitet
 * ({@code getUserByEmail}) i racun ({@code getAccount}). {@code Listing}/{@code RecurringOrder}
 * su lokalni entiteti pa se seeduju direktno. Ruta {@code /recurring-orders/**} je
 * {@code authenticated()} — vlasnistvo proverava service (AccessDenied -> 403, not-found
 * -> 400).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RecurringOrderControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long CLIENT_ID = 9201L;
    private static final Long OTHER_CLIENT_ID = 9202L;
    private static final Long ACCOUNT_ID = 9290L;

    @Value("${local.server.port}")
    private int port;

    @Autowired private RecurringOrderRepository recurringOrderRepository;
    @Autowired private ListingRepository listingRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
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
        recurringOrderRepository.deleteAll();
        listingRepository.deleteAll();

        lenient().when(bankaCoreClient.getUserByEmail("client.ro@test.com")).thenReturn(
                new InternalUserDto(CLIENT_ID, "CLIENT", "client.ro@test.com", "Client", "User", true, null));
        lenient().when(bankaCoreClient.getUserByEmail("other.ro@test.com")).thenReturn(
                new InternalUserDto(OTHER_CLIENT_ID, "CLIENT", "other.ro@test.com", "Other", "User", true, null));
        // R1-242: TradingAccessGuard zahteva TRADE_STOCKS za klijenta pri kreiranju
        // trajnog naloga. JWT filter razresava permisije preko getUserPermissions.
        lenient().when(bankaCoreClient.getUserPermissions("client.ro@test.com"))
                .thenReturn(java.util.List.of("TRADE_STOCKS"));
        lenient().when(bankaCoreClient.getUserPermissions("other.ro@test.com"))
                .thenReturn(java.util.List.of("TRADE_STOCKS"));
        // Racun pripada klijentu CLIENT_ID.
        lenient().when(bankaCoreClient.getAccount(ACCOUNT_ID)).thenReturn(
                clientAccount(ACCOUNT_ID, CLIENT_ID));
    }

    // ── POST /recurring-orders ───────────────────────────────────────────────

    @Test
    @DisplayName("POST /recurring-orders — 201 + perzistira DCA nalog")
    void create_ok_returnsCreated() throws Exception {
        Listing listing = savedListing();

        String payload = """
                {
                  "listingId": %d,
                  "direction": "BUY",
                  "mode": "BY_AMOUNT",
                  "value": 1000.00,
                  "accountId": %d,
                  "cadence": "MONTHLY"
                }
                """.formatted(listing.getId(), ACCOUNT_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/recurring-orders"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("client.ro@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("ownerId").asLong()).isEqualTo(CLIENT_ID);
        assertThat(body.path("direction").asText()).isEqualTo("BUY");
        assertThat(body.path("mode").asText()).isEqualTo("BY_AMOUNT");
        assertThat(body.path("cadence").asText()).isEqualTo("MONTHLY");
        assertThat(body.path("active").asBoolean()).isTrue();
        assertThat(recurringOrderRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("POST /recurring-orders — 400 za nevazeci direction (validacija)")
    void create_invalidDirection_returnsBadRequest() {
        String payload = """
                {
                  "listingId": 1,
                  "direction": "HOLD",
                  "mode": "BY_AMOUNT",
                  "value": 1000.00,
                  "accountId": %d,
                  "cadence": "MONTHLY"
                }
                """.formatted(ACCOUNT_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/recurring-orders"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("client.ro@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Smer mora biti BUY ili SELL");
        assertThat(recurringOrderRepository.count()).isZero();
    }

    @Test
    @DisplayName("R1 819: POST /recurring-orders — 400 + prijateljska poruka za nevalidnu mode enum vrednost")
    void create_invalidModeEnum_returnsFriendlyMessage_R1_819() {
        String payload = """
                {
                  "listingId": 1,
                  "direction": "BUY",
                  "mode": "NESTO_NEPOSTOJECE",
                  "value": 1000.00,
                  "accountId": %d,
                  "cadence": "MONTHLY"
                }
                """.formatted(ACCOUNT_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/recurring-orders"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("client.ro@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // R1 819: globalni handler detektuje enum-parse gresku i vraca polje + dozvoljene vrednosti.
        assertThat(response.getBody()).contains("mode");
        assertThat(response.getBody()).contains("BY_AMOUNT");
        assertThat(response.getBody()).contains("Dozvoljene vrednosti");
        assertThat(recurringOrderRepository.count()).isZero();
    }

    @Test
    @DisplayName("POST /recurring-orders — 403 kad racun ne pripada klijentu")
    void create_accountNotOwned_returnsForbidden() {
        // Racun pripada drugom klijentu -> AccessDeniedException -> 403.
        lenient().when(bankaCoreClient.getAccount(ACCOUNT_ID)).thenReturn(
                clientAccount(ACCOUNT_ID, OTHER_CLIENT_ID));
        Listing listing = savedListing();

        String payload = """
                {
                  "listingId": %d,
                  "direction": "BUY",
                  "mode": "BY_AMOUNT",
                  "value": 1000.00,
                  "accountId": %d,
                  "cadence": "WEEKLY"
                }
                """.formatted(listing.getId(), ACCOUNT_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/recurring-orders"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("client.ro@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(recurringOrderRepository.count()).isZero();
    }

    @Test
    @DisplayName("POST /recurring-orders — 403 kad klijent nema TRADE_STOCKS (R1-242)")
    void create_clientWithoutTradeStocks_returnsForbidden() {
        // R1-242: klijent bez TRADE_STOCKS ne sme da kreira trajni nalog (fail-fast).
        lenient().when(bankaCoreClient.getUserByEmail("notrade.ro@test.com")).thenReturn(
                new InternalUserDto(9203L, "CLIENT", "notrade.ro@test.com", "No", "Trade", true, null));
        lenient().when(bankaCoreClient.getUserPermissions("notrade.ro@test.com"))
                .thenReturn(java.util.List.of()); // bez TRADE_STOCKS
        Listing listing = savedListing();

        String payload = """
                {
                  "listingId": %d,
                  "direction": "BUY",
                  "mode": "BY_AMOUNT",
                  "value": 1000.00,
                  "accountId": %d,
                  "cadence": "MONTHLY"
                }
                """.formatted(listing.getId(), ACCOUNT_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/recurring-orders"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("notrade.ro@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(recurringOrderRepository.count()).isZero();
    }

    @Test
    @DisplayName("POST /recurring-orders — 403 bez JWT-a")
    void create_missingJwt_returnsForbidden() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/recurring-orders"),
                new HttpEntity<>("{}", jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── GET /recurring-orders ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /recurring-orders — vraca samo naloge ulogovanog klijenta")
    void listMy_returnsOnlyOwn() throws Exception {
        Listing listing = savedListing();
        savedRecurringOrder(CLIENT_ID, "CLIENT", listing.getId());
        savedRecurringOrder(OTHER_CLIENT_ID, "CLIENT", listing.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/recurring-orders"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.ro@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).path("ownerId").asLong()).isEqualTo(CLIENT_ID);
    }

    // ── PATCH pause / resume ─────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /recurring-orders/{id}/pause — 200 deaktivira vlasnikov nalog")
    void pause_ok() throws Exception {
        Listing listing = savedListing();
        RecurringOrder ro = savedRecurringOrder(CLIENT_ID, "CLIENT", listing.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/recurring-orders/" + ro.getId() + "/pause"),
                HttpMethod.PATCH,
                new HttpEntity<>(jsonHeaders(buildToken("client.ro@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("active").asBoolean()).isFalse();
        assertThat(recurringOrderRepository.findById(ro.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    @DisplayName("PATCH /recurring-orders/{id}/pause — 403 kad nalog nije vlasnikov")
    void pause_notOwner_returnsForbidden() {
        Listing listing = savedListing();
        RecurringOrder ro = savedRecurringOrder(OTHER_CLIENT_ID, "CLIENT", listing.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/recurring-orders/" + ro.getId() + "/pause"),
                HttpMethod.PATCH,
                new HttpEntity<>(jsonHeaders(buildToken("client.ro@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(recurringOrderRepository.findById(ro.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("PATCH /recurring-orders/{id}/pause — 400 kad nalog ne postoji")
    void pause_notFound_returnsBadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/recurring-orders/99999/pause"),
                HttpMethod.PATCH,
                new HttpEntity<>(jsonHeaders(buildToken("client.ro@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Trajni nalog ne postoji");
    }

    // ── DELETE /recurring-orders/{id} ────────────────────────────────────────

    @Test
    @DisplayName("DELETE /recurring-orders/{id} — 204 brise vlasnikov nalog")
    void cancel_ok_returnsNoContent() {
        Listing listing = savedListing();
        RecurringOrder ro = savedRecurringOrder(CLIENT_ID, "CLIENT", listing.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/recurring-orders/" + ro.getId()),
                HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders(buildToken("client.ro@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(recurringOrderRepository.existsById(ro.getId())).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private InternalAccountDto clientAccount(Long accountId, Long ownerClientId) {
        return new InternalAccountDto(accountId, "RS00" + accountId, "Client User",
                new BigDecimal("100000.00"), new BigDecimal("100000.00"),
                BigDecimal.ZERO, "RSD", "ACTIVE", ownerClientId, null, "CHECKING");
    }

    private Listing savedListing() {
        Listing l = new Listing();
        l.setTicker("AAPL");
        l.setName("Apple Inc.");
        l.setListingType(ListingType.STOCK);
        l.setPrice(BigDecimal.valueOf(150));
        l.setAsk(BigDecimal.valueOf(151));
        l.setBid(BigDecimal.valueOf(149));
        l.setExchangeAcronym("NASDAQ");
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    private RecurringOrder savedRecurringOrder(Long ownerId, String ownerType, Long listingId) {
        return recurringOrderRepository.save(RecurringOrder.builder()
                .ownerId(ownerId)
                .ownerType(ownerType)
                .listingId(listingId)
                .direction("BUY")
                .mode(RecurringMode.BY_AMOUNT)
                .value(new BigDecimal("1000.00"))
                .accountId(ACCOUNT_ID)
                .cadence(RecurringCadence.MONTHLY)
                .nextRun(LocalDateTime.now().plusDays(1))
                .active(true)
                .build());
    }

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
}
