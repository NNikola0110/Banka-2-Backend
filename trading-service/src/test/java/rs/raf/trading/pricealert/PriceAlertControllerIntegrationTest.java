package rs.raf.trading.pricealert;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
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
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.pricealert.model.PriceAlert;
import rs.raf.trading.pricealert.model.PriceAlertCondition;
import rs.raf.trading.pricealert.repository.PriceAlertRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * [B5 - Cenovni alarmi] HTTP integracioni test {@code PriceAlertController}-a.
 *
 * <p>Pun Spring kontekst (H2 test profil), RANDOM_PORT, realan security filter chain
 * (JWT validacija) + realan servis + JPA persistencija. {@link BankaCoreClient} +
 * {@link TradingUserResolver} + {@link NotificationService} su mockovani.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PriceAlertControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long CLIENT_STEFAN_ID = 1001L;
    private static final Long CLIENT_MILICA_ID = 1002L;
    private static final String STEFAN_EMAIL = "stefan@example.com";
    private static final String MILICA_EMAIL = "milica@example.com";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private PriceAlertRepository alertRepository;

    @Autowired
    private ListingRepository listingRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    @MockitoBean
    private TradingUserResolver userResolver;

    @MockitoBean
    private NotificationService notificationService;

    private final RestTemplate restTemplate = createRestTemplate();
    private final SecretKey secretKey =
            Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    private String stefanToken;
    private String milicaToken;
    private Long aaplId;

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
        alertRepository.deleteAll();
        listingRepository.deleteAll();

        Listing aapl = new Listing();
        aapl.setTicker("AAPL");
        aapl.setName("Apple Inc.");
        aapl.setListingType(ListingType.STOCK);
        aapl.setPrice(new BigDecimal("150.00"));
        aaplId = listingRepository.save(aapl).getId();

        stefanToken = buildToken(STEFAN_EMAIL, UserRole.CLIENT);
        milicaToken = buildToken(MILICA_EMAIL, UserRole.CLIENT);

        // resolveCurrent vraca Stefana po default-u; individualni testovi mogu
        // da prepisuju (npr. za 403 ownership scenario).
        lenient().when(userResolver.resolveCurrent())
                .thenReturn(new UserContext(CLIENT_STEFAN_ID, UserRole.CLIENT));
        lenient().when(bankaCoreClient.getUserPermissions(anyString()))
                .thenReturn(List.of());
        lenient().when(bankaCoreClient.getUserById(anyString(), anyLong()))
                .thenAnswer(inv -> {
                    Long id = inv.getArgument(1);
                    return new InternalUserDto(id, UserRole.CLIENT, "user" + id + "@example.com",
                            "Ime" + id, "Prezime" + id, true, null);
                });
    }

    @AfterEach
    void tearDown() {
        alertRepository.deleteAll();
        listingRepository.deleteAll();
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

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    @DisplayName("POST /price-alerts -> 201 i perzistuje alarm")
    void createAlert_happyPath_returns201AndPersists() {
        String payload = "{\"listingId\":" + aaplId
                + ",\"condition\":\"ABOVE\",\"threshold\":160.00}";

        ResponseEntity<String> response = restTemplate.exchange(
                url("/price-alerts"),
                HttpMethod.POST,
                new HttpEntity<>(payload, authHeaders(stefanToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("\"active\":true");
        assertThat(response.getBody()).contains("\"condition\":\"ABOVE\"");
        assertThat(response.getBody()).contains("\"listingTicker\":\"AAPL\"");

        List<PriceAlert> all = alertRepository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getOwnerId()).isEqualTo(CLIENT_STEFAN_ID);
        assertThat(all.get(0).getOwnerType()).isEqualTo(UserRole.CLIENT);
    }

    @Test
    @DisplayName("POST /price-alerts bez bearer-a -> 401")
    void createAlert_noAuth_returns401() {
        String payload = "{\"listingId\":" + aaplId
                + ",\"condition\":\"ABOVE\",\"threshold\":160.00}";

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                url("/price-alerts"),
                HttpMethod.POST,
                new HttpEntity<>(payload, h),
                String.class
        );

        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    @DisplayName("POST /price-alerts sa threshold=0 -> 400")
    void createAlert_invalidThreshold_returns400() {
        String payload = "{\"listingId\":" + aaplId
                + ",\"condition\":\"ABOVE\",\"threshold\":0}";

        ResponseEntity<String> response = restTemplate.exchange(
                url("/price-alerts"),
                HttpMethod.POST,
                new HttpEntity<>(payload, authHeaders(stefanToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(alertRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("POST /price-alerts sa nepostojecim listingId -> 404")
    void createAlert_nonExistentListing_returns404() {
        String payload = "{\"listingId\":99999,\"condition\":\"ABOVE\",\"threshold\":160.00}";

        ResponseEntity<String> response = restTemplate.exchange(
                url("/price-alerts"),
                HttpMethod.POST,
                new HttpEntity<>(payload, authHeaders(stefanToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(alertRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("GET /price-alerts/my -> vraca alarme tekuceg korisnika")
    void listMyAlerts_returnsCurrentUsersAlerts() {
        PriceAlert mine = PriceAlert.builder()
                .ownerId(CLIENT_STEFAN_ID)
                .ownerType(UserRole.CLIENT)
                .listingId(aaplId)
                .condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("160.00"))
                .active(true)
                .build();
        alertRepository.save(mine);

        PriceAlert other = PriceAlert.builder()
                .ownerId(CLIENT_MILICA_ID)
                .ownerType(UserRole.CLIENT)
                .listingId(aaplId)
                .condition(PriceAlertCondition.BELOW)
                .threshold(new BigDecimal("100.00"))
                .active(true)
                .build();
        alertRepository.save(other);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/price-alerts/my"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(stefanToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"ownerId\":" + CLIENT_STEFAN_ID);
        assertThat(response.getBody()).doesNotContain("\"ownerId\":" + CLIENT_MILICA_ID);
    }

    @Test
    @DisplayName("GET /price-alerts/my?active=true -> vraca samo aktivne")
    void listMyAlerts_activeFilter_returnsOnlyActive() {
        PriceAlert active = PriceAlert.builder()
                .ownerId(CLIENT_STEFAN_ID).ownerType(UserRole.CLIENT)
                .listingId(aaplId).condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("160.00")).active(true).build();
        PriceAlert inactive = PriceAlert.builder()
                .ownerId(CLIENT_STEFAN_ID).ownerType(UserRole.CLIENT)
                .listingId(aaplId).condition(PriceAlertCondition.BELOW)
                .threshold(new BigDecimal("100.00")).active(false).build();
        alertRepository.save(active);
        alertRepository.save(inactive);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/price-alerts/my?active=true"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(stefanToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Treba da sadrzi samo aktivan alarm (ABOVE/160) ali ne i inaktivan (BELOW/100).
        assertThat(response.getBody()).contains("\"condition\":\"ABOVE\"");
        assertThat(response.getBody()).doesNotContain("\"condition\":\"BELOW\"");
    }

    @Test
    @DisplayName("DELETE /price-alerts/{id} kao vlasnik -> 204")
    void deleteAlert_owner_returns204() {
        PriceAlert mine = PriceAlert.builder()
                .ownerId(CLIENT_STEFAN_ID).ownerType(UserRole.CLIENT)
                .listingId(aaplId).condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("160.00")).active(true).build();
        PriceAlert saved = alertRepository.save(mine);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/price-alerts/" + saved.getId()),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(stefanToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(alertRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("DELETE /price-alerts/{id} sa ownership mismatch -> 403")
    void deleteAlert_ownershipMismatch_returns403() {
        PriceAlert milicasAlert = PriceAlert.builder()
                .ownerId(CLIENT_MILICA_ID).ownerType(UserRole.CLIENT)
                .listingId(aaplId).condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("160.00")).active(true).build();
        PriceAlert saved = alertRepository.save(milicasAlert);

        // resolveCurrent vraca Stefana po default setUp-u; Stefan pokusava da
        // obrise Milicin alarm.
        ResponseEntity<String> response = restTemplate.exchange(
                url("/price-alerts/" + saved.getId()),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(stefanToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Alarm i dalje postoji
        assertThat(alertRepository.findById(saved.getId())).isPresent();
    }

    @Test
    @DisplayName("DELETE /price-alerts/{id} nepostojeci -> 404")
    void deleteAlert_notFound_returns404() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/price-alerts/99999"),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(stefanToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
