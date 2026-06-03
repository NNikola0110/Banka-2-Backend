package rs.raf.banka2_bek.savings.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.banka2_bek.IntegrationTestCleanup;
import rs.raf.banka2_bek.TestObjectMapperConfig;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.auth.service.JwtService;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.company.model.Company;
import rs.raf.banka2_bek.company.repository.CompanyRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.savings.entity.SavingsInterestRate;
import rs.raf.banka2_bek.savings.repository.SavingsInterestRateRepository;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP integracioni test {@link SavingsDepositController} (klijentske rute,
 * {@code /savings/**} = authenticated) i {@link SavingsAdminController}
 * ({@code /admin/savings/**} = ADMIN/SUPERVISOR). Pun Spring kontekst (H2 test profil),
 * RANDOM_PORT, realan {@code JwtService} + {@code GlobalSecurityConfig} filter chain.
 *
 * <p>Obrazac kopiran iz {@code LoanControllerIntegrationTest}: token izdaje
 * {@code JwtService} za perzistiran {@code User}/{@code Employee}, {@code OtpService} je
 * {@code @MockitoBean} (verify -> success), {@code IntegrationTestCleanup} truncate-uje
 * tabele pre svakog testa. Stedni racun/depozit zahtevaju realan {@code Client} + dva
 * {@code Account}-a + {@code SavingsInterestRate} pa se seeduju direktno.
 */
@Import(TestObjectMapperConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SavingsControllerIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private SavingsInterestRateRepository rateRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManager entityManager;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private rs.raf.banka2_bek.otp.service.OtpService otpService;

    @BeforeEach
    void cleanDatabase() {
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });
        IntegrationTestCleanup.truncateAllTables(dataSource);
        org.mockito.Mockito.when(otpService.verify(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of("verified", true));
    }

    // ── POST /savings/deposits (klijent) ─────────────────────────────────────

    @Test
    @DisplayName("POST /savings/deposits — 200 otvara depozit za klijenta")
    void openDeposit_okForClient() throws Exception {
        Client client = createClient("sav.open@test.com");
        Employee emp = createEmployee("sav.openemp@test.com", "sav.openemp");
        Currency eur = ensureCurrency("EUR", "Euro", "E", "EU");
        Account source = createAccount("310000000000000001", client, emp, eur, new BigDecimal("5000.00"));
        Account linked = createAccount("310000000000000002", client, emp, eur, new BigDecimal("100.00"));
        // P0-B1: bankin liability racun (custody glavnice) mora postojati u valuti depozita.
        Company bank = ensureBank();
        Account bankAccount = createBankAccount("222000100000000010", bank, emp, eur, new BigDecimal("1000000.00"));
        seedRate(eur, 12, new BigDecimal("3.50"));
        User user = createAuthUserForClient(client);

        String payload = """
                {
                  "sourceAccountId": %d,
                  "linkedAccountId": %d,
                  "principalAmount": 1000.00,
                  "termMonths": 12,
                  "autoRenew": false,
                  "otpCode": "123456"
                }
                """.formatted(source.getId(), linked.getId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/savings/deposits"),
                new HttpEntity<>(payload, jsonHeaders(jwtService.generateAccessToken(user))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("termMonths").asInt()).isEqualTo(12);
        assertThat(body.path("principalAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(body.path("currencyCode").asText()).isEqualTo("EUR");
        // izvorni racun je umanjen za principal
        Account after = accountRepository.findByAccountNumber("310000000000000001").orElseThrow();
        assertThat(after.getBalance()).isEqualByComparingTo("4000.0000");
        // P0-B1 double-entry: bankin liability racun kreditiran za istu glavnicu (net = 0)
        Account bankAfter = accountRepository.findByAccountNumber("222000100000000010").orElseThrow();
        assertThat(bankAfter.getBalance()).isEqualByComparingTo("1001000.0000");
    }

    @Test
    @DisplayName("POST /savings/deposits — 403 za zaposlenog (samo klijenti)")
    void openDeposit_forbiddenForEmployee() {
        Employee emp = createEmployee("sav.empdep@test.com", "sav.empdep");

        String payload = """
                {
                  "sourceAccountId": 1,
                  "linkedAccountId": 2,
                  "principalAmount": 1000.00,
                  "termMonths": 12,
                  "autoRenew": false,
                  "otpCode": "123456"
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/savings/deposits"),
                new HttpEntity<>(payload, jsonHeaders(jwtService.generateAccessToken(emp))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("Samo klijenti");
    }

    @Test
    @DisplayName("POST /savings/deposits — 400 za nevalidan rok (term van skupa)")
    void openDeposit_invalidTerm_returnsBadRequest() {
        Client client = createClient("sav.badterm@test.com");
        User user = createAuthUserForClient(client);

        // term=18 prolazi DTO @Min(3)/@Max(36) ali nije u {3,6,12,24,36} -> service 400.
        String payload = """
                {
                  "sourceAccountId": 1,
                  "linkedAccountId": 2,
                  "principalAmount": 1000.00,
                  "termMonths": 18,
                  "autoRenew": false,
                  "otpCode": "123456"
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/savings/deposits"),
                new HttpEntity<>(payload, jsonHeaders(jwtService.generateAccessToken(user))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Rok mora biti jedan od");
    }

    @Test
    @DisplayName("POST /savings/deposits — 400 (DTO validacija) kad nedostaju polja")
    void openDeposit_missingFields_returnsBadRequest() {
        Client client = createClient("sav.missing@test.com");
        User user = createAuthUserForClient(client);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/savings/deposits"),
                new HttpEntity<>("{\"principalAmount\":1000.00}", jsonHeaders(jwtService.generateAccessToken(user))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /savings/deposits — 401/403 bez tokena")
    void openDeposit_unauthenticated() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/savings/deposits"),
                new HttpEntity<>("{}", jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    // ── GET /savings/deposits/my (klijent) ───────────────────────────────────

    @Test
    @DisplayName("GET /savings/deposits/my — 200 vraca depozite ulogovanog klijenta")
    void listMyDeposits_ok() throws Exception {
        Client client = createClient("sav.my@test.com");
        Employee emp = createEmployee("sav.myemp@test.com", "sav.myemp");
        Currency eur = ensureCurrency("EUR", "Euro", "E", "EU");
        Account source = createAccount("311000000000000001", client, emp, eur, new BigDecimal("5000.00"));
        Account linked = createAccount("311000000000000002", client, emp, eur, new BigDecimal("100.00"));
        Company bank = ensureBank();
        createBankAccount("222000100000000011", bank, emp, eur, new BigDecimal("1000000.00"));
        seedRate(eur, 6, new BigDecimal("2.00"));
        User user = createAuthUserForClient(client);
        String token = jwtService.generateAccessToken(user);

        // Otvori jedan depozit kroz REST da bi se realno perzistirao.
        String payload = """
                {
                  "sourceAccountId": %d,
                  "linkedAccountId": %d,
                  "principalAmount": 500.00,
                  "termMonths": 6,
                  "autoRenew": true,
                  "otpCode": "123456"
                }
                """.formatted(source.getId(), linked.getId());
        ResponseEntity<String> open = restTemplate.postForEntity(
                url("/savings/deposits"), new HttpEntity<>(payload, jsonHeaders(token)), String.class);
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/savings/deposits/my"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(token)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("termMonths").asInt()).isEqualTo(6);
    }

    // ── GET /admin/savings/deposits (ADMIN/SUPERVISOR) ───────────────────────

    @Test
    @DisplayName("GET /admin/savings/deposits — 200 za ADMIN")
    void adminListDeposits_okForAdmin() throws Exception {
        User admin = createAdminUser("sav.admin@test.com");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/savings/deposits"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(response.getBody()).path("content");
        assertThat(content.isArray()).isTrue();
    }

    @Test
    @DisplayName("GET /admin/savings/deposits — 200 za SUPERVISOR zaposlenog")
    void adminListDeposits_okForSupervisor() {
        Employee supervisor = createSupervisor("sav.sup@test.com", "sav.sup");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/savings/deposits"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(supervisor))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /admin/savings/deposits — 403 za CLIENT")
    void adminListDeposits_forbiddenForClient() {
        Client client = createClient("sav.cl@test.com");
        User user = createAuthUserForClient(client);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/savings/deposits"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(user))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── TEST-savings-7: GET /admin/savings/deposits filteri (status/clientId) + nevalidan status ──

    @Test
    @DisplayName("TEST-savings-7: GET /admin/savings/deposits?status=ACTIVE — 200, filter prolazi kao enum")
    void adminListDeposits_validStatusFilter_ok() throws Exception {
        User admin = createAdminUser("sav.filterstatus@test.com");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/savings/deposits?status=ACTIVE"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(response.getBody()).path("content");
        assertThat(content.isArray()).isTrue();
    }

    @Test
    @DisplayName("TEST-savings-7: GET /admin/savings/deposits?clientId=12345 — 200, clientId filter prolazi")
    void adminListDeposits_clientIdFilter_ok() throws Exception {
        User admin = createAdminUser("sav.filterclient@test.com");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/savings/deposits?clientId=12345"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Nepostojeci clientId -> prazna stranica (ne 500/404).
        JsonNode content = objectMapper.readTree(response.getBody()).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content).isEmpty();
    }

    @Test
    @DisplayName("TEST-savings-7: GET /admin/savings/deposits?status=NEPOSTOJI — 400 (nevalidan enum string)")
    void adminListDeposits_invalidStatus_returnsBadRequest() {
        User admin = createAdminUser("sav.filterbad@test.com");

        // SavingsDepositStatus.valueOf("NEPOSTOJI") -> IllegalArgumentException -> 400.
        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/savings/deposits?status=NEPOSTOJI"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── POST /admin/savings/rates (ADMIN) ────────────────────────────────────

    @Test
    @DisplayName("POST /admin/savings/rates — 200 upsert nove stope za ADMIN")
    void upsertRate_okForAdmin() throws Exception {
        User admin = createAdminUser("sav.rateadmin@test.com");
        ensureCurrency("RSD", "Dinar", "din", "RS");

        String payload = """
                {
                  "currencyCode": "RSD",
                  "termMonths": 12,
                  "annualRate": 4.25
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/admin/savings/rates"),
                new HttpEntity<>(payload, jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("currencyCode").asText()).isEqualTo("RSD");
        assertThat(body.path("termMonths").asInt()).isEqualTo(12);
        assertThat(body.path("annualRate").decimalValue()).isEqualByComparingTo("4.25");
        assertThat(body.path("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("POST /admin/savings/rates — 400 za nevalidan termMonths (van skupa)")
    void upsertRate_invalidTerm_returnsBadRequest() {
        User admin = createAdminUser("sav.ratebad@test.com");
        ensureCurrency("RSD", "Dinar", "din", "RS");

        // term=7 prolazi @Min(3)/@Max(36) ali @AssertTrue isValidTermMonths pada -> 400.
        String payload = """
                {
                  "currencyCode": "RSD",
                  "termMonths": 7,
                  "annualRate": 4.25
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/admin/savings/rates"),
                new HttpEntity<>(payload, jsonHeaders(jwtService.generateAccessToken(admin))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /admin/savings/rates — 403 za CLIENT")
    void upsertRate_forbiddenForClient() {
        Client client = createClient("sav.ratecl@test.com");
        User user = createAuthUserForClient(client);

        String payload = """
                {
                  "currencyCode": "RSD",
                  "termMonths": 12,
                  "annualRate": 4.25
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/admin/savings/rates"),
                new HttpEntity<>(payload, jsonHeaders(jwtService.generateAccessToken(user))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /admin/savings/rates — 403 bez tokena")
    void listRates_unauthenticated() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/admin/savings/rates"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void seedRate(Currency currency, int termMonths, BigDecimal annualRate) {
        rateRepository.save(SavingsInterestRate.builder()
                .currency(currency)
                .termMonths(termMonths)
                .annualRate(annualRate)
                .active(true)
                .effectiveFrom(LocalDate.now())
                .build());
    }

    private Client createClient(String email) {
        Client c = new Client();
        c.setFirstName("Test"); c.setLastName("User");
        c.setDateOfBirth(LocalDate.of(1995, 1, 1));
        c.setGender("M"); c.setEmail(email);
        c.setPhone("+381600000001"); c.setAddress("Test Address");
        c.setPassword("x"); c.setSaltPassword("salt"); c.setActive(true);
        return clientRepository.save(c);
    }

    private User createAuthUserForClient(Client client) {
        User user = new User();
        user.setFirstName(client.getFirstName());
        user.setLastName(client.getLastName());
        user.setEmail(client.getEmail());
        user.setPassword(client.getPassword());
        user.setActive(true);
        user.setRole("CLIENT");
        return userRepository.save(user);
    }

    private User createAdminUser(String email) {
        User user = new User();
        user.setFirstName("Admin"); user.setLastName("Test");
        user.setEmail(email); user.setPassword("x");
        user.setActive(true); user.setRole("ADMIN");
        return userRepository.save(user);
    }

    private Employee createEmployee(String email, String username) {
        return employeeRepository.save(Employee.builder()
                .firstName("Emp").lastName("Test")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M").email(email).phone("+381600000000")
                .address("Test").username(username).password("x").saltPassword("salt")
                .position("QA").department("IT").active(true).permissions(Set.of("VIEW_STOCKS"))
                .build());
    }

    private Employee createSupervisor(String email, String username) {
        return employeeRepository.save(Employee.builder()
                .firstName("Sup").lastName("Test")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M").email(email).phone("+381600000000")
                .address("Test").username(username).password("x").saltPassword("salt")
                .position("Supervizor").department("IT").active(true).permissions(Set.of("SUPERVISOR"))
                .build());
    }

    private Currency ensureCurrency(String code, String name, String symbol, String country) {
        List<Long> ids = jdbcTemplate.query(
                "select id from currencies where code = ?", (rs, rowNum) -> rs.getLong(1), code);
        Long id;
        if (ids.isEmpty()) {
            jdbcTemplate.update("insert into currencies(code, name, symbol, country, description, active) values (?, ?, ?, ?, ?, ?)",
                    code, name, symbol, country, "test", true);
            id = jdbcTemplate.queryForObject("select id from currencies where code = ?", Long.class, code);
        } else {
            id = ids.get(0);
        }
        return entityManager.getReference(Currency.class, id);
    }

    private Account createAccount(String accountNumber, Client owner, Employee employee, Currency currency, BigDecimal balance) {
        return accountRepository.save(Account.builder()
                .accountNumber(accountNumber).accountType(AccountType.CHECKING)
                .currency(currency).client(owner).employee(employee)
                .status(AccountStatus.ACTIVE).balance(balance).availableBalance(balance)
                .dailyLimit(new BigDecimal("50000.00")).monthlyLimit(new BigDecimal("200000.00"))
                .dailySpending(BigDecimal.ZERO).monthlySpending(BigDecimal.ZERO)
                .build());
    }

    /** Bankin pravni subjekt sa bank.registration-number (22200022 u test profilu). */
    private Company ensureBank() {
        return companyRepository.save(Company.builder()
                .name("Banka 2025 Tim 2").registrationNumber("22200022")
                .taxNumber("222000222").activityCode("64.19").address("Test")
                .build());
    }

    private Account createBankAccount(String accountNumber, Company company, Employee employee, Currency currency, BigDecimal balance) {
        return accountRepository.save(Account.builder()
                .accountNumber(accountNumber).accountType(AccountType.BUSINESS)
                .currency(currency).company(company).employee(employee)
                .status(AccountStatus.ACTIVE).balance(balance).availableBalance(balance)
                .dailyLimit(new BigDecimal("999999999.00")).monthlyLimit(new BigDecimal("999999999.00"))
                .dailySpending(BigDecimal.ZERO).monthlySpending(BigDecimal.ZERO)
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
