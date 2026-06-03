package rs.raf.banka2_bek.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-error-contract-1: {@link SecurityErrorResponder} mora upisati JSON
 * {@code {"message":...}} telo (ne prazno) sa zadatim statusom, da Mobile/FE
 * parser dobiju smislenu poruku za istek sesije / access denied.
 */
class SecurityErrorResponderTest {

    @Test
    void writeJson_writes401JsonBodyWithMessage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SecurityErrorResponder.writeJson(response, HttpStatus.UNAUTHORIZED,
                SecurityErrorResponder.SESSION_EXPIRED_MESSAGE);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString())
                .isEqualTo("{\"message\":\"" + SecurityErrorResponder.SESSION_EXPIRED_MESSAGE + "\"}");
        // KLJUC: telo NIJE prazno (regresija — pre fix-a bilo prazno → Mobile generic poruka).
        assertThat(response.getContentAsString()).isNotBlank();
    }

    @Test
    void writeJson_writes403ForAccessDenied() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SecurityErrorResponder.writeJson(response, HttpStatus.FORBIDDEN,
                SecurityErrorResponder.ACCESS_DENIED_MESSAGE);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains(SecurityErrorResponder.ACCESS_DENIED_MESSAGE);
    }

    @Test
    void writeJson_escapesQuotesAndControlChars() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SecurityErrorResponder.writeJson(response, HttpStatus.UNAUTHORIZED, "say \"hi\"\nnewline");

        // Navodnici i newline moraju biti escape-ovani da telo ostane validan JSON.
        assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"say \\\"hi\\\"\\nnewline\"}");
    }

    @Test
    void writeJson_nullMessage_writesEmptyString() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        SecurityErrorResponder.writeJson(response, HttpStatus.UNAUTHORIZED, null);

        assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"\"}");
    }

    @Test
    void writeJson_committedResponse_doesNotThrowOrOverwrite() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getWriter().write("already-committed");
        response.flushBuffer(); // mark committed

        SecurityErrorResponder.writeJson(response, HttpStatus.UNAUTHORIZED, "ignored");

        // Ne sme da pukne; ne prepisuje vec poslato telo.
        assertThat(response.getContentAsString()).isEqualTo("already-committed");
    }
}
