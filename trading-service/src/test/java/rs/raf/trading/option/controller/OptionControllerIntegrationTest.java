package rs.raf.trading.option.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.option.model.Option;
import rs.raf.trading.option.model.OptionType;
import rs.raf.trading.option.repository.OptionRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * HTTP integracioni test {@link OptionController} — pun Spring kontekst (H2 test
 * profil), RANDOM_PORT, realan security filter chain (JWT validacija) + realan
 * {@code OptionService} + JPA persistencija.
 *
 * <p>NAPOMENA (copy-first ekstrakcija, faza 2d-C): monolitni test je pravio
 * prave {@code Employee}/{@code Account} zapise i koristio {@code JwtService}.
 * trading-service NEMA login endpoint ni {@code Employee}/{@code Account}
 * tabele — JWT se generise lokalno deljenim test secret-om (HS256, isto kao
 * banka-core), {@link BankaCoreClient} je {@code @MockitoBean} (razresava
 * aktuara + daje bankin USD racun + izvodi novcanu nogu exercise-a),
 * {@code ActuaryInfo} se seeduje direktno preko repozitorijuma sa soft
 * {@code employeeId}-evima, a {@code Listing}/{@code Option} preko repozitorijuma.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OptionControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long AGENT_ID = 4101L;
    private static final Long ADMIN_ID = 4102L;
    private static final Long PLAIN_EMPLOYEE_ID = 4103L;
    private static final Long INACTIVE_AGENT_ID = 4104L;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OptionRepository optionRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private ActuaryInfoRepository actuaryInfoRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

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
        optionRepository.deleteAll();
        listingRepository.deleteAll();
        actuaryInfoRepository.deleteAll();

        actuaryInfoRepository.save(createActuaryInfo(AGENT_ID, ActuaryType.AGENT));
        actuaryInfoRepository.save(createActuaryInfo(INACTIVE_AGENT_ID, ActuaryType.AGENT));

        // Bankin USD racun — banka-core ga daje preko getBankTradingAccount.
        lenient().when(bankaCoreClient.getBankTradingAccount("USD")).thenReturn(
                new InternalAccountDto(99L, "333000000000000001", "Banka 2025",
                        new BigDecimal("100000000.0000"), new BigDecimal("100000000.0000"),
                        BigDecimal.ZERO, "USD", "ACTIVE", null, null, "BANK_TRADING"));
    }

    // ── exercise: identitet razresava banka-core mock ────────────────────────

    @Test
    void exerciseOption_returnsOkAndDecrementsOpenInterest_forAgentActuary() {
        mockBankaCoreUser("agent@test.com", AGENT_ID, true, List.of("AGENT"));

        Listing listing = createListing("AAPL", new BigDecimal("210.00"));
        Option option = createOption(listing, OptionType.CALL, new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 4);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/" + option.getId() + "/exercise"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Opcija uspesno izvrsena");

        Option updated = optionRepository.findById(option.getId()).orElseThrow();
        assertThat(updated.getOpenInterest()).isEqualTo(3);
    }

    @Test
    void exerciseOption_returnsOk_forAdminEmployeeWithoutActuaryInfo() {
        // ADMIN preko permisije — bez ActuaryInfo reda.
        mockBankaCoreUser("admin@test.com", ADMIN_ID, true, List.of("ADMIN"));

        Listing listing = createListing("MSFT", new BigDecimal("200.00"));
        Option option = createOption(listing, OptionType.CALL, new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 2);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/" + option.getId() + "/exercise"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Option updated = optionRepository.findById(option.getId()).orElseThrow();
        assertThat(updated.getOpenInterest()).isEqualTo(1);
    }

    @Test
    void exerciseOption_returnsForbidden_whenMissingJwt() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/999/exercise"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(null)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void exerciseOption_returnsForbidden_forAuthenticatedNonActuaryEmployee() {
        // Zaposleni bez ADMIN permisije i bez ActuaryInfo reda.
        mockBankaCoreUser("plain@test.com", PLAIN_EMPLOYEE_ID, true, List.of("VIEW_STOCKS"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/999/exercise"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(buildToken("plain@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void exerciseOption_returnsForbidden_forClient_deniedAtMethodGuardBeforeService() {
        // P2-authz-method-1 (R1 462) — RED pre fix-a: exercise ruta je bila samo
        // authenticated() (svaki ulogovan, ukljucujuci KLIJENTA, je ulazio u servis
        // koji onda baca AccessDenied). Sad @PreAuthorize(hasRole ADMIN/EMPLOYEE)
        // odbija klijenta PRE poziva servisa (defense-in-depth: identitet se ni ne
        // razresava). Opcije su bankarski instrumenti — klijent ih nikad ne exercise-uje.
        Listing listing = createListing("AAPL", new BigDecimal("210.00"));
        Option option = createOption(listing, OptionType.CALL, new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 4);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/" + option.getId() + "/exercise"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(buildToken("client@test.com", "CLIENT"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Servis NIJE ni pozvan — openInterest nepromenjen, identitet nije razresavan.
        Option unchanged = optionRepository.findById(option.getId()).orElseThrow();
        assertThat(unchanged.getOpenInterest()).isEqualTo(4);
        org.mockito.Mockito.verify(bankaCoreClient, org.mockito.Mockito.never())
                .getUserByEmail("client@test.com");
    }

    @Test
    void exerciseOption_returnsForbidden_forInactiveActuaryEmployee() {
        mockBankaCoreUser("inactive.agent@test.com", INACTIVE_AGENT_ID, false, List.of("AGENT"));

        Listing listing = createListing("NVDA", new BigDecimal("500.00"));
        Option option = createOption(listing, OptionType.CALL, new BigDecimal("450.00"),
                LocalDate.now().plusDays(5), 3);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/" + option.getId() + "/exercise"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(buildToken("inactive.agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("aktivan aktuar");
    }

    @Test
    void exerciseOption_returnsNotFound_whenOptionMissing_forAuthorizedAgent() {
        mockBankaCoreUser("agent@test.com", AGENT_ID, true, List.of("AGENT"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/999/exercise"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Option id: 999 not found.");
    }

    @Test
    void exerciseOption_returnsBadRequest_whenOptionExpired() {
        mockBankaCoreUser("agent@test.com", AGENT_ID, true, List.of("AGENT"));

        Listing listing = createListing("TSLA", new BigDecimal("260.00"));
        Option option = createOption(listing, OptionType.CALL, new BigDecimal("200.00"),
                LocalDate.now().minusDays(1), 3);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/" + option.getId() + "/exercise"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("istekla");
    }

    @Test
    void exerciseOption_otmCall_allowedPerSpec() {
        // [BE-STK-02] Spec: kupac opcije ima PRAVO da je iskoristi i OTM. Ranija
        // hard-blokada zamenjena warning log-om; endpoint dalje moze pasti zbog
        // drugih biznis razloga (npr. nedovoljno sredstava na bankinom racunu),
        // ali NE zbog ITM gate-a. Test verifikuje da response NIJE 400 zbog ITM:
        // ili 200 (uspeh) ili 409/500 zbog drugog razloga, samo NE "in-the-money".
        mockBankaCoreUser("agent@test.com", AGENT_ID, true, List.of("AGENT"));

        Listing listing = createListing("META", new BigDecimal("150.00"));
        Option option = createOption(listing, OptionType.CALL, new BigDecimal("180.00"),
                LocalDate.now().plusDays(5), 3);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/" + option.getId() + "/exercise"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class
        );

        // ITM gate vise ne odbija OTM exercise — body NE sme da sadrzi "in-the-money".
        assertThat(response.getBody()).doesNotContain("in-the-money");
    }

    // ── GET option chain / details ───────────────────────────────────────────

    @Test
    @DisplayName("GET /options?stockListingId — 200 sa option chain-om")
    void getOptionsForStock_returnsChain() {
        Listing listing = createListing("AAPL", new BigDecimal("150.00"));
        createOption(listing, OptionType.CALL, new BigDecimal("145.00"),
                LocalDate.now().plusDays(10), 5);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options?stockListingId=" + listing.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("currentStockPrice");
    }

    @Test
    @DisplayName("OT-1097: GET /options?stockListingId — 200 za KLIJENTA (Sc10/72 — klijent vidi option chain)")
    void getOptionsForStock_returnsChain_forClient() {
        // Sc10/72: klijent (sa permisijom za trgovinu) sme da vidi option chain.
        // Ruta je authenticated() (bez @PreAuthorize na role) — CLIENT JWT prolazi.
        // Permisije se razresavaju rezilijentno; stub-ujemo praznu listu da filter
        // (buildAuthorities) ne NPE-uje nad null permisijama.
        lenient().when(bankaCoreClient.getUserPermissions("client@test.com")).thenReturn(List.of());

        Listing listing = createListing("AAPL", new BigDecimal("150.00"));
        createOption(listing, OptionType.CALL, new BigDecimal("145.00"),
                LocalDate.now().plusDays(10), 5);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options?stockListingId=" + listing.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client@test.com", "CLIENT"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("currentStockPrice");
    }

    @Test
    @DisplayName("OT-1097: GET /options/{id} — 200 za KLIJENTA (detalji opcije dostupni klijentu)")
    void getOptionById_returnsDetails_forClient() {
        lenient().when(bankaCoreClient.getUserPermissions("client@test.com")).thenReturn(List.of());

        Listing listing = createListing("MSFT", new BigDecimal("200.00"));
        Option option = createOption(listing, OptionType.CALL, new BigDecimal("180.00"),
                LocalDate.now().plusDays(10), 5);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/" + option.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client@test.com", "CLIENT"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("strikePrice");
    }

    @Test
    @DisplayName("OT-1097: GET /options bez JWT → 403 (anonimni nema pristup option chain-u)")
    void getOptionsForStock_forbidden_whenMissingJwt() {
        Listing listing = createListing("NVDA", new BigDecimal("500.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/options?stockListingId=" + listing.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /options/{id} — 404 kad opcija ne postoji")
    void getOptionById_notFound() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/999999"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("not found");
    }

    // ── POST /options/generate (@PreAuthorize ADMIN) ─────────────────────────

    @Test
    @DisplayName("POST /options/generate — 403 za EMPLOYEE (nije ADMIN)")
    void generateOptions_forbiddenForEmployee() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/generate"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /options/generate — 200 za ADMIN (prazan listing skup)")
    void generateOptions_okForAdmin() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/options/generate"),
                HttpMethod.POST,
                new HttpEntity<>(jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("uspesno generisane");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void mockBankaCoreUser(String email, Long userId, boolean active, List<String> permissions) {
        when(bankaCoreClient.getUserByEmail(email)).thenReturn(
                new InternalUserDto(userId, "EMPLOYEE", email, "Test", "Employee", active, "Agent"));
        lenient().when(bankaCoreClient.getUserPermissions(email)).thenReturn(permissions);
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

    private ActuaryInfo createActuaryInfo(Long employeeId, ActuaryType type) {
        ActuaryInfo info = new ActuaryInfo();
        info.setEmployeeId(employeeId);
        info.setActuaryType(type);
        info.setDailyLimit(new BigDecimal("1000000.00"));
        info.setUsedLimit(BigDecimal.ZERO);
        info.setNeedApproval(false);
        return info;
    }

    private Listing createListing(String ticker, BigDecimal price) {
        Listing listing = new Listing();
        listing.setTicker(ticker);
        listing.setName(ticker + " Inc.");
        listing.setExchangeAcronym("NASDAQ");
        listing.setListingType(ListingType.STOCK);
        listing.setPrice(price);
        listing.setAsk(price.add(new BigDecimal("1.00")));
        listing.setBid(price.subtract(new BigDecimal("1.00")));
        listing.setVolume(1000L);
        listing.setPriceChange(new BigDecimal("0.50"));
        listing.setLastRefresh(LocalDateTime.now());
        listing.setOutstandingShares(1_000_000L);
        listing.setDividendYield(new BigDecimal("0.0100"));
        listing.setContractSize(1);
        return listingRepository.save(listing);
    }

    private Option createOption(Listing listing,
                                OptionType optionType,
                                BigDecimal strikePrice,
                                LocalDate settlementDate,
                                int openInterest) {
        Option option = new Option();
        option.setStockListing(listing);
        option.setOptionType(optionType);
        option.setStrikePrice(strikePrice);
        option.setImpliedVolatility(0.25d);
        option.setOpenInterest(openInterest);
        option.setSettlementDate(settlementDate);
        option.setContractSize(100);
        option.setPrice(new BigDecimal("10.0000"));
        option.setAsk(new BigDecimal("10.5000"));
        option.setBid(new BigDecimal("9.5000"));
        option.setVolume(100L);
        option.setTicker(
                listing.getTicker()
                        + settlementDate.toString().replace("-", "")
                        + optionType.name().charAt(0)
                        + strikePrice.movePointRight(3).toBigInteger().toString()
        );
        return optionRepository.save(option);
    }
}
