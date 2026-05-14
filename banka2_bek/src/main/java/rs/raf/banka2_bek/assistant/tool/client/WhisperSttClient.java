package rs.raf.banka2_bek.assistant.tool.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import rs.raf.banka2_bek.assistant.exception.NoSpeechDetectedException;
import rs.raf.banka2_bek.assistant.exception.WhisperSttUnavailableException;
import rs.raf.banka2_bek.assistant.tool.dto.WhisperTranscription;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * HTTP klijent za Whisper STT sidecar (Banka-2-Tools/whisper-service, port 8093).
 *
 * <p>Sidecar API:
 * <ul>
 *   <li>POST {url}/transcribe  multipart audio (form polje "audio") + opcionalni
 *       query param {@code ?language=sr} (BCP-47); vraca {@link WhisperTranscription} JSON.</li>
 *   <li>GET  {url}/health      vraca {@code {"status":"ok","model_loaded":true,...}}.</li>
 * </ul>
 *
 * <p>Koristi {@link java.net.http.HttpClient} (paritet sa KokoroTtsClient,
 * WikipediaToolClient i RagToolClient — sve Arbitro sidecar klijent klase
 * koriste low-level java.net.http jer Spring 7 RestClient body() pattern nije
 * pouzdano serijalizovao body preko Docker network-a).</p>
 *
 * <p>Hallucination guard: ako sidecar vrati {@code detected_speech=false}, klijent
 * baca {@link NoSpeechDetectedException} umesto da vrati prazan transcript.
 * Controller mapira u 400 sa user-friendly srpskom porukom.</p>
 */
@Component
@Slf4j
public class WhisperSttClient {

