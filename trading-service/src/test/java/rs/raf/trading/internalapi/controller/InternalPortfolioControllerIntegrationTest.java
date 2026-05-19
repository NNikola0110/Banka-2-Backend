package rs.raf.trading.internalapi.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.trading.internalapi.repository.InternalRequestRepository;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP integracioni test {@link InternalPortfolioController} — pun Spring kontekst
 * (H2 test profil, RANDOM_PORT), realan {@code InternalAuthFilter} + service + JPA.
 *
 * <p>Verifikuje X-Internal-Key zastitu, idempotency replay i reserve/commit/release
 * semantiku internog portfolio/stock seam-a (faza 2f).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InternalPortfolioControllerIntegrationTest {

    @Value("${internal.api-key}")
    private String internalKey;

    @Value("${local.server.port}")
    private int port;

    @Autowired private PortfolioRepository portfolioRepository;
    @Autowired private ListingRepository listingRepository;
    @Autowired private InternalRequestRepository internalRequestRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = createRestTemplate();

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
    void clean() {
        internalRequestRepository.deleteAll();
        portfolioRepository.deleteAll();
        listingRepository.deleteAll();
    }

    // ─── X-Internal-Key zastita ───────────────────────────────────────────────

    @Test
    @DisplayName("reserve-stock bez X-Internal-Key → 401")
    void reserveStock_missingInternalKey_returns401() {
        String body = "{ \"userId\": 1, \"userRole\": \"CLIENT\", \"ticker\": \"AAPL\", \"quantity\": 5 }";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Idempotency-Key", "it-no-key");

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/portfolio/reserve-stock"),
                new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("reserve-stock bez X-Idempotency-Key → 400 MISSING_IDEMPOTENCY_KEY")
    void reserveStock_missingIdempotencyKey_returns400() throws Exception {
        String body = "{ \"userId\": 1, \"userRole\": \"CLIENT\", \"ticker\": \"AAPL\", \"quantity\": 5 }";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", internalKey);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/portfolio/reserve-stock"),
                new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    // ─── reserve-stock happy path ─────────────────────────────────────────────

    @Test
    @DisplayName("reserve-stock happy path → 200, reservedQuantity povecan u bazi")
    void reserveStock_happyPath() throws Exception {
        Listing listing = persistListing("AAPL");
        persistPortfolio(42L, "CLIENT", listing, 20, 0);

        String body = "{ \"userId\": 42, \"userRole\": \"CLIENT\", \"ticker\": \"AAPL\", \"quantity\": 8 }";

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/portfolio/reserve-stock"),
                new HttpEntity<>(body, internalHeaders("it-reserve-1")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("reservedQuantity").asInt()).isEqualTo(8);
        assertThat(json.path("availableQuantity").asInt()).isEqualTo(12);

        Portfolio reloaded = portfolioRepository
                .findByUserIdAndUserRoleAndListingId(42L, "CLIENT", listing.getId()).orElseThrow();
        assertThat(reloaded.getReservedQuantity()).isEqualTo(8);
        assertThat(internalRequestRepository.findByIdempotencyKey("it-reserve-1")).isPresent();
    }

    @Test
    @DisplayName("reserve-stock: nedovoljna kolicina → 409 CONFLICT")
    void reserveStock_insufficient_returns409() {
        Listing listing = persistListing("AAPL");
        persistPortfolio(42L, "CLIENT", listing, 5, 0);

        String body = "{ \"userId\": 42, \"userRole\": \"CLIENT\", \"ticker\": \"AAPL\", \"quantity\": 10 }";

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/portfolio/reserve-stock"),
                new HttpEntity<>(body, internalHeaders("it-reserve-insufficient")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("reserve-stock: listing ne postoji → 404 NOT_FOUND")
    void reserveStock_listingMissing_returns404() {
        String body = "{ \"userId\": 42, \"userRole\": \"CLIENT\", \"ticker\": \"ZZZZ\", \"quantity\": 5 }";

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/portfolio/reserve-stock"),
                new HttpEntity<>(body, internalHeaders("it-reserve-no-listing")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── idempotency replay ───────────────────────────────────────────────────

    @Test
    @DisplayName("reserve-stock: ponovljen idempotency kljuc → kesiran odgovor, rezervacija primenjena jednom")
    void reserveStock_repeatedKey_idempotent() throws Exception {
        Listing listing = persistListing("AAPL");
        persistPortfolio(42L, "CLIENT", listing, 20, 0);

        String body = "{ \"userId\": 42, \"userRole\": \"CLIENT\", \"ticker\": \"AAPL\", \"quantity\": 6 }";

        ResponseEntity<String> first = restTemplate.postForEntity(
                url("/internal/portfolio/reserve-stock"),
                new HttpEntity<>(body, internalHeaders("it-reserve-idem")), String.class);
        ResponseEntity<String> second = restTemplate.postForEntity(
                url("/internal/portfolio/reserve-stock"),
                new HttpEntity<>(body, internalHeaders("it-reserve-idem")), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(second.getBody()))
                .isEqualTo(objectMapper.readTree(first.getBody()));

        // Rezervacija primenjena SAMO jednom (6, ne 12)
        Portfolio reloaded = portfolioRepository
                .findByUserIdAndUserRoleAndListingId(42L, "CLIENT", listing.getId()).orElseThrow();
        assertThat(reloaded.getReservedQuantity()).isEqualTo(6);
    }

    // ─── commit-stock ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("commit-stock debit=true, portfolio ne postoji → kreira nov portfolio")
    void commitStock_debit_createsPortfolio() throws Exception {
        Listing listing = persistListing("MSFT");

        String body = "{ \"userId\": 99, \"userRole\": \"CLIENT\", \"ticker\": \"MSFT\","
                + " \"quantity\": 12, \"debit\": true }";

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/portfolio/commit-stock"),
                new HttpEntity<>(body, internalHeaders("it-commit-debit")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Portfolio created = portfolioRepository
                .findByUserIdAndUserRoleAndListingId(99L, "CLIENT", listing.getId()).orElseThrow();
        assertThat(created.getQuantity()).isEqualTo(12);
        assertThat(created.getReservedQuantity()).isZero();
    }

    @Test
    @DisplayName("commit-stock debit=false → quantity i reservedQuantity smanjeni")
    void commitStock_credit_decrements() throws Exception {
        Listing listing = persistListing("AAPL");
        persistPortfolio(42L, "CLIENT", listing, 20, 10);

        String body = "{ \"userId\": 42, \"userRole\": \"CLIENT\", \"ticker\": \"AAPL\","
                + " \"quantity\": 10, \"debit\": false }";

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/portfolio/commit-stock"),
                new HttpEntity<>(body, internalHeaders("it-commit-credit")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Portfolio reloaded = portfolioRepository
                .findByUserIdAndUserRoleAndListingId(42L, "CLIENT", listing.getId()).orElseThrow();
        assertThat(reloaded.getQuantity()).isEqualTo(10);
        assertThat(reloaded.getReservedQuantity()).isZero();
    }

    // ─── release-stock ────────────────────────────────────────────────────────

    @Test
    @DisplayName("release-stock → reservedQuantity smanjen, clamp na 0")
    void releaseStock_clampsToZero() throws Exception {
        Listing listing = persistListing("AAPL");
        persistPortfolio(42L, "CLIENT", listing, 20, 5);

        String body = "{ \"userId\": 42, \"userRole\": \"CLIENT\", \"ticker\": \"AAPL\", \"quantity\": 10 }";

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/portfolio/release-stock"),
                new HttpEntity<>(body, internalHeaders("it-release-1")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Portfolio reloaded = portfolioRepository
                .findByUserIdAndUserRoleAndListingId(42L, "CLIENT", listing.getId()).orElseThrow();
        assertThat(reloaded.getReservedQuantity()).isZero();
    }

    // ─── read-side ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /listing → 200 InternalListingDto; nepostojeci ticker → 404")
    void getListing() throws Exception {
        persistListing("AAPL");

        ResponseEntity<String> ok = restTemplate.exchange(
                url("/internal/portfolio/listing?ticker=AAPL"),
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(getHeaders()), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(ok.getBody()).path("ticker").asText()).isEqualTo("AAPL");

        ResponseEntity<String> notFound = restTemplate.exchange(
                url("/internal/portfolio/listing?ticker=ZZZZ"),
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(getHeaders()), String.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /holding → exists=true sa availableQuantity")
    void getHolding() throws Exception {
        Listing listing = persistListing("AAPL");
        persistPortfolio(42L, "CLIENT", listing, 20, 5);

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/portfolio/holding?userId=42&userRole=CLIENT&ticker=AAPL"),
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(getHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("exists").asBoolean()).isTrue();
        assertThat(json.path("availableQuantity").asInt()).isEqualTo(15);
    }

    @Test
    @DisplayName("GET /public-stock → samo pozicije sa publicQuantity > 0")
    void getPublicStock() throws Exception {
        Listing aapl = persistListing("AAPL");
        Listing msft = persistListing("MSFT");
        Portfolio withPublic = persistPortfolio(42L, "CLIENT", aapl, 50, 0);
        withPublic.setPublicQuantity(7);
        portfolioRepository.save(withPublic);
        persistPortfolio(43L, "CLIENT", msft, 30, 0); // publicQuantity 0

        ResponseEntity<String> resp = restTemplate.exchange(
                url("/internal/portfolio/public-stock"),
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(getHeaders()), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json).hasSize(1);
        assertThat(json.get(0).path("ticker").asText()).isEqualTo("AAPL");
        assertThat(json.get(0).path("publicQuantity").asInt()).isEqualTo(7);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders internalHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", internalKey);
        headers.set("X-Idempotency-Key", idempotencyKey);
        return headers;
    }

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", internalKey);
        return headers;
    }

    private Listing persistListing(String ticker) {
        Listing l = new Listing();
        l.setTicker(ticker);
        l.setName(ticker + " Inc.");
        l.setListingType(ListingType.STOCK);
        l.setPrice(new BigDecimal("180.00"));
        return listingRepository.save(l);
    }

    private Portfolio persistPortfolio(Long userId, String role, Listing listing,
                                       int quantity, int reserved) {
        Portfolio p = Portfolio.builder()
                .userId(userId)
                .userRole(role)
                .listingId(listing.getId())
                .listingTicker(listing.getTicker())
                .listingName(listing.getName())
                .listingType("STOCK")
                .averageBuyPrice(new BigDecimal("100.00"))
                .quantity(quantity)
                .reservedQuantity(reserved)
                .publicQuantity(0)
                .lastModified(LocalDateTime.now())
                .build();
        return portfolioRepository.save(p);
    }
}
