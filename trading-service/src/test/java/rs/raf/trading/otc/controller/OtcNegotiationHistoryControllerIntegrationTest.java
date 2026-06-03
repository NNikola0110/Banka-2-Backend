package rs.raf.trading.otc.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.otc.model.OtcNegotiationHistory;
import rs.raf.trading.otc.model.OtcOffer;
import rs.raf.trading.otc.model.OtcOfferStatus;
import rs.raf.trading.otc.repository.OtcNegotiationHistoryRepository;
import rs.raf.trading.otc.repository.OtcOfferRepository;
import rs.raf.trading.security.TradingUserResolver;
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

import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * HTTP integracioni test {@link OtcNegotiationHistoryController} — pun Spring kontekst
 * (H2 test profil), RANDOM_PORT, realan {@code TradingSecurityConfig} filter chain +
 * realan {@link rs.raf.trading.otc.service.OtcNegotiationHistoryService} + JPA.
 *
 * <p>Rutni guard {@code /otc/**} dozvoljava
 * {@code ROLE_ADMIN/ROLE_CLIENT/ADMIN/SUPERVISOR/CLIENT}. Paginiran pregled dodatno
 * radi {@code ensureSupervisorOrAdmin()} u service-u (AccessDenied -> 403) — pa CLIENT
 * prolazi rutni guard ali pada na servisnu autorizaciju kod {@code GET /otc/negotiation-history}.
 * Po-pregovor ruta {@code /{id}} je dostupna svim {@code /otc/**} ulogama.
 *
 * <p>SUPERVISOR autoritet dolazi iz {@code getUserPermissions} (JWT role=EMPLOYEE +
 * SUPERVISOR permisija). Obican AGENT (samo {@code ROLE_EMPLOYEE} + {@code AGENT}) NE
 * match-uje {@code /otc/**} matcher -> 403 na rutnom sloju.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OtcNegotiationHistoryControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    @Value("${local.server.port}")
    private int port;

    @Autowired private OtcNegotiationHistoryRepository historyRepository;
    @Autowired private OtcOfferRepository offerRepository;
    @Autowired private ListingRepository listingRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    // P1-authz-idor-1 (R1 208): IDOR guard razresava ucesnika preko TradingUserResolver.
    @MockitoBean
    private TradingUserResolver tradingUserResolver;

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

    private static final Long PARTICIPANT_CLIENT_ID = 7001L;
    private static final Long OTHER_CLIENT_ID = 7002L;

    @BeforeEach
    void setUp() {
        historyRepository.deleteAll();
        offerRepository.deleteAll();
        listingRepository.deleteAll();
        lenient().when(bankaCoreClient.getUserPermissions("sup.otc@test.com"))
                .thenReturn(List.of("SUPERVISOR"));
        lenient().when(bankaCoreClient.getUserPermissions("agent.otc@test.com"))
                .thenReturn(List.of("AGENT"));
        // Default identitet za CLIENT poziv: ucesnik (buyer) pregovora.
        lenient().when(tradingUserResolver.resolveCurrent())
                .thenReturn(new UserContext(PARTICIPANT_CLIENT_ID, "CLIENT"));
        lenient().when(bankaCoreClient.getUserByEmail("client.otc@test.com"))
                .thenReturn(new InternalUserDto(PARTICIPANT_CLIENT_ID, "CLIENT",
                        "client.otc@test.com", "Cli", "Ent", true, null));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // Cisti seed-ovane ponude/listinge da ne procure u druge IT-ove u istom
        // shared H2 contextu (FK otc_offers→listings). Offers PRE listings.
        historyRepository.deleteAll();
        offerRepository.deleteAll();
        listingRepository.deleteAll();
    }

    /** Seed-uje ponudu (negotiationId == offer.id) gde je dati klijent kupac. */
    private OtcOffer seedOfferWithBuyer(Long buyerId) {
        Listing listing = new Listing();
        listing.setTicker("AAPL");
        listing.setName("Apple Inc.");
        listing.setListingType(ListingType.STOCK);
        listing.setQuoteCurrency("USD");
        listing.setExchangeAcronym("NASDAQ");
        listing.setPrice(BigDecimal.valueOf(150));
        listing.setLastRefresh(LocalDateTime.now());
        listing = listingRepository.save(listing);

        OtcOffer offer = new OtcOffer();
        offer.setBuyerId(buyerId);
        offer.setBuyerRole("CLIENT");
        offer.setSellerId(OTHER_CLIENT_ID);
        offer.setSellerRole("CLIENT");
        offer.setListing(listing);
        offer.setQuantity(10);
        offer.setPricePerStock(new BigDecimal("12.5000"));
        offer.setPremium(new BigDecimal("1.0000"));
        offer.setSettlementDate(LocalDate.now().plusDays(5));
        offer.setLastModifiedById(buyerId);
        offer.setLastModifiedByName("Cli Ent");
        offer.setWaitingOnUserId(OTHER_CLIENT_ID);
        offer.setStatus(OtcOfferStatus.ACTIVE);
        return offerRepository.save(offer);
    }

    // ── GET /otc/negotiation-history/{id} (po pregovoru) ─────────────────────

    @Test
    @DisplayName("GET /otc/negotiation-history/{id} — 200 vraca istoriju za UCESNIKA (buyer CLIENT)")
    void getHistoryForNegotiation_okForClient() throws Exception {
        // P1-authz-idor-1 (R1 208): klijent koji JESTE ucesnik (buyer) sme da cita.
        OtcOffer offer = seedOfferWithBuyer(PARTICIPANT_CLIENT_ID);
        Long negotiationId = offer.getId();
        historyRepository.save(historyEntry(negotiationId, "COUNTER_OFFERED"));
        historyRepository.save(historyEntry(negotiationId, "ACCEPTED"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/negotiation-history/" + negotiationId),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.otc@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.get(0).path("negotiationId").asLong()).isEqualTo(negotiationId);
    }

    @Test
    @DisplayName("GET /otc/negotiation-history/{id} — 403 za CLIENT koji NIJE ucesnik (IDOR)")
    void getHistoryForNegotiation_forbiddenForNonParticipantClient() {
        // Ponuda pripada drugim klijentima; ulogovan klijent (PARTICIPANT_CLIENT_ID
        // — default resolver) NIJE buyer ni seller -> 403.
        Listing listing = new Listing();
        listing.setTicker("MSFT");
        listing.setName("Microsoft");
        listing.setListingType(ListingType.STOCK);
        listing.setQuoteCurrency("USD");
        listing.setExchangeAcronym("NASDAQ");
        listing.setPrice(BigDecimal.valueOf(300));
        listing.setLastRefresh(LocalDateTime.now());
        listing = listingRepository.save(listing);

        OtcOffer offer = new OtcOffer();
        offer.setBuyerId(8888L);
        offer.setBuyerRole("CLIENT");
        offer.setSellerId(9999L);
        offer.setSellerRole("CLIENT");
        offer.setListing(listing);
        offer.setQuantity(5);
        offer.setPricePerStock(new BigDecimal("10.0000"));
        offer.setPremium(new BigDecimal("1.0000"));
        offer.setSettlementDate(LocalDate.now().plusDays(5));
        offer.setLastModifiedById(8888L);
        offer.setLastModifiedByName("Other");
        offer.setWaitingOnUserId(9999L);
        offer.setStatus(OtcOfferStatus.ACTIVE);
        offer = offerRepository.save(offer);

        historyRepository.save(historyEntry(offer.getId(), "COUNTER_OFFERED"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/negotiation-history/" + offer.getId()),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.otc@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /otc/negotiation-history/{id} — 400 kad pregovor ne postoji")
    void getHistoryForNegotiation_notFound_returnsBadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/negotiation-history/99999"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.otc@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Pregovor nije pronadjen");
    }

    @Test
    @DisplayName("GET /otc/negotiation-history/{id} — 403 za obicnog AGENT-a (nije /otc/** uloga)")
    void getHistoryForNegotiation_forbiddenForAgent() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/negotiation-history/1"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("agent.otc@test.com", "EMPLOYEE"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── GET /otc/negotiation-history (paginiran, ADMIN/SUPERVISOR) ───────────

    @Test
    @DisplayName("GET /otc/negotiation-history — 200 za SUPERVISOR (paginiran)")
    void getHistory_okForSupervisor() throws Exception {
        historyRepository.save(historyEntry(7L, "PROPOSED"));

        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/negotiation-history"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("sup.otc@test.com", "EMPLOYEE"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(response.getBody()).path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /otc/negotiation-history — 200 za ADMIN")
    void getHistory_okForAdmin() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/negotiation-history"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /otc/negotiation-history — 403 za CLIENT (rutni guard prosao, service odbija)")
    void getHistory_forbiddenForClient() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/negotiation-history"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.otc@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("supervizorima i adminima");
    }

    @Test
    @DisplayName("GET /otc/negotiation-history?from=bad — 400 za nevalidan ISO datum-vreme")
    void getHistory_invalidFrom_returnsBadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/negotiation-history?from=not-a-datetime"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("admin@test.com", "ADMIN"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("nije validan ISO-8601");
    }

    @Test
    @DisplayName("GET /otc/negotiation-history — 403 bez JWT-a")
    void getHistory_missingJwt_returnsForbidden() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/otc/negotiation-history"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private OtcNegotiationHistory historyEntry(Long negotiationId, String status) {
        return OtcNegotiationHistory.builder()
                .negotiationId(negotiationId)
                .quantity(100)
                .pricePerShare(new BigDecimal("12.5000"))
                .premium(new BigDecimal("1.0000"))
                .settlementDate(LocalDate.now().plusDays(5))
                .status(status)
                .modifiedById(123L)
                .modifiedByName("Test User")
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
