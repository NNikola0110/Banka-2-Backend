package rs.raf.banka2_bek.interbank.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import rs.raf.banka2_bek.IntegrationTestCleanup;
import rs.raf.banka2_bek.TestObjectMapperConfig;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.auth.service.JwtService;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.notification.NotificationPublisher;

import javax.sql.DataSource;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP integration tests za {@link OtcNegotiationController} — inbound OTC
 * negotiation protokol (§3.1–§3.7).
 *
 * <p><b>Auth model (vazno):</b> sve rute ovog kontrolera (/public-stock,
 * /negotiations, /user) su <b>iskljucivo</b> X-Api-Key
 * (inter-bank) — vidi {@code .hasAuthority("ROLE_INTERBANK")} u
 * {@link rs.raf.banka2_bek.auth.config.GlobalSecurityConfig} +
 * {@link rs.raf.banka2_bek.auth.config.InterbankAuthFilter}. NEMA JWT/user-facing
 * rute. Test profil konfigurise partnera: routing 999, inbound-token
 * {@code test-key-dummy} (application-test.properties).</p>
 *
 * <p>Posto je kontroler cisto inbound-X-Api-Key, pokrivamo: (1) happy path sa
 * validnim kljucem, (2) 401 za missing/invalid kljuc, (3) 401 za JWT-only zahtev
 * (filter presrece inter-bank putanju pre JWT-a), (4) 404 putanje za §3.7 user
 * lookup. JWT-facing rute ne postoje za ovaj kontroler (wrapper kontroler ih ima),
 * pa nema role-gated happy path da se testira preko JWT-a.</p>
 */
@Import(TestObjectMapperConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OtcNegotiationControllerIntegrationTest {

    private static final String VALID_API_KEY = "test-key-dummy"; // application-test.properties
    private static final int MY_ROUTING = 222;

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ExchangeService exchangeService;
    @MockitoBean private NotificationPublisher notificationPublisher;
    @MockitoBean private rs.raf.banka2_bek.otp.service.OtpService otpService;
    // /public-stock cita javne pozicije preko trading-service seam-a -> mock prazno.
    @MockitoBean private rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient tradingServiceInternalClient;

    @BeforeEach
    void cleanDatabase() {
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });
        IntegrationTestCleanup.truncateAllTables(dataSource);
        org.mockito.Mockito.when(tradingServiceInternalClient.findAllPublicStock())
                .thenReturn(java.util.List.of());
    }

    // ===== §3.1 /public-stock — happy path + auth gating =====

    @Test
    void publicStock_validApiKey_returns200Array() throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/public-stock"), HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(VALID_API_KEY)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        // Prazan portfolio (mock) -> nema javnih akcija za ponudu.
        assertThat(body).isEmpty();
    }

    @Test
    void publicStock_missingApiKey_returns401() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/public-stock"), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        // InterbankAuthFilter presrece inter-bank putanju i odbija bez X-Api-Key.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void publicStock_invalidApiKey_returns401() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/public-stock"), HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders("wrong-key")), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void publicStock_jwtOnlyNoApiKey_returns401() {
        // JWT korisnik nema ROLE_INTERBANK; InterbankAuthFilter presrece putanju
        // PRE JWT filtera i odbija jer X-Api-Key nedostaje -> 401 (ne 403).
        User user = createUser("otc.jwt@test.com", "ADMIN");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateAccessToken(user));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/public-stock"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ===== §3.7 /user/{routingNumber}/{id} — 404 putanje =====

    @Test
    void getUserInfo_wrongRoutingNumber_returns404() {
        // routingNumber != nas (222) -> mi nismo autoritativni -> 404.
        ResponseEntity<String> response = restTemplate.exchange(
                url("/user/999/C-1"), HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(VALID_API_KEY)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getUserInfo_unknownLocalId_returns404() {
        // Nas routing ali nepostojeci klijent -> InterbankUserNotFoundException -> 404.
        ResponseEntity<String> response = restTemplate.exchange(
                url("/user/" + MY_ROUTING + "/C-987654"), HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(VALID_API_KEY)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getUserInfo_missingApiKey_returns401() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/user/" + MY_ROUTING + "/C-1"), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ===== Helpers =====

    private User createUser(String email, String role) {
        User user = new User();
        user.setFirstName("Test"); user.setLastName("User");
        user.setEmail(email); user.setPassword("x");
        user.setActive(true); user.setRole(role);
        return userRepository.save(user);
    }

    private HttpHeaders apiKeyHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) headers.set("X-Api-Key", apiKey);
        return headers;
    }

    private String url(String path) { return "http://localhost:" + port + path; }
}
