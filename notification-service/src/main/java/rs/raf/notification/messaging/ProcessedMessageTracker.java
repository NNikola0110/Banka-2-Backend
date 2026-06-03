package rs.raf.notification.messaging;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [P1-notif-svc-1 / 1530 / 1850 / 1527] In-memory dedup + retry-attempt tracker
 * za email consumer (at-least-once delivery → moramo da zastitimo idempotenciju).
 *
 * <p><b>Dedup (1530/1850):</b> consumer moze da padne IZMEDJU {@code mailSender.send()}
 * i {@code basicAck} → broker redeliveruje istu poruku → duplikat OTP/payment mejla.
 * {@link #markProcessed} belezi kljuc posle uspesnog slanja; {@link #isProcessed}
 * vraca true pri redelivery-ju → consumer ack-uje i preskace ponovno slanje.
 *
 * <p><b>Bounded retry / backoff cap (1527):</b> bez retry-interceptora,
 * {@code nack(requeue=true)} vraca poruku na glavu queue-a → broker odmah
 * re-deliveruje → tight busy-loop bez backoff-a na trajnom SMTP outage-u (poruka
 * nikad ne ulazi u DLQ). {@link #recordFailureAndCheckExhausted} broji transient
 * neuspehe po kljucu; kad dostigne {@code maxAttempts}, consumer salje poruku u
 * DLQ (requeue=false) umesto beskonacnog requeue-a.
 *
 * <p>Zapisi imaju TTL (default 30 min) i mapa je ogranicena ({@code maxEntries})
 * sa lazy + size-triggered evikcijom — nema eksterne zavisnosti (Caffeine se
 * izbegava da se ne menja build/dependency set ovog modula). Tracker je
 * per-instanca (in-memory); za multi-replica striktnu dedup bi trebao deljen
 * store (Redis/DB), ali je single-instance slucaj (jedan notification-service pod)
 * dominantan — dokumentovano kao DEFERRED.
 */
@Component
public class ProcessedMessageTracker {

    private static final long DEFAULT_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final int DEFAULT_MAX_ENTRIES = 50_000;
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final long ttlMillis;
    private final int maxEntries;
    private final int maxAttempts;

    /** key → entry (processed flag, attempt count, last-touch epoch millis). */
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public ProcessedMessageTracker() {
        this(DEFAULT_TTL_MILLIS, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ATTEMPTS);
    }

    ProcessedMessageTracker(long ttlMillis, int maxEntries, int maxAttempts) {
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
        this.maxAttempts = maxAttempts;
    }

    /** Maksimalan broj transient pokusaja pre nego sto poruka ide u DLQ. */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** True ako je poruka sa datim kljucem vec USPESNO obradjena (poslata). */
    public boolean isProcessed(String key) {
        if (key == null) {
            return false;
        }
        Entry e = entries.get(key);
        if (e == null) {
            return false;
        }
        if (isExpired(e)) {
            entries.remove(key, e);
            return false;
        }
        return e.processed;
    }

    /** Belezi uspesnu obradu poruke (za dedup pri redelivery-ju). */
    public void markProcessed(String key) {
        if (key == null) {
            return;
        }
        evictIfNeeded();
        entries.compute(key, (k, e) -> {
            if (e == null) {
                e = new Entry();
            }
            e.processed = true;
            e.touchedAt = now();
            return e;
        });
    }

    /**
     * Belezi transient neuspeh za kljuc i vraca {@code true} ako je broj pokusaja
     * dostigao {@code maxAttempts} (→ poruka treba u DLQ, ne requeue).
     */
    public boolean recordFailureAndCheckExhausted(String key) {
        if (key == null) {
            // Bez kljuca ne mozemo da brojimo pokusaje — fallback na requeue
            // (ne escaliraj u DLQ tiho).
            return false;
        }
        evictIfNeeded();
        Entry e = entries.compute(key, (k, existing) -> {
            if (existing == null || isExpired(existing)) {
                existing = new Entry();
            }
            existing.attempts++;
            existing.touchedAt = now();
            return existing;
        });
        return e.attempts >= maxAttempts;
    }

    /** Cisti zapis kljuca (npr. posle slanja u DLQ — ne treba vise brojanje). */
    public void clear(String key) {
        if (key != null) {
            entries.remove(key);
        }
    }

    int size() {
        return entries.size();
    }

    private boolean isExpired(Entry e) {
        return (now() - e.touchedAt) > ttlMillis;
    }

    private void evictIfNeeded() {
        if (entries.size() < maxEntries) {
            return;
        }
        long cutoff = now() - ttlMillis;
        entries.entrySet().removeIf(en -> en.getValue().touchedAt < cutoff);
        // Ako je i dalje preveliko (svi sveži), obrisi najstarije do polovine.
        if (entries.size() >= maxEntries) {
            entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(
                            (a, b) -> Long.compare(a.touchedAt, b.touchedAt)))
                    .limit(Math.max(1, entries.size() / 2))
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(entries::remove);
        }
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private static final class Entry {
        private boolean processed;
        private int attempts;
        private long touchedAt = System.currentTimeMillis();
    }
}
