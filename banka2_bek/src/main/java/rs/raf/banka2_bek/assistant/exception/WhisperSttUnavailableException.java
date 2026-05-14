package rs.raf.banka2_bek.assistant.exception;

/**
 * Bacanje kad Whisper STT sidecar nije dostupan (mreza pala, 5xx, timeout).
 * Mapira se u {@code /assistant/chat-multipart} controller-u na fallback flow
 * (ako je {@code arbitro.whisper.enabled=false} ili sidecar trenutno down),
 * u kom slucaju audio se ignorise i koristi se tekstualni {@code message} field.
 */
public class WhisperSttUnavailableException extends RuntimeException {

    public WhisperSttUnavailableException(String message) {
        super(message);
    }

    public WhisperSttUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
