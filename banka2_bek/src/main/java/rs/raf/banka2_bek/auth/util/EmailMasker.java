package rs.raf.banka2_bek.auth.util;

/**
 * [P2-input-validation-1 / R4 1781] Maskiranje email adrese pre logovanja —
 * sprecava PII leak u app-log.
 *
 * <p>Ironicno, SEC-07 anti-enumeration grana (koja namerno vraca generic
 * odgovor da NE oda da li nalog postoji) je istovremeno logovala PUN email
 * svakog ko enumerise → log postaje PII dump enumeracionih pokusaja. Maskiranje
 * zadrzava dovoljno za debug korelaciju ({@code j***c@gmail.com}) bez izlaganja
 * cele adrese.
 *
 * <p>Pravila: cuva prvi karakter lokalnog dela + domen; ostatak lokalnog dela
 * zamenjuje sa {@code ***}. {@code null}/prazan → {@code "(none)"};
 * {@code "x@y"} bez dovoljno karaktera → {@code "***@y"}.
 */
public final class EmailMasker {

    private EmailMasker() {
    }

    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "(none)";
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at <= 0) {
            // Nema validnog lokalnog dela — ne otkrivaj nista osim domena ako postoji.
            return at == 0 ? "***" + trimmed.substring(at) : "***";
        }
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at); // ukljucuje '@'
        char first = local.charAt(0);
        char last = local.length() > 1 ? local.charAt(local.length() - 1) : '\0';
        if (local.length() <= 2) {
            return first + "***" + domain;
        }
        return first + "***" + last + domain;
    }
}
