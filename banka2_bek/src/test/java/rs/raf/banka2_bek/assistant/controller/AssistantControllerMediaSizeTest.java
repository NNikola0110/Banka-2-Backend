package rs.raf.banka2_bek.assistant.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import rs.raf.banka2_bek.assistant.service.AssistantService;
import rs.raf.banka2_bek.assistant.service.ProactiveSuggestionService;
import rs.raf.banka2_bek.assistant.tool.client.KokoroTtsClient;
import rs.raf.banka2_bek.assistant.tool.client.WhisperSttClient;
import rs.raf.banka2_bek.auth.util.UserResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [P2-input-validation-1 / R3 1609] Guard za velicinu media uploada — sprecava
 * OOM (getBytes()/base64 celog fajla). Oversized upload vraca SSE error i NE
 * poziva AssistantService.
 */
class AssistantControllerMediaSizeTest {

    private AssistantService assistantService;
    private UserResolver userResolver;
    private AssistantController controller;

    @BeforeEach
    void setUp() {
        assistantService = mock(AssistantService.class);
        userResolver = mock(UserResolver.class);
        KokoroTtsClient kokoro = mock(KokoroTtsClient.class);
        WhisperSttClient whisper = mock(WhisperSttClient.class);
        ProactiveSuggestionService suggestions = mock(ProactiveSuggestionService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        controller = new AssistantController(
                assistantService, userResolver, kokoro, whisper, suggestions, objectMapper);
    }

    @Test
    void oversizedImage_returnsSseError_doesNotCallService() {
        // 6MB > 5MB limit.
        byte[] big = new byte[6 * 1024 * 1024];
        MultipartFile image = new MockMultipartFile(
                "media", "huge.png", "image/png", big);

        SseEmitter emitter = controller.chatMultipart(
                image, null, "opisi sliku", null, null, null, null, null);

        assertThat(emitter).isNotNull();
        // Kljucno: AssistantService.chat se NE poziva (nema getBytes/base64 OOM put).
        verify(assistantService, never()).chat(any(), any());
    }

    @Test
    void oversizedAudio_returnsSseError_doesNotCallService() {
        byte[] big = new byte[6 * 1024 * 1024];
        MultipartFile audio = new MockMultipartFile(
                "audio", "huge.wav", "audio/wav", big);

        SseEmitter emitter = controller.chatMultipart(
                null, audio, "", null, null, null, null, null);

        assertThat(emitter).isNotNull();
        verify(assistantService, never()).chat(any(), any());
    }
}
