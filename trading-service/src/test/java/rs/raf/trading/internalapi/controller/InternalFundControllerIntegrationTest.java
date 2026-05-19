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
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP integracioni test {@link InternalFundController} — pun Spring kontekst
 * (H2 test profil, RANDOM_PORT), realan {@code InternalAuthFilter} + service + JPA.
 *
 * <p>Verifikuje X-Internal-Key zastitu, idempotency replay i bulk reassign
 * semantiku internog fond seam-a (faza 2f). Mirror
 * {@code InternalPortfolioControllerIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InternalFundControllerIntegrationTest {

    @Value("${internal.api-key}")
    private String internalKey;

    @Value("${local.server.port}")
    private int port;

    @Autowired private InvestmentFundRepository investmentFundRepository;
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
        investmentFundRepository.deleteAll();
    }

    // ─── X-Internal-Key zastita ───────────────────────────────────────────────

    @Test
    @DisplayName("reassign-manager bez X-Internal-Key → 401")
    void reassignManager_missingInternalKey_returns401() {
        String body = "{ \"oldManagerEmployeeId\": 1, \"newManagerEmployeeId\": 2 }";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Idempotency-Key", "it-no-key");

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/reassign-manager"),
                new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("reassign-manager bez X-Idempotency-Key → 400 MISSING_IDEMPOTENCY_KEY")
    void reassignManager_missingIdempotencyKey_returns400() throws Exception {
        String body = "{ \"oldManagerEmployeeId\": 1, \"newManagerEmployeeId\": 2 }";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", internalKey);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/reassign-manager"),
                new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    // ─── bulk reassign happy path ─────────────────────────────────────────────

    @Test
    @DisplayName("reassign-manager happy path → svi fondovi starog menadzera prebaceni, count tacan")
    void reassignManager_happyPath() throws Exception {
        // Stari menadzer #100 upravlja sa 2 fonda; menadzer #101 sa 1 fondom.
        persistFund("Alpha Fund", 100L);
        persistFund("Beta Fund", 100L);
        persistFund("Gamma Fund", 101L);

        String body = "{ \"oldManagerEmployeeId\": 100, \"newManagerEmployeeId\": 200 }";

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/reassign-manager"),
                new HttpEntity<>(body, internalHeaders("it-reassign-1")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("reassignedCount").asInt()).isEqualTo(2);

        // Oba fonda menadzera #100 sad imaju #200; fond menadzera #101 netaknut.
        assertThat(investmentFundRepository.findByManagerEmployeeId(100L)).isEmpty();
        assertThat(investmentFundRepository.findByManagerEmployeeId(200L)).hasSize(2);
        assertThat(investmentFundRepository.findByManagerEmployeeId(101L)).hasSize(1);
        assertThat(internalRequestRepository.findByIdempotencyKey("it-reassign-1")).isPresent();
    }

    @Test
    @DisplayName("reassign-manager: stari menadzer bez fondova → count 0")
    void reassignManager_noFunds_returnsZero() throws Exception {
        persistFund("Gamma Fund", 101L);

        String body = "{ \"oldManagerEmployeeId\": 999, \"newManagerEmployeeId\": 200 }";

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/reassign-manager"),
                new HttpEntity<>(body, internalHeaders("it-reassign-empty")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(resp.getBody()).path("reassignedCount").asInt()).isZero();
        assertThat(investmentFundRepository.findByManagerEmployeeId(101L)).hasSize(1);
    }

    @Test
    @DisplayName("reassign-manager: oldId == newId → no-op, count 0")
    void reassignManager_sameId_isNoOp() throws Exception {
        persistFund("Alpha Fund", 100L);

        String body = "{ \"oldManagerEmployeeId\": 100, \"newManagerEmployeeId\": 100 }";

        ResponseEntity<String> resp = restTemplate.postForEntity(
                url("/internal/funds/reassign-manager"),
                new HttpEntity<>(body, internalHeaders("it-reassign-same")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(resp.getBody()).path("reassignedCount").asInt()).isZero();
        assertThat(investmentFundRepository.findByManagerEmployeeId(100L)).hasSize(1);
    }

    // ─── idempotency replay ───────────────────────────────────────────────────

    @Test
    @DisplayName("reassign-manager: ponovljen idempotency kljuc → kesiran odgovor (count iz prvog poziva)")
    void reassignManager_repeatedKey_idempotent() throws Exception {
        persistFund("Alpha Fund", 100L);
        persistFund("Beta Fund", 100L);

        String body = "{ \"oldManagerEmployeeId\": 100, \"newManagerEmployeeId\": 200 }";

        ResponseEntity<String> first = restTemplate.postForEntity(
                url("/internal/funds/reassign-manager"),
                new HttpEntity<>(body, internalHeaders("it-reassign-idem")), String.class);
        ResponseEntity<String> second = restTemplate.postForEntity(
                url("/internal/funds/reassign-manager"),
                new HttpEntity<>(body, internalHeaders("it-reassign-idem")), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Drugi poziv vraca KESIRAN count iz prvog (2), ne 0 (kao da nijedan
        // fond vise nema starog menadzera) — idempotentno ponasanje.
        assertThat(objectMapper.readTree(second.getBody()))
                .isEqualTo(objectMapper.readTree(first.getBody()));
        assertThat(objectMapper.readTree(second.getBody()).path("reassignedCount").asInt())
                .isEqualTo(2);
        assertThat(investmentFundRepository.findByManagerEmployeeId(200L)).hasSize(2);
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

    private InvestmentFund persistFund(String name, Long managerEmployeeId) {
        InvestmentFund fund = new InvestmentFund();
        fund.setName(name);
        fund.setDescription(name + " description");
        fund.setMinimumContribution(new BigDecimal("1000.0000"));
        fund.setManagerEmployeeId(managerEmployeeId);
        fund.setAccountId(1L);
        fund.setCreatedAt(LocalDateTime.now());
        fund.setActive(true);
        return investmentFundRepository.save(fund);
    }
}
