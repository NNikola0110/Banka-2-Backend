package rs.raf.banka2_bek.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import rs.raf.banka2_bek.assistant.dto.ChatRequestDto;
import rs.raf.banka2_bek.assistant.dto.ConversationListItemDto;
import rs.raf.banka2_bek.assistant.dto.HealthDto;
import rs.raf.banka2_bek.assistant.dto.MessageDto;
import rs.raf.banka2_bek.assistant.dto.PageContextDto;
import rs.raf.banka2_bek.assistant.exception.NoSpeechDetectedException;
import rs.raf.banka2_bek.assistant.exception.WhisperSttUnavailableException;
import rs.raf.banka2_bek.assistant.service.AssistantService;
import rs.raf.banka2_bek.assistant.service.ProactiveSuggestionService;
import rs.raf.banka2_bek.assistant.tool.client.KokoroTtsClient;
import rs.raf.banka2_bek.assistant.tool.client.WhisperSttClient;
import rs.raf.banka2_bek.assistant.tool.dto.WhisperTranscription;
import rs.raf.banka2_bek.auth.util.UserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

/**
 * Arbitro endpoint surface (Day 1 stub).
 *
 *  POST   /assistant/chat                     SSE chat stream
 *  GET    /assistant/conversations            list of user's convs
 *  GET    /assistant/conversations/{uuid}/messages
 *  DELETE /assistant/conversations/{uuid}     soft delete
 *  POST   /assistant/conversations/{uuid}/clear   clear messages
 *  GET    /assistant/health                   provider + tools reachability
 *
 * Sve trase su `authenticated()` u GlobalSecurityConfig — dodaje se u Day 2.
 */
