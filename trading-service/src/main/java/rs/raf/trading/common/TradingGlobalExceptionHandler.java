package rs.raf.trading.common;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.dto.MessageResponseDto;

/**
 * Globalni (app-wide) exception handler za trading-service.
 *
 * <p>NAPOMENA (copy-first ekstrakcija, faza 2c): u monolitu su aktuarski (i ostali)
 * kontroleri za greske koje njihov scoped handler ne hvata padali na monolitni
 * {@code rs.raf.banka2_bek.auth.config.GlobalExceptionHandler}. trading-service je
 * do sada imao samo scoped handlere ({@code ActuaryExceptionHandler},
 * {@code ListingExceptionHandler}), pa su nehvtaceni izuzeci (npr. bare
 * {@code RuntimeException} iz {@code ActuaryServiceImpl.updateAgentLimit} kad cilj
 * nije agent) izlazili kao 5xx. Ovaj advice preslikava monolitne mapinge tako da
 * domenske greske vracaju verne 4xx statuse.</p>
 *
 * <p>Preslikani mapinzi monolitnog {@code GlobalExceptionHandler}-a:
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} &rarr; 400 (prva field greska)</li>
 *   <li>{@link EntityNotFoundException} &rarr; 404</li>
 *   <li>{@link IllegalArgumentException} &rarr; 400</li>
 *   <li>{@link IllegalStateException} &rarr; 403</li>
 *   <li>{@link AccessDeniedException} &rarr; 403</li>
 *   <li>{@link TransactionSystemException} &rarr; unwrap cause &rarr; 404/403/400</li>
 *   <li>{@link RuntimeException} (catch-all) &rarr; 400</li>
 * </ul>
 * Telo odgovora je {@link MessageResponseDto} ({@code {"message": ...}}) — isti
 * oblik koji monolitni {@code GlobalExceptionHandler} koristi.</p>
 *
 * <p>Scoped {@code @RestControllerAdvice(assignableTypes = ...)} handleri zadrzavaju
 * prednost za svoje kontrolere — Spring bira advice po specificnosti, a uz
 * {@code @Order(HIGHEST_PRECEDENCE)} na scoped handlerima i {@code LOWEST_PRECEDENCE}
 * ovde redosled je determinisitkan.</p>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class TradingGlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MessageResponseDto> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("Validation error");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new MessageResponseDto(message));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<MessageResponseDto> handleEntityNotFound(EntityNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<MessageResponseDto> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /**
     * Bare {@code IllegalStateException} se app-wide mapira na 403 FORBIDDEN jer ga
     * vecina modula (order/actuary/investmentfund/stock) koristi kao invarijant/authz-denial
     * signal. Genuini state-conflict-i u tim modulima nose TIPIZIRAN izuzetak
     * ({@code OrderStateConflictException}/{@code FundInactiveException}&rarr;409), pa ne
     * padaju ovde.
     *
     * <p>NAMERNA DIVERGENCIJA: {@code OtcExceptionHandler} i {@code InternalApiExceptionHandler}
     * (scoped, HIGHEST_PRECEDENCE) mapiraju {@code IllegalStateException}&rarr;409 jer u tim
     * kontekstima on UVEK znaci state-conflict (OTC ugovor/ponuda u losem statusu; interni
     * API nedovoljna kolicina — banka-core klijent se oslanja na 409 za INSUFFICIENT_QUANTITY).
     * Status zavisi od modula koji baca i to je svesna konvencija (dokumentovano u oba scoped
     * handler-a), ne nekonzistentnost koju treba "popraviti" remapiranjem (to bi obrnulo
     * postojece 403 ugovore i polomilo zelene web-test suite).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<MessageResponseDto> handleForbidden(IllegalStateException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<MessageResponseDto> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /**
     * Spring AOP wrap-uje nehvtacene RuntimeException-e koji padaju na commit fazi
     * {@code @Transactional} metode u {@link TransactionSystemException}. Pravu
     * poruku korisnika dobijemo unwrap-ovanjem cause lanca i mapiramo poznate
     * tipove u prikladan HTTP status (isto kao monolitni GlobalExceptionHandler).
     */
    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<MessageResponseDto> handleTransactionSystemException(TransactionSystemException ex) {
        Throwable cause = ex.getMostSpecificCause();
        while (cause != null
                && cause.getCause() != null
                && cause.getCause() != cause
                && (cause instanceof TransactionException
                    || cause instanceof DataAccessException)) {
            cause = cause.getCause();
        }
        String message = cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : "Doslo je do greske prilikom obrade zahteva.";
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (cause instanceof EntityNotFoundException) {
            status = HttpStatus.NOT_FOUND;
        } else if (cause instanceof AccessDeniedException) {
            status = HttpStatus.FORBIDDEN;
        } else if (cause instanceof org.springframework.dao.OptimisticLockingFailureException
                || cause instanceof jakarta.persistence.OptimisticLockException) {
            // @Version concurrent update na commit fazi -> 409 (UI moze da retry-uje).
            status = HttpStatus.CONFLICT;
            message = "Resurs je u medjuvremenu modifikovan. Osvezite stranicu i pokusajte ponovo.";
        }
        return ResponseEntity.status(status).body(new MessageResponseDto(message));
    }

    /**
     * Spring Data JPA {@code @Version} + concurrent update -> ranije Spring vracao
     * generic 500. Sad 409 CONFLICT sa jasnom porukom za korisnika — UI moze da
     * uradi retry sa fresh state-om (refresh stranice).
     *
     * <p>Relevantno za trading-service: Order/Portfolio/MarginAccount/FundAccount
     * imaju {@code @Version} kolonu i racunari mogu imati concurrent update-e
     * (npr. dva agenta paralelno menjaju istu rezervaciju margina).
     *
     * <p>P2 MINOR fix iz OPEN_TASKS.md — semanticki ispravniji status code za
     * optimistic locking failure (409 = client retry-able conflict).
     */
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<MessageResponseDto> handleOptimisticLock(
            org.springframework.dao.OptimisticLockingFailureException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new MessageResponseDto(
                        "Resurs je u medjuvremenu modifikovan. Osvezite stranicu i pokusajte ponovo."));
    }

    /**
     * Pokriva i jakarta {@code OptimisticLockException} koja moze biti bacena direktno
     * iz JPA provider-a (Hibernate) pre nego sto Spring zavrsi conversion u
     * {@link org.springframework.dao.OptimisticLockingFailureException}.
     */
    @ExceptionHandler(jakarta.persistence.OptimisticLockException.class)
    public ResponseEntity<MessageResponseDto> handleJpaOptimisticLock(
            jakarta.persistence.OptimisticLockException ex) {
        return handleOptimisticLock(
                new org.springframework.dao.OptimisticLockingFailureException(ex.getMessage(), ex));
    }

    /**
     * Greska pri pozivu banka-core internog {@code /internal/**} API-ja.
     *
     * <p>{@link BankaCoreClientException} nosi upstream HTTP status koji je vratio
     * banka-core. Servisi koji ocekuju domensku gresku (npr. {@code OptionService}
     * pri 409 = nedovoljno sredstava) sami hvataju {@code BankaCoreClientException}
     * i bacaju domensku gresku — ovaj handler hvata SAMO {@code BankaCoreClientException}
     * koji je izmakao servisu (nepredvidjen upstream pad / nedostupnost). Pre ovog
     * handler-a takav izuzetak je padao na {@code RuntimeException} catch-all → 400,
     * sto je pogresno: banka-core 500/503 je upstream kvar, ne losa klijentska
     * poruka. Mapiranje po upstream statusu:
     * <ul>
     *   <li>upstream 503 &rarr; 503 (interni servis privremeno nedostupan);</li>
     *   <li>upstream ostali 5xx &rarr; 502 (interni servis vratio gresku);</li>
     *   <li>upstream 404 &rarr; 404 (resurs ne postoji u internom servisu);</li>
     *   <li>upstream 409 &rarr; 409 (konflikt — konzistentno sa servisnim mapiranjem
     *       nedovoljnih sredstava);</li>
     *   <li>upstream ostali 4xx &rarr; 500 (genuini upstream 4xx koji nijedan servis
     *       nije obradio ukazuje na nesklad ugovora internog API-ja).</li>
     * </ul>
     */
    @ExceptionHandler(BankaCoreClientException.class)
    public ResponseEntity<MessageResponseDto> handleBankaCoreClientException(BankaCoreClientException ex) {
        int upstream = ex.getHttpStatus();
        HttpStatus status;
        String message;
        if (upstream == 503) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "Interni servis privremeno nedostupan: " + ex.getMessage();
        } else if (upstream >= 500) {
            status = HttpStatus.BAD_GATEWAY;
            message = "Interni servis nedostupan: " + ex.getMessage();
        } else if (upstream == 404) {
            status = HttpStatus.NOT_FOUND;
            message = ex.getMessage();
        } else if (upstream == 409) {
            status = HttpStatus.CONFLICT;
            message = ex.getMessage();
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "Greska internog API ugovora: " + ex.getMessage();
        }
        return ResponseEntity.status(status).body(new MessageResponseDto(message));
    }

    /**
     * Propagira originalni HTTP status iz {@link ResponseStatusException}.
     * Bez ovog handler-a, generic {@link RuntimeException} catch-all bi pretvorio
     * sve {@code ResponseStatusException} u 400 (npr. {@code PricePredictionController}
     * baca {@code ResponseStatusException(NOT_FOUND, ...)} kad nema predikcije za
     * simbol — to mora ostati 404 da FE moze gracefully sakriti widget).
     *
     * <p>Spring bira najspecificniji exception type, pa ovaj handler ima prioritet
     * nad {@link #handleRuntimeException} za {@code ResponseStatusException}.</p>
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<MessageResponseDto> handleResponseStatusException(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return ResponseEntity
                .status(ex.getStatusCode())
                .body(new MessageResponseDto(message));
    }

    /**
     * R1 409 — nedovoljno sredstava / nedovoljno hartija je 409 Conflict (stanje
     * resursa konfliktuje sa zahtevom), ne 400 Bad Request (payload je validan).
     * Konzistentno sa internim API mapiranjem (banka-core reserve daje 409) i sa
     * {@code OtcExceptionHandler}/{@code OrderExceptionHandler}.
     */
    @ExceptionHandler({
            rs.raf.trading.order.exception.InsufficientFundsException.class,
            rs.raf.trading.order.exception.InsufficientHoldingsException.class
    })
    public ResponseEntity<MessageResponseDto> handleInsufficient(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /**
     * R1 819 — necitljiv JSON request body (npr. nepostojeca enum vrednost za
     * {@code mode}/{@code cadence}/{@code direction}). Jackson baca
     * {@link com.fasterxml.jackson.databind.exc.InvalidFormatException} (umotanu u
     * {@link org.springframework.http.converter.HttpMessageNotReadableException})
     * sa stack-trace-nom porukom koja curi interne tipove. Globalni handler ovde
     * detektuje enum-parse gresku i vraca prijateljsku poruku sa spiskom validnih
     * vrednosti — DRY zamena za per-polje custom deserializer-e.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<MessageResponseDto> handleNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        String message = "Telo zahteva nije moguce procitati (proverite format polja).";
        // Spring Boot 4 koristi Jackson 3 (paket tools.jackson). Prodji cause-lanac
        // trazeci InvalidFormatException (npr. nepostojeca enum vrednost za
        // mode/cadence/direction) — moze biti umotana dublje od getCause().
        tools.jackson.databind.exc.InvalidFormatException ife = null;
        for (Throwable c = ex.getCause(); c != null; c = c.getCause()) {
            if (c instanceof tools.jackson.databind.exc.InvalidFormatException found) {
                ife = found;
                break;
            }
        }
        if (ife != null && ife.getTargetType() != null && ife.getTargetType().isEnum()) {
            var path = ife.getPath();
            String field = path.isEmpty()
                    ? "polje"
                    : path.get(path.size() - 1).getPropertyName();
            String allowed = java.util.Arrays.stream(ife.getTargetType().getEnumConstants())
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(", "));
            message = "Nevalidna vrednost za '" + field + "': '" + ife.getValue()
                    + "'. Dozvoljene vrednosti: " + allowed + ".";
        }
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new MessageResponseDto(message));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<MessageResponseDto> handleRuntimeException(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new MessageResponseDto(ex.getMessage()));
    }
}
