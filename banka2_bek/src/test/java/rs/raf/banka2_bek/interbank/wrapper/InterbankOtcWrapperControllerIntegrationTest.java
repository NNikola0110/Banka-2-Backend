package rs.raf.banka2_bek.interbank.wrapper;

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
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.notification.NotificationPublisher;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP integration tests za {@link InterbankOtcWrapperController} (/interbank/otc/**).
 *
 * <p>Auth: ove FE-facing rute koriste standardni JWT nase banke
 * ({@code .requestMatchers("/interbank/otc/**").authenticated()} u
 * {@link rs.raf.banka2_bek.auth.config.GlobalSecurityConfig}) — NE X-Api-Key.
 * {@code UserResolver.resolveCurrent()} mapira JWT email na Client/Employee, pa
 * svaki test korisnik mora imati i {@link User} (za JWT) i {@link Client}
 * (za resolveCurrent).</p>
 *
 * <p>GET rute (/listings, /offers/my, /contracts/my) rade nad praznom bazom
 * (lokalni repo-i prazni -&gt; []) bez outbound poziva; partner-fetch greske se
 * gutaju u wrapper service-u, pa /listings vraca [] cak i kad partner nije ziv.</p>
 */
@Import(TestObjectMapperConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InterbankOtcWrapperControllerIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ExchangeService exchangeService;
    @MockitoBean private NotificationPublisher notificationPublisher;
    @MockitoBean private rs.raf.banka2_bek.otp.service.OtpService otpService;
    // Outbound partner poziv (POST/GET /negotiations + portfolio) — mock-uj da
    // /offers/my pull-sync ne pokusava real HTTP. Prazna baza ionako ne triguje
    // sync, ali drzimo bean stub-ovan za determinizam.
    @MockitoBean private rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient tradingServiceInternalClient;

    @BeforeEach
    void cleanDatabase() {
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });
        IntegrationTestCleanup.truncateAllTables(dataSource);
        org.mockito.Mockito.when(tradingServiceInternalClient.findAllPublicStock())
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(tradingServiceInternalClient.findListingByTicker(
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
    }

    @Test
    void listMyOffers_authenticatedClient_emptyDb_returns200EmptyArray() throws Exception {
        String token = createClientUser("otcw.offers@test.com");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/interbank/otc/offers/my"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    void listMyContracts_authenticatedClient_emptyDb_returns200EmptyArray() throws Exception {
        String token = createClientUser("otcw.contracts@test.com");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/interbank/otc/contracts/my"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    void listListings_authenticatedClient_partnerUnreachable_returns200EmptyArray() throws Exception {
        String token = createClientUser("otcw.listings@test.com");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/interbank/otc/listings"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)), String.class);

        // Partner banka (routing 999 -> localhost:9999) nije ziva; wrapper guta
        // RuntimeException i vraca prazno umesto da rusi ceo zahtev.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
    }

    @Test
    void createOffer_invalidBody_returns400() throws Exception {
        String token = createClientUser("otcw.create@test.com");
        // quantity negativan (@Positive) + prazan sellerBankCode (@NotBlank) -> bean validacija 400.
        String payload = """
                {
                  "sellerBankCode": "",
                  "sellerUserId": "C-1",
                  "listingTicker": "AAPL",
                  "quantity": -5,
                  "pricePerStock": 100,
                  "premium": 2,
                  "settlementDate": "2030-01-01"
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/interbank/otc/offers"),
                new HttpEntity<>(payload, jsonHeaders(token)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createOffer_unknownTicker_returns4xx() throws Exception {
        String token = createClientUser("otcw.create2@test.com");
        // Validan body ali nepostojeci ticker -> wrapper baca InterbankProtocolException
        // (ticker ne postoji) PRE outbound poziva -> 4xx (ne 5xx).
        String payload = """
                {
                  "sellerBankCode": "RN-999",
                  "sellerUserId": "C-1",
                  "listingTicker": "NOPE",
                  "quantity": 10,
                  "pricePerStock": 100,
                  "premium": 2,
                  "settlementDate": "2030-01-01"
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/interbank/otc/offers"),
                new HttpEntity<>(payload, jsonHeaders(token)),
                String.class);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void listMyOffers_unauthenticated_returns401or403() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/interbank/otc/offers/my"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)), String.class);
        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void createOffer_unauthenticated_returns401or403() throws Exception {
        String payload = """
                {
                  "sellerBankCode": "RN-999",
                  "sellerUserId": "C-1",
                  "listingTicker": "AAPL",
                  "quantity": 10,
                  "pricePerStock": 100,
                  "premium": 2,
                  "settlementDate": "2030-01-01"
                }
                """;
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/interbank/otc/offers"),
                new HttpEntity<>(payload, jsonHeaders(null)),
                String.class);
        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    // ===== Helpers =====

    /** Kreira i {@link User} (za JWT) i {@link Client} (za UserResolver) sa istim email-om; vraca JWT. */
    private String createClientUser(String email) {
        Client c = new Client();
        c.setFirstName("Otc"); c.setLastName("Buyer");
        c.setDateOfBirth(LocalDate.of(1995, 1, 1));
        c.setGender("M"); c.setEmail(email);
        c.setPhone("+381600000009"); c.setAddress("Test");
        c.setPassword("x"); c.setSaltPassword("salt"); c.setActive(true);
        clientRepository.save(c);

        User user = new User();
        user.setFirstName("Otc"); user.setLastName("Buyer");
        user.setEmail(email); user.setPassword("x");
        user.setActive(true); user.setRole("CLIENT");
        userRepository.save(user);
        return jwtService.generateAccessToken(user);
    }

    private HttpHeaders jsonHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) headers.setBearerAuth(bearerToken);
        return headers;
    }

    private String url(String path) { return "http://localhost:" + port + path; }
}
