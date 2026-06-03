package rs.raf.trading.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.CommitFundsResponse;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.CreditFundsResponse;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsResponse;
import rs.raf.banka2.contracts.internal.IdempotencyStatusResponse;
import rs.raf.banka2.contracts.internal.InterbankOtcExercisedDto;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalNotificationRequest;
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
@Slf4j
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
     * P2-tax-interbank-otc-1 — vraca sve EXERCISED inter-bank OTC ugovore sa
     * lokalnom stranom (GET /internal/interbank-otc/exercised). Koristi ga tax
     * engine ({@code TaxService}) da ukljuci realizovanu kapitalnu dobit lokalnih
     * CLIENT-ova iz inter-bank opcija u 15% obracun — banka-core nema tax modul,
     * pa ovi ugovori inace ne bi bili oporezovani nigde. READ-ONLY; ne dira 2PC
     * wire-protokol. Na HTTP gresku baca {@link BankaCoreClientException}.
     */
    public List<InterbankOtcExercisedDto> getExercisedInterbankOtc() {
        InterbankOtcExercisedDto[] contracts = client.get()
                .uri("/internal/interbank-otc/exercised")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/interbank-otc/exercised → " + response.getStatusCode());
                })
                .body(InterbankOtcExercisedDto[].class);
        return contracts == null ? List.of() : List.of(contracts);
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
     * OT-1061: vraca id-eve svih aktivnih supervizora (banka-core
     * {@code GET /internal/users/supervisors}). trading-service ga koristi da
     * razresi primaoce tax-FX-failure in-app notifikacije — nema lokalnu listu
     * supervizora. Na HTTP gresku baca {@link BankaCoreClientException}.
     */
    public List<Long> getSupervisorIds() {
        Long[] ids = client.get()
                .uri("/internal/users/supervisors")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/users/supervisors → " + response.getStatusCode());
                })
                .body(Long[].class);
        return ids == null ? List.of() : List.of(ids);
    }

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
     * Vraca podrazumevani racun OTC ucesnika u datoj valuti — za CLIENT klijentov
     * preferiran aktivan racun, za EMPLOYEE/ADMIN bankin trading racun (verno
     * monolitovom {@code OtcService.findDefaultAccount}). Na HTTP gresku
     * (ukljucujuci 404 — racun ne postoji) baca {@link BankaCoreClientException}.
     */
    public InternalAccountDto getPreferredAccount(String userRole, Long userId, String currencyCode) {
        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/accounts/preferred/{userRole}/{userId}")
                        .queryParam("currency", currencyCode)
                        .build(userRole, userId))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/accounts/preferred/" + userRole + "/" + userId
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
     * Jednostrani debit racuna bez credit kontra-strane (option exercise CALL,
     * margin createForUser pocetna uplata). Idempotentni funds poziv — zahteva
     * X-Idempotency-Key.
     */
    public DebitFundsResponse debitFunds(String idempotencyKey, DebitFundsRequest req) {
        return post("/internal/funds/debit", idempotencyKey, req, DebitFundsResponse.class);
    }

    /**
     * Naplata poreza na kapitalnu dobit (debit klijent, credit drzava).
     * Idempotentni funds poziv — zahteva X-Idempotency-Key.
     */
    public TaxCollectResponse collectTax(String idempotencyKey, TaxCollectRequest req) {
        return post("/internal/funds/tax-collect", idempotencyKey, req, TaxCollectResponse.class);
    }

    /**
     * <b>N4 (P0-T2):</b> autoritativan READ-ONLY upit banka-core-u da li je dati
     * idempotency kljuc VEC KONZUMIRAN (operacija sa tim kljucem zaista izvrsena).
     * Koristi ga OTC SAGA recovery (C3) da utvrdi da li je F3 {@code creditFunds}
     * proslo pre pada — pa da bira pun reverzni transfer (consumed) vs commit-only
     * refund kupcu (ne-consumed), umesto pogadjanja po write-ahead flag-ovima.
     * Ne menja stanje (GET). Na HTTP gresku baca {@link BankaCoreClientException}.
     */
    public boolean isIdempotencyKeyConsumed(String idempotencyKey) {
        IdempotencyStatusResponse resp = client.get()
                .uri("/internal/funds/idempotency/{key}", idempotencyKey)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new BankaCoreClientException(response.getStatusCode().value(),
                            "banka-core GET /internal/funds/idempotency/" + idempotencyKey
                                    + " → " + response.getStatusCode());
                })
                .body(IdempotencyStatusResponse.class);
        return resp != null && resp.consumed();
    }

    /**
     * Cross-DB perzistencija in-app notifikacije u banka-core
     * {@code notifications} tabelu. Best-effort: bilo koja greska (broker down,
     * banka-core 5xx, mrezni timeout) se loguje na WARN i NE ruzi pozivni
     * business flow. Trading-service nezavisno publishuje RabbitMQ email
     * event, tako da nestanak in-app notifikacije nije fatalan.
     *
     * <p>Idempotency: {@link InternalNotificationRequest#idempotencyKey()} (obicno
     * UUID); banka-core kesira odgovor i pri retry-u vraca 200 OK bez novog
     * upisa u {@code notifications}. Pozivalac generise nov UUID po pozivu.
     */
    public void postNotification(InternalNotificationRequest req) {
        try {
            client.post()
                    .uri("/internal/notifications")
                    .body(req)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new BankaCoreClientException(response.getStatusCode().value(),
                                "banka-core POST /internal/notifications → " + response.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Failed to persist in-app notification in banka-core (recipient={} {}, type={}): {}",
                    req != null ? req.recipientType() : null,
                    req != null ? req.recipientId() : null,
                    req != null ? req.type() : null,
                    ex.getMessage());
        }
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
