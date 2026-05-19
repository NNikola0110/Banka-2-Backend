package rs.raf.banka2_bek.interbank.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import rs.raf.banka2.contracts.internal.CommitStockRequest;
import rs.raf.banka2.contracts.internal.CommitStockResponse;
import rs.raf.banka2.contracts.internal.InternalErrorDto;
import rs.raf.banka2.contracts.internal.InternalListingDto;
import rs.raf.banka2.contracts.internal.InternalPortfolioHoldingDto;
import rs.raf.banka2.contracts.internal.InternalPublicStockSellerDto;
import rs.raf.banka2.contracts.internal.ReleaseStockRequest;
import rs.raf.banka2.contracts.internal.ReleaseStockResponse;
import rs.raf.banka2.contracts.internal.ReserveStockRequest;
import rs.raf.banka2.contracts.internal.ReserveStockResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Testovi za {@link TradingServiceInternalClient} — koristi MockRestServiceServer
 * da stubira HTTP pozive ka trading-service {@code /internal/portfolio/**}.
 *
 * <p>Proverava da se X-Internal-Key + X-Idempotency-Key salju, da se 404 na
 * {@code findListingByTicker} mapira u {@code Optional.empty()}, i da se ostale
 * HTTP greske propagiraju kao {@link TradingServiceClientException} sa porukom
 * iz {@code InternalErrorDto} tela.
 */
class TradingServiceInternalClientTest {

    private static final String INTERNAL_API_KEY = "test-internal-key";
    private static final String BASE_URL = "http://localhost:18082";

    private MockRestServiceServer mockServer;
    private TradingServiceInternalClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("X-Internal-Key", INTERNAL_API_KEY);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new TradingServiceInternalClient(builder.build(), objectMapper);
    }

    @Test
    @DisplayName("reserveStock: salje X-Internal-Key + X-Idempotency-Key, deserijalizuje odgovor")
    void reserveStock_sendsHeaders_andDeserializes() throws Exception {
        ReserveStockResponse stub = new ReserveStockResponse(1L, 7L, "AAPL", 10, 10);
        mockServer.expect(requestTo(BASE_URL + "/internal/portfolio/reserve-stock"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andExpect(header("X-Idempotency-Key", "idem-r1"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(stub),
                        MediaType.APPLICATION_JSON));

        ReserveStockResponse resp = client.reserveStock("idem-r1",
                new ReserveStockRequest(42L, "CLIENT", "AAPL", 10));

        assertThat(resp.reservedQuantity()).isEqualTo(10);
        assertThat(resp.listingId()).isEqualTo(7L);
        mockServer.verify();
    }

    @Test
    @DisplayName("commitStock: happy path")
    void commitStock_happyPath() throws Exception {
        CommitStockResponse stub = new CommitStockResponse(1L, 7L, "AAPL", 30);
        mockServer.expect(requestTo(BASE_URL + "/internal/portfolio/commit-stock"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Idempotency-Key", "idem-c1"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(stub),
                        MediaType.APPLICATION_JSON));

        CommitStockResponse resp = client.commitStock("idem-c1",
                new CommitStockRequest(42L, "CLIENT", "AAPL", 10, true));

        assertThat(resp.quantity()).isEqualTo(30);
        mockServer.verify();
    }

    @Test
    @DisplayName("releaseStock: happy path")
    void releaseStock_happyPath() throws Exception {
        ReleaseStockResponse stub = new ReleaseStockResponse(1L, "AAPL", 0);
        mockServer.expect(requestTo(BASE_URL + "/internal/portfolio/release-stock"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(objectMapper.writeValueAsString(stub),
                        MediaType.APPLICATION_JSON));

        ReleaseStockResponse resp = client.releaseStock("idem-rl1",
                new ReleaseStockRequest(42L, "CLIENT", "AAPL", 5));

        assertThat(resp.reservedQuantity()).isZero();
        mockServer.verify();
    }

    @Test
    @DisplayName("commitStock: 409 CONFLICT → TradingServiceClientException sa porukom iz InternalErrorDto")
    void commitStock_conflict_throwsWithErrorBody() throws Exception {
        InternalErrorDto err = new InternalErrorDto("CONFLICT", "INSUFFICIENT_QUANTITY on listing 7");
        mockServer.expect(requestTo(BASE_URL + "/internal/portfolio/commit-stock"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(err)));

        assertThatThrownBy(() -> client.commitStock("idem-c2",
                new CommitStockRequest(42L, "CLIENT", "AAPL", 10, false)))
                .isInstanceOf(TradingServiceClientException.class)
                .hasMessageContaining("INSUFFICIENT_QUANTITY")
                .satisfies(e -> assertThat(((TradingServiceClientException) e).getHttpStatus())
                        .isEqualTo(409));
        mockServer.verify();
    }

    @Test
    @DisplayName("findListingByTicker: 200 → InternalListingDto")
    void findListingByTicker_present() throws Exception {
        InternalListingDto stub = new InternalListingDto(7L, "AAPL", "AAPL Inc.", "STOCK",
                new BigDecimal("180"), null, null);
        mockServer.expect(requestTo(BASE_URL + "/internal/portfolio/listing?ticker=AAPL"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(objectMapper.writeValueAsString(stub),
                        MediaType.APPLICATION_JSON));

        Optional<InternalListingDto> result = client.findListingByTicker("AAPL");

        assertThat(result).isPresent();
        assertThat(result.get().ticker()).isEqualTo("AAPL");
        mockServer.verify();
    }

    @Test
    @DisplayName("findListingByTicker: 404 → Optional.empty (mapirano u NO_SUCH_ASSET kod pozivaoca)")
    void findListingByTicker_notFound_returnsEmpty() throws Exception {
        InternalErrorDto err = new InternalErrorDto("NOT_FOUND", "Listing not found: ZZZZ");
        mockServer.expect(requestTo(BASE_URL + "/internal/portfolio/listing?ticker=ZZZZ"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(objectMapper.writeValueAsString(err)));

        Optional<InternalListingDto> result = client.findListingByTicker("ZZZZ");

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("findHolding: 200 → InternalPortfolioHoldingDto")
    void findHolding_present() throws Exception {
        InternalPortfolioHoldingDto stub = new InternalPortfolioHoldingDto(
                true, 1L, 7L, "AAPL", 20, 5, 15);
        mockServer.expect(requestTo(
                        BASE_URL + "/internal/portfolio/holding?userId=42&userRole=CLIENT&ticker=AAPL"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(stub),
                        MediaType.APPLICATION_JSON));

        InternalPortfolioHoldingDto result = client.findHolding(42L, "CLIENT", "AAPL");

        assertThat(result.exists()).isTrue();
        assertThat(result.availableQuantity()).isEqualTo(15);
        mockServer.verify();
    }

    @Test
    @DisplayName("findAllPublicStock: 200 → lista InternalPublicStockSellerDto")
    void findAllPublicStock_returnsList() throws Exception {
        InternalPublicStockSellerDto[] stub = {
                new InternalPublicStockSellerDto(42L, "CLIENT", "AAPL", 7)
        };
        mockServer.expect(requestTo(BASE_URL + "/internal/portfolio/public-stock"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(stub),
                        MediaType.APPLICATION_JSON));

        List<InternalPublicStockSellerDto> result = client.findAllPublicStock();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ticker()).isEqualTo("AAPL");
        mockServer.verify();
    }

    @Test
    @DisplayName("findPublicStockForSeller: query param userId/userRole/ticker")
    void findPublicStockForSeller_sendsQueryParams() throws Exception {
        InternalPublicStockSellerDto[] stub = {
                new InternalPublicStockSellerDto(42L, "CLIENT", "AAPL", 5)
        };
        mockServer.expect(requestTo(
                        BASE_URL + "/internal/portfolio/public-stock?userId=42&userRole=CLIENT&ticker=AAPL"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(stub),
                        MediaType.APPLICATION_JSON));

        List<InternalPublicStockSellerDto> result =
                client.findPublicStockForSeller(42L, "CLIENT", "AAPL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).publicQuantity()).isEqualTo(5);
        mockServer.verify();
    }
}