@RestController
@RequestMapping("/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final AssistantService assistantService;
    private final UserResolver userResolver;
    private final KokoroTtsClient kokoroTtsClient;
    private final WhisperSttClient whisperSttClient;
    private final ProactiveSuggestionService proactiveSuggestionService;

    /**
     * Field name MORA da matchuje ime bean-a u AssistantConfig
     * (assistantObjectMapper) — Spring resolution by name jer Lombok
     * {@code @RequiredArgsConstructor} ne propagira {@code @Qualifier}.
     */
    private final ObjectMapper assistantObjectMapper;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequestDto request) {
        return assistantService.chat(userResolver.resolveCurrent(), request);
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationListItemDto>> listConversations() {
        return ResponseEntity.ok(assistantService.listConversations(userResolver.resolveCurrent()));
    }

    @GetMapping("/conversations/{uuid}/messages")
    public ResponseEntity<List<MessageDto>> getMessages(@PathVariable UUID uuid) {
        return ResponseEntity.ok(assistantService.getMessages(userResolver.resolveCurrent(), uuid));
    }

    @DeleteMapping("/conversations/{uuid}")
    public ResponseEntity<Void> softDelete(@PathVariable UUID uuid) {
        assistantService.softDelete(userResolver.resolveCurrent(), uuid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversations/{uuid}/clear")
    public ResponseEntity<Void> clearMessages(@PathVariable UUID uuid) {
        assistantService.clearMessages(userResolver.resolveCurrent(), uuid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    public ResponseEntity<HealthDto> health() {
        return ResponseEntity.ok(assistantService.health());
    }

    /* ================ Phase 5 — Voice output (Kokoro TTS) ================ */

    /**
     * Body za POST /assistant/tts. Drzano u istom fajlu jer je samo jedan
     * endpoint i nema potrebe za zasebnim DTO modulom.
     */
    @Data
    public static class TtsRequestBody {
        @NotBlank
        @Size(max = 5000, message = "Tekst max 5000 chars")
        private String text;
        private String voice;       // null = default ("af_bella")
        private String lang;        // null = default ("en-us")
        private Double speed;       // null = 1.0
    }

    /**
     * Phase 5 — proaktivne sugestije za usera (idle funds, stale orders, ...).
     * FE povlaci on-demand (npr. pri otvaranju panel-a) i prikazuje kao
     * tooltip iznad FAB-a. Server-side analiza, nema spam-a.
     */
    @GetMapping("/suggestions")
    public ResponseEntity<List<ProactiveSuggestionService.ProactiveSuggestion>> suggestions() {
        var user = userResolver.resolveCurrent();
        return ResponseEntity.ok(
                proactiveSuggestionService.getSuggestions(user.userId(), user.userRole())
        );
    }

    /**
     * Phase 5 — multimodal upload (audio ILI slika). Gemma 4 native ASR za
     * audio (16kHz mono WAV) i image-to-text za slike. Ollama prosledjuje
     * media kroz {@code images} polje u messages array-u (issue ollama#15333).
     *
     * - audio: korisnik prica → mic → MediaRecorder PCM/WAV → BE → base64 →
     *   Ollama → Gemma 4 transkribuje + odgovara u istom turn-u
     * - image: PDF racun, screenshot grafika, ... → BE → base64 → Gemma 4
     *
     * Format vraca isti SSE stream kao /chat — FE deli istu stream parser
     * logiku. Razlika: BE pre LLM poziva ubacuje "audio" flag u user message
     * koji asistent koristi za sopstveni transcript.
     */
    @PostMapping(value = "/chat-multipart", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatMultipart(
            @RequestPart(value = "media", required = false) org.springframework.web.multipart.MultipartFile media,
            // Posebno polje "audio" za eksplicitan audio upload (FE moze posalti
            // ili kao "audio" radi clarity ili kao "media" za backward-compat).
            @RequestPart(value = "audio", required = false) org.springframework.web.multipart.MultipartFile audio,
            // message NIJE required — kad korisnik salje samo audio (bez ukucanog teksta),
            // FE salje formData.append('message', '') sto Spring strict mode tretira kao
            // "missing part" i baca 400. required=false + null-check ispod.
            @RequestPart(value = "message", required = false) String message,
            @RequestPart(value = "conversationUuid", required = false) String conversationUuid,
            @RequestPart(value = "agenticMode", required = false) String agenticMode,
            @RequestPart(value = "useTools", required = false) String useTools,
            @RequestPart(value = "pageContext", required = false) String pageContextJson,
            @RequestPart(value = "language", required = false) String language
    ) {
        ChatRequestDto request = new ChatRequestDto();
        if (conversationUuid != null && !conversationUuid.isBlank()) {
            try {
                request.setConversationUuid(UUID.fromString(conversationUuid));
            } catch (IllegalArgumentException ignored) { /* novi UUID */ }
        }
        request.setUseTools(useTools == null || "true".equalsIgnoreCase(useTools));
        request.setAgenticMode("true".equalsIgnoreCase(agenticMode));

        // Page context — opciono multipart polje sa JSON sadrzajem; tih fail
        // ako je malformed, ide se bez konteksta.
        if (pageContextJson != null && !pageContextJson.isBlank()) {
            try {
                PageContextDto pc = assistantObjectMapper.readValue(pageContextJson, PageContextDto.class);
                request.setPageContext(pc);
            } catch (Exception ignored) { /* invalid JSON — ignorisi */ }
        }

        // Whisper STT integracija. Audio se transkribuje SERVER-SIDE pre nego sto
        // poruka stigne LLM-u — Gemma 4 native ASR je nepouzdan za srpski. Whisper
        // tiny model (CPU, ~2-3s na 10s audija) daje 95%+ tacnost.
        //
        // Tok:
        //   1. Detect audio: explicit "audio" part ili "media" part sa audio/* MIME-om
        //   2. Whisper /transcribe → WhisperTranscription
        //   3. Hallucination guard: detectedSpeech=false → SSE error + complete
        //   4. Effective message = transcript + (optional) text message
        //   5. Pass to AssistantService kao tekstualan chat (bez mediaBase64)
        //
        // Image flow (Gemma 4 image-to-text) ostaje nepromenjen — slike i dalje
        // idu kroz mediaBase64 polje jer Gemma to nativno podrzava.
        org.springframework.web.multipart.MultipartFile audioPart = pickAudioPart(audio, media);
        org.springframework.web.multipart.MultipartFile imagePart = pickImagePart(media, audioPart);

        String transcript = null;
        if (audioPart != null && !audioPart.isEmpty()) {
            try {
                byte[] audioBytes = audioPart.getBytes();
                WhisperTranscription t = whisperSttClient.transcribe(audioBytes,
                        audioPart.getOriginalFilename(),
                        (language == null || language.isBlank()) ? null : language);
                transcript = t.text() != null ? t.text().trim() : "";
                log.info("[Whisper] Transcribed: \"{}\" (lang={}, audio_dur={}s, speech_dur={}s)",
                        transcript.length() > 120 ? transcript.substring(0, 120) + "..." : transcript,
                        t.language(),
                        t.durationSeconds(),
                        t.speechDurationSeconds());
            } catch (NoSpeechDetectedException e) {
                // Hallucination guard — sidecar javio da audio nema govor.
                // Emit-uj SSE error i complete-uj emitter (FE prepoznaje `error`
                // event i prikazuje toast korisniku).
                SseEmitter emitter = new SseEmitter(5000L);
                rs.raf.banka2_bek.assistant.service.SseEvents.error(emitter,
                        "no_speech_detected",
                        "Nisam te cuo - ponovi ako mozes");
                emitter.complete();
                return emitter;
            } catch (WhisperSttUnavailableException e) {
                // Graceful fallback: sidecar nije dostupan ili je flagovan disabled.
                // Ako korisnik nije ukucao text, javljamo gresku. Inace nastavlja
                // se sa tekstualnom porukom (audio se tiho ignorise).
                log.warn("Whisper STT unavailable, falling back to text-only: {}", e.getMessage());
                if (message == null || message.isBlank()) {
                    SseEmitter emitter = new SseEmitter(5000L);
                    rs.raf.banka2_bek.assistant.service.SseEvents.error(emitter,
                            "whisper_unavailable",
                            "Glasovni servis trenutno nije dostupan. Pokusaj ponovo ili ukucaj poruku.");
                    emitter.complete();
                    return emitter;
                }
                // Ostavi transcript = null pa ce text-only flow ispod uzeti message
            } catch (java.io.IOException e) {
                log.warn("Failed to read audio bytes from multipart: {}", e.getMessage());
                // Tih fail — text-only flow ispod
            }
        }

        // Effective message:
        //   - imamo i transcript i text → spojimo "transcript text"
        //   - samo transcript → transcript
        //   - samo text → text
        //   - nista (samo image) → placeholder za Gemma multimodal
        String effectiveMessage;
        if (transcript != null && !transcript.isEmpty() && message != null && !message.isBlank()) {
            effectiveMessage = transcript + " " + message.trim();
        } else if (transcript != null && !transcript.isEmpty()) {
            effectiveMessage = transcript;
        } else if (message != null && !message.isBlank()) {
            effectiveMessage = message;
        } else if (imagePart != null && !imagePart.isEmpty()) {
            effectiveMessage = "(Korisnik je poslao sliku — molim te opisi je i odgovori.)";
        } else {
            effectiveMessage = "(Korisnik je poslao audio/slika fajl — molim te transkribuj i odgovori.)";
        }
        request.setMessage(effectiveMessage);

        // Slika (NE audio) — Gemma 4 ce je primiti kroz `images` polje. Audio se NE
        // prosledi Gemmi posto smo ga vec transkribovali kroz Whisper.
        if (imagePart != null && !imagePart.isEmpty()) {
            try {
                String b64 = java.util.Base64.getEncoder().encodeToString(imagePart.getBytes());
                request.setMediaBase64(java.util.List.of(b64));
            } catch (java.io.IOException e) {
                // Tih fail — message ide bez slike
            }
        }
        return assistantService.chat(userResolver.resolveCurrent(), request);
    }

    /**
     * Odredjuje koji multipart part predstavlja audio. Prioritet:
     * <ol>
     *   <li>Eksplicitan "audio" part ako postoji</li>
     *   <li>"media" part ako Content-Type pocinje sa audio/ ili filename ima
     *       audio ekstenziju (.wav/.webm/.mp3/.ogg/.m4a/.flac)</li>
     *   <li>null ako nijedan nije audio</li>
     * </ol>
     */
    private org.springframework.web.multipart.MultipartFile pickAudioPart(
            org.springframework.web.multipart.MultipartFile audio,
            org.springframework.web.multipart.MultipartFile media) {
        if (audio != null && !audio.isEmpty()) return audio;
        if (media != null && !media.isEmpty() && isAudioPart(media)) return media;
        return null;
    }

    /**
     * Vraca media part SAMO ako nije isti kao audio part (da se ne salje audio
     * Gemmi koji je vec transkribovan kroz Whisper).
     */
    private org.springframework.web.multipart.MultipartFile pickImagePart(
            org.springframework.web.multipart.MultipartFile media,
            org.springframework.web.multipart.MultipartFile audioPart) {
        if (media == null || media.isEmpty()) return null;
        if (media == audioPart) return null;  // vec se koristi za STT
        if (isAudioPart(media)) return null;  // audio detektovan ali je u media polju
        return media;
    }

    private boolean isAudioPart(org.springframework.web.multipart.MultipartFile part) {
        if (part == null) return false;
        String ct = part.getContentType();
        if (ct != null && ct.toLowerCase().startsWith("audio/")) return true;
        String name = part.getOriginalFilename();
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".wav") || lower.endsWith(".webm") || lower.endsWith(".mp3")
                || lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".m4a")
                || lower.endsWith(".flac");
    }

    /**
     * POST /assistant/tts — generise audio iz teksta i strim-uje WAV bytes.
     * FE moze da uzme response kao Blob i prosledi <audio> tag-u.
     */
    @PostMapping(value = "/tts", produces = "audio/wav")
    public ResponseEntity<byte[]> tts(@Valid @RequestBody TtsRequestBody body) {
        double speed = body.getSpeed() == null ? 1.0 : body.getSpeed();
        byte[] wav = kokoroTtsClient.synthesize(body.getText(), body.getVoice(), body.getLang(), speed);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(wav);
    }
}
