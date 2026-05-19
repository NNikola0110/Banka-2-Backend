package rs.raf.trading.order.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * HTTP integracioni test {@link OrderController} — pun Spring kontekst (H2 test
 * profil), RANDOM_PORT, realan security filter chain (lokalna JWT validacija) +
 * realan service + JPA persistencija.
 *
 * NAPOMENA (faza 2c): monolitni test je pravio prave {@code User}/{@code Employee}/
 * {@code Client} zapise i izdavao JWT preko {@code JwtService}. trading-service NEMA
 * korisnicku bazu ni login endpoint — JWT izdaje banka-core. Ovde se JWT mintuje
 * lokalno deljenim test secret-om, a {@code TradingUserResolver}/{@code BankaCoreClient}
 * su {@code @MockitoBean}. {@code Order}/{@code Listing} su trading-service entiteti
 * pa se seeduju direktno preko repozitorijuma.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderControllerIntegrationTest {

    private static final String TEST_SECRET =
            "test-jwt-secret-do-not-use-in-prod-32-bytes-minimum-required-for-hs256";

    private static final Long CLIENT_ID = 7001L;
    private static final Long EMPLOYEE_ID = 7002L;

    @Value("${local.server.port}")
    private int port;

    @Autowired private OrderRepository orderRepository;
    @Autowired private ListingRepository listingRepository;

    @MockitoBean
    private BankaCoreClient bankaCoreClient;

    @MockitoBean
    private TradingUserResolver tradingUserResolver;

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
    void clean() {
        orderRepository.deleteAll();
        listingRepository.deleteAll();
        // Default identitet stubovi (pojedinacni testovi prekrivaju po potrebi).
        lenient().when(tradingUserResolver.resolveCurrent())
                .thenReturn(new UserContext(CLIENT_ID, "CLIENT"));
        lenient().when(tradingUserResolver.resolveName(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("Test User");
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        listingRepository.deleteAll();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String buildToken(String subject, String role) {
        return Jwts.builder()
                .subject(subject)
                .claim("role", role)
                .claim("active", true)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 600_000L))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private Listing savedListing() {
        Listing l = new Listing();
        l.setTicker("AAPL");
        l.setName("Apple Inc.");
        l.setListingType(ListingType.STOCK);
        l.setPrice(BigDecimal.valueOf(150));
        l.setAsk(BigDecimal.valueOf(151));
        l.setBid(BigDecimal.valueOf(149));
        l.setExchangeAcronym("NASDAQ");
        l.setLastRefresh(LocalDateTime.now());
        return listingRepository.save(l);
    }

    private Order savedOrder(Long userId, String userRole, OrderStatus status, Listing listing) {
        Order o = new Order();
        o.setUserId(userId);
        o.setUserRole(userRole);
        o.setListing(listing);
        o.setOrderType(OrderType.MARKET);
        o.setDirection(OrderDirection.BUY);
        o.setQuantity(5);
        o.setContractSize(1);
        o.setPricePerUnit(BigDecimal.valueOf(150));
        o.setApproximatePrice(BigDecimal.valueOf(750));
        o.setStatus(status);
        o.setApprovedBy("No need for approval");
        o.setDone(false);
        o.setRemainingPortions(5);
        o.setAfterHours(false);
        o.setAllOrNone(false);
        o.setMargin(false);
        o.setCreatedAt(LocalDateTime.now());
        o.setLastModification(LocalDateTime.now());
        return orderRepository.save(o);
    }

    // ── GET /orders ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /orders")
    class GetAllOrdersIntegration {

        @Test
        @DisplayName("Admin gets all orders — 200 OK")
        void adminGetsAllOrders() {
            Listing listing = savedListing();
            savedOrder(1L, "CLIENT", OrderStatus.APPROVED, listing);
            savedOrder(2L, "CLIENT", OrderStatus.PENDING, listing);

            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders"),
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(buildToken("admin@test.com", "ADMIN"))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("APPROVED");
            assertThat(response.getBody()).contains("PENDING");
        }

        @Test
        @DisplayName("Admin filters by PENDING — only pending orders returned")
        void adminFiltersByPending() {
            Listing listing = savedListing();
            savedOrder(1L, "CLIENT", OrderStatus.APPROVED, listing);
            savedOrder(2L, "CLIENT", OrderStatus.PENDING, listing);

            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders?status=PENDING"),
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(buildToken("admin@test.com", "ADMIN"))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("PENDING");
            assertThat(response.getBody()).doesNotContain("APPROVED");
        }

        @Test
        @DisplayName("Admin sends invalid status — 400 Bad Request")
        void invalidStatusReturnsBadRequest() {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders?status=INVALID"),
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(buildToken("admin@test.com", "ADMIN"))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Unauthenticated request — 401/403")
        void unauthenticatedRejected() {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders"),
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    String.class);

            assertThat(response.getStatusCode())
                    .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    // ── GET /orders/my ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /orders/my")
    class GetMyOrdersIntegration {

        @Test
        @DisplayName("Client sees only their own orders — 200 OK")
        void clientSeesOwnOrders() {
            Listing listing = savedListing();
            savedOrder(CLIENT_ID, "CLIENT", OrderStatus.APPROVED, listing);
            savedOrder(999L, "CLIENT", OrderStatus.APPROVED, listing); // tudji

            when(tradingUserResolver.resolveCurrent())
                    .thenReturn(new UserContext(CLIENT_ID, "CLIENT"));

            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders/my"),
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(buildToken("client@test.com", "CLIENT"))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"totalElements\":1");
        }

        @Test
        @DisplayName("Employee sees only their own orders — 200 OK")
        void employeeSeesOwnOrders() {
            Listing listing = savedListing();
            savedOrder(EMPLOYEE_ID, "EMPLOYEE", OrderStatus.PENDING, listing);
            savedOrder(999L, "EMPLOYEE", OrderStatus.PENDING, listing); // tudji

            when(tradingUserResolver.resolveCurrent())
                    .thenReturn(new UserContext(EMPLOYEE_ID, "EMPLOYEE"));

            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders/my"),
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(buildToken("agent@test.com", "EMPLOYEE"))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"totalElements\":1");
        }

        @Test
        @DisplayName("Unauthenticated request — 401/403")
        void unauthenticatedRejected() {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders/my"),
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    String.class);

            assertThat(response.getStatusCode())
                    .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    // ── GET /orders/{id} ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /orders/{id}")
    class GetOrderByIdIntegration {

        @Test
        @DisplayName("Admin can see any order — 200 OK")
        void adminCanSeeAnyOrder() {
            Listing listing = savedListing();
            Order order = savedOrder(999L, "CLIENT", OrderStatus.APPROVED, listing);

            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders/" + order.getId()),
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(buildToken("admin@test.com", "ADMIN"))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"id\":" + order.getId());
        }

        @Test
        @DisplayName("Client can see their own order — 200 OK")
        void clientCanSeeOwnOrder() {
            Listing listing = savedListing();
            Order order = savedOrder(CLIENT_ID, "CLIENT", OrderStatus.APPROVED, listing);

            when(tradingUserResolver.resolveCurrent())
                    .thenReturn(new UserContext(CLIENT_ID, "CLIENT"));

            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders/" + order.getId()),
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(buildToken("client@test.com", "CLIENT"))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"id\":" + order.getId());
        }

        @Test
        @DisplayName("Client cannot see another user's order — 403 Forbidden")
        void clientCannotSeeOtherUsersOrder() {
            Listing listing = savedListing();
            Order order = savedOrder(999L, "CLIENT", OrderStatus.APPROVED, listing); // tudji

            when(tradingUserResolver.resolveCurrent())
                    .thenReturn(new UserContext(CLIENT_ID, "CLIENT"));

            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders/" + order.getId()),
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(buildToken("other@test.com", "CLIENT"))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Order not found — 404 Not Found")
        void orderNotFound() {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders/99999"),
                    HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(buildToken("admin@test.com", "ADMIN"))),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Unauthenticated request — 401/403")
        void unauthenticatedRejected() {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/orders/1"),
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    String.class);

            assertThat(response.getStatusCode())
                    .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }
}
