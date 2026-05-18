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
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.internalapi.repository.FundReservationRepository;
import rs.raf.banka2_bek.internalapi.repository.InternalRequestRepository;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestObjectMapperConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InternalFundsControllerIntegrationTest {

    @Value("${internal.api-key}")
    private String internalKey;

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

    @Autowired private AccountRepository accountRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private FundReservationRepository fundReservationRepository;
    @Autowired private InternalRequestRepository internalRequestRepository;
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

    // ─── Reserve: happy path ──────────────────────────────────────────────────

    @Test
    void reserve_happyPath_returns200AndCreatesReservation() throws Exception {
        Account account = persistAccount("222000000000000001", "RSD", new BigDecimal("10000.00"));
        String idempotencyKey = "it-reserve-001";

        String body = """
                { "accountId": %d, "amount": 500.00, "currencyCode": "RSD" }
                """.formatted(account.getId());

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/reserve"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("reservationId").asText()).isNotBlank();
        assertThat(json.path("accountId").asLong()).isEqualTo(account.getId());
        assertThat(new BigDecimal(json.path("reservedAmount").asText()))
                .isEqualByComparingTo("500.00");
        assertThat(new BigDecimal(json.path("availableBalanceAfter").asText()))
                .isEqualByComparingTo("9500.00");

        // Idempotency record persisted
        assertThat(internalRequestRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
        // Reservation persisted
        assertThat(fundReservationRepository.count()).isEqualTo(1);
    }

    // ─── Reserve: missing X-Internal-Key → 401 ───────────────────────────────

    @Test
    void reserve_missingInternalKey_returns401() throws Exception {
        String body = """
                { "accountId": 1, "amount": 100.00, "currencyCode": "RSD" }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Idempotency-Key", "it-no-key");

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/reserve"),
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Reserve: missing X-Idempotency-Key → 400 ────────────────────────────

    @Test
    void reserve_missingIdempotencyKey_returns400() throws Exception {
        String body = """
                { "accountId": 1, "amount": 100.00, "currencyCode": "RSD" }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", internalKey);
        // No X-Idempotency-Key

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/reserve"),
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    // ─── Idempotency: repeated key returns cached response ───────────────────

    @Test
    void reserve_repeatedIdempotencyKey_returnsCachedResponse() throws Exception {
        Account account = persistAccount("222000000000000002", "RSD", new BigDecimal("10000.00"));
        String idempotencyKey = "it-reserve-idem-001";

        String body = """
                { "accountId": %d, "amount": 300.00, "currencyCode": "RSD" }
                """.formatted(account.getId());

        // First call
        ResponseEntity<String> first = restTemplate.postForEntity(
                url("/internal/funds/reserve"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstBody = first.getBody();

        // Second call — same key, same body
        ResponseEntity<String> second = restTemplate.postForEntity(
                url("/internal/funds/reserve"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondBody = second.getBody();

        // Must return identical reservationId and amounts
        JsonNode firstJson = objectMapper.readTree(firstBody);
        JsonNode secondJson = objectMapper.readTree(secondBody);
        assertThat(secondJson.path("reservationId").asText())
                .isEqualTo(firstJson.path("reservationId").asText());
        assertThat(secondJson.path("reservedAmount").asText())
                .isEqualTo(firstJson.path("reservedAmount").asText());

        // Only one reservation should exist (second call was idempotent)
        assertThat(fundReservationRepository.count()).isEqualTo(1);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private HttpHeaders internalHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", internalKey);
        headers.set("X-Idempotency-Key", idempotencyKey);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Persists the minimum Account needed for the reserve operation.
     * Uses JDBC-inserted Currency to avoid sequence issues.
     */
    private Account persistAccount(String accountNumber, String currencyCode, BigDecimal balance) {
        // Ensure currency exists
        long currencyId = findOrCreateCurrency(currencyCode);

        Employee employee = employeeRepository.save(Employee.builder()
                .firstName("Internal").lastName("Test")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M")
                .email("internal-test-" + accountNumber + "@test.com")
                .phone("+381600000000")
                .address("Test")
                .username("internal-" + accountNumber)
                .password("x")
                .saltPassword("salt")
                .position("QA")
                .department("IT")
                .active(true)
                .permissions(Set.of())
                .build());

        Client client = createClient(accountNumber);

        return accountRepository.save(Account.builder()
                .accountNumber(accountNumber)
                .accountType(AccountType.CHECKING)
                .currency(persistCurrencyEntity(currencyCode, currencyId))
                .employee(employee)
                .client(client)
                .status(AccountStatus.ACTIVE)
                .balance(balance)
                .availableBalance(balance)
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(new BigDecimal("50000.00"))
                .monthlyLimit(new BigDecimal("200000.00"))
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build());
    }

    private Client createClient(String suffix) {
        Client c = new Client();
        c.setFirstName("Internal");
        c.setLastName("Client");
        c.setDateOfBirth(LocalDate.of(1995, 1, 1));
        c.setGender("M");
        c.setEmail("internal-client-" + suffix + "@test.com");
        c.setPhone("+381600000001");
        c.setAddress("Test Address");
        c.setPassword("x");
        c.setSaltPassword("salt");
        c.setActive(true);
        return clientRepository.save(c);
    }

    private Currency persistCurrencyEntity(String code, long id) {
        Currency c = new Currency();
        c.setId(id);
        c.setCode(code);
        c.setName(code);
        c.setSymbol(code);
        c.setCountry("RS");
        c.setDescription("test");
        c.setActive(true);
        return c;
    }

    private long findOrCreateCurrency(String code) {
        var jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var ids = jdbcTemplate.query(
                "select id from currencies where code = ?",
                (rs, rowNum) -> rs.getLong(1), code);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        jdbcTemplate.update(
                "insert into currencies(code, name, symbol, country, description, active) values (?,?,?,?,?,?)",
                code, code, code, "RS", "test", true);
        return jdbcTemplate.queryForObject("select id from currencies where code = ?", Long.class, code);
    }
}
