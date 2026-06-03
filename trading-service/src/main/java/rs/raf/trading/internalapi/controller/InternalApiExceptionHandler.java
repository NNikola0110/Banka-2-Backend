package rs.raf.trading.internalapi.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.banka2.contracts.internal.InternalErrorDto;

/**
 * Exception mapper za trading-service interni API ({@code rs.raf.trading.internalapi}).
 * Mirror banka-core {@code internalapi.controller.InternalApiExceptionHandler}.
 *
 * <p>Mapiranja:
 * <ul>
 *   <li>{@code IllegalArgumentException} &rarr; 404 NOT_FOUND (listing/portfolio ne postoji)</li>
 *   <li>{@code IllegalStateException} &rarr; 409 CONFLICT (nedovoljno raspolozive kolicine)</li>
 *   <li>{@code MethodArgumentNotValidException} &rarr; 400 BAD_REQUEST (R5 1883)</li>
 *   <li>{@code Exception} (ostalo) &rarr; 500 INTERNAL_ERROR (R4 1777 — staticna poruka, log server-side)</li>
 * </ul>
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} — interni API mora da pobedi
 * {@code TradingGlobalExceptionHandler} (koji {@code IllegalArgumentException}
 * mapira u 400, {@code IllegalStateException} u 403). banka-core
 * {@code TradingServiceInternalClient} se oslanja na 404/409 razliku da
 * razresi {@code NO_SUCH_ASSET} vs {@code INSUFFICIENT_QUANTITY}.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "rs.raf.trading.internalapi")
public class InternalApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<InternalErrorDto> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new InternalErrorDto("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<InternalErrorDto> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new InternalErrorDto("CONFLICT", ex.getMessage()));
    }

    /**
     * R5 1883 — nevalidan @RequestBody na internom API-ju je klijentska greska (400),
     * ne 500. Pre fix-a je padala na {@link #handleGeneral} catch-all → 500.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<InternalErrorDto> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("Validation error");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new InternalErrorDto("BAD_REQUEST", message));
    }

    /**
     * R4 1777 — INFO-DISCLOSURE fix: 500-handler vise NE vraca {@code ex.getMessage()}
     * (SQL/constraint detalj bi curio preko /internal kanala). Staticna poruka +
     * server-side log.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<InternalErrorDto> handleGeneral(Exception ex) {
        log.error("Internal API neocekivana greska", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new InternalErrorDto("INTERNAL_ERROR", "Interna greska servisa."));
    }
}
