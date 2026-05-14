package rs.raf.banka2_bek.assistant.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stream-aware filter koji uklanja meta-reasoning preamble + Gemma channel
 * markere PRE nego sto se token chunk-ovi proslede SSE emitter-u.
 *
 * <p>Razlika u odnosu na {@link ContentLeakFilter}: {@code ContentLeakFilter}
 * radi nad PUNIM finalContent-om (posle-the-fact). {@code SmartOutputFilter}
 * je stateful i namenjen pipeline-u — buffer-uje dok ne odluci da li je u
 * preamble fazi ili u "real content" fazi, pa flush-uje.</p>
 *
 * <p>State machine:</p>
 * <ol>
 *   <li><b>BUFFERING</b> (default) — sakuplja chunk-ove dok ne detektuje:
 *     <ul>
 *       <li>{@code <channel|>} marker → real content krece odmah posle</li>
 *       <li>preamble keyword + {@code \n\n} → real content krece posle blank line-a</li>
 *       <li>{@code <thinking>...</thinking>} blok → strip</li>
 *       <li>buffer prevazidje {@link #MAX_BUFFER} bez preamble signala → flush sve</li>
 *     </ul>
 *   </li>
 *   <li><b>STREAMING</b> — sve sledece chunk-ove emit-uje direktno bez ikakve
 *       filter logike (pass-through)</li>
 * </ol>
 *
 * <p>Filter je <b>NIJE</b> Spring bean — instancira se per-stream u
 * AssistantService.chatStream callback-u (nije thread-safe).</p>
 */
public class SmartOutputFilter {

    private static final List<String> PREAMBLE_MARKERS = List.of(
            "Constraint Check:",
            "The prompt states:",
            "Final Output Generation",
            "Final Output:",
            "I must now rely on internal knowledge",
            "I must now rely on my internal knowledge",
            "based on synthesized knowledge",
            "based on the fallback mechanism",
            "according to the system prompt",
            "according to the instructions"
    );

    private static final String CHANNEL_OPEN_PREFIX = "<|channel>";
    private static final String CHANNEL_CLOSE_TAG = "<channel|>";
    private static final Pattern THINKING = Pattern.compile("<thinking>.*?</thinking>", Pattern.DOTALL);
    private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    private static final int MAX_BUFFER = 500;

    private final StringBuilder buffer = new StringBuilder();
    private boolean realContentStarted;

    /**
     * Obradjuje sledeci chunk iz LLM stream-a.
     *
     * @param chunk dolazni delta sadrzaj (moze biti null/prazno)
     * @return tekst koji treba <b>odmah</b> emit-ovati FE-u, ili prazan string
     *         ako jos uvek buffer-ujemo
     */
    public String process(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        if (realContentStarted) {
            // Pass-through faza — chunk ide direktno
            return chunk;
        }

        buffer.append(chunk);
        String text = buffer.toString();

        // 1) Strip kompletne <thinking>...</thinking> i <think>...</think> blokove
        //    iz buffer-a (mogu biti otvoreni-i-zatvoreni unutar buffer-a).
        String stripped = THINKING.matcher(text).replaceAll("");
        stripped = THINK.matcher(stripped).replaceAll("");
        if (!stripped.equals(text)) {
            buffer.setLength(0);
            buffer.append(stripped);
            text = stripped;
        }

        // 2) Eksplicitan channel close marker — sve PRE njega je preamble,
        //    sve POSLE je real content.
        int closeIdx = text.indexOf(CHANNEL_CLOSE_TAG);
        if (closeIdx >= 0) {
            String after = text.substring(closeIdx + CHANNEL_CLOSE_TAG.length());
            buffer.setLength(0);
            realContentStarted = true;
            return after;
        }

        // 3) Channel open marker bez close-a — model jos uvek u channel mode-u,
        //    nastavi da buffer-ujes.
        if (text.contains(CHANNEL_OPEN_PREFIX)) {
            // Nista za emit jos uvek — cekamo close
            return "";
        }

        // 4) Preamble keyword? Ako da, cekaj prvi paragraph break (\n\n).
        boolean hasPreamble = false;
        for (String marker : PREAMBLE_MARKERS) {
            if (text.contains(marker)) {
                hasPreamble = true;
                break;
            }
        }
        if (hasPreamble) {
            int sep = text.indexOf("\n\n");
            if (sep >= 0 && sep < text.length() - 2) {
                String after = text.substring(sep + 2);
                buffer.setLength(0);
                realContentStarted = true;
                return after;
            }
            // Drzi u buffer-u dok ne dodje \n\n
            return "";
        }

        // 5) Nema preamble + buffer veliki = nije meta, flush sve sto imamo.
        if (buffer.length() >= MAX_BUFFER) {
            String all = buffer.toString();
            buffer.setLength(0);
            realContentStarted = true;
            return all;
        }

        // 6) Nedovoljno informacija — cekaj jos.
        return "";
    }

    /**
     * Stream je gotov — vrati preostali buffer (ako ima) i markiraj filter kao
     * "real content emitted" (idempotent — bezbedno za vise pozivanja).
     */
    public String flush() {
        if (realContentStarted) {
            return "";
        }
        // Preostali buffer — proveri jos jednom da li ima leaktoken markera
        // (zaboravljeni close ili samostalan thinking blok), strip ako ima.
        String text = buffer.toString();
        text = THINKING.matcher(text).replaceAll("");
        text = THINK.matcher(text).replaceAll("");
        // Samostalan channel close marker (modeli ponekad emit-uju zatvarac
        // bez otvaraca posle preamble-a) — vraca samo deo posle markera.
        int closeIdx = text.indexOf(CHANNEL_CLOSE_TAG);
        if (closeIdx >= 0) {
            text = text.substring(closeIdx + CHANNEL_CLOSE_TAG.length());
        }
        buffer.setLength(0);
        realContentStarted = true;
        return text;
    }

    /**
     * Da li je filter prosao u pass-through fazu. Testni helper.
     */
    public boolean isRealContentStarted() {
        return realContentStarted;
    }
}
