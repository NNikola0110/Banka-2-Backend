package rs.raf.banka2_bek.internalapi.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.banka2.contracts.internal.InternalErrorDto;
import rs.raf.banka2_bek.internalapi.service.UnknownUserRoleException;

/**
 * Exception mapper za interni API (rs.raf.banka2_bek.internalapi paket).
 *
 * Mapiranja:
 *   UnknownUserRoleException        → 400 BAD_REQUEST  (R1 402 — nevalidan {userRole} segment, nije 404)
 *   IllegalArgumentException        → 404 NOT_FOUND   (racun/rezervacija ne postoji, pogresna valuta, ...)
 *   IllegalStateException           → 409 CONFLICT     (nedovoljno sredstava, neaktivna rezervacija, ...)
 *   MethodArgumentNotValidException → 400 BAD_REQUEST  (R5 1883 — los request payload nije 500)
 *   Exception (ostalo)              → 500 INTERNAL_ERROR (R4 1777 — STATICNA poruka, bez leak-a)
 *
 * {@code @Order(HIGHEST_PRECEDENCE)} — interni API mora da pobedi globalni
 * {@code GlobalExceptionHandler} (koji IllegalArgumentException mapira u 400);
 * @RestControllerAdvice samo po basePackages ne garantuje prioritet.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "rs.raf.banka2_bek.internalapi")
public class InternalApiExceptionHandler {

    /**
     * R1 402 — nevalidan {@code {userRole}} putanja-segment je BAD_REQUEST (400),
     * ne NOT_FOUND (404). Specifičniji od {@link #handleIllegalArgument} (Spring
     * bira najuzi tip), pa "Client/Employee not found" i dalje vraca 404.
     */
    @ExceptionHandler(UnknownUserRoleException.class)
    public ResponseEntity<InternalErrorDto> handleUnknownUserRole(UnknownUserRoleException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new InternalErrorDto("BAD_REQUEST", ex.getMessage()));
    }

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
     * ne 500. Pre fix-a je {@link MethodArgumentNotValidException} (RuntimeException)
     * padala na {@link #handleGeneral} catch-all → 500 INTERNAL_ERROR.
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
     * (sirov SQL/constraint/stacktrace detalj curi preko /internal kanala). Vracamo
     * STATICNU poruku, a pravi uzrok logujemo server-side za dijagnostiku.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<InternalErrorDto> handleGeneral(Exception ex) {
        log.error("Internal API neocekivana greska", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new InternalErrorDto("INTERNAL_ERROR", "Interna greska servisa."));
    }
}
