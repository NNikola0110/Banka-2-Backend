package rs.raf.banka2_bek.interbank.wrapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit testovi za {@link InterbankOtcWrapperExceptionHandler} — HTTP status mapiranje
 * inter-bank OTC wrapper greska. Handler metode su cisto mapiranje (bez Spring konteksta),
 * pa ih pozivamo direktno.
 */
class InterbankOtcWrapperExceptionHandlerTest {

    private final InterbankOtcWrapperExceptionHandler handler = new InterbankOtcWrapperExceptionHandler();

    @Test
    @DisplayName("exercise-400 fix: 2PC abort (partner vote=NO) → 409 Conflict + prosledjen razlog")
    void transactionAborted_mapsTo409WithReason() {
        // exercise-400: partner (prodavceva banka) glasao NO sa OPTION_USED_OR_EXPIRED.
        // Bez ovog handler-a → goli 400 (GlobalExceptionHandler). Sad mora 409 + razlog
        // u poruci (TransactionExecutorService.abort sad ugradjuje " reasons=[...]").
        InterbankExceptions.InterbankTransactionAbortedException ex =
                new InterbankExceptions.InterbankTransactionAbortedException(
                        "Inter-bank 2PC aborted for transaction abc — partner vote=NO "
                                + "reasons=[NoVoteReason[reason=OPTION_USED_OR_EXPIRED, posting=null]]");

        ResponseEntity<Map<String, String>> response = handler.handleTransactionAborted(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message"))
                .contains("Iskoriscenje nije moguce")
                .contains("OPTION_USED_OR_EXPIRED");
    }

    @Test
    @DisplayName("exercise konflikt (status/owner/settlement) → 409 Conflict, poruka prosledjena")
    void exerciseConflict_mapsTo409() {
        InterbankExceptions.InterbankExerciseConflictException ex =
                new InterbankExceptions.InterbankExerciseConflictException("Ugovor nije ACTIVE");

        ResponseEntity<Map<String, String>> response = handler.handleExerciseConflict(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Ugovor nije ACTIVE");
    }
}
