package rs.raf.notification.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedMessageTrackerTest {

    @Test
    void unseenKey_isNotProcessed() {
        ProcessedMessageTracker t = new ProcessedMessageTracker();
        assertThat(t.isProcessed("k1")).isFalse();
    }

    @Test
    void markProcessed_thenIsProcessed() {
        ProcessedMessageTracker t = new ProcessedMessageTracker();
        t.markProcessed("k1");
        assertThat(t.isProcessed("k1")).isTrue();
        // Razlicit kljuc nije pogodjen
        assertThat(t.isProcessed("k2")).isFalse();
    }

    @Test
    void nullKey_isSafe() {
        ProcessedMessageTracker t = new ProcessedMessageTracker();
        assertThat(t.isProcessed(null)).isFalse();
        t.markProcessed(null); // ne sme da pukne
        assertThat(t.recordFailureAndCheckExhausted(null)).isFalse();
    }

    @Test
    void expiredEntry_isNotProcessed() throws InterruptedException {
        // TTL 20ms → posle isteka markProcessed nestaje
        ProcessedMessageTracker t = new ProcessedMessageTracker(20, 10_000, 5);
        t.markProcessed("k1");
        assertThat(t.isProcessed("k1")).isTrue();
        Thread.sleep(40);
        assertThat(t.isProcessed("k1")).isFalse();
    }

    @Test
    void recordFailure_exhaustsAfterMaxAttempts() {
        ProcessedMessageTracker t = new ProcessedMessageTracker(60_000, 10_000, 3);
        assertThat(t.recordFailureAndCheckExhausted("k1")).isFalse(); // 1
        assertThat(t.recordFailureAndCheckExhausted("k1")).isFalse(); // 2
        assertThat(t.recordFailureAndCheckExhausted("k1")).isTrue();  // 3 == max → exhausted
    }

    @Test
    void recordFailure_perKeyIndependent() {
        ProcessedMessageTracker t = new ProcessedMessageTracker(60_000, 10_000, 2);
        assertThat(t.recordFailureAndCheckExhausted("a")).isFalse();
        assertThat(t.recordFailureAndCheckExhausted("b")).isFalse();
        assertThat(t.recordFailureAndCheckExhausted("a")).isTrue();
    }

    @Test
    void clear_resetsKey() {
        ProcessedMessageTracker t = new ProcessedMessageTracker(60_000, 10_000, 2);
        t.recordFailureAndCheckExhausted("k1");
        t.clear("k1");
        // posle clear, brojac krece od nule
        assertThat(t.recordFailureAndCheckExhausted("k1")).isFalse();
    }

    @Test
    void boundedEviction_keepsSizeUnderCap() {
        ProcessedMessageTracker t = new ProcessedMessageTracker(60_000, 100, 5);
        for (int i = 0; i < 500; i++) {
            t.markProcessed("k" + i);
        }
        // Evikcija drzi mapu ispod (ili oko) max-entries
        assertThat(t.size()).isLessThan(200);
    }
}
