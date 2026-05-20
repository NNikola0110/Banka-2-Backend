package rs.raf.banka2_bek.interbank.util;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Pomocne metode za kriptografski sigurne poredbe.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * Poredi dva stringa u konstantnom vremenu kako bi se sprecili timing side-channel
     * napadi pri verifikaciji API kljuceva (X-Api-Key header).
     *
     * <p>Koristimo {@link MessageDigest#isEqual} koji garantuje da vreme izvrsavanja
     * ne zavisi od sadrzaja ni duzine niza — oba niza se najpre konvertuju u bajtove
     * pa se porede bajt-po-bajt bez kratkih puteva (short-circuit) evaluacije.
     *
     * @param a prvi string (npr. primljeni token iz HTTP headera)
     * @param b drugi string (npr. konfigurisani inbound token)
     * @return {@code true} ako su oba stringa identicna (ukljucujuci {@code null == null})
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
