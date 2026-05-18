package rs.raf.trading.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CommitFundsResponse;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsResponse;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.banka2.contracts.internal.TransferFundsResponse;

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
