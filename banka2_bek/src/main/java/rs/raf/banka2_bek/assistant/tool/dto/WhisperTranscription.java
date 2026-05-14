package rs.raf.banka2_bek.assistant.tool.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rezultat transkripcije Whisper STT sidecar-a (port 8093).
 *
 * <p>Sidecar emituje JSON sa snake_case poljima (FastAPI default) — Jackson mapira
 * na camelCase Java polja kroz {@link JsonProperty} anotacije.</p>
 *
 * <p>Hallucination guard: ako Whisper ne pronadje stvaran govor u audiju (npr.
 * korisnik je samo disao u mikrofon ili je ambient noise), sidecar postavlja
 * {@code detectedSpeech=false} i {@code reason} sa kratkim opisom ("no speech",
 * "vad_below_threshold", "silent_audio"). BE u tom slucaju ne salje text Gemmi
 * i vraca 400 sa user-friendly porukom.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WhisperTranscription(
        @JsonProperty("text") String text,
        @JsonProperty("language") String language,
        @JsonProperty("language_probability") Double languageProbability,
        @JsonProperty("duration_seconds") Double durationSeconds,
        @JsonProperty("speech_duration_seconds") Double speechDurationSeconds,
        @JsonProperty("voice_activity_ratio") Double voiceActivityRatio,
        @JsonProperty("detected_speech") boolean detectedSpeech,
        @JsonProperty("reason") String reason
) {
    /**
     * Helper koji kreira "no speech" varijantu kad sidecar ili FE prosledi
     * prazan audio. Sluzi unutar BE-a kao defensive default.
     */
    public static WhisperTranscription noSpeech(String reason) {
        return new WhisperTranscription("", null, null, null, null, null, false,
                reason == null ? "no_speech_detected" : reason);
    }
}
