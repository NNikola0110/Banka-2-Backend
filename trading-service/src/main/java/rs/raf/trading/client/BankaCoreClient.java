package rs.raf.trading.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CommitFundsResponse;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalOtpVerifyRequest;
import rs.raf.banka2.contracts.internal.InternalOtpVerifyResponse;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2.contracts.internal.ProvisionFundAccountRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsResponse;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.banka2.contracts.internal.TaxCollectRequest;
import rs.raf.banka2.contracts.internal.TaxCollectResponse;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsResponse;
import rs.raf.banka2.contracts.internal.FxRateDto;

import java.util.List;

/**
 * HTTP klijent ka banka-core internom /internal/funds SAGA API-ju (Korak 0).
 * Trgovinske SAGA operacije (2c+) ga koriste za novcane noge.
 */
@Component
public class BankaCoreClient {

    private final RestClient client;

    public BankaCoreClient(RestClient bankaCoreRestClient) {
        this.client = bankaCoreRestClient;
    }

    public ReserveFundsResponse reserveFunds(String idempotencyKey, ReserveFundsRequest req) {
        return post("/internal/funds/reserve", idempotencyKey, req, ReserveFundsResponse.class);
    }

    public CommitFundsResponse commitFunds(String reservationId, String idempotencyKey,
                                           CommitFundsRequest req) {
        return post("/internal/funds/reservations/" + reservationId + "/commit",
                idempotencyKey, req, CommitFundsResponse.class);
    }

    public ReleaseFundsResponse releaseFunds(String reservationId, String idempotencyKey,
                                             ReleaseFundsRequest req) {
        return post("/internal/funds/reservations/" + reservationId + "/release",
                idempotencyKey, req, ReleaseFundsResponse.class);
    }

    public TransferFundsResponse transferFunds(String idempotencyKey, TransferFundsRequest req) {
        return post("/internal/funds/transfer", idempotencyKey, req, TransferFundsResponse.class);
    }

    public InternalAccountDto getAccount(Long accountId) {
        return client.get()
                .uri("/internal/accounts/{id}", accountId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/accounts/" + accountId
                                    + " → " + response.getStatusCode());
                })
                .body(InternalAccountDto.class);
    }

    public List<String> getUserPermissions(String email) {
        String[] perms = client.get()
                .uri("/internal/users/{email}/permissions", email)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/users/.../permissions → " + response.getStatusCode());
                })
                .body(String[].class);
        return perms == null ? List.of() : List.of(perms);
    }

    /**
     * Vraca srednje devizne kurseve sa banka-core (GET /internal/fx/rates).
     * Koristi ga stock.ListingServiceImpl za FOREX cross-rate racun.
     */
    public List<FxRateDto> getFxRates() {
        FxRateDto[] rates = client.get()
                .uri("/internal/fx/rates")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/fx/rates → " + response.getStatusCode());
                })
                .body(FxRateDto[].class);
        return rates == null ? List.of() : List.of(rates);
    }

    // ── Identitet (faza 2c) ──────────────────────────────────────────────────

    /**
     * Razresava identitet (numericki id + rola) korisnika po email-u.
     * Na HTTP gresku (ukljucujuci 404) baca {@link BankaCoreClientException} —
     * pozivalac odlucuje sta dalje.
     */
    public InternalUserDto getUserByEmail(String email) {
        return client.get()
                .uri("/internal/users/by-email/{email}", email)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/users/by-email/" + email
                                    + " → " + response.getStatusCode());
                })
                .body(InternalUserDto.class);
    }

    /**
     * Razresava identitet (ime/prezime) korisnika po roli + id-u.
     */
    public InternalUserDto getUserById(String userRole, Long id) {
        return client.get()
                .uri("/internal/users/{userRole}/{id}", userRole, id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/users/" + userRole + "/" + id
                                    + " → " + response.getStatusCode());
                })
                .body(InternalUserDto.class);
    }

    /**
     * Verifikuje OTP kod za dati email (POST /internal/otp/verify).
     * Nije idempotentni funds poziv — bez X-Idempotency-Key header-a.
     * Vraca pun odgovor da pozivalac vidi i {@code blocked} (previse pokusaja).
     */
    public InternalOtpVerifyResponse verifyOtp(String email, String code) {
        return client.post()
                .uri("/internal/otp/verify")
                .body(new InternalOtpVerifyRequest(email, code))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core POST /internal/otp/verify → " + response.getStatusCode());
                })
                .body(InternalOtpVerifyResponse.class);
    }

    /**
     * Provizionira gotovinski (RSD) FUND racun za nov investicioni fond
     * (POST /internal/accounts/fund). Nije idempotentni funds poziv —
     * bez X-Idempotency-Key header-a.
     */
    public InternalAccountDto provisionFundAccount(String fundName, Long managerEmployeeId) {
        return client.post()
                .uri("/internal/accounts/fund")
                .body(new ProvisionFundAccountRequest(fundName, managerEmployeeId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core POST /internal/accounts/fund → " + response.getStatusCode());
                })
                .body(InternalAccountDto.class);
    }

    /**
     * Vraca bankin trading racun za datu valutu (kod).
     */
    public InternalAccountDto getBankTradingAccount(String currencyCode) {
        return client.get()
                .uri("/internal/accounts/bank-trading/{currencyCode}", currencyCode)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/accounts/bank-trading/" + currencyCode
                                    + " → " + response.getStatusCode());
                })
                .body(InternalAccountDto.class);
    }

    /**
     * Vraca zaposlene filtrirane po opcionim atributima (firstName / lastName /
     * email / position). Null/prazni filteri se izostavljaju iz query string-a.
     */
    public List<InternalUserDto> findEmployees(String firstName, String lastName,
                                               String email, String position) {
        InternalUserDto[] employees = client.get()
                .uri(uriBuilder -> {
                    UriBuilder b = uriBuilder.path("/internal/employees");
                    addQueryParamIfPresent(b, "firstName", firstName);
                    addQueryParamIfPresent(b, "lastName", lastName);
                    addQueryParamIfPresent(b, "email", email);
                    addQueryParamIfPresent(b, "position", position);
                    return b.build();
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/employees → " + response.getStatusCode());
                })
                .body(InternalUserDto[].class);
        return employees == null ? List.of() : List.of(employees);
    }

    /**
     * Jednostrani kredit racuna bez debit kontra-strane (SELL prihod, dividende).
     * Idempotentni funds poziv — zahteva X-Idempotency-Key.
     */
    public CreditFundsResponse creditFunds(String idempotencyKey, CreditFundsRequest req) {
        return post("/internal/funds/credit", idempotencyKey, req, CreditFundsResponse.class);
    }

    /**
     * Naplata poreza na kapitalnu dobit (debit klijent, credit drzava).
     * Idempotentni funds poziv — zahteva X-Idempotency-Key.
     */
    public TaxCollectResponse collectTax(String idempotencyKey, TaxCollectRequest req) {
        return post("/internal/funds/tax-collect", idempotencyKey, req, TaxCollectResponse.class);
    }

    private static void addQueryParamIfPresent(UriBuilder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.queryParam(name, value);
        }
    }

    private <T> T post(String path, String idempotencyKey, Object body, Class<T> responseType) {
        return client.post()
                .uri(path)
                .header("X-Idempotency-Key", idempotencyKey)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core POST " + path + " → " + response.getStatusCode());
                })
                .body(responseType);
    }
}
