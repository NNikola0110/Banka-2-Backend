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
import rs.raf.banka2_bek.account.model.AccountCategory;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.company.model.Company;
import rs.raf.banka2_bek.company.repository.CompanyRepository;
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

    @Value("${state.registration-number}")
    private String stateRegistrationNumber;

    @Value("${local.server.port}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

    @Autowired private AccountRepository accountRepository;
    @Autowired private ClientRepository clientRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private CompanyRepository companyRepository;
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

    // ─── Credit: happy path bez provizije ────────────────────────────────────

    @Test
    void credit_noCommission_returns200AndCreditsAccount() throws Exception {
        Account account = persistAccount("222000000000000010", "RSD", new BigDecimal("1000.00"));
        String idempotencyKey = "it-credit-001";

        String body = """
                { "accountId": %d, "amount": 750.00, "commission": 0,
                  "currencyCode": "RSD", "description": "SELL prihod" }
                """.formatted(account.getId());

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/credit"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("accountId").asLong()).isEqualTo(account.getId());
        assertThat(new BigDecimal(json.path("creditedAmount").asText()))
                .isEqualByComparingTo("750.00");
        assertThat(new BigDecimal(json.path("balanceAfter").asText()))
                .isEqualByComparingTo("1750.00");

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("1750.00");
        assertThat(reloaded.getAvailableBalance()).isEqualByComparingTo("1750.00");
        assertThat(internalRequestRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
    }

    // ─── Credit: sa provizijom — banka kreditovana ───────────────────────────

    @Test
    void credit_withCommission_creditsAccountAndBankTradingAccount() throws Exception {
        Account account = persistAccount("222000000000000011", "RSD", new BigDecimal("1000.00"));
        Account bankAccount = persistBankTradingAccount("RSD", new BigDecimal("100000.00"));
        String idempotencyKey = "it-credit-comm-001";

        String body = """
                { "accountId": %d, "amount": 1000.00, "commission": 25.00,
                  "currencyCode": "RSD", "description": "SELL prihod" }
                """.formatted(account.getId());

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/credit"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Account reloadedAccount = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloadedAccount.getBalance()).isEqualByComparingTo("2000.00");
        Account reloadedBank = accountRepository.findById(bankAccount.getId()).orElseThrow();
        assertThat(reloadedBank.getBalance()).isEqualByComparingTo("100025.00");
        assertThat(reloadedBank.getAvailableBalance()).isEqualByComparingTo("100025.00");
    }

    // ─── Credit: idempotency replay ──────────────────────────────────────────

    @Test
    void credit_repeatedIdempotencyKey_appliesOnlyOnce() throws Exception {
        Account account = persistAccount("222000000000000012", "RSD", new BigDecimal("1000.00"));
        String idempotencyKey = "it-credit-idem-001";

        String body = """
                { "accountId": %d, "amount": 200.00, "commission": 0,
                  "currencyCode": "RSD", "description": "dividenda" }
                """.formatted(account.getId());

        restTemplate.postForEntity(url("/internal/funds/credit"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)), String.class);
        restTemplate.postForEntity(url("/internal/funds/credit"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)), String.class);

        // Drugi poziv je idempotentan — balans uvecan SAMO jednom (1000 + 200)
        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("1200.00");
    }

    // ─── Credit: missing X-Idempotency-Key → 400 ─────────────────────────────

    @Test
    void credit_missingIdempotencyKey_returns400() throws Exception {
        String body = """
                { "accountId": 1, "amount": 100.00, "commission": 0,
                  "currencyCode": "RSD", "description": "x" }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", internalKey);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/credit"),
                new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    // ─── Debit: happy path bez provizije ─────────────────────────────────────

    @Test
    void debit_noCommission_returns200AndDebitsAccount() throws Exception {
        Account account = persistAccount("222000000000000040", "RSD", new BigDecimal("5000.00"));
        String idempotencyKey = "it-debit-001";

        String body = """
                { "accountId": %d, "amount": 650.00, "commission": 0,
                  "currencyCode": "RSD", "description": "Option exercise CALL" }
                """.formatted(account.getId());

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/debit"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("accountId").asLong()).isEqualTo(account.getId());
        assertThat(new BigDecimal(json.path("debitedAmount").asText()))
                .isEqualByComparingTo("650.00");
        assertThat(new BigDecimal(json.path("balanceAfter").asText()))
                .isEqualByComparingTo("4350.00");

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("4350.00");
        assertThat(reloaded.getAvailableBalance()).isEqualByComparingTo("4350.00");
        assertThat(internalRequestRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
    }

    // ─── Debit: sa provizijom — banka kreditovana ────────────────────────────

    @Test
    void debit_withCommission_debitsAccountAndCreditsBankTradingAccount() throws Exception {
        Account account = persistAccount("222000000000000041", "RSD", new BigDecimal("5000.00"));
        Account bankAccount = persistBankTradingAccount("RSD", new BigDecimal("100000.00"));
        String idempotencyKey = "it-debit-comm-001";

        String body = """
                { "accountId": %d, "amount": 1000.00, "commission": 30.00,
                  "currencyCode": "RSD", "description": "Margin uplata" }
                """.formatted(account.getId());

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/debit"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Account reloadedAccount = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloadedAccount.getBalance()).isEqualByComparingTo("4000.00");
        Account reloadedBank = accountRepository.findById(bankAccount.getId()).orElseThrow();
        assertThat(reloadedBank.getBalance()).isEqualByComparingTo("100030.00");
        assertThat(reloadedBank.getAvailableBalance()).isEqualByComparingTo("100030.00");
    }

    // ─── Debit: nedovoljno sredstava → 409 ───────────────────────────────────

    @Test
    void debit_insufficientFunds_returns409() throws Exception {
        Account account = persistAccount("222000000000000042", "RSD", new BigDecimal("100.00"));
        String idempotencyKey = "it-debit-insufficient-001";

        String body = """
                { "accountId": %d, "amount": 99999.00, "commission": 0,
                  "currencyCode": "RSD", "description": "Option exercise" }
                """.formatted(account.getId());

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/debit"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Nista nije skinuto
        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("100.00");
        assertThat(reloaded.getAvailableBalance()).isEqualByComparingTo("100.00");
    }

    // ─── Debit: idempotency replay ───────────────────────────────────────────

    @Test
    void debit_repeatedIdempotencyKey_appliesOnlyOnce() throws Exception {
        Account account = persistAccount("222000000000000043", "RSD", new BigDecimal("5000.00"));
        String idempotencyKey = "it-debit-idem-001";

        String body = """
                { "accountId": %d, "amount": 200.00, "commission": 0,
                  "currencyCode": "RSD", "description": "Option exercise CALL" }
                """.formatted(account.getId());

        restTemplate.postForEntity(url("/internal/funds/debit"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)), String.class);
        restTemplate.postForEntity(url("/internal/funds/debit"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)), String.class);

        // Drugi poziv je idempotentan — balans umanjen SAMO jednom (5000 - 200)
        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("4800.00");
    }

    // ─── Debit: missing X-Idempotency-Key → 400 ──────────────────────────────

    @Test
    void debit_missingIdempotencyKey_returns400() throws Exception {
        String body = """
                { "accountId": 1, "amount": 100.00, "commission": 0,
                  "currencyCode": "RSD", "description": "x" }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", internalKey);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/debit"),
                new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    // ─── Tax-collect: happy path — klijent debitovan, drzava kreditovana ─────

    @Test
    void taxCollect_happyPath_debitsClientAndCreditsState() throws Exception {
        Account stateAccount = persistStateAccount(new BigDecimal("0.00"));
        Account clientAccount = persistAccount("222000000000000020", "RSD", new BigDecimal("5000.00"));
        Long clientId = clientAccount.getClient().getId();
        String idempotencyKey = "it-tax-001";

        String body = """
                { "payerClientId": %d, "amount": 300.00, "description": "Porez 2026-05" }
                """.formatted(clientId);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/tax-collect"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("collected").asBoolean()).isTrue();
        assertThat(new BigDecimal(json.path("collectedAmount").asText()))
                .isEqualByComparingTo("300.00");

        Account reloadedClient = accountRepository.findById(clientAccount.getId()).orElseThrow();
        assertThat(reloadedClient.getBalance()).isEqualByComparingTo("4700.00");
        Account reloadedState = accountRepository.findById(stateAccount.getId()).orElseThrow();
        assertThat(reloadedState.getBalance()).isEqualByComparingTo("300.00");
    }

    // ─── Tax-collect: nedovoljno sredstava → collected=false ─────────────────

    @Test
    void taxCollect_insufficientFunds_returns200WithCollectedFalse() throws Exception {
        Account stateAccount = persistStateAccount(new BigDecimal("0.00"));
        Account clientAccount = persistAccount("222000000000000021", "RSD", new BigDecimal("100.00"));
        Long clientId = clientAccount.getClient().getId();
        String idempotencyKey = "it-tax-insufficient-001";

        String body = """
                { "payerClientId": %d, "amount": 300.00, "description": "Porez" }
                """.formatted(clientId);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/tax-collect"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);

        // Verno monolitu: NE puca, vraca 200 sa collected=false
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("collected").asBoolean()).isFalse();
        assertThat(new BigDecimal(json.path("collectedAmount").asText()))
                .isEqualByComparingTo("0");

        // Nista nije skinuto
        Account reloadedClient = accountRepository.findById(clientAccount.getId()).orElseThrow();
        assertThat(reloadedClient.getBalance()).isEqualByComparingTo("100.00");
        Account reloadedState = accountRepository.findById(stateAccount.getId()).orElseThrow();
        assertThat(reloadedState.getBalance()).isEqualByComparingTo("0.00");
    }

    // ─── Tax-collect: idempotency replay ─────────────────────────────────────

    @Test
    void taxCollect_repeatedIdempotencyKey_collectsOnlyOnce() throws Exception {
        Account stateAccount = persistStateAccount(new BigDecimal("0.00"));
        Account clientAccount = persistAccount("222000000000000022", "RSD", new BigDecimal("5000.00"));
        Long clientId = clientAccount.getClient().getId();
        String idempotencyKey = "it-tax-idem-001";

        String body = """
                { "payerClientId": %d, "amount": 250.00, "description": "Porez" }
                """.formatted(clientId);

        restTemplate.postForEntity(url("/internal/funds/tax-collect"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)), String.class);
        restTemplate.postForEntity(url("/internal/funds/tax-collect"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)), String.class);

        // Naplaceno SAMO jednom: klijent 5000-250, drzava 0+250
        Account reloadedClient = accountRepository.findById(clientAccount.getId()).orElseThrow();
        assertThat(reloadedClient.getBalance()).isEqualByComparingTo("4750.00");
        Account reloadedState = accountRepository.findById(stateAccount.getId()).orElseThrow();
        assertThat(reloadedState.getBalance()).isEqualByComparingTo("250.00");
    }

    // ─── Transfer: cross-currency — from u EUR, to u RSD, provizija u EUR ─────

    @Test
    void transfer_crossCurrency_debitsFromEurCreditsRsdAndCreditsBankCommission() throws Exception {
        // Fond uplata sa EUR racuna: from gubi debitAmount u EUR, fond dobija
        // creditAmount u RSD, banka dobija proviziju u EUR. banka-core debituje/
        // kreditira svaki racun u NJEGOVOJ sopstvenoj valuti.
        Account eurFrom = persistAccount("311000000000000001", "EUR", new BigDecimal("5000.00"));
        Account rsdTo = persistAccount("222000000000000031", "RSD", new BigDecimal("0.00"));
        Account eurBank = persistBankTradingAccount("EUR", new BigDecimal("100000.00"));
        String idempotencyKey = "it-transfer-xccy-001";

        String body = """
                { "fromAccountId": %d, "debitAmount": 1015.00,
                  "toAccountId": %d, "creditAmount": 119000.00,
                  "commission": 15.00, "commissionCurrency": "EUR",
                  "description": "Uplata u fond" }
                """.formatted(eurFrom.getId(), rsdTo.getId());

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/transfer"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("fromAccountId").asLong()).isEqualTo(eurFrom.getId());
        assertThat(json.path("toAccountId").asLong()).isEqualTo(rsdTo.getId());

        // from-noga: EUR racun gubi 1015 EUR
        Account reloadedFrom = accountRepository.findById(eurFrom.getId()).orElseThrow();
        assertThat(reloadedFrom.getBalance()).isEqualByComparingTo("3985.00");
        assertThat(reloadedFrom.getAvailableBalance()).isEqualByComparingTo("3985.00");
        // to-noga: RSD racun dobija 119000 RSD
        Account reloadedTo = accountRepository.findById(rsdTo.getId()).orElseThrow();
        assertThat(reloadedTo.getBalance()).isEqualByComparingTo("119000.00");
        assertThat(reloadedTo.getAvailableBalance()).isEqualByComparingTo("119000.00");
        // banka dobija proviziju u EUR
        Account reloadedBank = accountRepository.findById(eurBank.getId()).orElseThrow();
        assertThat(reloadedBank.getBalance()).isEqualByComparingTo("100015.00");
        assertThat(reloadedBank.getAvailableBalance()).isEqualByComparingTo("100015.00");

        assertThat(internalRequestRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
    }

    // ─── Transfer: nedovoljno sredstava na from-nozi → 409 ───────────────────

    @Test
    void transfer_insufficientFunds_returns409() throws Exception {
        Account from = persistAccount("222000000000000032", "RSD", new BigDecimal("100.00"));
        Account to = persistAccount("222000000000000033", "RSD", new BigDecimal("0.00"));
        String idempotencyKey = "it-transfer-insufficient-001";

        String body = """
                { "fromAccountId": %d, "debitAmount": 99999.00,
                  "toAccountId": %d, "creditAmount": 99999.00,
                  "commission": 0, "commissionCurrency": "RSD",
                  "description": "preveliki prenos" }
                """.formatted(from.getId(), to.getId());

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/transfer"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Nista nije pomereno
        Account reloadedFrom = accountRepository.findById(from.getId()).orElseThrow();
        assertThat(reloadedFrom.getBalance()).isEqualByComparingTo("100.00");
        Account reloadedTo = accountRepository.findById(to.getId()).orElseThrow();
        assertThat(reloadedTo.getBalance()).isEqualByComparingTo("0.00");
    }

    // ─── N4: idempotency-status read-back (consumed?) ────────────────────────

    /**
     * <b>N4 (P0-T2):</b> posle uspesnog idempotentnog poziva (credit), GET
     * /internal/funds/idempotency/{key} mora vratiti {@code consumed=true} — autoritativan
     * read-back banka-core dedup store-a koji OTC SAGA recovery koristi za C3 odluku.
     */
    @Test
    void idempotencyStatus_afterConsumed_returnsTrue() throws Exception {
        Account account = persistAccount("222000000000000050", "RSD", new BigDecimal("1000.00"));
        String idempotencyKey = "it-idemstatus-consumed-001";
        String body = """
                { "accountId": %d, "amount": 100.00, "commission": 0,
                  "currencyCode": "RSD", "description": "credit za idem-status test" }
                """.formatted(account.getId());
        ResponseEntity<String> credit = restTemplate.postForEntity(
                url("/internal/funds/credit"),
                new HttpEntity<>(body, internalHeaders(idempotencyKey)), String.class);
        assertThat(credit.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/funds/idempotency/" + idempotencyKey),
                HttpMethod.GET, new HttpEntity<>(internalGetHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
        assertThat(json.path("consumed").asBoolean()).isTrue();
    }

    /**
     * <b>N4 (P0-T2):</b> za NEPOZNAT (nekonzumiran) kljuc GET vraca {@code consumed=false}
     * BEZ izvrsavanja ikakve operacije — recovery time bira commit-only refund (prodavac NETAKNUT).
     */
    @Test
    void idempotencyStatus_unknownKey_returnsFalse() throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/funds/idempotency/it-idemstatus-unknown-999"),
                HttpMethod.GET, new HttpEntity<>(internalGetHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("idempotencyKey").asText()).isEqualTo("it-idemstatus-unknown-999");
        assertThat(json.path("consumed").asBoolean()).isFalse();
    }

    /**
     * <b>N4 (P0-T2):</b> idempotency-status endpoint je INTERNI (X-Internal-Key zasticen) —
     * bez kljuca vraca 401 (InternalAuthFilter pokriva i nove /internal/funds/** GET rute).
     */
    @Test
    void idempotencyStatus_missingInternalKey_returns401() {
        HttpHeaders headers = new HttpHeaders();
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/funds/idempotency/whatever"),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private HttpHeaders internalGetHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", internalKey);
        return headers;
    }

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

    /**
     * Persistuje bankin BANK_TRADING racun (company-owned) u datoj valuti.
     * creditBankCommission ga razresava preko (P0-B3) PESSIMISTIC_WRITE
     * findByAccountCategoryAndCurrencyCodeForUpdate(BANK_TRADING, ...).
     */
    private Account persistBankTradingAccount(String currencyCode, BigDecimal balance) {
        long currencyId = findOrCreateCurrency(currencyCode);
        Company bank = companyRepository.save(buildCompany(
                "Banka 2", "22200022", "TAX22200022", false, true));
        Employee employee = persistEmployee("bank-trading-" + currencyCode);
        return accountRepository.save(Account.builder()
                .accountNumber("2220009000000000" + Math.abs(currencyCode.hashCode() % 100))
                .accountType(AccountType.CHECKING)
                .accountCategory(AccountCategory.BANK_TRADING)
                .currency(persistCurrencyEntity(currencyCode, currencyId))
                .company(bank)
                .employee(employee)
                .status(AccountStatus.ACTIVE)
                .balance(balance)
                .availableBalance(balance)
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(BigDecimal.ZERO)
                .monthlyLimit(BigDecimal.ZERO)
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build());
    }

    /**
     * Persistuje drzavni RSD racun — Republika Srbija je Firma sa
     * registracionim brojem {@code state.registration-number}.
     * collectTax ga razresava preko findBankAccountForUpdateByCurrency.
     */
    private Account persistStateAccount(BigDecimal balance) {
        long currencyId = findOrCreateCurrency("RSD");
        Company state = companyRepository.save(buildCompany(
                "Republika Srbija", stateRegistrationNumber,
                "TAX" + stateRegistrationNumber, true, false));
        Employee employee = persistEmployee("state-rsd");
        return accountRepository.save(Account.builder()
                .accountNumber("178000000000000001")
                .accountType(AccountType.CHECKING)
                .accountCategory(AccountCategory.CLIENT)
                .currency(persistCurrencyEntity("RSD", currencyId))
                .company(state)
                .employee(employee)
                .status(AccountStatus.ACTIVE)
                .balance(balance)
                .availableBalance(balance)
                .reservedAmount(BigDecimal.ZERO)
                .dailyLimit(BigDecimal.ZERO)
                .monthlyLimit(BigDecimal.ZERO)
                .dailySpending(BigDecimal.ZERO)
                .monthlySpending(BigDecimal.ZERO)
                .build());
    }

    private Company buildCompany(String name, String regNumber, String taxNumber,
                                 boolean isState, boolean isBank) {
        Company c = new Company();
        c.setName(name);
        c.setRegistrationNumber(regNumber);
        c.setTaxNumber(taxNumber);
        c.setActivityCode("6419");
        c.setAddress("Test Address");
        c.setActive(true);
        c.setIsState(isState);
        c.setIsBank(isBank);
        return c;
    }

    private Employee persistEmployee(String suffix) {
        return employeeRepository.save(Employee.builder()
                .firstName("Internal").lastName("Test")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M")
                .email("internal-emp-" + suffix + "@test.com")
                .phone("+381600000000")
                .address("Test")
                .username("internal-emp-" + suffix)
                .password("x")
                .saltPassword("salt")
                .position("QA")
                .department("IT")
                .active(true)
                .permissions(Set.of())
                .build());
    }
}
