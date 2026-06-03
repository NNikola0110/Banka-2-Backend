package rs.raf.banka2_bek.assistant.tool.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import rs.raf.banka2_bek.assistant.config.AssistantProperties;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-perf-nplus1-1 (R5 1905): strukturalni test za @PreDestroy shutdown
 * dedikovanog 16-thread HTTP executor-a u {@link LlmHttpClient}.
 *
 * <p>Ranije je executor bio LOKALNA promenljiva — curio je na svaki context
 * restart (test/redeploy), 16 daemon thread-ova bez join-a. Sad je polje sa
 * {@code @PreDestroy} {@code shutdown()} hook-om. Pin-ujemo OBLIK fixa:
 * (1) {@code shutdown()} ima {@code @PreDestroy}; (2) poziv ga zaista gasi.
 */
class LlmHttpClientShutdownTest {

    @Test
    @DisplayName("R5 1905: shutdown() metoda nosi @PreDestroy")
    void shutdownMethodHasPreDestroy() throws Exception {
        Method shutdown = LlmHttpClient.class.getDeclaredMethod("shutdown");
        assertThat(shutdown.isAnnotationPresent(PreDestroy.class))
                .as("shutdown() mora biti @PreDestroy (graceful executor drain pri context close)")
                .isTrue();
    }

    @Test
    @DisplayName("R5 1905: shutdown() zaista gasi dedikovani executor")
    void shutdownTerminatesExecutor() throws Exception {
        AssistantProperties props = new AssistantProperties();
        LlmHttpClient client = new LlmHttpClient(props, new ObjectMapper());

        Field executorField = LlmHttpClient.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        ExecutorService executor = (ExecutorService) executorField.get(client);

        assertThat(executor.isShutdown()).as("pre shutdown-a executor je aktivan").isFalse();

        client.shutdown();

        assertThat(executor.isShutdown()).as("posle shutdown-a executor je ugasen").isTrue();
        assertThat(executor.isTerminated())
                .as("prazan pool se odmah terminira posle shutdown-a")
                .isTrue();
    }
}
