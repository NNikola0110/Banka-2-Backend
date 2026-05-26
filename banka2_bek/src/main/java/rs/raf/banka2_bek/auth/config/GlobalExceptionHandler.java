package rs.raf.banka2_bek.auth.config;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.banka2_bek.auth.dto.MessageResponseDto;
import rs.raf.banka2_bek.auth.exception.AuthenticationFailedException;
import rs.raf.banka2_bek.auth.service.AccountLockoutService;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.payment.exception.OtpInvalidException;
import rs.raf.banka2_bek.payment.exception.OtpLockedException;
import rs.raf.banka2_bek.payment.exception.PaymentAlreadyFinalizedException;
import rs.raf.banka2_bek.payment.exception.PaymentNotFoundException;
import rs.raf.banka2_bek.payment.exception.PaymentNotOwnedException;
import rs.raf.banka2_bek.payment.exception.PaymentTimeoutException;
import org.springframework.security.access.AccessDeniedException;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<MessageResponseDto> handleForbidden(IllegalStateException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /**
     * Spec Celina 1 Sc 2/3/5/14 — autentifikacioni neuspesi vracaju 401, ne
     * generic 400 (Bug T1-001/002/005/012 prijavljen 12.05.2026 manuelnim
     * testiranjem). Mora biti pre handleRuntimeException jer
     * AuthenticationFailedException extends RuntimeException — Spring bira
     * najspecifičniji handler.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<MessageResponseDto> handleAuthenticationFailed(AuthenticationFailedException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /**
     * Account lockout posle 5 neuspeha — takodje mora biti pre RuntimeException
     * handler-a. Vraca 401 (sa SR porukom — vidi {@code AccountLockoutService}).
     */
    @ExceptionHandler(AccountLockoutService.AccountLockedException.class)
    public ResponseEntity<MessageResponseDto> handleAccountLocked(AccountLockoutService.AccountLockedException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /**
     * Spring AOP wrap-uje sve nehvtacene RuntimeException-e unutar @Transactional
     * metoda u TransactionSystemException sa generic porukom "Could not commit JPA
     * transaction". Pravu poruku korisnika dobijemo unwrap-ovanjem cause lanca.
     *
     * Bez ovog handler-a, korisnik vidi cryptic "Could not commit JPA transaction"
     * umesto stvarne poruke (npr. "Racun ne pripada klijentu", "Nedovoljno sredstava").
     * Prijavljeno 14.05.2026 vece-4: Ana fund invest + OTC accept padaju sa generic
     * porukom umesto pravih BE validation poruka.
     */
    @ExceptionHandler(org.springframework.transaction.TransactionSystemException.class)
    public ResponseEntity<MessageResponseDto> handleTransactionSystemException(
            org.springframework.transaction.TransactionSystemException ex) {
        Throwable cause = ex.getMostSpecificCause();
        // Unwrap chain dok ne nadjemo non-Spring exception
        while (cause != null
                && cause.getCause() != null
                && cause.getCause() != cause
                && (cause instanceof org.springframework.transaction.TransactionException
                    || cause instanceof org.springframework.dao.DataAccessException)) {
            cause = cause.getCause();
        }
        String message = cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : "Doslo je do greske prilikom obrade zahteva.";
        // Mapiraj poznate exception tipove u prikladan HTTP status
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (cause instanceof EntityNotFoundException) {
            status = HttpStatus.NOT_FOUND;
        } else if (cause instanceof AccessDeniedException) {
            status = HttpStatus.FORBIDDEN;
        } else if (cause instanceof AuthenticationFailedException
                || cause instanceof AccountLockoutService.AccountLockedException) {
            status = HttpStatus.UNAUTHORIZED;
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

    // ===== Inter-bank protokol exception mappinzi (Tim 1 cross-bank live test, 2026-05-20)
    // ============================================================================
    // GlobalExceptionHandler catch-all `handleRuntimeException` hvata generic
    // RuntimeException-e PRE scoped `OtcNegotiationExceptionHandler`, pa moramo
    // ovde eksplicitno mapirati interbank-domain exception tipove na ispravne
    // HTTP status code-ove per Tim 2 §3.7 (404 user not found), §6.3 (404 negotiation
    // not found, 409 conflict), §6.4 (401 outbound auth).

    /** §3.7 GET /user/{rn}/{id} sa nepoznatim id-em — Tim 2 spec trazi 404. */
    @ExceptionHandler(InterbankExceptions.InterbankUserNotFoundException.class)
    public ResponseEntity<MessageResponseDto> handleInterbankUserNotFound(
            InterbankExceptions.InterbankUserNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /** §3.3-§3.6 GET/PUT/DELETE/accept za nepostojeci pregovor — Tim 2 spec trazi 404. */
    @ExceptionHandler(InterbankExceptions.InterbankNegotiationNotFoundException.class)
    public ResponseEntity<MessageResponseDto> handleInterbankNegotiationNotFound(
            InterbankExceptions.InterbankNegotiationNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /** §3.3 turn violation ili zatvoreni pregovor — Tim 2 spec trazi 409. */
    @ExceptionHandler(InterbankExceptions.InterbankNegotiationConflictException.class)
    public ResponseEntity<MessageResponseDto> handleInterbankNegotiationConflict(
            InterbankExceptions.InterbankNegotiationConflictException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /** Outbound 401 od partnera — propagiramo kao 502 Bad Gateway (problem je sa partner-om, ne sa nama). */
    @ExceptionHandler(InterbankExceptions.InterbankAuthException.class)
    public ResponseEntity<MessageResponseDto> handleInterbankAuthFailure(
            InterbankExceptions.InterbankAuthException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    // ===== TODO_final Mobile bonus #7 — Quick Approve exception mappinzi =====
    // Mora biti pre handleRuntimeException jer sve nase Quick Approve exception-e
    // extend-uju RuntimeException — Spring bira najspecifičniji handler.

    /** Quick Approve — payment id ne postoji u DB. */
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<MessageResponseDto> handlePaymentNotFound(PaymentNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /** Quick Approve — payment.fromAccount ne pripada current user-u. */
    @ExceptionHandler(PaymentNotOwnedException.class)
    public ResponseEntity<MessageResponseDto> handlePaymentNotOwned(PaymentNotOwnedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /** Quick Approve — payment status u finalized failure stanju (REJECTED/ABORTED/CANCELLED). */
    @ExceptionHandler(PaymentAlreadyFinalizedException.class)
    public ResponseEntity<MessageResponseDto> handlePaymentAlreadyFinalized(PaymentAlreadyFinalizedException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /** Quick Approve — deep-link istekao (payment.createdAt + 5min &lt; now). */
    @ExceptionHandler(PaymentTimeoutException.class)
    public ResponseEntity<MessageResponseDto> handlePaymentTimeout(PaymentTimeoutException ex) {
        return ResponseEntity
                .status(HttpStatus.GONE)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /** Quick Approve — OTP code nije validan (ali jos uvek ima pokusaja). */
    @ExceptionHandler(OtpInvalidException.class)
    public ResponseEntity<MessageResponseDto> handleOtpInvalid(OtpInvalidException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    /** Quick Approve — OTP zakljucan zbog 3 uzastopna fail-a (Caffeine 5min window). */
    @ExceptionHandler(OtpLockedException.class)
    public ResponseEntity<MessageResponseDto> handleOtpLocked(OtpLockedException ex) {
        return ResponseEntity
                .status(HttpStatus.LOCKED)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<MessageResponseDto> handleRuntimeException(RuntimeException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new MessageResponseDto(ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<MessageResponseDto> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new MessageResponseDto(ex.getMessage()));
    }
}
