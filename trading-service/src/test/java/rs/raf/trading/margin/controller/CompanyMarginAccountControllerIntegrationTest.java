package rs.raf.trading.margin.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
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
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsResponse;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.margin.model.CompanyMarginAccount;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.repository.MarginAccountRepository;
import rs.raf.trading.margin.repository.MarginTransactionRepository;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * HTTP integracioni test {@link MarginAccountController} — COMPANY (BE-STK-06)
 * rute. Pun Spring kontekst (H2 test profil), RANDOM_PORT, realan security filter
 * chain (JWT validacija + employee-only autorizacija) + realan
 * {@code MarginAccountService} + JPA persistencija.
 *
 * <p>Mirror {@link MarginAccountControllerIntegrationTest}: {@link BankaCoreClient}
 * je {@code @MockitoBean} (razresava identitet preko {@code getUserByEmail}, daje
 * bazni racun preko {@code getAccount}, debituje preko {@code debitFunds}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CompanyMarginAccountControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long CLIENT_ID = 6101L;
    private static final Long EMPLOYEE_ID = 6103L;
    private static final Long COMPANY_ID = 9001L;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private MarginAccountRepository marginAccountRepository;

    @Autowired
    private MarginTransactionRepository marginTransactionRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RestTemplate restTemplate = createRestTemplate();
    private final SecretKey secretKey =
            Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

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
        marginTransactionRepository.deleteAll();
        marginAccountRepository.deleteAll();

        lenient().when(bankaCoreClient.getUserByEmail("client@test.com")).thenReturn(
                new InternalUserDto(CLIENT_ID, "CLIENT", "client@test.com", "Client", "User", true, null));
        lenient().when(bankaCoreClient.getUserByEmail("company.margin.emp@test.com")).thenReturn(
                new InternalUserDto(EMPLOYEE_ID, "EMPLOYEE", "company.margin.emp@test.com", "Emp", "Loyee", true, "Supervisor"));
        lenient().when(bankaCoreClient.getUserByEmail("company.margin.agent@test.com")).thenReturn(
                new InternalUserDto(EMPLOYEE_ID, "EMPLOYEE", "company.margin.agent@test.com", "Ag", "Ent", true, "Agent"));
        // P2-authz-method-1 (R1 468/467): company-margin endpointi sad zahtevaju
        // SUPERVISOR/ADMIN authority koju TradingPermissionResolver razresava preko
        // banka-core getUserPermissions. employee@test.com je supervizor → dobija
        // SUPERVISOR; agent@test.com je agent → samo AGENT (NE sme company margin).
        lenient().when(bankaCoreClient.getUserPermissions("company.margin.emp@test.com"))
                .thenReturn(java.util.List.of("SUPERVISOR"));
        lenient().when(bankaCoreClient.getUserPermissions("company.margin.agent@test.com"))
                .thenReturn(java.util.List.of("AGENT"));
        // OT-1272: dedikovan, JEDINSTVEN email za deposit/withdraw nad company
        // racunom (CLIENT prolazi security gate pa userId resolve VAZI). Generican
        // "client@test.com" deli Caffeine kes TradingUserResolver-a izmedju test
        // klasa (vidi WatchlistControllerIntegrationTest:100-103) i pokupio bi
        // kesirani UserContext iz MarginAccountControllerIntegrationTest (drugi ID).
        lenient().when(bankaCoreClient.getUserByEmail("company.dw.client@test.com")).thenReturn(
                new InternalUserDto(7777L, "CLIENT", "company.dw.client@test.com", "Comp", "Client", true, null));
    }

    // ── POST /margin-accounts/company ────────────────────────────────────────

    @Test
    void createCompanyMarginAccount_returnsOK_andPersistsCompanyAccount() throws Exception {
        when(bankaCoreClient.getAccount(8801L)).thenReturn(
                companyAccount(8801L, "ACTIVE", "880000000000008801", "20000.00", "20000.00"));
        when(bankaCoreClient.debitFunds(any(), any(DebitFundsRequest.class)))
                .thenReturn(new DebitFundsResponse(8801L, new BigDecimal("12000.00"),
                        new BigDecimal("12000.00")));

        String payload = """
                {
                  "accountId": 8801,
                  "companyId": %d,
                  "initialMargin": 8000.00,
                  "maintenanceMargin": 4000.00,
                  "bankParticipation": 0.60
                }
                """.formatted(COMPANY_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/margin-accounts/company"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("company.margin.emp@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("id").asLong()).isPositive();
        assertThat(body.path("companyId").asLong()).isEqualTo(COMPANY_ID);
        assertThat(body.path("userId").isNull()).isTrue();
        assertThat(body.path("accountNumber").asText()).isEqualTo("880000000000008801");
        assertThat(body.path("initialMargin").decimalValue()).isEqualByComparingTo("8000.0000");
        assertThat(body.path("maintenanceMargin").decimalValue()).isEqualByComparingTo("4000.0000");
        assertThat(body.path("bankParticipation").decimalValue()).isEqualByComparingTo("0.60");
        assertThat(body.path("loanValue").decimalValue()).isEqualByComparingTo("0.0000");
        assertThat(body.path("status").asText()).isEqualTo("ACTIVE");

        assertThat(marginAccountRepository.count()).isEqualTo(1L);
        MarginAccount saved = marginAccountRepository.findAll().get(0);
        assertThat(saved).isInstanceOf(CompanyMarginAccount.class);
        assertThat(((CompanyMarginAccount) saved).getCompanyId()).isEqualTo(COMPANY_ID);
        assertThat(saved.getOwnerType()).isEqualTo("COMPANY");
        assertThat(marginTransactionRepository.count()).isEqualTo(1L);
    }

    @Test
    void createCompanyMarginAccount_returnsForbidden_forClient() {
        String payload = """
                {
                  "accountId": 8802,
                  "companyId": %d,
                  "initialMargin": 8000.00,
                  "maintenanceMargin": 4000.00,
                  "bankParticipation": 0.60
                }
                """.formatted(COMPANY_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/margin-accounts/company"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("client@test.com", "CLIENT"))),
                String.class
        );

        // Security chain gejtuje na zaposlene → CLIENT dobija 403 pre servisa.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(marginAccountRepository.count()).isZero();
    }

    @Test
    void createCompanyMarginAccount_returnsForbidden_forAgent() {
        // P2-authz-method-1 (R1 468/467) — RED pre fix-a: agent (EMPLOYEE rola,
        // ali samo AGENT permisija) je prolazio HTTP matcher (hasAnyRole EMPLOYEE)
        // i servisni requireEmployee (EMPLOYEE.equals) → kreirao company margin.
        // Sad servisni guard zahteva SUPERVISOR/ADMIN → 403.
        when(bankaCoreClient.getAccount(8830L)).thenReturn(
                companyAccount(8830L, "ACTIVE", "880000000000008830", "20000.00", "20000.00"));

        String payload = """
                {
                  "accountId": 8830,
                  "companyId": %d,
                  "initialMargin": 8000.00,
                  "maintenanceMargin": 4000.00,
                  "bankParticipation": 0.60
                }
                """.formatted(COMPANY_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/margin-accounts/company"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("company.margin.agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(marginAccountRepository.count()).isZero();
    }

    @Test
    void createCompanyMarginAccount_returnsForbidden_whenMissingJwt() {
        String payload = """
                {
                  "accountId": 8803,
                  "companyId": %d,
                  "initialMargin": 8000.00,
                  "maintenanceMargin": 4000.00,
                  "bankParticipation": 0.60
                }
                """.formatted(COMPANY_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/margin-accounts/company"),
                new HttpEntity<>(payload, jsonHeaders(null)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createCompanyMarginAccount_returnsBadRequest_whenCompanyAlreadyHasAccount() {
        marginAccountRepository.save(companyMarginAccount(COMPANY_ID, 8810L, "880000000000008810"));

        when(bankaCoreClient.getAccount(8811L)).thenReturn(
                companyAccount(8811L, "ACTIVE", "880000000000008811", "20000.00", "20000.00"));

        String payload = """
                {
                  "accountId": 8811,
                  "companyId": %d,
                  "initialMargin": 8000.00,
                  "maintenanceMargin": 4000.00,
                  "bankParticipation": 0.60
                }
                """.formatted(COMPANY_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/margin-accounts/company"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("company.margin.emp@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("already exists for this company");
        // Niko nije debitovan; i dalje samo jedan racun.
        assertThat(marginAccountRepository.count()).isEqualTo(1L);
    }

    @Test
    void createCompanyMarginAccount_returnsBadRequest_whenMaintenanceExceedsInitial() {
        when(bankaCoreClient.getAccount(8812L)).thenReturn(
                companyAccount(8812L, "ACTIVE", "880000000000008812", "20000.00", "20000.00"));

        String payload = """
                {
                  "accountId": 8812,
                  "companyId": %d,
                  "initialMargin": 4000.00,
                  "maintenanceMargin": 5000.00,
                  "bankParticipation": 0.60
                }
                """.formatted(COMPANY_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/margin-accounts/company"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("company.margin.emp@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("MaintenanceMargin must not exceed InitialMargin");
        assertThat(marginAccountRepository.count()).isZero();
    }

    @Test
    void createCompanyMarginAccount_returnsBadRequest_whenBankParticipationOutOfRange() {
        when(bankaCoreClient.getAccount(8813L)).thenReturn(
                companyAccount(8813L, "ACTIVE", "880000000000008813", "20000.00", "20000.00"));

        String payload = """
                {
                  "accountId": 8813,
                  "companyId": %d,
                  "initialMargin": 8000.00,
                  "maintenanceMargin": 4000.00,
                  "bankParticipation": 1.50
                }
                """.formatted(COMPANY_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/margin-accounts/company"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("company.margin.emp@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("BankParticipation must be strictly between 0 and 1");
        assertThat(marginAccountRepository.count()).isZero();
    }

    @Test
    void createCompanyMarginAccount_returnsBadRequest_whenBankaCoreDebitReturns409() {
        when(bankaCoreClient.getAccount(8814L)).thenReturn(
                companyAccount(8814L, "ACTIVE", "880000000000008814", "20000.00", "20000.00"));
        when(bankaCoreClient.debitFunds(any(), any(DebitFundsRequest.class)))
                .thenThrow(new BankaCoreClientException(409, "insufficient funds"));

        String payload = """
                {
                  "accountId": 8814,
                  "companyId": %d,
                  "initialMargin": 8000.00,
                  "maintenanceMargin": 4000.00,
                  "bankParticipation": 0.60
                }
                """.formatted(COMPANY_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/margin-accounts/company"),
                new HttpEntity<>(payload, jsonHeaders(buildToken("company.margin.emp@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Insufficient available balance");
        assertThat(marginAccountRepository.count()).isZero();
    }

    // ── GET /margin-accounts/company/{companyId} ─────────────────────────────

    @Test
    void getCompanyMarginAccount_returnsOK_whenExists() throws Exception {
        marginAccountRepository.save(companyMarginAccount(COMPANY_ID, 8820L, "880000000000008820"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/margin-accounts/company/" + COMPANY_ID),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("company.margin.emp@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("companyId").asLong()).isEqualTo(COMPANY_ID);
        assertThat(body.path("userId").isNull()).isTrue();
        assertThat(body.path("accountNumber").asText()).isEqualTo("880000000000008820");
    }

    @Test
    void getCompanyMarginAccount_returnsNotFound_whenAbsent() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/margin-accounts/company/" + COMPANY_ID),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("company.margin.emp@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains(String.valueOf(COMPANY_ID));
    }

    @Test
    void getCompanyMarginAccount_returnsForbidden_forClient() {
        marginAccountRepository.save(companyMarginAccount(COMPANY_ID, 8821L, "880000000000008821"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/margin-accounts/company/" + COMPANY_ID),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client@test.com", "CLIENT"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getCompanyMarginAccount_returnsForbidden_forAgent() {
        // P2-authz-method-1 (R1 468/467) — citanje company margin isto zahteva
        // SUPERVISOR/ADMIN; agent (samo AGENT permisija) → 403.
        marginAccountRepository.save(companyMarginAccount(COMPANY_ID, 8822L, "880000000000008822"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/margin-accounts/company/" + COMPANY_ID),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("company.margin.agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getCompanyMarginAccount_returnsForbidden_whenMissingJwt() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/margin-accounts/company/" + COMPANY_ID),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── deposit/withdraw nad COMPANY margin racunom (OT-1272) ────────────────
    //    Dokumentuje da deposit/withdraw rute (`/{id}/deposit|withdraw`) sluze
    //    SAMO klijent-vlasnicke (userId) margin racune. COMPANY racun ima
    //    userId == null (vlasnistvo preko companyId), pa owner-check
    //    `clientId.equals(account.getUserId())` NIKAD ne prolazi → 403 sa
    //    {message} "can deposit/withdraw funds". Company se finansira pri
    //    kreiranju (createForCompany), ne kroz ove rute (vidi CLAUDE.md:
    //    "company-margin trading u order engine NIJE wired").

    @Test
    void deposit_onCompanyMarginAccount_returnsForbidden_ownerMismatchMessage_OT_1272() {
        // Company margin racun (userId == null). Klijent ga ne poseduje.
        marginAccountRepository.save(companyMarginAccount(COMPANY_ID, 8840L, "880000000000008840"));
        MarginAccount company = marginAccountRepository.findAll().get(0);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/margin-accounts/" + company.getId() + "/deposit"),
                new HttpEntity<>("{\"amount\": 1000.00}", jsonHeaders(buildToken("company.dw.client@test.com", "CLIENT"))),
                String.class);

        // IllegalStateException (owner mismatch) → 403 + {message} envelope.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("can deposit funds");
    }

    @Test
    void withdraw_onCompanyMarginAccount_returnsForbidden_ownerMismatchMessage_OT_1272() {
        marginAccountRepository.save(companyMarginAccount(COMPANY_ID, 8841L, "880000000000008841"));
        MarginAccount company = marginAccountRepository.findAll().get(0);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/margin-accounts/" + company.getId() + "/withdraw"),
                new HttpEntity<>("{\"amount\": 1000.00}", jsonHeaders(buildToken("company.dw.client@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("can withdraw funds");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private InternalAccountDto companyAccount(Long accountId, String status, String accountNumber,
                                              String available, String balance) {
        return new InternalAccountDto(accountId, accountNumber, "Company",
                new BigDecimal(balance), new BigDecimal(available),
                BigDecimal.ZERO, "RSD", status, null, null, "COMPANY");
    }

    private CompanyMarginAccount companyMarginAccount(Long companyId, Long accountId, String accountNumber) {
        return CompanyMarginAccount.builder()
                .accountId(accountId)
                .accountNumber(accountNumber)
                .companyId(companyId)
                .currency("RSD")
                .initialMargin(new BigDecimal("8000.0000"))
                .loanValue(BigDecimal.ZERO)
                .maintenanceMargin(new BigDecimal("4000.0000"))
                .bankParticipation(new BigDecimal("0.60"))
                .status(MarginAccountStatus.ACTIVE)
                .build();
    }

    private String buildToken(String email, String role) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + 3_600_000);
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .claim("active", true)
                .issuedAt(now)
                .expiration(exp)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }
}
