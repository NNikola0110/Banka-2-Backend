package rs.raf.banka2_bek.audit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.banka2_bek.IntegrationTestCleanup;
import rs.raf.banka2_bek.TestObjectMapperConfig;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.service.AuditLogService;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.auth.service.JwtService;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-04 — HTTP integracioni test {@link AuditLogController}: ucvrscuje guard za
 * {@code /audit/**} na HTTP sloju. {@code GlobalSecurityConfig} ogranicava citanje
 * audit log-a na {@code ROLE_ADMIN / ADMIN / SUPERVISOR} — bez ovog matcher-a generic
 * {@code anyRequest().authenticated()} bi pustio svaki klijentski JWT da cita revizioni
 * dnevnik.
 *
 * <p>Obrazac kopiran iz {@code LoanControllerIntegrationTest}: pun Spring kontekst
 * (H2 test profil), RANDOM_PORT, realan {@code JwtService} izdaje token za perzistiran
 * {@code User}/{@code Employee}, {@code JwtAuthenticationFilter} ucitava authorities
 * preko {@code CustomUserDetailsService} (ADMIN korisnik -> {@code ROLE_ADMIN};
 * SUPERVISOR zaposleni -> {@code SUPERVISOR} permisija; CLIENT -> samo {@code ROLE_CLIENT}).
 * {@code IntegrationTestCleanup} truncate-uje sve tabele pre svakog testa.
 */
@Import(TestObjectMapperConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuditLogControllerIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private AuditLogService auditLogService;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });
        IntegrationTestCleanup.truncateAllTables(dataSource);
    }

    // ── Authorization (SEC-04 gate) ──────────────────────────────────────────

    @Test
    @DisplayName("GET /audit — CLIENT dobija 403 (SEC-04: ne sme da cita audit log)")
    void getAudit_forbiddenForClient() {
        User client = createUser("audit.client@test.com", "CLIENT");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/audit"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(client))),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT ne sme da cita audit log — mora 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /audit — 401/403 bez tokena")
    void getAudit_unauthenticated() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/audit"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    @DisplayName("GET /audit — 200 za ADMIN, vraca seed-ovan zapis")
    void getAudit_okForAdmin() throws Exception {
        User admin = createUser("audit.admin@test.com", "ADMIN");
        Employee actor = createSupervisor("audit.actor@test.com", "audit.actor");
        auditLogService.record(actor.getId(), "EMPLOYEE", AuditActionType.PAYMENT_CREATED,
                "Payment created by audit test", "PAYMENT", 555L);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/audit"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(response.getBody()).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("actionType").asText()).isEqualTo("PAYMENT_CREATED");
        assertThat(content.get(0).path("targetType").asText()).isEqualTo("PAYMENT");
        assertThat(content.get(0).path("targetId").asLong()).isEqualTo(555L);
    }

    @Test
    @DisplayName("GET /audit — 200 za SUPERVISOR zaposlenog")
    void getAudit_okForSupervisor() {
        Employee supervisor = createSupervisor("audit.sup@test.com", "audit.sup");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/audit"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(supervisor))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /audit — 403 za zaposlenog bez SUPERVISOR/ADMIN permisije (AGENT)")
    void getAudit_forbiddenForAgentEmployee() {
        Employee agent = createEmployeeWithPermissions("audit.agent@test.com", "audit.agent",
                Set.of("VIEW_STOCKS"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/audit"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(agent))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Param validation (from/to ISO parse) ─────────────────────────────────

    @Test
    @DisplayName("GET /audit?actionType=BOGUS — 400 za nepoznat actionType")
    void getAudit_unknownActionType_returnsBadRequest() {
        User admin = createUser("audit.admin2@test.com", "ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/audit?actionType=BOGUS"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /audit?from=not-a-date — 400/500 za nevalidan ISO from datum")
    void getAudit_invalidFromDate_returnsError() {
        User admin = createUser("audit.admin3@test.com", "ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/audit?from=not-a-date"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        // LocalDateTime.parse baca DateTimeParseException -> global handler 400 (ili 500
        // ako nije mapiran); kljucno je da NIJE 2xx — nevalidan datum se ne sme tiho progutati.
        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(response.getStatusCode().value()).isIn(400, 500);
    }

    @Test
    @DisplayName("GET /audit?from=<ISO> — 200 za validan ISO datum-vreme (ADMIN)")
    void getAudit_validIsoFrom_returnsOk() {
        User admin = createUser("audit.admin4@test.com", "ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/audit?from=2020-01-01T00:00:00"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── getById status contract (R1 158 / R1 698) ────────────────────────────

    @Test
    @DisplayName("GET /audit/{id} — nepostojeci id vraca 404 (ne 400) za ADMIN")
    void getAuditById_notFound_returns404() {
        User admin = createUser("audit.byid404@test.com", "ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/audit/999999"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        // R1 158: AuditLogController baca ResponseStatusException(NOT_FOUND); bez
        // dedikovanog @ExceptionHandler(ResponseStatusException) catch-all
        // handleRuntimeException bi ga premapirao na 400 → ovaj test cuva 404.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /audit/{id} — postojeci id vraca 200 + zapis za ADMIN")
    void getAuditById_found_returns200() {
        User admin = createUser("audit.byid200@test.com", "ADMIN");
        Employee actor = createSupervisor("audit.byidactor@test.com", "audit.byidactor");
        auditLogService.record(actor.getId(), "EMPLOYEE", AuditActionType.PAYMENT_CREATED,
                "Payment created by byId test", "PAYMENT", 777L);
        // record() je void → id procitamo iz query rezultata (samo jedan zapis u truncate-ovanoj bazi).
        Long savedId = auditLogService.query(null, null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().get(0).getId();

        ResponseEntity<String> response = restTemplate.exchange(
                url("/audit/" + savedId),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User createUser(String email, String role) {
        User user = new User();
        user.setFirstName("Test");
        user.setLastName(role);
        user.setEmail(email);
        user.setPassword("x");
        user.setActive(true);
        user.setRole(role);
        return userRepository.save(user);
    }

    @SuppressWarnings("unused")
    private Client createClient(String email) {
        Client c = new Client();
        c.setFirstName("Test"); c.setLastName("Client");
        c.setDateOfBirth(LocalDate.of(1995, 1, 1));
        c.setGender("M"); c.setEmail(email);
        c.setPhone("+381600000001"); c.setAddress("Test Address");
        c.setPassword("x"); c.setSaltPassword("salt"); c.setActive(true);
        return clientRepository.save(c);
    }

    private Employee createSupervisor(String email, String username) {
        return createEmployeeWithPermissions(email, username, Set.of("SUPERVISOR"));
    }

    private Employee createEmployeeWithPermissions(String email, String username, Set<String> permissions) {
        return employeeRepository.save(Employee.builder()
                .firstName("Emp").lastName("Test")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M").email(email).phone("+381600000000")
                .address("Test").username(username).password("x").saltPassword("salt")
                .position("QA").department("IT").active(true).permissions(permissions)
                .build());
    }

    private HttpHeaders jsonHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) headers.setBearerAuth(bearerToken);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
