package rs.raf.banka2_bek.interbank.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.banka2.contracts.error.ApiErrorDto;
import rs.raf.banka2_bek.interbank.controller.InterbankInboundController;
import lombok.extern.slf4j.Slf4j;

/**
 * Exception mapper za inbound bank-to-bank protokol ({@link InterbankInboundController}).
 *
 * <p><b>R4 1778 (P2-error-contract-2):</b> pre fix-a su protocol/json handleri vracali
 * SIROV plain-text body sa {@code e.getMessage()} — kod {@link JsonProcessingException}
 * to je curilo interne Jackson detalje (lokacija parsera, naziv polja, ocekivani tip)
 * partner-banci. Sada vracaju JSON {@link ApiErrorDto}{message} sa SANITIZOVANOM
 * porukom (bez Jackson internih detalja); status kod ostaje isti (protokol-ugovor je
 * status, ne telo). Auth/general handleri zadrzavaju prazno telo (nema sta da curi).
 */
@Slf4j
@RestControllerAdvice(assignableTypes = InterbankInboundController.class)
public class InterbankExceptionHandler {


    @ExceptionHandler(InterbankExceptions.InterbankAuthException.class)
    public ResponseEntity<Void> handleAuth(InterbankExceptions.InterbankAuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler(InterbankExceptions.InterbankProtocolException.class)
    public ResponseEntity<ApiErrorDto> handleProtocol(InterbankExceptions.InterbankProtocolException e) {
        // Protokol-validaciona poruka je nasa domenska (npr. "Unbalanced transaction") —
        // bezbedna za partnera, ali je serviramo kao JSON {message} radi konzistentnog oblika.
        return ResponseEntity.badRequest().body(ApiErrorDto.of(e.getMessage()));
    }

    /**
     * TEST-interbank-3: malformed/incomplete NEW_TX body (npr. {@code postings == null}
     * ili prazan postings, null transactionId) baca {@link IllegalArgumentException} iz
     * {@code TransactionExecutorService.handleNewTx}. Po Tim 2 spec §6.1 ovakav
     * incomplete inbound mora biti <b>400 Bad Request</b> sa razumnom porukom — pre fix-a
     * je padao u {@code handleGeneral(Exception)} → <b>500</b>, sto je partneru izgledalo
     * kao nasa interna greska umesto njihov malformed payload. Poruka je nasa domenska
     * (npr. "transaction.postings is required ...") — bezbedna za partnera.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorDto> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiErrorDto.of(e.getMessage()));
    }

    @ExceptionHandler(JsonProcessingException.class)
    public ResponseEntity<ApiErrorDto> handleJson(JsonProcessingException e) {
        // R4 1778 — NE prosledjuj e.getMessage() (Jackson interni detalji = info-disclosure).
        log.warn("Inbound envelope malformed JSON: {}", e.getOriginalMessage());
        return ResponseEntity.badRequest().body(ApiErrorDto.of("Malformed envelope."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleGeneral(Exception e) {
        log.error("Unexpected error processing inbound message", e);
        return ResponseEntity.internalServerError().build();
    }
}
