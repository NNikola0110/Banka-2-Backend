package rs.raf.banka2_bek.assistant.util;

/**
 * [P2-input-validation-1 / R4 1782] Sanitizacija user-controlled stringova pre
 * logovanja — sprecava log/CRLF injection.
 *
 * <p>Bez ovoga, napadac koji u Arbitro poruku ({@code userMessage}) ubaci
 * {@code "\n"}/{@code "\r"} moze da iskuje lazne log linije (npr. lazan
 * {@code "ARBITRO ... approved loan"} red) ili da ubaci PII u app-log. Helper
 * uklanja CR/LF (i susedne kontrolne znake) i opcionalno trunc-uje predugacku
 * vrednost da log ostane citljiv i jednolinijski.
 */
public final class LogSanitizer {

    /** Default maksimalna duzina logovane user-input vrednosti. */
    private static final int DEFAULT_MAX_LEN = 200;

    private LogSanitizer() {
    }

    /**
     * Uklanja CR/LF i druge ASCII kontrolne znake i trunc-uje na default duzinu.
     * Null → {@code "null"} (eksplicitno, da se razlikuje od prazne vrednosti).
     */
    public static String sanitize(String value) {
        return sanitize(value, DEFAULT_MAX_LEN);
    }

    /**
     * Uklanja CR/LF + ASCII kontrolne znake (0x00-0x1F, 0x7F) zamenjujuci ih
     * razmakom, kolabira visestruke razmake i trunc-uje na {@code maxLen}
     * (dodajuci "..." ako je skraceno).
     */
    public static String sanitize(String value, int maxLen) {
        if (value == null) {
            return "null";
        }
        // Zameni sve kontrolne znake (uklj. CR/LF/TAB) jednim razmakom, kolabira
        // uzastopne razmake, trim-uj rubove.
        String cleaned = value.replaceAll("[\\x00-\\x1F\\x7F]+", " ")
                .replaceAll(" {2,}", " ")
                .trim();
        if (maxLen > 0 && cleaned.length() > maxLen) {
            return cleaned.substring(0, maxLen) + "...";
        }
        return cleaned;
    }
}
