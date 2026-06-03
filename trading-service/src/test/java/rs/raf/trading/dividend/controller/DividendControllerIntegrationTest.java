package rs.raf.trading.dividend.controller;

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
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.dividend.model.DividendPayout;
import rs.raf.trading.dividend.repository.DividendPayoutRepository;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * HTTP integracioni test {@link DividendController} (korisnicki, {@code /dividends/**}
 * = authenticated) i {@link DividendAdminController} ({@code /admin/dividends} = ADMIN/
 * SUPERVISOR). Pun Spring kontekst (H2 test profil), RANDOM_PORT, realan
 * {@code TradingSecurityConfig} filter chain + realan {@link rs.raf.trading.dividend.service.DividendService}
 * + JPA.
 *
 * <p>JWT mintovan lokalno (HS256). {@link BankaCoreClient} {@code @MockitoBean}:
 * {@code getUserByEmail} razresava CLIENT identitet (za {@code /dividends/my}),
 * {@code getUserPermissions} daje SUPERVISOR autoritet zaposlenom (za admin pregled).
 * ADMIN ima {@code ROLE_ADMIN} iz JWT role claim-a. {@code DividendPayout} se seeduje
 * direktno (lokalni entitet).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DividendControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long CLIENT_ID = 9301L;

    @Value("${local.server.port}")
    private int port;

    @Autowired private DividendPayoutRepository dividendPayoutRepository;
    @Autowired private PortfolioRepository portfolioRepository;

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
        dividendPayoutRepository.deleteAll();
        portfolioRepository.deleteAll();

        lenient().when(bankaCoreClient.getUserByEmail("client.dv@test.com")).thenReturn(
                new InternalUserDto(CLIENT_ID, "CLIENT", "client.dv@test.com", "Client", "User", true, null));
        // Supervizor: JWT role=EMPLOYEE + SUPERVISOR permisija -> SUPERVISOR autoritet.
        lenient().when(bankaCoreClient.getUserPermissions("sup.dv@test.com"))
                .thenReturn(List.of("SUPERVISOR"));
        lenient().when(bankaCoreClient.getUserPermissions("agent.dv@test.com"))
                .thenReturn(List.of("AGENT"));
    }

    // ── GET /dividends/my (korisnicki) ───────────────────────────────────────

    @Test
    @DisplayName("GET /dividends/my — 200 vraca dividende ulogovanog klijenta")
    void getMyDividends_ok() throws Exception {
        dividendPayoutRepository.save(payout(CLIENT_ID, "CLIENT", "AAPL"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/dividends/my"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.dv@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).path("ownerId").asLong()).isEqualTo(CLIENT_ID);
        assertThat(body.get(0).path("stockTicker").asText()).isEqualTo("AAPL");
    }

    @Test
    @DisplayName("GET /dividends/my — 403 bez JWT-a")
    void getMyDividends_missingJwt_returnsForbidden() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/dividends/my"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── GET /dividends/by-position/{portfolioId} (OT-1149) ───────────────────

    @Test
    @DisplayName("GET /dividends/by-position/{id} — 200 vlasniku, vraca dividende pozicije")
    void getByPosition_ownerOk_returnsPositionDividends() throws Exception {
        Portfolio position = portfolioRepository.save(position(CLIENT_ID, "CLIENT", 5001L));
        dividendPayoutRepository.save(payout(CLIENT_ID, "CLIENT", "AAPL"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/dividends/by-position/" + position.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.dv@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).path("ownerId").asLong()).isEqualTo(CLIENT_ID);
        assertThat(body.get(0).path("stockListingId").asLong()).isEqualTo(5001L);
    }

    @Test
    @DisplayName("GET /dividends/by-position/{id} — 403 kad pozicija pripada drugom klijentu")
    void getByPosition_notOwner_returnsForbidden() {
        // Pozicija pripada DRUGOM klijentu (9999), a JWT je za CLIENT_ID -> 403.
        Portfolio othersPosition = portfolioRepository.save(position(9999L, "CLIENT", 5001L));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/dividends/by-position/" + othersPosition.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.dv@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /dividends/by-position/{id} — 400 kad pozicija ne postoji")
    void getByPosition_missingPosition_returnsBadRequest() {
        // IllegalArgumentException (pozicija nije pronadjena) -> 400 preko global handler-a.
        ResponseEntity<String> response = restTemplate.exchange(
                url("/dividends/by-position/8888777"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.dv@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /dividends/by-position/{id} — 403 bez JWT-a")
    void getByPosition_missingJwt_returnsForbidden() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/dividends/by-position/1"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── GET /admin/dividends (admin/supervizor pregled) ──────────────────────

    @Test
    @DisplayName("GET /admin/dividends — 200 za ADMIN, paginiran")
    void adminDividends_okForAdmin() throws Exception {
        dividendPayoutRepository.save(payout(CLIENT_ID, "CLIENT", "AAPL"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/dividends"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(response.getBody()).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).path("stockTicker").asText()).isEqualTo("AAPL");
    }

    @Test
    @DisplayName("GET /admin/dividends — 200 za SUPERVISOR")
    void adminDividends_okForSupervisor() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/dividends"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("sup.dv@test.com", "EMPLOYEE"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /admin/dividends — 403 za CLIENT")
    void adminDividends_forbiddenForClient() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/dividends"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.dv@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /admin/dividends — 403 za obican EMPLOYEE/AGENT (bez SUPERVISOR)")
    void adminDividends_forbiddenForAgent() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/dividends"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("agent.dv@test.com", "EMPLOYEE"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /admin/dividends?from=bad — 400 za nevalidan datum parametar")
    void adminDividends_invalidFromDate_returnsBadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/dividends?from=not-a-date"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /admin/dividends — 403 bez JWT-a")
    void adminDividends_missingJwt_returnsForbidden() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/dividends"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DividendPayout payout(Long ownerId, String ownerType, String ticker) {
        return DividendPayout.builder()
                .ownerId(ownerId)
                .ownerType(ownerType)
                .stockListingId(5001L)
                .stockTicker(ticker)
                .quantity(10)
                .priceOnDate(new BigDecimal("150.0000"))
                .dividendYieldRate(new BigDecimal("0.005000"))
                .grossAmount(new BigDecimal("7.5000"))
                .tax(new BigDecimal("1.1250"))
                .netAmount(new BigDecimal("6.3750"))
                .creditedAccountId(9390L)
                .currencyCode("USD")
                .paymentDate(LocalDate.now())
                .taxExempt(false)
                .build();
    }

    private Portfolio position(Long ownerId, String ownerRole, Long listingId) {
        return Portfolio.builder()
                .userId(ownerId)
                .userRole(ownerRole)
                .listingId(listingId)
                .listingTicker("AAPL")
                .listingName("Apple Inc.")
                .listingType("STOCK")
                .quantity(10)
                .averageBuyPrice(new BigDecimal("150.0000"))
                .publicQuantity(0)
                .reservedQuantity(0)
                .build();
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
