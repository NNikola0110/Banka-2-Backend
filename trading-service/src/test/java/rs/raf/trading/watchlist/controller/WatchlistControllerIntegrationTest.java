package rs.raf.trading.watchlist.controller;

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
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.watchlist.model.Watchlist;
import rs.raf.trading.watchlist.model.WatchlistItem;
import rs.raf.trading.watchlist.model.WatchlistOwnerType;
import rs.raf.trading.watchlist.repository.WatchlistItemRepository;
import rs.raf.trading.watchlist.repository.WatchlistRepository;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * HTTP integracioni test {@link WatchlistController} — pun Spring kontekst (H2 test
 * profil), RANDOM_PORT, realan {@code TradingSecurityConfig} filter chain (lokalna
 * JWT validacija) + realan {@link rs.raf.trading.watchlist.service.WatchlistService}
 * + JPA persistencija.
 *
 * <p>Obrazac kopiran iz {@code MarginAccountControllerIntegrationTest}: JWT se mintuje
 * lokalno deljenim test secret-om (HS256), {@link BankaCoreClient} je {@code @MockitoBean}
 * koji razresava identitet preko {@code getUserByEmail} (realan {@code TradingUserResolver}).
 * {@code Watchlist}/{@code Listing} su trading-service entiteti pa se seeduju direktno
 * preko repozitorijuma. Ruta {@code /watchlists/**} je {@code authenticated()} u
 * {@code TradingSecurityConfig} — vlasnistvo (ownerId + ownerType) proverava service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WatchlistControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long CLIENT_ID = 9101L;
    private static final Long OTHER_CLIENT_ID = 9102L;

    @Value("${local.server.port}")
    private int port;

    @Autowired private WatchlistRepository watchlistRepository;
    @Autowired private WatchlistItemRepository watchlistItemRepository;
    @Autowired private ListingRepository listingRepository;

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
        watchlistItemRepository.deleteAll();
        watchlistRepository.deleteAll();
        listingRepository.deleteAll();

        // Realan TradingUserResolver razresava JWT subject email -> UserContext.
        // Email-ovi su per-test-klasa jedinstveni jer @SpringBootTest deli kontekst
        // (i Caffeine kes resolvera) izmedju test klasa — generican "client@test.com"
        // bi pokupio kesirani UserContext iz drugog test fajla.
        lenient().when(bankaCoreClient.getUserByEmail("client.wl@test.com")).thenReturn(
                new InternalUserDto(CLIENT_ID, "CLIENT", "client.wl@test.com", "Client", "User", true, null));
        lenient().when(bankaCoreClient.getUserByEmail("other.wl@test.com")).thenReturn(
                new InternalUserDto(OTHER_CLIENT_ID, "CLIENT", "other.wl@test.com", "Other", "User", true, null));
    }

    // ── POST /watchlists ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /watchlists — 200 + perzistira listu za ulogovanog klijenta")
    void createWatchlist_ok() throws Exception {
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/watchlists"),
                new HttpEntity<>("{\"name\":\"Tech picks\"}", jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("name").asText()).isEqualTo("Tech picks");
        assertThat(body.path("ownerId").asLong()).isEqualTo(CLIENT_ID);
        assertThat(body.path("ownerType").asText()).isEqualTo("CLIENT");
        assertThat(body.path("itemCount").asInt()).isZero();
        assertThat(watchlistRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("POST /watchlists — 400 kad je ime prazno (validacija)")
    void createWatchlist_blankName_returnsBadRequest() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/watchlists"),
                new HttpEntity<>("{\"name\":\"\"}", jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Watchlist name cannot be blank.");
        assertThat(watchlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("POST /watchlists — 401 za nevalidan token")
    void createWatchlist_invalidToken_returnsUnauthorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("not-a-real-jwt");

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/watchlists"),
                new HttpEntity<>("{\"name\":\"X\"}", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("POST /watchlists — 403 bez JWT-a (anyRequest authenticated)")
    void createWatchlist_missingJwt_returnsForbidden() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/watchlists"),
                new HttpEntity<>("{\"name\":\"X\"}", jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── GET /watchlists ──────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /watchlists — vraca samo liste ulogovanog klijenta")
    void listWatchlists_returnsOnlyOwn() throws Exception {
        watchlistRepository.save(Watchlist.builder()
                .ownerId(CLIENT_ID).ownerType(WatchlistOwnerType.CLIENT).name("Moja").build());
        watchlistRepository.save(Watchlist.builder()
                .ownerId(OTHER_CLIENT_ID).ownerType(WatchlistOwnerType.CLIENT).name("Tudja").build());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/watchlists"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).path("name").asText()).isEqualTo("Moja");
        assertThat(body.get(0).path("ownerId").asLong()).isEqualTo(CLIENT_ID);
    }

    // ── POST /watchlists/{id}/items ──────────────────────────────────────────

    @Test
    @DisplayName("POST /watchlists/{id}/items — 200 dodaje stavku (JSON body listingId)")
    void addItem_ok() throws Exception {
        Watchlist wl = watchlistRepository.save(Watchlist.builder()
                .ownerId(CLIENT_ID).ownerType(WatchlistOwnerType.CLIENT).name("Moja").build());
        Listing listing = savedListing("AAPL");

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/watchlists/" + wl.getId() + "/items"),
                new HttpEntity<>("{\"listingId\":" + listing.getId() + "}",
                        jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("ticker").asText()).isEqualTo("AAPL");
        assertThat(body.path("listingId").asLong()).isEqualTo(listing.getId());
        assertThat(watchlistItemRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("POST /watchlists/{id}/items — 400 kad listingId nedostaje (validacija)")
    void addItem_missingListingId_returnsBadRequest() {
        Watchlist wl = watchlistRepository.save(Watchlist.builder()
                .ownerId(CLIENT_ID).ownerType(WatchlistOwnerType.CLIENT).name("Moja").build());

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/watchlists/" + wl.getId() + "/items"),
                new HttpEntity<>("{}", jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Listing ID je obavezan");
    }

    @Test
    @DisplayName("POST /watchlists/{id}/items — 400 kad lista nije vlasnistvo klijenta")
    void addItem_notOwner_returnsBadRequest() {
        // Lista pripada drugom klijentu; service baca IllegalArgumentException -> 400.
        Watchlist others = watchlistRepository.save(Watchlist.builder()
                .ownerId(OTHER_CLIENT_ID).ownerType(WatchlistOwnerType.CLIENT).name("Tudja").build());
        Listing listing = savedListing("MSFT");

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/watchlists/" + others.getId() + "/items"),
                new HttpEntity<>("{\"listingId\":" + listing.getId() + "}",
                        jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("nije vasa");
        assertThat(watchlistItemRepository.count()).isZero();
    }

    // ── GET /watchlists/{id}/items ───────────────────────────────────────────

    @Test
    @DisplayName("GET /watchlists/{id}/items — 200 + market-data polja iz live listinga (OT-1173)")
    void listItems_returnsMarketDataFields_OT_1173() throws Exception {
        // OT-1173: listItems resolve-uje live market-data preko ListingService
        // (cena/promena/volumen/berza). Pin da DTO nosi ta polja iz seed-ovanog
        // listinga (ne samo id/ticker) — sprecava field-mismatch regresiju na BE.
        Watchlist wl = watchlistRepository.save(Watchlist.builder()
                .ownerId(CLIENT_ID).ownerType(WatchlistOwnerType.CLIENT).name("Tech").build());
        Listing listing = savedListing("AAPL");
        watchlistItemRepository.save(WatchlistItem.builder()
                .watchlistId(wl.getId()).listingId(listing.getId()).build());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/watchlists/" + wl.getId() + "/items"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(1);
        JsonNode item = body.get(0);
        assertThat(item.path("ticker").asText()).isEqualTo("AAPL");
        assertThat(item.path("listingId").asLong()).isEqualTo(listing.getId());
        assertThat(item.path("securityType").asText()).isEqualTo("STOCK");
        assertThat(item.path("exchangeName").asText()).isEqualTo("NASDAQ");
        assertThat(item.path("currentPrice").decimalValue()).isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("GET /watchlists/{id}/items?type=FOREX — filtrira po tipu hartije (OT-1173)")
    void listItems_typeFilter_returnsOnlyMatching_OT_1173() throws Exception {
        Watchlist wl = watchlistRepository.save(Watchlist.builder()
                .ownerId(CLIENT_ID).ownerType(WatchlistOwnerType.CLIENT).name("Mix").build());
        Listing stock = savedListing("AAPL");
        Listing forex = forexListing("USDEUR");
        watchlistItemRepository.save(WatchlistItem.builder()
                .watchlistId(wl.getId()).listingId(stock.getId()).build());
        watchlistItemRepository.save(WatchlistItem.builder()
                .watchlistId(wl.getId()).listingId(forex.getId()).build());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/watchlists/" + wl.getId() + "/items?type=FOREX"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).path("securityType").asText()).isEqualTo("FOREX");
    }

    @Test
    @DisplayName("GET /watchlists/{id}/items — 400 kad lista nije vlasnistvo (cross-owner authz)")
    void listItems_notOwner_returnsBadRequest_OT_1178() {
        Watchlist others = watchlistRepository.save(Watchlist.builder()
                .ownerId(OTHER_CLIENT_ID).ownerType(WatchlistOwnerType.CLIENT).name("Tudja").build());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/watchlists/" + others.getId() + "/items"),
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("nije vasa");
    }

    // ── DELETE /watchlists/{id} ──────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /watchlists/{id} — 204 brise vlasnikovu listu")
    void deleteWatchlist_ok_returnsNoContent() {
        Watchlist wl = watchlistRepository.save(Watchlist.builder()
                .ownerId(CLIENT_ID).ownerType(WatchlistOwnerType.CLIENT).name("Za brisanje").build());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/watchlists/" + wl.getId()),
                HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(watchlistRepository.count()).isZero();
    }

    @Test
    @DisplayName("DELETE /watchlists/{id} — 400 kad lista nije vlasnistvo (tudja ostaje)")
    void deleteWatchlist_notOwner_returnsBadRequest() {
        Watchlist others = watchlistRepository.save(Watchlist.builder()
                .ownerId(OTHER_CLIENT_ID).ownerType(WatchlistOwnerType.CLIENT).name("Tudja").build());

        ResponseEntity<String> response = restTemplate.exchange(
                url("/watchlists/" + others.getId()),
                HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders(buildToken("client.wl@test.com", "CLIENT"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(watchlistRepository.count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("DELETE /watchlists/{id} — 403 bez JWT-a")
    void deleteWatchlist_missingJwt_returnsForbidden() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/watchlists/1"),
                HttpMethod.DELETE,
                new HttpEntity<>(jsonHeaders(null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Listing savedListing(String ticker) {
        Listing l = new Listing();
        l.setTicker(ticker);
        l.setName(ticker + " Inc.");
        l.setListingType(ListingType.STOCK);
        l.setPrice(BigDecimal.valueOf(150));
        l.setAsk(BigDecimal.valueOf(151));
        l.setBid(BigDecimal.valueOf(149));
        l.setExchangeAcronym("NASDAQ");
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    private Listing forexListing(String ticker) {
        Listing l = new Listing();
        l.setTicker(ticker);
        l.setName(ticker + " pair");
        l.setListingType(ListingType.FOREX);
        l.setPrice(BigDecimal.valueOf(1));
        l.setAsk(BigDecimal.valueOf(1));
        l.setBid(BigDecimal.valueOf(1));
        l.setExchangeAcronym("FX");
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
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
