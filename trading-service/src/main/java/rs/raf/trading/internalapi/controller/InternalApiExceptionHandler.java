package rs.raf.trading.internalapi.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 *   <li>{@code Exception} (ostalo) &rarr; 500 INTERNAL_ERROR</li>
 * </ul>
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} — interni API mora da pobedi
 * {@code TradingGlobalExceptionHandler} (koji {@code IllegalArgumentException}
 * mapira u 400, {@code IllegalStateException} u 403). banka-core
 * {@code TradingServiceInternalClient} se oslanja na 404/409 razliku da
 * razresi {@code NO_SUCH_ASSET} vs {@code INSUFFICIENT_QUANTITY}.
 */
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<InternalErrorDto> handleGeneral(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new InternalErrorDto("INTERNAL_ERROR", ex.getMessage()));
    }
}
