package rs.raf.trading.internalapi.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import rs.raf.banka2.contracts.internal.InternalErrorDto;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-error-contract-2 — trading-service interni API handler.
 * R4 1777 (500 ne curi ex.getMessage()) + R5 1883 (MethodArgumentNotValid→400).
 */
class InternalApiExceptionHandlerTest {

    private final InternalApiExceptionHandler handler = new InternalApiExceptionHandler();

    @Test
    void illegalArgument_mapsTo404() {
        ResponseEntity<InternalErrorDto> r = handler.handleIllegalArgument(
                new IllegalArgumentException("Listing ne postoji"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void illegalState_mapsTo409() {
        ResponseEntity<InternalErrorDto> r = handler.handleIllegalState(
                new IllegalStateException("Nedovoljno raspolozive kolicine"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().code()).isEqualTo("CONFLICT");
    }

    @Test
    void methodArgumentNotValid_mapsTo400() throws NoSuchMethodException {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "target");
        br.addError(new FieldError("target", "quantity", "must be > 0"));
        MethodParameter param = new MethodParameter(
                InternalApiExceptionHandlerTest.class.getDeclaredMethod("illegalState_mapsTo409"), -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, br);

        ResponseEntity<InternalErrorDto> r = handler.handleValidation(ex);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void generalException_returns500_staticMessage_noLeak() {
        Exception leaky = new RuntimeException("SQL [select ...]; constraint [pk_listing]");

        ResponseEntity<InternalErrorDto> r = handler.handleGeneral(leaky);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody().message()).isEqualTo("Interna greska servisa.");
        assertThat(r.getBody().message()).doesNotContain("SQL").doesNotContain("constraint");
    }
}
