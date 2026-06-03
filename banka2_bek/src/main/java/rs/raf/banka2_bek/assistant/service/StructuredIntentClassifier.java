package rs.raf.banka2_bek.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import rs.raf.banka2_bek.assistant.config.AssistantProperties;
import rs.raf.banka2_bek.assistant.dto.openai.OllamaGenerateRequest;
import rs.raf.banka2_bek.assistant.dto.openai.OllamaGenerateResponse;
import rs.raf.banka2_bek.assistant.tool.client.LlmHttpClient;
import rs.raf.banka2_bek.assistant.util.LogSanitizer;
import rs.raf.banka2_bek.auth.util.UserContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plan v3.6 §Task 3 — TRUE structured intent classifier preko Ollama
 * {@code /api/generate} sa {@code format} JSON schema constraint-om.
 *
 * <p>Pouzdanije od OpenAI-compat {@code tool_choice="required"} pristupa
 * koje koristi {@link rs.raf.banka2_bek.assistant.wizard.service.IntentClassifier}
 * — Gemma 4 E2B ignorise tool_choice cca 40% slucajeva, dok Ollama format
 * constraint forsira output kroz GBNF grammar (~95% reliability).</p>
 *
 * <p>Format result-a:</p>
 * <pre>{
 *   "tool": "create_payment",  // enum od dozvoljenih
 *   "confidence": 0.95         // 0..1
 * }</pre>
 *
 * <p>Confidence &lt; 0.5 ili tool == "none" → {@link Optional#empty()}
 * (caller ce pasti na legacy regex/tool_choice classifier).</p>
 */
@Component
@Slf4j
public class StructuredIntentClassifier {

    private static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "tool": {
                  "type": "string",
                  "enum": [
                    "create_payment",
                    "create_buy_order",
                    "create_sell_order",
                    "block_card",
                    "create_otc_offer",
                    "exercise_otc_contract",
                    "create_transfer_internal",
                    "create_transfer_fx",
                    "invest_in_fund",
                    "withdraw_from_fund",
                    "cancel_order",
                    "none"
                  ]
                },
                "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
              },
              "required": ["tool", "confidence"]
            }
            """;

    private static final String SYSTEM_PROMPT = """
            Klasifikuj korisnicku poruku u tacno jedan tool iz dozvoljenog skupa.
            Vrati JSON: { "tool": "...", "confidence": 0.0-1.0 }.
            Confidence > 0.7 = jasna namera; 0.3-0.7 = mozda; < 0.3 = vrati "none".
            Ne dodaj objasnjenja, samo JSON.
            """;

    private final LlmHttpClient llmHttpClient;
    private final AssistantProperties properties;
    private final ObjectMapper objectMapper;

    /** Minimum confidence da bismo prihvatili klasifikaciju. */
    private static final double MIN_CONFIDENCE = 0.5;

    public StructuredIntentClassifier(LlmHttpClient llmHttpClient,
                                       AssistantProperties properties,
                                       @Qualifier("assistantObjectMapper") ObjectMapper objectMapper) {
        this.llmHttpClient = llmHttpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Lista dozvoljenih intent toolova (paritet sa SCHEMA enum-om).
     * Vidljiva spolja radi testiranja i wizard registry sync-a.
     */
    public List<String> supportedTools() {
        return List.of(
                "create_payment",
                "create_buy_order",
                "create_sell_order",
                "block_card",
                "create_otc_offer",
                "exercise_otc_contract",
                "create_transfer_internal",
                "create_transfer_fx",
                "invest_in_fund",
                "withdraw_from_fund",
                "cancel_order"
        );
    }

    public Optional<IntentResult> classify(String userMessage, UserContext user) {
        if (userMessage == null || userMessage.isBlank()) return Optional.empty();
        try {
            Object schema = objectMapper.readValue(SCHEMA_JSON, Map.class);
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("temperature", 0.0);
            options.put("num_predict", 80);

            OllamaGenerateRequest req = new OllamaGenerateRequest(
                    properties.getModel(),
                    "Korisnicka poruka: " + userMessage,
                    SYSTEM_PROMPT,
                    schema,
                    Boolean.FALSE,
                    options
            );

            OllamaGenerateResponse resp = llmHttpClient.generate(req);
            if (resp == null || resp.response() == null || resp.response().isBlank()) {
                // [P2-input-validation-1 / R4 1782] sanitize CRLF iz user-input pre logovanja.
                log.info("ARBITRO StructuredIntentClassifier empty response for: {}",
                        LogSanitizer.sanitize(userMessage));
                return Optional.empty();
            }

            JsonNode parsed = objectMapper.readTree(resp.response());
            JsonNode toolNode = parsed.get("tool");
            JsonNode confNode = parsed.get("confidence");
            if (toolNode == null || confNode == null) {
                // resp.response() je LLM output (sadrzi reflektovan user-input) → sanitize.
                log.info("ARBITRO StructuredIntentClassifier malformed JSON: {}",
                        LogSanitizer.sanitize(resp.response()));
                return Optional.empty();
            }
            String tool = toolNode.asText();
            double confidence = confNode.asDouble();
            if ("none".equals(tool) || confidence < MIN_CONFIDENCE) {
                log.debug("ARBITRO StructuredIntentClassifier skipped tool={} conf={}",
                        tool, confidence);
                return Optional.empty();
            }
            log.info("ARBITRO StructuredIntentClassifier picked tool='{}' conf={} msg='{}'",
                    tool, confidence,
                    userMessage.length() > 60 ? userMessage.substring(0, 60) + "..." : userMessage);
            return Optional.of(new IntentResult(tool, confidence));
        } catch (Exception e) {
            log.warn("ARBITRO StructuredIntentClassifier failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public record IntentResult(String tool, double confidence) {}
}
