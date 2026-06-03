package rs.raf.notification.mail.template;

import org.springframework.web.util.HtmlUtils;

/**
 * [P1-notif-svc-1 / 1528 / 1742] Bezbedno escape-ovanje dinamickih (user-controlled)
 * vrednosti pre interpolacije u HTML email body preko {@code String.formatted}.
 *
 * <p>Bez ovoga, vrednosti kao naziv firme {@code "A&B <Co>"}, maliciozan order
 * komentar ili {@code title}/{@code body} in-app notifikacije lome markup ili
 * ubacuju {@code <a>/<img>} (HTML injection). {@link org.springframework.web.util.HtmlUtils#htmlEscape}
 * escape-uje {@code < > & " '} ali NE dira ne-ASCII (srpska latinica / cirilica
 * prolaze netaknute), pa branding i pozdravi ostaju citljivi.
 *
 * <p>{@code escape} je null-safe: null → prazan string (sprecava literalni
 * "null" u email-u i NPE).
 */
final class EmailHtml {

    private EmailHtml() {
    }

    /**
     * Null-safe HTML escape dinamicke vrednosti pre umetanja u HTML telo.
     *
     * <p>KRITICNO: koristi {@code htmlEscape(value, "UTF-8")} — bez eksplicitnog
     * UTF-8 encoding-a, {@code HtmlUtils.htmlEscape(String)} escape-uje SVE ne-ASCII
     * znake u numericke entitete (npr. {@code š}→{@code &#353;}), sto bi izoblicilo
     * srpsku latinicu/cirilicu u email-u. Sa "UTF-8" se escape-uju samo opasni
     * markup znaci ({@code < > & " '} + kontrolni), a ne-ASCII ostaje citljiv.
     */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
