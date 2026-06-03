package rs.raf.banka2_bek.interbank.exception;

import com.fasterxml.jackson.core.JsonParseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import rs.raf.banka2.contracts.error.ApiErrorDto;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-error-contract-2 R4 1778 — inbound interbank handler vraca JSON {message} sa
 * SANITIZOVANOM porukom (bez Jackson internih detalja); status kod ostaje protokol-ugovor.
 */
class InterbankExceptionHandlerTest {

    private final InterbankExceptionHandler handler = new InterbankExceptionHandler();

    @Test
    void auth_returns401_emptyBody() {
        ResponseEntity<Void> r = handler.handleAuth(
                new InterbankExceptions.InterbankAuthException("bad token"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getBody()).isNull();
    }

    @Test
    void protocol_returns400_jsonMessageBody() {
        ResponseEntity<ApiErrorDto> r = handler.handleProtocol(
                new InterbankExceptions.InterbankProtocolException("Unbalanced transaction"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().message()).isEqualTo("Unbalanced transaction");
    }

    // R4 1778 — Jackson getMessage() (lokacija parsera, naziv polja) se NE prosledjuje partneru.
    @Test
    void json_returns400_sanitizedMessage_noJacksonLeak() {
        JsonParseException jackson = new JsonParseException(
                null, "Unexpected character ('}' (code 125)) at [Source: line 7, column 42] field secretField");

        ResponseEntity<ApiErrorDto> r = handler.handleJson(jackson);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().message()).isEqualTo("Malformed envelope.");
        assertThat(r.getBody().message()).doesNotContain("Source").doesNotContain("secretField");
    }

    @Test
    void general_returns500_emptyBody() {
        ResponseEntity<Void> r = handler.handleGeneral(new RuntimeException("boom; SQL [...]"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).isNull();
    }
}
