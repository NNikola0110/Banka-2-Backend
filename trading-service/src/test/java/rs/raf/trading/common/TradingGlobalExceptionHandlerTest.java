package rs.raf.trading.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.dto.MessageResponseDto;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test {@link TradingGlobalExceptionHandler} — fokus na H1 hardening: mapiranje
 * {@link BankaCoreClientException} (greska banka-core internog {@code /internal/**}
 * API-ja) po upstream HTTP statusu.
 *
 * <p>Pre H1 je nehvtaceni {@code BankaCoreClientException} padao na
 * {@code RuntimeException} catch-all → 400 Bad Request, sto je pogresno za
 * upstream-interni/nedostupan kvar. H1 mapira: banka-core 503 → 503, ostali 5xx →
 * 502, 404 → 404, 409 → 409, ostali 4xx → 500.
 */
class TradingGlobalExceptionHandlerTest {

    private TradingGlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TradingGlobalExceptionHandler();
    }

    @Test
    @DisplayName("BankaCoreClientException upstream 500 → 502 BAD_GATEWAY")
    void bankaCore500_mapsToBadGateway() {
        ResponseEntity<MessageResponseDto> response = handler.handleBankaCoreClientException(
                new BankaCoreClientException(500, "interna greska banka-core"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .contains("Interni servis nedostupan")
                .contains("interna greska banka-core");
    }

    @Test
    @DisplayName("BankaCoreClientException upstream 503 → 503 SERVICE_UNAVAILABLE")
    void bankaCore503_mapsToServiceUnavailable() {
        ResponseEntity<MessageResponseDto> response = handler.handleBankaCoreClientException(
                new BankaCoreClientException(503, "banka-core privremeno nedostupan"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .contains("Interni servis privremeno nedostupan");
    }

    @Test
    @DisplayName("BankaCoreClientException upstream 502 (ostali 5xx) → 502 BAD_GATEWAY")
    void bankaCore502_mapsToBadGateway() {
        ResponseEntity<MessageResponseDto> response = handler.handleBankaCoreClientException(
                new BankaCoreClientException(502, "bad gateway upstream"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("BankaCoreClientException upstream 404 → 404 NOT_FOUND")
    void bankaCore404_mapsToNotFound() {
        ResponseEntity<MessageResponseDto> response = handler.handleBankaCoreClientException(
                new BankaCoreClientException(404, "racun ne postoji"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("racun ne postoji");
    }

    @Test
    @DisplayName("BankaCoreClientException upstream 409 → 409 CONFLICT")
    void bankaCore409_mapsToConflict() {
        ResponseEntity<MessageResponseDto> response = handler.handleBankaCoreClientException(
                new BankaCoreClientException(409, "nedovoljno sredstava"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("nedovoljno sredstava");
    }

    @Test
    @DisplayName("BankaCoreClientException upstream ostali 4xx (npr. 400) → 500 — nesklad internog API ugovora")
    void bankaCoreUnhandled4xx_mapsToInternalServerError() {
        ResponseEntity<MessageResponseDto> response = handler.handleBankaCoreClientException(
                new BankaCoreClientException(400, "los zahtev ka internom API-ju"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .contains("Greska internog API ugovora")
                .contains("los zahtev ka internom API-ju");
    }

    // ── ResponseStatusException → propagira originalni status (26.05 fix) ──
    // Pre fix-a: ResponseStatusException(404) je padao na handleRuntimeException
    // (RuntimeException catch-all) i bio rewrap-ovan u 400. Sada postoji specifican
    // @ExceptionHandler(ResponseStatusException) koji propagira originalni status.

    @Test
    @DisplayName("ResponseStatusException(404) propagira 404 NOT_FOUND (ne 400)")
    void responseStatusException_notFound_propagates404() {
        ResponseEntity<MessageResponseDto> response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Nema predikcije za simbol: AAPL"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Nema predikcije za simbol: AAPL");
    }

    @Test
    @DisplayName("ResponseStatusException(409) propagira 409 CONFLICT")
    void responseStatusException_conflict_propagates409() {
        ResponseEntity<MessageResponseDto> response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.CONFLICT, "Order vec u izvrsenju"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Order vec u izvrsenju");
    }

    @Test
    @DisplayName("ResponseStatusException bez reason → fallback na ex.getMessage()")
    void responseStatusException_noReason_fallsBackToMessage() {
        ResponseEntity<MessageResponseDto> response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        // ex.getMessage() format: "503 SERVICE_UNAVAILABLE"
        assertThat(response.getBody().getMessage()).contains("503");
    }

    @Test
    @DisplayName("plain RuntimeException i dalje → 400 (BankaCoreClientException handler ga ne zasenjuje)")
    void plainRuntimeException_stillMapsToBadRequest() {
        ResponseEntity<MessageResponseDto> response = handler.handleRuntimeException(
                new RuntimeException("generic greska"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("generic greska");
    }

    // ── OptimisticLockingFailureException → 409 CONFLICT (P2 MINOR fix) ──
    // @Version + concurrent update -> ranije generic 500. UI sad ima jasan
    // 409 CONFLICT signal sa SR porukom za retry.

    @Test
    @DisplayName("OptimisticLockingFailureException → 409 CONFLICT sa SR porukom")
    void optimisticLockingFailure_mapsTo409() {
        org.springframework.dao.OptimisticLockingFailureException ex =
                new org.springframework.dao.OptimisticLockingFailureException("Stale entity version");

        ResponseEntity<MessageResponseDto> response = handler.handleOptimisticLock(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("modifikovan");
    }

    @Test
    @DisplayName("jakarta OptimisticLockException → 409 CONFLICT sa SR porukom")
    void jpaOptimisticLockException_mapsTo409() {
        jakarta.persistence.OptimisticLockException ex =
                new jakarta.persistence.OptimisticLockException("Row was updated by another transaction");

        ResponseEntity<MessageResponseDto> response = handler.handleJpaOptimisticLock(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("modifikovan");
    }

    @Test
    @DisplayName("TransactionSystemException sa OptimisticLockingFailureException cause → 409 CONFLICT")
    void transactionSystemException_optimisticLockCause_mapsTo409() {
        // Spring AOP cesto wrap-uje OptimisticLockingFailureException u TransactionSystemException
        // na commit fazi @Transactional-a — moramo unwrap-ovati i vratiti 409 umesto 400.
        org.springframework.dao.OptimisticLockingFailureException root =
                new org.springframework.dao.OptimisticLockingFailureException("Stale entity");
        org.springframework.transaction.TransactionSystemException tx =
                new org.springframework.transaction.TransactionSystemException("Could not commit JPA transaction", root);

        ResponseEntity<MessageResponseDto> response = handler.handleTransactionSystemException(tx);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("modifikovan");
    }

    // ── P2-error-contract-2 R1 409 — nedovoljno sredstava/hartija → 409 (ne 400) ──

    @Test
    @DisplayName("InsufficientFundsException → 409 CONFLICT (ne 400)")
    void insufficientFunds_mapsTo409() {
        ResponseEntity<MessageResponseDto> response = handler.handleInsufficient(
                new rs.raf.trading.order.exception.InsufficientFundsException("Nedovoljno sredstava"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Nedovoljno sredstava");
    }

    @Test
    @DisplayName("InsufficientHoldingsException → 409 CONFLICT (ne 400)")
    void insufficientHoldings_mapsTo409() {
        ResponseEntity<MessageResponseDto> response = handler.handleInsufficient(
                new rs.raf.trading.order.exception.InsufficientHoldingsException("Nedovoljno hartija"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getMessage()).isEqualTo("Nedovoljno hartija");
    }
}
