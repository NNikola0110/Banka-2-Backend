package rs.raf.banka2_bek.security;

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
import rs.raf.banka2_bek.card.model.Card;
import rs.raf.banka2_bek.card.model.CardStatus;
import rs.raf.banka2_bek.card.model.CardType;
import rs.raf.banka2_bek.card.repository.CardRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.loan.model.Loan;
import rs.raf.banka2_bek.loan.model.LoanStatus;
import rs.raf.banka2_bek.loan.model.LoanType;
import rs.raf.banka2_bek.loan.repository.LoanRepository;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.transfers.model.Transfer;
import rs.raf.banka2_bek.transfers.model.TransferType;
import rs.raf.banka2_bek.transfers.repository.TransferRepository;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-B9 — IDOR / broken-object-level-authorization regresioni testovi. Pokriva 4
 * nalaza (R1+R2 verifikovani) na HTTP sloju kroz pun Spring security chain (realan
 * {@link JwtService}, {@link rs.raf.banka2_bek.auth.config.JwtAuthenticationFilter},
 * {@link rs.raf.banka2_bek.auth.config.GlobalSecurityConfig}):
 *
 * <ul>
 *   <li>N1 — accounts: POST /accounts (samo employee/admin) + PATCH /accounts/{id}/status
 *       (samo employee/admin; klijent ne sme menjati status tudjeg/svog racuna).</li>
 *   <li>N2 — cards: GET /cards/account/{accountId} cureo je ime+saldi/limiti tudjeg
 *       racuna (sad ownership ili employee/admin).</li>
 *   <li>N3 — loans: GET /loans (svi) employee/admin only; GET /loans/{id} +
 *       /installments ownership (klijent samo svoj).</li>
 *   <li>N4 — transfers: GET /transfers/{id} ownership (klijent samo svoj).</li>
 * </ul>
 *
 * <p>Obrazac kopiran iz {@code LoanControllerIntegrationTest} / {@code
 * AuditLogControllerIntegrationTest}: RANDOM_PORT, H2 test profil,
 * {@code IntegrationTestCleanup} truncate pre svakog testa.</p>
 */
