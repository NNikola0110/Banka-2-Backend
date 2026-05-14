package rs.raf.banka2_bek.assistant.exception;

/**
 * Bacanje kad Whisper sidecar transkribuje audio ali javlja {@code detected_speech=false}
 * (audio sadrzi samo tisinu, ambient noise ili je VAD odsekao sve segmente).
 *
 * <p>Hallucination guard: bez ovog provere, Whisper ponekad emituje besmislen
 * tekst za audio bez govora ("...", "Subtitles by ...", random reci) — Gemma bi
 * onda na to odgovorila. Bolje da BE odmah vrati 400 sa porukom korisniku da
 * ponovi.</p>
 *
 * <p>Mapira se u 400 Bad Request sa srpskom porukom "Nisam te cuo - ponovi ako mozes".</p>
 */
public class NoSpeechDetectedException extends RuntimeException {

    private final String reason;

    public NoSpeechDetectedException(String reason) {
        super("Whisper sidecar reported no speech in audio (reason="
                + (reason == null ? "unknown" : reason) + ")");
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
