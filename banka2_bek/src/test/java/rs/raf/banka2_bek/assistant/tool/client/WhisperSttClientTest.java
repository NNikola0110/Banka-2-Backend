package rs.raf.banka2_bek.assistant.tool.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.raf.banka2_bek.assistant.exception.NoSpeechDetectedException;
import rs.raf.banka2_bek.assistant.exception.WhisperSttUnavailableException;
import rs.raf.banka2_bek.assistant.tool.dto.WhisperTranscription;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test za {@link WhisperSttClient}.
 *
 * <p>Pristup: pokrecemo lokalni {@link com.sun.net.httpserver.HttpServer}
 * (JDK built-in) na ephemeral portu (0) — daje deterministican mock sidecar
 * koji moze da emituje proizvoljne JSON odgovore + HTTP status kodove. Bolje
 * od Mockito mock-a {@link java.net.http.HttpClient} jer je client final
 * klasa (HttpClient.newBuilder().build() vraca final implementation).</p>
 */
class WhisperSttClientTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Captured headers + body iz poslednjeg request-a — koristi se za assertion-e. */
    private final AtomicReference<String> capturedContentType = new AtomicReference<>();
    private final AtomicReference<String> capturedQuery = new AtomicReference<>();
    private final AtomicReference<byte[]> capturedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private WhisperSttClient newClient() {
        return new WhisperSttClient("http://127.0.0.1:" + port, true, 5000L, objectMapper);
    }

    private WhisperSttClient newDisabledClient() {
        return new WhisperSttClient("http://127.0.0.1:" + port, false, 5000L, objectMapper);
    }

    private void registerHandler(String path, HttpHandler handler) {
        server.createContext(path, handler);
        server.start();
    }

    /* ============================== happy path ============================== */

    @Test
    void transcribeReturnsParsedTranscriptionWhenSidecarReturns200() throws Exception {
        registerHandler("/transcribe", exchange -> {
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedQuery.set(exchange.getRequestURI().getQuery());
            capturedBody.set(exchange.getRequestBody().readAllBytes());

            String body = """
                    {
                      "text": "Pozdrav, kako si?",
                      "language": "sr",
                      "language_probability": 0.98,
                      "duration_seconds": 3.5,
                      "speech_duration_seconds": 3.1,
                      "voice_activity_ratio": 0.89,
                      "detected_speech": true,
                      "reason": null
                    }
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        WhisperTranscription t = client.transcribe(new byte[] {1, 2, 3, 4}, "audio.webm", "sr");

        assertThat(t.text()).isEqualTo("Pozdrav, kako si?");
        assertThat(t.language()).isEqualTo("sr");
        assertThat(t.languageProbability()).isEqualTo(0.98);
        assertThat(t.detectedSpeech()).isTrue();
    }

    @Test
    void transcribeBuildsMultipartRequestWithAudioFieldAndCorrectContentType() throws Exception {
        registerHandler("/transcribe", exchange -> {
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedBody.set(exchange.getRequestBody().readAllBytes());

            String body = """
                    {"text":"x","language":"en","language_probability":1.0,
                     "duration_seconds":1.0,"speech_duration_seconds":1.0,
                     "voice_activity_ratio":1.0,"detected_speech":true,"reason":null}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        byte[] fakeAudio = {0x52, 0x49, 0x46, 0x46};  // "RIFF" header
        client.transcribe(fakeAudio, "test.wav", null);

        // Content-Type header mora pocinjati sa multipart/form-data + boundary
        assertThat(capturedContentType.get()).startsWith("multipart/form-data; boundary=");
        // Body mora sadrziti "audio" form field, ime fajla i RIFF bytes
        String bodyAsString = new String(capturedBody.get(), StandardCharsets.ISO_8859_1);
        assertThat(bodyAsString).contains("name=\"audio\"");
        assertThat(bodyAsString).contains("filename=\"test.wav\"");
        assertThat(bodyAsString).contains("RIFF");
    }

    @Test
    void transcribeAppendsLanguageQueryParamWhenProvided() throws Exception {
        registerHandler("/transcribe", exchange -> {
            capturedQuery.set(exchange.getRequestURI().getQuery());
            String body = """
                    {"text":"x","language":"sr","language_probability":1.0,
                     "duration_seconds":1.0,"speech_duration_seconds":1.0,
                     "voice_activity_ratio":1.0,"detected_speech":true,"reason":null}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        client.transcribe(new byte[] {1, 2}, "x.wav", "sr");

        assertThat(capturedQuery.get()).isEqualTo("language=sr");
    }

    @Test
    void transcribeOmitsLanguageQueryParamWhenNullOrBlank() throws Exception {
        registerHandler("/transcribe", exchange -> {
            capturedQuery.set(exchange.getRequestURI().getQuery());
            String body = """
                    {"text":"x","language":"en","language_probability":1.0,
                     "duration_seconds":1.0,"speech_duration_seconds":1.0,
                     "voice_activity_ratio":1.0,"detected_speech":true,"reason":null}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        client.transcribe(new byte[] {1, 2}, "x.wav", null);

        assertThat(capturedQuery.get()).isNull();
    }

    /* ============================== error path ============================== */

    @Test
    void transcribeThrowsNoSpeechDetectedExceptionWhenSidecarReportsDetectedSpeechFalse() throws Exception {
        registerHandler("/transcribe", exchange -> {
            String body = """
                    {
                      "text": "",
                      "language": null,
                      "language_probability": null,
                      "duration_seconds": 2.0,
                      "speech_duration_seconds": 0.0,
                      "voice_activity_ratio": 0.05,
                      "detected_speech": false,
                      "reason": "vad_below_threshold"
                    }
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        assertThatThrownBy(() -> client.transcribe(new byte[] {1, 2}, "silent.wav", null))
                .isInstanceOf(NoSpeechDetectedException.class)
                .hasMessageContaining("vad_below_threshold");
    }

    @Test
    void transcribeThrowsWhisperUnavailableWhenSidecarReturns500() throws Exception {
        registerHandler("/transcribe", exchange -> {
            byte[] bytes = "Internal Server Error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        assertThatThrownBy(() -> client.transcribe(new byte[] {1, 2}, "x.wav", null))
                .isInstanceOf(WhisperSttUnavailableException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void transcribeThrowsWhisperUnavailableWhenSidecarReturns422() throws Exception {
        registerHandler("/transcribe", exchange -> {
            byte[] bytes = "Unsupported audio format".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        assertThatThrownBy(() -> client.transcribe(new byte[] {1, 2}, "x.wav", null))
                .isInstanceOf(WhisperSttUnavailableException.class)
                .hasMessageContaining("HTTP 422");
    }

    @Test
    void transcribeThrowsWhisperUnavailableWhenSidecarReturnsMalformedJson() throws Exception {
        registerHandler("/transcribe", exchange -> {
            byte[] bytes = "not valid json {".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        assertThatThrownBy(() -> client.transcribe(new byte[] {1, 2}, "x.wav", null))
                .isInstanceOf(WhisperSttUnavailableException.class)
                .hasMessageContaining("malformed JSON");
    }

    @Test
    void transcribeThrowsWhisperUnavailableWhenSidecarIsUnreachable() {
        // Server nije pokrenut (ne pozivamo registerHandler) — port 0 / loopback
        // ce odbiti connection.
        // Koristimo netezeen port (rezervisemo + pustimo) — connect ce probati i pasti.
        WhisperSttClient client = new WhisperSttClient(
                "http://127.0.0.1:1",  // port 1 — sigurno odbija connection
                true, 2000L, objectMapper);

        assertThatThrownBy(() -> client.transcribe(new byte[] {1, 2}, "x.wav", null))
                .isInstanceOf(WhisperSttUnavailableException.class);
    }

    @Test
    void transcribeThrowsWhenDisabled() {
        WhisperSttClient client = newDisabledClient();
        assertThatThrownBy(() -> client.transcribe(new byte[] {1, 2}, "x.wav", null))
                .isInstanceOf(WhisperSttUnavailableException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void transcribeThrowsForEmptyAudioBytes() {
        WhisperSttClient client = newClient();
        assertThatThrownBy(() -> client.transcribe(new byte[] {}, "x.wav", null))
                .isInstanceOf(WhisperSttUnavailableException.class)
                .hasMessageContaining("null or empty");
        assertThatThrownBy(() -> client.transcribe(null, "x.wav", null))
                .isInstanceOf(WhisperSttUnavailableException.class)
                .hasMessageContaining("null or empty");
    }

    /* ============================== health check ============================== */

    @Test
    void isReachableReturnsTrueWhen200AndModelLoaded() throws Exception {
        registerHandler("/health", exchange -> {
            String body = "{\"status\":\"ok\",\"model_loaded\":true,\"model\":\"tiny\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        assertThat(client.isReachable()).isTrue();
        assertThat(client.ping()).isTrue();  // alias
    }

    @Test
    void isReachableReturnsFalseWhen200ButModelNotLoaded() throws Exception {
        registerHandler("/health", exchange -> {
            String body = "{\"status\":\"loading\",\"model_loaded\":false}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        assertThat(client.isReachable()).isFalse();
    }

    @Test
    void isReachableReturnsTrueWhen200AndNoModelLoadedField() throws Exception {
        // Backward compat: stariji sidecar /health endpoint moze vratiti samo {"status":"ok"}.
        registerHandler("/health", exchange -> {
            String body = "{\"status\":\"ok\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = newClient();
        assertThat(client.isReachable()).isTrue();
    }

    @Test
    void isReachableReturnsFalseWhenNon200() throws Exception {
        registerHandler("/health", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        WhisperSttClient client = newClient();
        assertThat(client.isReachable()).isFalse();
    }

    @Test
    void isReachableReturnsFalseWhenDisabled() {
        WhisperSttClient client = newDisabledClient();
        assertThat(client.isReachable()).isFalse();
    }

    @Test
    void isReachableReturnsFalseWhenSidecarUnreachable() {
        WhisperSttClient client = new WhisperSttClient(
                "http://127.0.0.1:1", true, 2000L, objectMapper);
        assertThat(client.isReachable()).isFalse();
    }

    /* ============================== misc ============================== */

    @Test
    void normalizesBaseUrlTrailingSlash() throws Exception {
        registerHandler("/health", exchange -> {
            String body = "{\"status\":\"ok\",\"model_loaded\":true}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        WhisperSttClient client = new WhisperSttClient(
                "http://127.0.0.1:" + port + "/",  // sa trailing slash-om
                true, 5000L, objectMapper);
        // Ako klijent nije normalizovao base URL, koncatenacija "/" + "/health" pravi "//health"
        // sto bi server vratio kao 404. Klijent normalizuje pa ovo prolazi.
        assertThat(client.isReachable()).isTrue();
    }

    @Test
    void isEnabledReflectsConstructorFlag() {
        WhisperSttClient enabled = newClient();
        WhisperSttClient disabled = newDisabledClient();
        assertThat(enabled.isEnabled()).isTrue();
        assertThat(disabled.isEnabled()).isFalse();
    }
}
