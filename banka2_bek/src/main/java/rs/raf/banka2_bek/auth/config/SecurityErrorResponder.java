package rs.raf.banka2_bek.auth.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;

/**
 * P1-error-contract-1: jedinstveno pisanje JSON {@code {"message": "..."}} tela
 * za auth/authz greske generisane na nivou Servlet filtera i Spring Security
 * entrypoint/accessDeniedHandler-a.
 *
 * <p>Pre fix-a: JWT/Interbank filteri su radili {@code response.setStatus(401)}
 * sa PRAZNIM telom → Mobile {@code parseHttpError} (parsed=null) pada na
 * {@code defaultMessageForCode(401)} = "Neispravan email ili lozinka." i za
 * isteklu sesiju (a ne login pokusaj). Standardizujemo telo na {@code {"message":...}}
 * (isti shape kao {@code GlobalExceptionHandler} → {@code MessageResponseDto}), pa
 * i FE ({@code getErrorMessage} cita {@code message}) i Mobile ({@code ServerErrorBody.message})
 * dobijaju smislenu poruku.
 */
public final class SecurityErrorResponder {

    private SecurityErrorResponder() {
    }

    /** Default poruka za 401 na zasticenoj ruti (istekla/nevalidna sesija, NE login). */
    public static final String SESSION_EXPIRED_MESSAGE =
            "Sesija je istekla ili nije validna. Prijavite se ponovo.";

    /** Default poruka za 403 (autentifikovan ali bez prava). */
    public static final String ACCESS_DENIED_MESSAGE =
            "Nemate dozvolu za pristup ovom resursu.";

    /**
     * Upisuje JSON {@code {"message": "..."}} telo sa zadatim HTTP statusom ako
     * response jos nije committed. Poruka se JSON-escape-uje (navodnici/backslash/
     * kontrolni znaci) da se izbegne lomljenje tela.
     */
    public static void writeJson(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String safe = jsonEscape(message != null ? message : "");
        response.getWriter().write("{\"message\":\"" + safe + "\"}");
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
