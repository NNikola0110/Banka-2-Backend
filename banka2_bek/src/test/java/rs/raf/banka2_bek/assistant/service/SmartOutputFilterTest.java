package rs.raf.banka2_bek.assistant.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit testovi za SmartOutputFilter — stream-aware preamble + channel marker
 * removal. Vidi {@link SmartOutputFilter} javadoc za state machine pravila.
 */
class SmartOutputFilterTest {

    @Test
    void filtersConstraintCheckPreamble() {
        SmartOutputFilter f = new SmartOutputFilter();
        StringBuilder out = new StringBuilder();
        // Preamble + \n\n + real content
        out.append(f.process("Constraint Check: The prompt states X.\n\n"));
        out.append(f.process("BELIBOR je referentna stopa"));
        out.append(f.flush());
        assertThat(out.toString()).isEqualTo("BELIBOR je referentna stopa");
    }

    @Test
    void filtersChannelMarker() {
        SmartOutputFilter f = new SmartOutputFilter();
        StringBuilder out = new StringBuilder();
        out.append(f.process("Some preamble<channel|>The real answer"));
        out.append(f.flush());
        assertThat(out.toString()).isEqualTo("The real answer");
    }

    @Test
    void filtersThinkingBlocks() {
        SmartOutputFilter f = new SmartOutputFilter();
        StringBuilder out = new StringBuilder();
        // <thinking> blok + real content. Filter mora da skine blok ali emit
        // ostalo. Posto pocinje sa <thinking>, posle strip-ovanja preostaje
        // "Final response" — koji nije preamble keyword, pa flush emit-uje.
        out.append(f.process("<thinking>internal</thinking>Final response"));
        out.append(f.flush());
        assertThat(out.toString()).isEqualTo("Final response");
    }

    @Test
    void passesThroughCleanOutput() {
        SmartOutputFilter f = new SmartOutputFilter();
        // Tekst NEMA preamble — filter mora flush-ovati ceo sadrzaj
        // (kroz MAX_BUFFER ili kroz flush()).
        String text = "Belibor je referentna kamatna stopa.";
        StringBuilder out = new StringBuilder();
        out.append(f.process(text));
        out.append(f.flush());
        assertThat(out.toString()).isEqualTo(text);
    }

    @Test
    void chunkedCleanOutputFlushesCompletelyOnFlush() {
        // Sitan stream — niti jedan chunk ne dostize MAX_BUFFER, niti ima
        // preamble. Filter cuva u buffer-u dok flush() ne fix-uje.
        SmartOutputFilter f = new SmartOutputFilter();
        StringBuilder out = new StringBuilder();
        out.append(f.process("Belibor "));
        out.append(f.process("je "));
        out.append(f.process("referentna "));
        out.append(f.process("stopa."));
        out.append(f.flush());
        assertThat(out.toString()).isEqualTo("Belibor je referentna stopa.");
    }

    @Test
    void passThroughAfterRealContentStarted() {
        // Kad se filter prebaci u STREAMING mode, sve dalje chunk-ove ne dira.
        SmartOutputFilter f = new SmartOutputFilter();
        // Forsiraj prelaz preko channel marker-a
        String first = f.process("<channel|>start");
        assertThat(first).isEqualTo("start");
        assertThat(f.isRealContentStarted()).isTrue();
        // Sad dalji chunk-ovi (cak i sa preamble keyword-ima) pass-through
        assertThat(f.process(" Final Output: foo")).isEqualTo(" Final Output: foo");
        assertThat(f.flush()).isEmpty();
    }
}
