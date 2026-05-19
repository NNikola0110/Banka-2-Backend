package rs.raf.banka2_bek.interbank.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import rs.raf.banka2.contracts.internal.CommitStockRequest;
import rs.raf.banka2.contracts.internal.CommitStockResponse;
import rs.raf.banka2.contracts.internal.InternalErrorDto;
import rs.raf.banka2.contracts.internal.InternalListingDto;
import rs.raf.banka2.contracts.internal.InternalPortfolioHoldingDto;
import rs.raf.banka2.contracts.internal.InternalPublicStockSellerDto;
import rs.raf.banka2.contracts.internal.ReassignFundManagerRequest;
import rs.raf.banka2.contracts.internal.ReassignFundManagerResponse;
import rs.raf.banka2.contracts.internal.ReleaseStockRequest;
import rs.raf.banka2.contracts.internal.ReleaseStockResponse;
import rs.raf.banka2.contracts.internal.ReserveStockRequest;
import rs.raf.banka2.contracts.internal.ReserveStockResponse;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * HTTP klijent ka trading-service internom {@code /internal/**} seam-u
 * (faza 2f). Mirror trading-service-ovog {@code BankaCoreClient} — drugi smer
 * iste interne komunikacije.
 *
 * <p>Posle 2f cutover-a {@code portfolios}/{@code listings}/{@code investment_funds}
 * tabele zive samo u trading_db. banka-core vise ne radi in-process JPA pristup
 * tim tabelama nego ih cita/menja preko ovog klijenta:
 * <ul>
 *   <li>{@code interbank} paket (inter-bank OTC + 2PC settlement) → {@code /internal/portfolio/**};</li>
 *   <li>{@code employee} paket (bulk reassign menadzera fondova) → {@code /internal/funds/**}.</li>
 * </ul>
 * Pozivalac (npr. {@code InterbankReservationApplier}, {@code TransactionExecutorService})
 * prevodi {@link TradingServiceClientException} u {@code InterbankExceptions} tipove.
 *
 * <p>Mutirajuci pozivi (reserve/commit/release/reassign-manager) salju
 * {@code X-Idempotency-Key} — trading-service kesira odgovor tako da retry ne
 * primeni operaciju dvaput.
 */
@Component
public class TradingServiceInternalClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public TradingServiceInternalClient(
            @Qualifier("tradingServiceRestClient") RestClient tradingServiceRestClient,
            ObjectMapper objectMapper) {
        this.client = tradingServiceRestClient;
        this.objectMapper = objectMapper;
    }

    // ── Mutirajuci pozivi (idempotentni) ─────────────────────────────────────

    /** Rezervise hartije u portfoliju vlasnika (inter-bank OTC 2PC prepare faza). */
    public ReserveStockResponse reserveStock(String idempotencyKey, ReserveStockRequest req) {
        return postIdempotent("/internal/portfolio/reserve-stock", idempotencyKey, req,
                ReserveStockResponse.class);
    }

    /** Commit kretanja hartija (inter-bank OTC 2PC commit faza). */
    public CommitStockResponse commitStock(String idempotencyKey, CommitStockRequest req) {
        return postIdempotent("/internal/portfolio/commit-stock", idempotencyKey, req,
                CommitStockResponse.class);
    }

    /** Oslobadja rezervisane hartije (inter-bank OTC 2PC rollback kompenzacija). */
    public ReleaseStockResponse releaseStock(String idempotencyKey, ReleaseStockRequest req) {
        return postIdempotent("/internal/portfolio/release-stock", idempotencyKey, req,
                ReleaseStockResponse.class);
    }

    /**
     * Bulk prebacivanje vlasnistva nad fondovima — svi fondovi kojima upravlja
     * {@code oldManagerEmployeeId} dobijaju {@code newManagerEmployeeId} kao novog
     * menadzera. Poziva ga {@code employee} paket kada admin oduzme SUPERVISOR
     * permisiju supervizoru.
     *
     * <p>Idempotency kljuc je deterministican ({@code reassign-mgr-{old}-{new}}):
     * operacija je apsolutan JPA {@code update} keyed na {@code oldManagerEmployeeId},
     * pa ponovljen poziv vraca kesiran broj iz prvog poziva.
     *
     * @return broj fondova kojima je promenjen menadzer
     */
    public ReassignFundManagerResponse reassignFundManager(Long oldManagerEmployeeId,
                                                           Long newManagerEmployeeId) {
        String idempotencyKey = "reassign-mgr-" + oldManagerEmployeeId + "-" + newManagerEmployeeId;
        return postIdempotent("/internal/funds/reassign-manager", idempotencyKey,
                new ReassignFundManagerRequest(oldManagerEmployeeId, newManagerEmployeeId),
                ReassignFundManagerResponse.class);
    }

    // ── Read-side pozivi ─────────────────────────────────────────────────────

    /**
     * Vraca listing po ticker-u. {@code Optional.empty()} ako trading-service
     * vrati 404 (ticker ne postoji) — banka-core validacija to mapira u
     * {@code NO_SUCH_ASSET}. Ostale HTTP greske se propagiraju kao izuzetak.
     */
    public Optional<InternalListingDto> findListingByTicker(String ticker) {
        try {
            InternalListingDto dto = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/internal/portfolio/listing")
                            .queryParam("ticker", ticker)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new TradingServiceClientException(response.getStatusCode().value(),
                                errorMessage(response, "GET /internal/portfolio/listing"));
                    })
                    .body(InternalListingDto.class);
            return Optional.ofNullable(dto);
        } catch (TradingServiceClientException e) {
            if (e.getHttpStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Vraca portfolio poziciju vlasnika za listing odredjen ticker-om.
     * {@code exists=false} ako listing/portfolio ne postoji.
     */
    public InternalPortfolioHoldingDto findHolding(Long userId, String userRole, String ticker) {
        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/portfolio/holding")
                        .queryParam("userId", userId)
                        .queryParam("userRole", userRole)
                        .queryParam("ticker", ticker)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new TradingServiceClientException(response.getStatusCode().value(),
                            errorMessage(response, "GET /internal/portfolio/holding"));
                })
                .body(InternalPortfolioHoldingDto.class);
    }

    /** Vraca sve javno-vidljive pozicije ({@code publicQuantity > 0}) — protokol §3.1. */
    public List<InternalPublicStockSellerDto> findAllPublicStock() {
        InternalPublicStockSellerDto[] arr = client.get()
                .uri("/internal/portfolio/public-stock")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new TradingServiceClientException(response.getStatusCode().value(),
                            errorMessage(response, "GET /internal/portfolio/public-stock"));
                })
                .body(InternalPublicStockSellerDto[].class);
        return arr == null ? List.of() : List.of(arr);
    }

    /**
     * Vraca javno-vidljive pozicije konkretnog vlasnika za odredjeni ticker —
     * kvota provera pri inbound §3.2 createNegotiation.
     */
    public List<InternalPublicStockSellerDto> findPublicStockForSeller(Long userId, String userRole,
                                                                       String ticker) {
        InternalPublicStockSellerDto[] arr = client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/portfolio/public-stock")
                        .queryParam("userId", userId)
                        .queryParam("userRole", userRole)
                        .queryParam("ticker", ticker)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new TradingServiceClientException(response.getStatusCode().value(),
                            errorMessage(response, "GET /internal/portfolio/public-stock"));
                })
                .body(InternalPublicStockSellerDto[].class);
        return arr == null ? List.of() : List.of(arr);
    }

    // ── Pomocne metode ───────────────────────────────────────────────────────

    private <T> T postIdempotent(String path, String idempotencyKey, Object body,
                                 Class<T> responseType) {
        return client.post()
                .uri(path)
                .header("X-Idempotency-Key", idempotencyKey)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new TradingServiceClientException(response.getStatusCode().value(),
                            errorMessage(response, "POST " + path));
                })
                .body(responseType);
    }

    /**
     * Cita {@code InternalErrorDto.message} iz tela trading-service-ovog error
     * odgovora; fallback na status string ako telo nije citljivo.
     */
    private String errorMessage(org.springframework.http.client.ClientHttpResponse response,
                                String requestDescription) {
        try {
            byte[] raw = response.getBody().readAllBytes();
            if (raw.length > 0) {
                InternalErrorDto err = objectMapper.readValue(raw, InternalErrorDto.class);
                if (err != null && err.message() != null && !err.message().isBlank()) {
                    return err.message();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // telo nije InternalErrorDto JSON — padamo na generic poruku
        }
        try {
            return requestDescription + " → " + response.getStatusCode();
        } catch (IOException e) {
            return requestDescription + " → trading-service error";
        }
    }
}