@Import(TestObjectMapperConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IdorAuthorizationIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CardRepository cardRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private TransferRepository transferRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManager entityManager;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private NotificationPublisher notificationPublisher;

    @BeforeEach
    void cleanDatabase() {
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) throws IOException { return false; }
        });
        IntegrationTestCleanup.truncateAllTables(dataSource);
    }

    // ───────────────────────── N1 — accounts ─────────────────────────

    @Test
    @DisplayName("N1 — POST /accounts kao CLIENT -> 403 (kreiranje racuna je employee-only)")
    void createAccount_forbiddenForClient() {
        Client client = createClient("n1.client@test.com");
        ensureCurrency("RSD", "Dinar", "RSD", "RS");
        User clientUser = createAuthUserForClient(client);

        String payload = """
                { "accountType": "CHECKING", "currency": "RSD",
                  "ownerEmail": "n1.client@test.com", "initialDeposit": 0 }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/accounts"),
                new HttpEntity<>(payload, jsonHeaders(jwtService.generateAccessToken(clientUser))),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT ne sme da kreira racun — mora 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    @DisplayName("N1 — POST /accounts kao EMPLOYEE -> 201 (legitiman put prolazi)")
    void createAccount_okForEmployee() throws Exception {
        Client owner = createClient("n1.owner@test.com");
        createAuthUserForClient(owner);
        ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Employee employee = createEmployee("n1.emp@test.com", "n1emp");

        String payload = """
                { "accountType": "CHECKING", "currency": "RSD",
                  "ownerEmail": "n1.owner@test.com", "initialDeposit": 100 }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/accounts"),
                new HttpEntity<>(payload, jsonHeaders(jwtService.generateAccessToken(employee))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("accountNumber").asText()).isNotEmpty();
        assertThat(accountRepository.count()).isEqualTo(1);
        // Kreator racuna je STVARNI autentifikovani zaposleni — ne silent-fallback.
        assertThat(body.path("createdByEmployee").asText()).contains(employee.getLastName());
    }

    @Test
    @DisplayName("N1 — PATCH /accounts/{id}/status kao CLIENT -> 403 (ne sme menjati status tudjeg racuna)")
    void changeAccountStatus_forbiddenForClient() {
        Client owner = createClient("n1.statusowner@test.com");
        Client attacker = createClient("n1.attacker@test.com");
        Employee employee = createEmployee("n1.empx@test.com", "n1empx");
        Currency rsd = ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Account victim = createAccount("111000000000000001", owner, employee, rsd, new BigDecimal("100.00"));
        User attackerUser = createAuthUserForClient(attacker);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/accounts/" + victim.getId() + "/status"),
                HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"BLOCKED\"}", jsonHeaders(jwtService.generateAccessToken(attackerUser))),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT ne sme da menja status racuna — mora 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        Account after = accountRepository.findById(victim.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("N1 — PATCH /accounts/{id}/status kao EMPLOYEE -> 200 (legitiman put prolazi)")
    void changeAccountStatus_okForEmployee() {
        Client owner = createClient("n1.statusok@test.com");
        Employee employee = createEmployee("n1.empy@test.com", "n1empy");
        Currency rsd = ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Account account = createAccount("111000000000000002", owner, employee, rsd, new BigDecimal("100.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/accounts/" + account.getId() + "/status"),
                HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"INACTIVE\"}", jsonHeaders(jwtService.generateAccessToken(employee))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Account after = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AccountStatus.INACTIVE);
    }

    // ───────────────────────── N2 — cards ─────────────────────────

    @Test
    @DisplayName("N2 — GET /cards/account/{tudji} kao CLIENT -> 403 (IDOR: curi tudje kartice)")
    void getCardsByAccount_forbiddenForForeignClient() {
        Client owner = createClient("n2.owner@test.com");
        Client attacker = createClient("n2.attacker@test.com");
        Employee employee = createEmployee("n2.emp@test.com", "n2emp");
        Currency rsd = ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Account victimAccount = createAccount("222000000000000001", owner, employee, rsd, new BigDecimal("100.00"));
        createCard(victimAccount, owner);
        User attackerUser = createAuthUserForClient(attacker);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/cards/account/" + victimAccount.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(attackerUser))),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT ne sme da cita kartice tudjeg racuna — mora 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("N2 — GET /cards/account/{svoj} kao vlasnik -> 200")
    void getCardsByAccount_okForOwner() throws Exception {
        Client owner = createClient("n2.owner2@test.com");
        Employee employee = createEmployee("n2.emp2@test.com", "n2emp2");
        Currency rsd = ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Account account = createAccount("222000000000000002", owner, employee, rsd, new BigDecimal("100.00"));
        createCard(account, owner);
        User ownerUser = createAuthUserForClient(owner);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/cards/account/" + account.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(ownerUser))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body).hasSize(1);
    }

    @Test
    @DisplayName("N2 — GET /cards/account/{tudji} kao EMPLOYEE -> 200 (employee portal)")
    void getCardsByAccount_okForEmployee() throws Exception {
        Client owner = createClient("n2.owner3@test.com");
        Employee employee = createEmployee("n2.emp3@test.com", "n2emp3");
        Currency rsd = ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Account account = createAccount("222000000000000003", owner, employee, rsd, new BigDecimal("100.00"));
        createCard(account, owner);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/cards/account/" + account.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(employee))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body).hasSize(1);
    }

    // ───────────────────────── N3 — loans ─────────────────────────

    @Test
    @DisplayName("N3 — GET /loans (svi) kao CLIENT -> 403 (employee portal, ne cure svi krediti)")
    void getAllLoans_forbiddenForClient() {
        Client client = createClient("n3.client@test.com");
        User clientUser = createAuthUserForClient(client);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/loans"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(clientUser))),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT ne sme da vidi sve kredite — mora 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("N3 — GET /loans/{tudji} kao CLIENT -> 403 (IDOR: tudji kredit)")
    void getLoanById_forbiddenForForeignClient() {
        Client owner = createClient("n3.owner@test.com");
        Client attacker = createClient("n3.attacker@test.com");
        Employee employee = createEmployee("n3.emp@test.com", "n3emp");
        Currency rsd = ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Account account = createAccount("333000000000000001", owner, employee, rsd, new BigDecimal("100.00"));
        Loan loan = createLoan(owner, account, rsd);
        User attackerUser = createAuthUserForClient(attacker);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/loans/" + loan.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(attackerUser))),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT ne sme da cita tudji kredit — mora 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("N3 — GET /loans/{svoj} kao vlasnik -> 200")
    void getLoanById_okForOwner() throws Exception {
        Client owner = createClient("n3.owner2@test.com");
        Employee employee = createEmployee("n3.emp2@test.com", "n3emp2");
        Currency rsd = ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Account account = createAccount("333000000000000002", owner, employee, rsd, new BigDecimal("100.00"));
        Loan loan = createLoan(owner, account, rsd);
        User ownerUser = createAuthUserForClient(owner);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/loans/" + loan.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(ownerUser))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("id").asLong()).isEqualTo(loan.getId());
    }

    @Test
    @DisplayName("N3 — GET /loans/{tudji}/installments kao CLIENT -> 403; vlasnik/EMPLOYEE -> 200")
    void getInstallments_ownershipEnforced() {
        Client owner = createClient("n3.iowner@test.com");
        Client attacker = createClient("n3.iattacker@test.com");
        Employee employee = createEmployee("n3.iemp@test.com", "n3iemp");
        Currency rsd = ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Account account = createAccount("333000000000000003", owner, employee, rsd, new BigDecimal("100.00"));
        Loan loan = createLoan(owner, account, rsd);
        User ownerUser = createAuthUserForClient(owner);
        User attackerUser = createAuthUserForClient(attacker);

        ResponseEntity<String> attackerResp = restTemplate.exchange(
                url("/loans/" + loan.getId() + "/installments"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(attackerUser))), String.class);
        assertThat(attackerResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> ownerResp = restTemplate.exchange(
                url("/loans/" + loan.getId() + "/installments"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(ownerUser))), String.class);
        assertThat(ownerResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> employeeResp = restTemplate.exchange(
                url("/loans/" + loan.getId() + "/installments"), HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(employee))), String.class);
        assertThat(employeeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ───────────────────────── N4 — transfers ─────────────────────────

    @Test
    @DisplayName("N4 — GET /transfers/{tudji} kao CLIENT -> 403 (IDOR: tudji transfer)")
    void getTransferById_forbiddenForForeignClient() {
        Client owner = createClient("n4.owner@test.com");
        Client attacker = createClient("n4.attacker@test.com");
        Employee employee = createEmployee("n4.emp@test.com", "n4emp");
        Currency rsd = ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Account from = createAccount("444000000000000001", owner, employee, rsd, new BigDecimal("100.00"));
        Account to = createAccount("444000000000000002", owner, employee, rsd, new BigDecimal("100.00"));
        Transfer transfer = createTransfer(owner, from, to, rsd);
        User attackerUser = createAuthUserForClient(attacker);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/transfers/" + transfer.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(attackerUser))),
                String.class);

        assertThat(response.getStatusCode())
                .as("CLIENT ne sme da cita tudji transfer — mora 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("N4 — GET /transfers/{svoj} kao vlasnik (kreator) -> 200")
    void getTransferById_okForOwner() throws Exception {
        Client owner = createClient("n4.owner2@test.com");
        Employee employee = createEmployee("n4.emp2@test.com", "n4emp2");
        Currency rsd = ensureCurrency("RSD", "Dinar", "RSD", "RS");
        Account from = createAccount("444000000000000003", owner, employee, rsd, new BigDecimal("100.00"));
        Account to = createAccount("444000000000000004", owner, employee, rsd, new BigDecimal("100.00"));
        Transfer transfer = createTransfer(owner, from, to, rsd);
        User ownerUser = createAuthUserForClient(owner);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/transfers/" + transfer.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(jwtService.generateAccessToken(ownerUser))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("id").asLong()).isEqualTo(transfer.getId());
    }

    // ───────────────────────── Helpers ─────────────────────────

    private HttpHeaders jsonHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) headers.setBearerAuth(bearerToken);
        return headers;
    }

    private String url(String path) { return "http://localhost:" + port + path; }

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
        return createAuthUser(client.getEmail(), "CLIENT");
    }

    private User createAuthUser(String email, String role) {
        User user = new User();
        user.setFirstName("Auth"); user.setLastName(role);
        user.setEmail(email); user.setPassword("x");
        user.setActive(true); user.setRole(role);
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

    private Card createCard(Account account, Client owner) {
        return cardRepository.save(Card.builder()
                .cardNumber("4222001234567890").cardName("Visa Debit").cvv("123")
                .account(account).client(owner).cardLimit(BigDecimal.valueOf(250000))
                .cardType(CardType.VISA).status(CardStatus.ACTIVE)
                .createdAt(LocalDate.now()).expirationDate(LocalDate.now().plusYears(4))
                .build());
    }

    private Loan createLoan(Client owner, Account account, Currency currency) {
        return loanRepository.save(Loan.builder()
                .loanNumber("LN-" + System.nanoTime())
                .loanType(LoanType.CASH)
                .interestType(rs.raf.banka2_bek.loan.model.InterestType.FIXED)
                .amount(new BigDecimal("100000.0000"))
                .repaymentPeriod(12)
                .nominalRate(new BigDecimal("5.00"))
                .effectiveRate(new BigDecimal("5.50"))
                .monthlyPayment(new BigDecimal("8500.0000"))
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusMonths(12))
                .remainingDebt(new BigDecimal("100000.0000"))
                .currency(currency)
                .status(LoanStatus.ACTIVE)
                .account(account)
                .client(owner)
                .build());
    }

    private Transfer createTransfer(Client creator, Account from, Account to, Currency currency) {
        return transferRepository.save(Transfer.builder()
                .orderNumber("TR-" + System.nanoTime())
                .fromAccount(from).toAccount(to)
                .fromAmount(new BigDecimal("50.00")).toAmount(new BigDecimal("50.00"))
                .fromCurrency(currency).toCurrency(currency)
                .commission(BigDecimal.ZERO)
                .transferType(TransferType.INTERNAL_TRANSFER)
                .status(PaymentStatus.COMPLETED)
                .createdBy(creator)
                .build());
    }
}
