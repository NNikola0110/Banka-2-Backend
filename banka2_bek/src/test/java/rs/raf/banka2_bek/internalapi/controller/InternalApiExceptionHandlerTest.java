package rs.raf.banka2_bek.internalapi.controller;

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
 * P2-error-contract-2 — banka-core interni API handler.
 * R4 1777 (500 ne curi ex.getMessage()) + R5 1883 (MethodArgumentNotValid→400).
 */
class InternalApiExceptionHandlerTest {

    private final InternalApiExceptionHandler handler = new InternalApiExceptionHandler();

    @Test
    void illegalArgument_mapsTo404_notFound() {
        ResponseEntity<InternalErrorDto> r = handler.handleIllegalArgument(
                new IllegalArgumentException("Racun ne postoji"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(r.getBody().message()).isEqualTo("Racun ne postoji");
    }

    @Test
    void illegalState_mapsTo409_conflict() {
        ResponseEntity<InternalErrorDto> r = handler.handleIllegalState(
                new IllegalStateException("Nedovoljno sredstava"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().code()).isEqualTo("CONFLICT");
    }

    // R5 1883 — nevalidan @RequestBody na internom API-ju je 400, ne 500.
    @Test
    void methodArgumentNotValid_mapsTo400_notInternalError() throws NoSuchMethodException {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "target");
        br.addError(new FieldError("target", "amount", "must be positive"));
        MethodParameter param = new MethodParameter(
                InternalApiExceptionHandlerTest.class.getDeclaredMethod("illegalState_mapsTo409_conflict"), -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, br);

        ResponseEntity<InternalErrorDto> r = handler.handleValidation(ex);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().code()).isEqualTo("BAD_REQUEST");
        assertThat(r.getBody().message()).contains("amount");
    }

    // R4 1777 — 500 vraca STATICNU poruku; sirov SQL/constraint detalj se NE prosledjuje.
    @Test
    void generalException_returns500_withStaticMessage_noLeak() {
        Exception leaky = new RuntimeException(
                "could not execute statement; SQL [insert ...]; constraint [uk_account_number]");

        ResponseEntity<InternalErrorDto> r = handler.handleGeneral(leaky);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(r.getBody().message()).isEqualTo("Interna greska servisa.");
        assertThat(r.getBody().message()).doesNotContain("SQL").doesNotContain("constraint");
    }
}
