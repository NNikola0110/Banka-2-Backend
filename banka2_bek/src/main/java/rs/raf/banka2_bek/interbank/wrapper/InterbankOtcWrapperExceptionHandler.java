package rs.raf.banka2_bek.interbank.wrapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;

import java.util.Map;

/**
 * Mapira inter-bank OTC wrapper greske u HTTP statuse za FE-facing rute.
 *
 * <p>Razlog za zaseban advice (umesto deljenja sa
 * {@code OtcNegotiationExceptionHandler}): inbound (§3.x) endpoint-i razmenjuju
 * JSON sa partner bankama po protokol-spec semantici (404 za nepostojeci user,
 * 409 za turn violation), dok wrapper rute razgovaraju sa FE-om i koriste
 * lokalne semantike (409 za exercise konflikt — ugovor nije ACTIVE, settlement
 * je prosao, pozivac nije buyer). Bez ovog handler-a, sve {@link
 * InterbankExceptions.InterbankException}-e bi {@code GlobalExceptionHandler}
 * mapirao u 400 preko {@code handleRuntimeException}.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = InterbankOtcWrapperController.class)
public class InterbankOtcWrapperExceptionHandler {

    /** §2.7.2 — exercise konflikt sa stanjem resursa (status/settlement/owner). */
    @ExceptionHandler(InterbankExceptions.InterbankExerciseConflictException.class)
    public ResponseEntity<Map<String, String>> handleExerciseConflict(
            InterbankExceptions.InterbankExerciseConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    /**
     * exercise-400 fix — §2.8 2PC abort (partner je glasao NO / nedostupan): payload je
     * validan i svi nasi (kupceve banke) guard-ovi prolaze, ali partner-side stanje
     * (opcija iskoriscena/istekla na njihovoj strani, hartije/sredstva nedostupna) ne
     * dozvoljava exercise. Bez ovog handler-a {@code InterbankTransactionAbortedException}
     * (extends {@code InterbankException}) pada na {@code GlobalExceptionHandler.handleRuntimeException}
     * → goli 400 (BAD_REQUEST), sto je semanticki pogresno (nije malformed input — konflikt je
     * partner-side stanje resursa). Vracamo 409 Conflict + prosledjenu partner poruku/razlog
     * (npr. {@code reasons=[OPTION_USED_OR_EXPIRED, ...]} koje sad nosi {@code abort(tx, ..., vote)}).
     */
    @ExceptionHandler(InterbankExceptions.InterbankTransactionAbortedException.class)
    public ResponseEntity<Map<String, String>> handleTransactionAborted(
            InterbankExceptions.InterbankTransactionAbortedException ex) {
        log.warn("Inter-bank 2PC exercise abort: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message",
                "Iskoriscenje nije moguce: prodavceva banka je odbila transakciju "
                        + "(opcija je verovatno vec iskoriscena ili istekla na njihovoj strani). "
                        + "Detalji: " + ex.getMessage()));
    }

    /**
     * Bug 2 (PDF "Zahtev je u konfliktu sa postojecim podacima.") — DB constraint /
     * unique / particija pad tokom inter-bank OTC (accept/exercise 2PC, koji upisuje
     * interbank_transactions / interbank_messages). Bez ovog handler-a DIV bi pao na
     * GlobalExceptionHandler i korisnik bi dobio OPAQUE generic poruku bez ikakvog
     * traga uzroka. Ovde LOGUJEMO {@code getMostSpecificCause()} (pravi SQL/constraint
     * razlog — vidljiv u serverskim logovima pri live testu) i vracamo akcionabilan 409.
     * (SQL detalj se NE prosledjuje korisniku — info-disclosure.)
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable specific = ex.getMostSpecificCause();
        log.warn("Inter-bank OTC DataIntegrityViolation (constraint/unique/particija): {}",
                specific != null ? specific.getMessage() : ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message",
                "Konflikt pri obradi inter-bank zahteva (resurs vec postoji ili je u medjuvremenu "
                        + "izmenjen). Osvezite i pokusajte ponovo."));
    }

    /** @Version concurrent update tokom inter-bank 2PC commit faze → 409 (UI moze retry). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Inter-bank OTC optimistic-lock konflikt: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message",
                "Resurs je u medjuvremenu izmenjen. Osvezite stranicu i pokusajte ponovo."));
    }

    @ExceptionHandler(InterbankExceptions.InterbankNegotiationConflictException.class)
    public ResponseEntity<Map<String, String>> handleNegotiationConflict(
            InterbankExceptions.InterbankNegotiationConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }

    /**
     * Nepostojeci resurs (ugovor / racun) tokom OTC wrapper akcije (npr.
     * {@code exerciseContract} na nepostojeci contractId ili buyerAccountId) → 404.
     * Pre-fix se ovo bacalo kao {@code InterbankProtocolException} → 400, sto je
     * pogresna semantika za "resurs ne postoji" (404). Razlikuje se od 409
     * (state-conflict: ugovor nije ACTIVE / posle settlement-a / pogresna strana).
     */
    @ExceptionHandler(InterbankExceptions.InterbankNegotiationNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNegotiationNotFound(
            InterbankExceptions.InterbankNegotiationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
    }

    /** Partner banka nije dostupna ili je vratila grešku — 502. */
    @ExceptionHandler(InterbankExceptions.InterbankCommunicationException.class)
    public ResponseEntity<Map<String, String>> handleCommunication(
            InterbankExceptions.InterbankCommunicationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(InterbankExceptions.InterbankProtocolException.class)
    public ResponseEntity<Map<String, String>> handleProtocol(
            InterbankExceptions.InterbankProtocolException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
    }
}
