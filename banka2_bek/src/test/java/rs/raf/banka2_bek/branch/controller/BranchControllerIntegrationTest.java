package rs.raf.banka2_bek.branch.controller;

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
import rs.raf.banka2_bek.branch.model.Branch;
import rs.raf.banka2_bek.branch.model.BranchType;
import rs.raf.banka2_bek.branch.repository.BranchRepository;
import rs.raf.banka2_bek.exchange.ExchangeService;
import rs.raf.banka2_bek.notification.NotificationPublisher;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP integration tests za {@link BranchController} (/branches).
 *
 * <p>Auth: /branches potpada pod {@code anyRequest().authenticated()} u
 * {@link rs.raf.banka2_bek.auth.config.GlobalSecurityConfig} — bilo koji
 * autentifikovan JWT (CLIENT/EMPLOYEE/ADMIN) sme da cita mapu lokacija;
 * neautentifikovan zahtev dobija 401/403.</p>
 */
@Import(TestObjectMapperConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BranchControllerIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ExchangeService exchangeService;
    @MockitoBean private NotificationPublisher notificationPublisher;
    @MockitoBean private rs.raf.banka2_bek.otp.service.OtpService otpService;

    @BeforeEach
    void cleanDatabase() {
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });
        IntegrationTestCleanup.truncateAllTables(dataSource);
    }

    @Test
    void listBranches_authenticatedClient_returns200AndShape() throws Exception {
        seedBranch("Ekspozitura Centar", BranchType.BRANCH, "Knez Mihailova 1",
                false, false, "08-16");
        seedBranch("Bankomat NBG", BranchType.ATM, "Bulevar Kralja Aleksandra 73",
                true, true, "00-24");
        User client = createUser("branch.client@test.com", "CLIENT");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/branches"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(client))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(2);
        // Osnovni shape: svaka stavka ima id, name, type, address, lat/lon.
        JsonNode first = body.get(0);
        assertThat(first.path("id").isNumber()).isTrue();
        assertThat(first.path("name").asText()).isNotBlank();
        assertThat(first.path("type").asText()).isIn("BRANCH", "ATM");
        assertThat(first.path("address").asText()).isNotBlank();
        assertThat(first.has("latitude")).isTrue();
        assertThat(first.has("longitude")).isTrue();
    }

    @Test
    void listBranches_filterByType_returnsOnlyAtms() throws Exception {
        seedBranch("Ekspozitura A", BranchType.BRANCH, "Adresa A", false, false, "08-16");
        seedBranch("ATM 1", BranchType.ATM, "Adresa B", true, false, "00-24");
        seedBranch("ATM 2", BranchType.ATM, "Adresa C", false, true, "00-24");
        User employee = createUser("branch.emp@test.com", "EMPLOYEE");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/branches?type=ATM"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(employee))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body).hasSize(2);
        for (JsonNode node : body) {
            assertThat(node.path("type").asText()).isEqualTo("ATM");
        }
    }

    @Test
    void listBranches_emptyTable_returnsEmptyArray() throws Exception {
        User admin = createUser("branch.admin@test.com", "ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/branches"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    void listBranches_unauthenticated_returns401or403() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/branches"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class);
        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    // ===== Helpers =====

    private Branch seedBranch(String name, BranchType type, String address,
                              boolean has24h, boolean driveThrough, String hours) {
        return branchRepository.save(Branch.builder()
                .name(name).type(type).address(address)
                .latitude(new BigDecimal("44.787000")).longitude(new BigDecimal("20.457000"))
                .openingHours(hours).has24h(has24h).hasDriveThrough(driveThrough)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private User createUser(String email, String role) {
        User user = new User();
        user.setFirstName("Test"); user.setLastName("User");
        user.setEmail(email); user.setPassword("x");
        user.setActive(true); user.setRole(role);
        return userRepository.save(user);
    }

    private HttpHeaders jsonHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) headers.setBearerAuth(bearerToken);
        return headers;
    }

    private String url(String path) { return "http://localhost:" + port + path; }
}
