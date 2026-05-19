package rs.raf.trading.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
import rs.raf.banka2.contracts.internal.FxRateDto;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalOtpVerifyResponse;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.banka2.contracts.internal.TaxCollectRequest;
import rs.raf.banka2.contracts.internal.TaxCollectResponse;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Testovi za BankaCoreClient — koristi MockRestServiceServer da stubiraju HTTP pozive.
 * Proverava da li se X-Internal-Key i X-Idempotency-Key header-i salju i da
 * li se ne-2xx odgovori mapiraju u BankaCoreClientException.
 */
class BankaCoreClientTest {

    private static final String INTERNAL_API_KEY = "test-internal-key";
    private static final String BASE_URL = "http://localhost:18081";

    private MockRestServiceServer mockServer;
    private BankaCoreClient bankaCoreClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // Pravimo RestClient.Builder, bindujemo MockRestServiceServer, pa gradimo BankaCoreClient
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("X-Internal-Key", INTERNAL_API_KEY);

        mockServer = MockRestServiceServer.bindTo(builder).build();

        RestClient restClient = builder.build();
        bankaCoreClient = new BankaCoreClient(restClient);
    }

    @Test
    void reserveFunds_happyPath_returnsDeserializedResponse_andSendsRequiredHeaders() throws Exception {
        // Pripremi stub odgovor
        ReserveFundsResponse stubResponse = new ReserveFundsResponse(
                "res-001", 42L, new BigDecimal("1500.00"), new BigDecimal("3500.00"));
        String responseJson = objectMapper.writeValueAsString(stubResponse);

        mockServer.expect(requestTo(BASE_URL + "/internal/funds/reserve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andExpect(header("X-Idempotency-Key", "idem-key-001"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        ReserveFundsRequest request = new ReserveFundsRequest(42L, new BigDecimal("1500.00"), "RSD");

        ReserveFundsResponse result = bankaCoreClient.reserveFunds("idem-key-001", request);

        assertThat(result.reservationId()).isEqualTo("res-001");
        assertThat(result.accountId()).isEqualTo(42L);
        assertThat(result.reservedAmount()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(result.availableBalanceAfter()).isEqualByComparingTo(new BigDecimal("3500.00"));

        mockServer.verify();
    }

    @Test
    void getAccount_happyPath_returnsDeserializedInternalAccountDto() throws Exception {
        InternalAccountDto stubAccount = new InternalAccountDto(
                7L, "222000112345678911", "Stefan Jovanovic",
                new BigDecimal("5000.00"), new BigDecimal("4500.00"),
                new BigDecimal("500.00"), "RSD", "ACTIVE");
        String responseJson = objectMapper.writeValueAsString(stubAccount);

        mockServer.expect(requestTo(BASE_URL + "/internal/accounts/7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        InternalAccountDto result = bankaCoreClient.getAccount(7L);

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.accountNumber()).isEqualTo("222000112345678911");
        assertThat(result.ownerName()).isEqualTo("Stefan Jovanovic");
        assertThat(result.currencyCode()).isEqualTo("RSD");
        assertThat(result.status()).isEqualTo("ACTIVE");

        mockServer.verify();
    }

    @Test
    void getAccount_notFound_throwsBankaCoreClientExceptionWith404() {
        mockServer.expect(requestTo(BASE_URL + "/internal/accounts/999"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> bankaCoreClient.getAccount(999L))
                .isInstanceOf(BankaCoreClientException.class)
                .satisfies(ex -> {
                    BankaCoreClientException bcEx = (BankaCoreClientException) ex;
                    assertThat(bcEx.getHttpStatus()).isEqualTo(404);
                });

        mockServer.verify();
    }

    @Test
    void reserveFunds_conflict_throwsBankaCoreClientExceptionWith409() throws Exception {
        mockServer.expect(requestTo(BASE_URL + "/internal/funds/reserve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Idempotency-Key", "idem-key-conflict"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT));

        ReserveFundsRequest request = new ReserveFundsRequest(42L, new BigDecimal("99999.00"), "RSD");

        assertThatThrownBy(() -> bankaCoreClient.reserveFunds("idem-key-conflict", request))
                .isInstanceOf(BankaCoreClientException.class)
                .satisfies(ex -> {
                    BankaCoreClientException bcEx = (BankaCoreClientException) ex;
                    assertThat(bcEx.getHttpStatus()).isEqualTo(409);
                });

        mockServer.verify();
    }

    @Test
    void reserveFunds_sendsXIdempotencyKeyHeader_uniquePerCall() throws Exception {
        ReserveFundsResponse stubResponse = new ReserveFundsResponse(
                "res-002", 5L, new BigDecimal("200.00"), new BigDecimal("800.00"));
        String responseJson = objectMapper.writeValueAsString(stubResponse);

        // Ocekuj tacno specificiran idempotency key
        mockServer.expect(requestTo(BASE_URL + "/internal/funds/reserve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Idempotency-Key", "unique-key-xyz-789"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        ReserveFundsRequest request = new ReserveFundsRequest(5L, new BigDecimal("200.00"), "EUR");

        bankaCoreClient.reserveFunds("unique-key-xyz-789", request);

        mockServer.verify();
    }

    @Test
    void getFxRates_happyPath_returnsDeserializedRates_andSendsInternalKeyHeader() throws Exception {
        List<FxRateDto> stubRates = List.of(new FxRateDto("RSD", 1.0), new FxRateDto("EUR", 0.0085));
        String responseJson = objectMapper.writeValueAsString(stubRates);

        mockServer.expect(requestTo(BASE_URL + "/internal/fx/rates"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        List<FxRateDto> result = bankaCoreClient.getFxRates();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).currency()).isEqualTo("RSD");
        assertThat(result.get(0).rate()).isEqualTo(1.0);
        assertThat(result.get(1).currency()).isEqualTo("EUR");
        assertThat(result.get(1).rate()).isEqualTo(0.0085);

        mockServer.verify();
    }

    // ── Identitet (faza 2c) ──────────────────────────────────────────────────

    @Test
    void getUserByEmail_happyPath_returnsDeserializedInternalUserDto() throws Exception {
        InternalUserDto stub = new InternalUserDto(
                7L, "CLIENT", "stefan.jovanovic@gmail.com", "Stefan", "Jovanovic", true);
        String responseJson = objectMapper.writeValueAsString(stub);

        // RestClient URL-enkoduje '@' u path varijabli u %40 (RFC 3986).
        mockServer.expect(requestTo(BASE_URL + "/internal/users/by-email/stefan.jovanovic%40gmail.com"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        InternalUserDto result = bankaCoreClient.getUserByEmail("stefan.jovanovic@gmail.com");

        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.userRole()).isEqualTo("CLIENT");
        assertThat(result.email()).isEqualTo("stefan.jovanovic@gmail.com");
        assertThat(result.firstName()).isEqualTo("Stefan");
        assertThat(result.lastName()).isEqualTo("Jovanovic");
        assertThat(result.active()).isTrue();

        mockServer.verify();
    }

    @Test
    void getUserByEmail_notFound_throwsBankaCoreClientExceptionWith404() {
        mockServer.expect(requestTo(BASE_URL + "/internal/users/by-email/missing%40example.com"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> bankaCoreClient.getUserByEmail("missing@example.com"))
                .isInstanceOf(BankaCoreClientException.class)
                .satisfies(ex -> {
                    BankaCoreClientException bcEx = (BankaCoreClientException) ex;
                    assertThat(bcEx.getHttpStatus()).isEqualTo(404);
                });

        mockServer.verify();
    }

    @Test
    void getUserById_happyPath_returnsDeserializedInternalUserDto() throws Exception {
        InternalUserDto stub = new InternalUserDto(
                3L, "EMPLOYEE", "tamara.pavlovic@banka.rs", "Tamara", "Pavlovic", true);
        String responseJson = objectMapper.writeValueAsString(stub);

        mockServer.expect(requestTo(BASE_URL + "/internal/users/EMPLOYEE/3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        InternalUserDto result = bankaCoreClient.getUserById("EMPLOYEE", 3L);

        assertThat(result.userId()).isEqualTo(3L);
        assertThat(result.userRole()).isEqualTo("EMPLOYEE");
        assertThat(result.firstName()).isEqualTo("Tamara");
        assertThat(result.lastName()).isEqualTo("Pavlovic");

        mockServer.verify();
    }

    @Test
    void verifyOtp_codeCorrect_returnsVerifiedTrue() throws Exception {
        InternalOtpVerifyResponse stub = new InternalOtpVerifyResponse(true, false);
        String responseJson = objectMapper.writeValueAsString(stub);

        mockServer.expect(requestTo(BASE_URL + "/internal/otp/verify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        InternalOtpVerifyResponse result = bankaCoreClient.verifyOtp("stefan@gmail.com", "123456");

        assertThat(result.verified()).isTrue();
        assertThat(result.blocked()).isFalse();

        mockServer.verify();
    }

    @Test
    void verifyOtp_codeWrong_returnsVerifiedFalse() throws Exception {
        InternalOtpVerifyResponse stub = new InternalOtpVerifyResponse(false, false);
        String responseJson = objectMapper.writeValueAsString(stub);

        mockServer.expect(requestTo(BASE_URL + "/internal/otp/verify"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        InternalOtpVerifyResponse result = bankaCoreClient.verifyOtp("stefan@gmail.com", "000000");

        assertThat(result.verified()).isFalse();
        assertThat(result.blocked()).isFalse();

        mockServer.verify();
    }

    @Test
    void provisionFundAccount_happyPath_returnsDeserializedInternalAccountDto() throws Exception {
        InternalAccountDto stub = new InternalAccountDto(
                55L, "222000999000000001", "Banka 2 Stable Income",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "RSD", "ACTIVE");
        String responseJson = objectMapper.writeValueAsString(stub);

        mockServer.expect(requestTo(BASE_URL + "/internal/accounts/fund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        InternalAccountDto result = bankaCoreClient.provisionFundAccount("Banka 2 Stable Income", 1L);

        assertThat(result.id()).isEqualTo(55L);
        assertThat(result.accountNumber()).isEqualTo("222000999000000001");
        assertThat(result.ownerName()).isEqualTo("Banka 2 Stable Income");
        assertThat(result.currencyCode()).isEqualTo("RSD");
        assertThat(result.status()).isEqualTo("ACTIVE");

        mockServer.verify();
    }

    @Test
    void getBankTradingAccount_happyPath_returnsDeserializedInternalAccountDto() throws Exception {
        InternalAccountDto stub = new InternalAccountDto(
                90L, "222000111000000099", "Banka 2",
                new BigDecimal("1000000.00"), new BigDecimal("1000000.00"),
                BigDecimal.ZERO, "EUR", "ACTIVE");
        String responseJson = objectMapper.writeValueAsString(stub);

        mockServer.expect(requestTo(BASE_URL + "/internal/accounts/bank-trading/EUR"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        InternalAccountDto result = bankaCoreClient.getBankTradingAccount("EUR");

        assertThat(result.id()).isEqualTo(90L);
        assertThat(result.currencyCode()).isEqualTo("EUR");
        assertThat(result.status()).isEqualTo("ACTIVE");

        mockServer.verify();
    }

    @Test
    void findEmployees_withQueryParams_sendsFiltersAndReturnsList() throws Exception {
        List<InternalUserDto> stub = List.of(
                new InternalUserDto(3L, "EMPLOYEE", "tamara.pavlovic@banka.rs",
                        "Tamara", "Pavlovic", true));
        String responseJson = objectMapper.writeValueAsString(stub);

        // Blank email/position se izostavljaju; firstName/lastName postaju query params.
        mockServer.expect(requestTo(BASE_URL + "/internal/employees?firstName=Tamara&lastName=Pavlovic"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        List<InternalUserDto> result =
                bankaCoreClient.findEmployees("Tamara", "Pavlovic", null, "  ");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(3L);
        assertThat(result.get(0).firstName()).isEqualTo("Tamara");

        mockServer.verify();
    }

    @Test
    void findEmployees_noFilters_sendsBarePathAndReturnsList() throws Exception {
        List<InternalUserDto> stub = List.of(
                new InternalUserDto(3L, "EMPLOYEE", "a@banka.rs", "A", "B", true),
                new InternalUserDto(4L, "EMPLOYEE", "c@banka.rs", "C", "D", true));
        String responseJson = objectMapper.writeValueAsString(stub);

        mockServer.expect(requestTo(BASE_URL + "/internal/employees"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        List<InternalUserDto> result = bankaCoreClient.findEmployees(null, null, null, null);

        assertThat(result).hasSize(2);

        mockServer.verify();
    }

    @Test
    void creditFunds_happyPath_sendsXIdempotencyKeyHeader_andReturnsResponse() throws Exception {
        CreditFundsResponse stub = new CreditFundsResponse(
                42L, new BigDecimal("750.00"), new BigDecimal("4250.00"));
        String responseJson = objectMapper.writeValueAsString(stub);

        mockServer.expect(requestTo(BASE_URL + "/internal/funds/credit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andExpect(header("X-Idempotency-Key", "credit-key-001"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        CreditFundsRequest request = new CreditFundsRequest(
                42L, new BigDecimal("750.00"), new BigDecimal("7.00"), "RSD", "SELL prihod");

        CreditFundsResponse result = bankaCoreClient.creditFunds("credit-key-001", request);

        assertThat(result.accountId()).isEqualTo(42L);
        assertThat(result.creditedAmount()).isEqualByComparingTo(new BigDecimal("750.00"));
        assertThat(result.balanceAfter()).isEqualByComparingTo(new BigDecimal("4250.00"));

        mockServer.verify();
    }

    @Test
    void collectTax_happyPath_sendsXIdempotencyKeyHeader_andReturnsResponse() throws Exception {
        TaxCollectResponse stub = new TaxCollectResponse(
                7L, new BigDecimal("120.00"), true);
        String responseJson = objectMapper.writeValueAsString(stub);

        mockServer.expect(requestTo(BASE_URL + "/internal/funds/tax-collect"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Key", INTERNAL_API_KEY))
                .andExpect(header("X-Idempotency-Key", "tax-key-001"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        TaxCollectRequest request = new TaxCollectRequest(
                7L, new BigDecimal("120.00"), "Porez na kapitalnu dobit");

        TaxCollectResponse result = bankaCoreClient.collectTax("tax-key-001", request);

        assertThat(result.payerClientId()).isEqualTo(7L);
        assertThat(result.collectedAmount()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(result.collected()).isTrue();

        mockServer.verify();
    }
}
