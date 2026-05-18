package rs.raf.banka2_bek.internalapi.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.banka2.contracts.internal.InternalErrorDto;

/**
 * Exception mapper za interni API (rs.raf.banka2_bek.internalapi paket).
 *
 * Mapiranja:
 *   IllegalArgumentException  → 404 NOT_FOUND   (racun/rezervacija ne postoji, pogresna valuta, ...)
 *   IllegalStateException     → 409 CONFLICT     (nedovoljno sredstava, neaktivna rezervacija, ...)
 *   Exception (ostalo)        → 500 INTERNAL_ERROR
 */
@RestControllerAdvice(basePackages = "rs.raf.banka2_bek.internalapi")
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
