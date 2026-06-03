package rs.raf.trading.berza.controller;

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
import rs.raf.trading.berza.model.Exchange;
import rs.raf.trading.berza.repository.ExchangeRepository;
import rs.raf.trading.client.BankaCoreClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP integracioni test {@link ExchangeManagementController} — pun Spring kontekst
 * (H2 test profil), RANDOM_PORT, realan {@code TradingSecurityConfig} filter chain +
 * realan {@link rs.raf.trading.berza.service.ExchangeManagementService} + JPA.
 *
 * <p>Security: {@code GET /exchanges}/{@code /exchanges/**} su {@code permitAll()};
 * {@code PATCH /exchanges/{acronym}/test-mode} trazi {@code hasAnyRole('ADMIN','EMPLOYEE')}
 * (rutni guard + {@code @PreAuthorize}). {@link BankaCoreClient} je {@code @MockitoBean}
 * (kontekst-bean stub, ovde se ne poziva — Exchange je lokalni entitet). Berza se seeduje
 * direktno preko repozitorijuma.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExchangeManagementControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    @Value("${local.server.port}")
    private int port;

    @Autowired private ExchangeRepository exchangeRepository;

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
        exchangeRepository.deleteAll();
    }

    // ── GET /exchanges (permitAll) ───────────────────────────────────────────

    @Test
    @DisplayName("GET /exchanges — 200 bez tokena (permitAll), vraca aktivne berze")
    void getAllExchanges_okWithoutToken() throws Exception {
        savedExchange("NYSE", false);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).path("acronym").asText()).isEqualTo("NYSE");
    }

    @Test
    @DisplayName("GET /exchanges/{acronym} — 200 detalji jedne berze")
    void getByAcronym_ok() throws Exception {
        savedExchange("BELEX", true);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/BELEX"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("acronym").asText()).isEqualTo("BELEX");
        assertThat(body.path("testMode").asBoolean()).isTrue();
    }

    // ── PATCH /exchanges/{acronym}/test-mode (ADMIN/EMPLOYEE) ────────────────

    @Test
    @DisplayName("PATCH /exchanges/{acronym}/test-mode — 200 za ADMIN, perzistira toggle")
    void setTestMode_okForAdmin() throws Exception {
        savedExchange("NYSE", false);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/NYSE/test-mode"),
                HttpMethod.PATCH,
                new HttpEntity<>("{\"enabled\":true}", jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Test mode set to true for NYSE");
        assertThat(exchangeRepository.findByAcronym("NYSE").orElseThrow().isTestMode()).isTrue();
    }

    @Test
    @DisplayName("PATCH /exchanges/{acronym}/test-mode — 200 za SUPERVISOR")
    void setTestMode_okForSupervisor() {
        savedExchange("NASDAQ", false);
        // role=EMPLOYEE + SUPERVISOR authority (resolver vraca SUPERVISOR) — dozvoljeno.
        org.mockito.Mockito.when(bankaCoreClient.getUserPermissions(
                        org.mockito.ArgumentMatchers.eq("super@test.com")))
                .thenReturn(java.util.List.of("SUPERVISOR"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/NASDAQ/test-mode"),
                HttpMethod.PATCH,
                new HttpEntity<>("{\"enabled\":true}", jsonHeaders(buildToken("super@test.com", "EMPLOYEE"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchangeRepository.findByAcronym("NASDAQ").orElseThrow().isTestMode()).isTrue();
    }

    @Test
    @DisplayName("R4-444: PATCH /exchanges/{acronym}/test-mode — 403 za plain EMPLOYEE (agent bez SUPERVISOR)")
    void setTestMode_forbiddenForPlainEmployee() {
        savedExchange("NASDAQ", false);
        // role=EMPLOYEE bez SUPERVISOR autoriteta (agent) — sada 403 (R4-444).
        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/NASDAQ/test-mode"),
                HttpMethod.PATCH,
                new HttpEntity<>("{\"enabled\":true}", jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchangeRepository.findByAcronym("NASDAQ").orElseThrow().isTestMode()).isFalse();
    }

    @Test
    @DisplayName("R4-444: POST /exchanges/{acronym}/holidays — 403 za CLIENT (ranije palo na authenticated())")
    void addHoliday_forbiddenForClient() {
        savedExchange("NASDAQ", false);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/NASDAQ/holidays"),
                HttpMethod.POST,
                new HttpEntity<>("{\"date\":\"2026-12-25\"}", jsonHeaders(buildToken("client@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("R4-444: POST /exchanges/{acronym}/holidays — 403 za plain EMPLOYEE (agent)")
    void addHoliday_forbiddenForPlainEmployee() {
        savedExchange("NASDAQ", false);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/NASDAQ/holidays"),
                HttpMethod.POST,
                new HttpEntity<>("{\"date\":\"2026-12-25\"}", jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH /exchanges/{acronym}/test-mode — 403 za CLIENT")
    void setTestMode_forbiddenForClient() {
        savedExchange("NYSE", false);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/NYSE/test-mode"),
                HttpMethod.PATCH,
                new HttpEntity<>("{\"enabled\":true}", jsonHeaders(buildToken("client@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // toggle nije primenjen
        assertThat(exchangeRepository.findByAcronym("NYSE").orElseThrow().isTestMode()).isFalse();
    }

    @Test
    @DisplayName("PATCH /exchanges/{acronym}/test-mode — 403 bez JWT-a")
    void setTestMode_missingJwt_returnsForbidden() {
        savedExchange("NYSE", false);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/NYSE/test-mode"),
                HttpMethod.PATCH,
                new HttpEntity<>("{\"enabled\":true}", jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("R1 446: PATCH /exchanges/{acronym}/test-mode — 404 kad berza ne postoji")
    void setTestMode_unknownExchange_returnsNotFound() {
        // R1 446: service baca EntityNotFoundException("Exchange not found") -> globalni handler 404 (bilo 400).
        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/NOPE/test-mode"),
                HttpMethod.PATCH,
                new HttpEntity<>("{\"enabled\":true}", jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Exchange not found");
    }

    @Test
    @DisplayName("R1-753: POST /exchanges/{acronym}/holidays — 400 kad nedostaje 'date' (umesto NPE/500)")
    void addHoliday_missingDate_returnsBadRequest() {
        savedExchange("NYSE", false);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/NYSE/holidays"),
                HttpMethod.POST,
                new HttpEntity<>("{}", jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("R1-753: POST /exchanges/{acronym}/holidays — 400 kad je 'date' nevalidan format")
    void addHoliday_invalidDate_returnsBadRequest() {
        savedExchange("NYSE", false);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/exchanges/NYSE/holidays"),
                HttpMethod.POST,
                new HttpEntity<>("{\"date\":\"not-a-date\"}", jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Exchange savedExchange(String acronym, boolean testMode) {
        return exchangeRepository.save(Exchange.builder()
                .name(acronym + " Exchange")
                .acronym(acronym)
                .micCode("X" + acronym)
                .country("US")
                .currency("USD")
                .timeZone("America/New_York")
                .openTime(LocalTime.of(9, 30))
                .closeTime(LocalTime.of(16, 0))
                .testMode(testMode)
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
