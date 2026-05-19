package rs.raf.banka2_bek.internalapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.banka2_bek.IntegrationTestCleanup;
import rs.raf.banka2_bek.TestObjectMapperConfig;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.otp.model.OtpVerification;
import rs.raf.banka2_bek.otp.repository.OtpVerificationRepository;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integracioni testovi za interne identitet + OTP rute (faza2c-A):
 *   GET  /internal/users/by-email/{email}
 *   GET  /internal/users/{role}/{id}
 *   POST /internal/otp/verify
 */
@Import(TestObjectMapperConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InternalUsersOtpControllerIntegrationTest {

    @Value("${internal.api-key}")
    private String internalKey;

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

    @Autowired private ClientRepository clientRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private OtpVerificationRepository otpRepository;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
        IntegrationTestCleanup.truncateAllTables(dataSource);
    }

    // ─── getUserByEmail: client ──────────────────────────────────────────────

    @Test
    void getUserByEmail_client_returnsClientIdentity() throws Exception {
        Client client = persistClient("user-byemail-client@test.com");

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/users/by-email/user-byemail-client@test.com"),
                HttpMethod.GET, new HttpEntity<>(internalHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("userId").asLong()).isEqualTo(client.getId());
        assertThat(json.path("userRole").asText()).isEqualTo("CLIENT");
        assertThat(json.path("email").asText()).isEqualTo("user-byemail-client@test.com");
        assertThat(json.path("active").asBoolean()).isTrue();
    }

    // ─── getUserByEmail: employee ────────────────────────────────────────────

    @Test
    void getUserByEmail_employee_returnsEmployeeIdentity() throws Exception {
        Employee employee = persistEmployee("user-byemail-emp@test.com");

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/users/by-email/user-byemail-emp@test.com"),
                HttpMethod.GET, new HttpEntity<>(internalHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("userId").asLong()).isEqualTo(employee.getId());
        assertThat(json.path("userRole").asText()).isEqualTo("EMPLOYEE");
        assertThat(json.path("active").asBoolean()).isTrue();
    }

    // ─── getUserByEmail: not found → 404 ─────────────────────────────────────

    @Test
    void getUserByEmail_notFound_returns404() {
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/users/by-email/nonexistent@test.com"),
                HttpMethod.GET, new HttpEntity<>(internalHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── getUserById: client ─────────────────────────────────────────────────

    @Test
    void getUserById_client_returnsClientIdentity() throws Exception {
        Client client = persistClient("user-byid-client@test.com");

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/users/CLIENT/" + client.getId()),
                HttpMethod.GET, new HttpEntity<>(internalHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("userId").asLong()).isEqualTo(client.getId());
        assertThat(json.path("userRole").asText()).isEqualTo("CLIENT");
        assertThat(json.path("email").asText()).isEqualTo("user-byid-client@test.com");
    }

    // ─── getUserById: employee ───────────────────────────────────────────────

    @Test
    void getUserById_employee_returnsEmployeeIdentity() throws Exception {
        Employee employee = persistEmployee("user-byid-emp@test.com");

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/users/EMPLOYEE/" + employee.getId()),
                HttpMethod.GET, new HttpEntity<>(internalHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("userId").asLong()).isEqualTo(employee.getId());
        assertThat(json.path("userRole").asText()).isEqualTo("EMPLOYEE");
    }

    // ─── getUserById: not found → 404 ────────────────────────────────────────

    @Test
    void getUserById_notFound_returns404() {
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/users/CLIENT/999999"),
                HttpMethod.GET, new HttpEntity<>(internalHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── missing X-Internal-Key → 401 ────────────────────────────────────────

    @Test
    void getUserByEmail_missingInternalKey_returns401() {
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/users/by-email/whatever@test.com"),
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── OTP verify: correct code → verified true ────────────────────────────

    @Test
    void otpVerify_correctCode_returnsVerified() throws Exception {
        persistOtp("otp-ok@test.com", "123456");

        String body = "{ \"email\": \"otp-ok@test.com\", \"code\": \"123456\" }";
        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/otp/verify"),
                new HttpEntity<>(body, jsonInternalHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("verified").asBoolean()).isTrue();
        assertThat(json.path("blocked").asBoolean()).isFalse();
    }

    // ─── OTP verify: wrong code → verified false ─────────────────────────────

    @Test
    void otpVerify_wrongCode_returnsNotVerified() throws Exception {
        persistOtp("otp-wrong@test.com", "654321");

        String body = "{ \"email\": \"otp-wrong@test.com\", \"code\": \"000000\" }";
        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/otp/verify"),
                new HttpEntity<>(body, jsonInternalHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("verified").asBoolean()).isFalse();
    }

    // ─── OTP verify: no active code → verified false ─────────────────────────

    @Test
    void otpVerify_noActiveCode_returnsNotVerified() throws Exception {
        String body = "{ \"email\": \"otp-none@test.com\", \"code\": \"123456\" }";
        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/otp/verify"),
                new HttpEntity<>(body, jsonInternalHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("verified").asBoolean()).isFalse();
        assertThat(json.path("blocked").asBoolean()).isFalse();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", internalKey);
        return headers;
    }

    private HttpHeaders jsonInternalHeaders() {
        HttpHeaders headers = internalHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Client persistClient(String email) {
        Client c = new Client();
        c.setFirstName("Internal");
        c.setLastName("Client");
        c.setDateOfBirth(LocalDate.of(1995, 1, 1));
        c.setGender("M");
        c.setEmail(email);
        c.setPhone("+381600000001");
        c.setAddress("Test Address");
        c.setPassword("x");
        c.setSaltPassword("salt");
        c.setActive(true);
        return clientRepository.save(c);
    }

    private Employee persistEmployee(String email) {
        return employeeRepository.save(Employee.builder()
                .firstName("Internal").lastName("Employee")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M")
                .email(email)
                .phone("+381600000000")
                .address("Test")
                .username("internal-emp-" + email)
                .password("x")
                .saltPassword("salt")
                .position("QA")
                .department("IT")
                .active(true)
                .permissions(Set.of())
                .build());
    }

    private void persistOtp(String email, String code) {
        otpRepository.save(OtpVerification.builder()
                .email(email)
                .code(code)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build());
    }
}