    private final String baseUrl;
    private final boolean enabled;
    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public WhisperSttClient(
            @Value("${arbitro.whisper.url:http://host.docker.internal:8093}") String baseUrl,
            @Value("${arbitro.whisper.enabled:true}") boolean enabled,
            @Value("${arbitro.whisper.timeout-ms:30000}") long timeoutMs,
            @Qualifier("assistantObjectMapper") ObjectMapper objectMapper) {
        // Normalizuj base URL — uklanjamo trailing slash da koncatenacija sa "/transcribe"
        // ne pravi dvostruki "//" (FastAPI uvicorn redirect-uje 307 koji ne nosi body).
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.enabled = enabled;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.objectMapper = objectMapper;
        // HTTP/1.1 obavezno — uvicorn (FastAPI) odbacuje HTTP/2 upgrade sa
        // "Invalid HTTP request received" warning-om. Paritet sa ostalim
        // Arbitro sidecar klijentima.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Sinhrono salje audio sidecar-u i vraca transkript.
     *
     * @param audioBytes raw audio bytes (WAV/MP3/OGG/Webm — sidecar koristi
     *                   ffmpeg za format detection)
     * @param filename   ime fajla koje ide u multipart form-data (npr.
     *                   "recording.webm"). Sidecar ga koristi za format
     *                   detection ako Content-Type fali; mora imati ekstenziju.
     * @param language   opcioni BCP-47 jezicki kod (sr, en, hr, ...). Null = autodetect.
     * @return parsed transcription
     * @throws NoSpeechDetectedException kad sidecar javi {@code detected_speech=false}
     * @throws WhisperSttUnavailableException kad sidecar 5xx, timeout-uje ili je flagovan disabled
     */
    public WhisperTranscription transcribe(byte[] audioBytes, String filename, @Nullable String language) {
        if (!enabled) {
            throw new WhisperSttUnavailableException("Whisper STT disabled (arbitro.whisper.enabled=false)");
        }
        if (audioBytes == null || audioBytes.length == 0) {
            throw new WhisperSttUnavailableException("Audio bytes are null or empty");
        }

        long startMs = System.currentTimeMillis();
        String safeFilename = (filename == null || filename.isBlank()) ? "audio.webm" : filename;
        // Multipart boundary — random UUID daje 36 hex char-ova, dovoljno random.
        String boundary = "----WhisperClient" + UUID.randomUUID().toString().replace("-", "");

        byte[] body;
        try {
            body = buildMultipartBody(boundary, "audio", safeFilename, audioBytes);
        } catch (IOException e) {
            throw new WhisperSttUnavailableException("Failed to build multipart body: " + e.getMessage(), e);
        }

        String url = baseUrl + "/transcribe";
        if (language != null && !language.isBlank()) {
            url += "?language=" + URLEncoder.encode(language, StandardCharsets.UTF_8);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpConnectTimeoutException e) {
            throw new WhisperSttUnavailableException(
                    "Whisper sidecar connect timeout (" + baseUrl + "): " + e.getMessage(), e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new WhisperSttUnavailableException(
                    "Whisper sidecar read timeout after " + timeout.toMillis() + "ms", e);
        } catch (Exception e) {
            throw new WhisperSttUnavailableException(
                    "Whisper sidecar unreachable (" + baseUrl + "): " + e.getMessage(), e);
        }

        int status = resp.statusCode();
        if (status / 100 == 5) {
            throw new WhisperSttUnavailableException(
                    "Whisper sidecar HTTP " + status + ": " + resp.body());
        }
        if (status / 100 != 2) {
            // 4xx (npr. 422 unsupported format) — tretiraj kao unavailable da BE moze
            // graceful fallback na tekstualni message.
            throw new WhisperSttUnavailableException(
                    "Whisper sidecar HTTP " + status + ": " + resp.body());
        }

        WhisperTranscription t;
        try {
            t = objectMapper.readValue(resp.body(), WhisperTranscription.class);
        } catch (Exception e) {
            throw new WhisperSttUnavailableException(
                    "Whisper sidecar returned malformed JSON: " + e.getMessage(), e);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("[Whisper] audio_bytes={} lang={} duration={}s speech={}s detected={} transcript_chars={} infer_ms={}",
                audioBytes.length,
                t.language(),
                t.durationSeconds(),
                t.speechDurationSeconds(),
                t.detectedSpeech(),
                t.text() != null ? t.text().length() : 0,
                elapsedMs);

        if (!t.detectedSpeech()) {
            throw new NoSpeechDetectedException(t.reason());
        }

        return t;
    }

    /**
     * Health check — sidecar je dostupan I model je ucitan.
     *
     * <p>Pingovaceemo {@code /health} endpoint sa 3s timeout-om. Sidecar treba
     * da vrati 200 sa {@code model_loaded=true} u body-ju. Ako je 200 ali
     * {@code model_loaded=false} (model jos uvek loaduje), tretiramo kao
     * not-reachable (FE-u javljamo da sidecar nije spreman).</p>
     */
    public boolean isReachable() {
        if (!enabled) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) return false;
            // Proverava da li body sadrzi "model_loaded":true; ako sidecar nema
            // model_loaded polje (stariji health endpoint), prihvatamo 200 kao OK.
            String body = resp.body();
            if (body == null || body.isBlank()) return true;
            if (!body.contains("model_loaded")) return true;
            return body.contains("\"model_loaded\":true")
                    || body.contains("\"model_loaded\": true");
        } catch (Exception e) {
            log.debug("Whisper sidecar ping failed: {}", e.getMessage());
            return false;
        }
    }

    /** Aliasiramo {@link #isReachable()} radi paritet sa drugim Arbitro klijentima. */
    public boolean ping() {
        return isReachable();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Gradi multipart/form-data body sa jednim "audio" file part-om.
     * RFC 7578 sintaksa — boundary se umnozava sa "--" prefiksom.
     */
    private byte[] buildMultipartBody(String boundary, String fieldName, String filename, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 256);
        String contentType = guessContentType(filename);

        // Part header
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName
                + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private String guessContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".webm")) return "audio/webm";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg") || lower.endsWith(".oga")) return "audio/ogg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".flac")) return "audio/flac";
        return "application/octet-stream";
    }
}
