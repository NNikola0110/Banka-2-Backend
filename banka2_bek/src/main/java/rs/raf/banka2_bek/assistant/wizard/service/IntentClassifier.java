package rs.raf.banka2_bek.assistant.wizard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import rs.raf.banka2_bek.assistant.config.AssistantProperties;
import rs.raf.banka2_bek.assistant.dto.openai.OpenAiChatRequest;
import rs.raf.banka2_bek.assistant.dto.openai.OpenAiChatResponse;
import rs.raf.banka2_bek.assistant.dto.openai.OpenAiMessage;
import rs.raf.banka2_bek.assistant.dto.openai.OpenAiToolCall;
import rs.raf.banka2_bek.assistant.service.StructuredIntentClassifier;
import rs.raf.banka2_bek.assistant.tool.client.LlmHttpClient;
import rs.raf.banka2_bek.assistant.util.LogSanitizer;
import rs.raf.banka2_bek.assistant.wizard.registry.WizardRegistry;
import rs.raf.banka2_bek.auth.util.UserContext;
import rs.raf.banka2_bek.auth.util.UserRole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 4.6 — TRUE agentic intent classifier.
 *
 * <p>Replace-uje stari {@code detectForcedTool} regex sa LLM pozivom koji
 * dobija samo <i>minimalne</i> tool definicije (name + jedna recenica
 * opisa, bez parametara) i {@code tool_choice="required"}. LLM bira koji
 * tool najbolje matchuje user intent. Wizard onda popunjava parametre
 * interaktivno.</p>
 *
 * <p>Arhitektura:</p>
 * <pre>
 *   user msg ────────────────────► [classifier prompt + 16 minimal tools] ──► LLM
 *                                                                                │
 *                                                                                ▼
 *   wizard.start(toolName)  ◄─────── tool name ◄────── tool_calls[0].function.name
 * </pre>
 *
 * <p>Razlog izdvojenog poziva (umesto da se izvrsava na glavnoj LLM petlji
 * u {@link rs.raf.banka2_bek.assistant.service.AssistantService}): prompt
 * je drasticno kraci ({@code <500 tokena}) sto Gemma 4 E2B (5.1B params)
 * pouzdano hendluje. Prosirjen prompt sa role overlay-om + page fragmentom +
 * istorijom razgovora (~1500+ tokena) confuses small model i tool_choice
 * se ignorise.</p>
 *
 * <p>Cache: brzi LRU sa max 64 entries (key = lowercased userMessage)
 * — ako ista poruka stigne ponovo (debug iteracije), preskace LLM poziv.
 * </p>
 */
@Component
@Slf4j
public class IntentClassifier {

    /** Map<tool_name, jedna recenica opisa> — koriste se za minimalne schemas. */
    private static final Map<String, String> INTENT_DESCRIPTIONS = buildIntentDescriptions();

    /**
     * P0-B8 N3: EMPLOYEE-only intent-i. CLIENT-u se NE nude u tool katalogu
     * (klasifikator ih ne sme izabrati), pa klijent ne moze da pokrene
     * EMPLOYEE akciju kroz agenta. {@code unblock_card} je employee akcija
     * (klijent moze samo blokirati svoju karticu, ne odblokirati je).
     */
    private static final java.util.Set<String> EMPLOYEE_ONLY_INTENTS =
            java.util.Set.of("unblock_card");

    private final LlmHttpClient llmHttpClient;
    private final AssistantProperties properties;
    // R1-880: mrtav `safeJson` helper uklonjen; mapper ostaje constructor-injected
    // (Spring + unit-test wiring) — rezervisan za buduce structured-output logovanje.
    @SuppressWarnings("unused")
    private final ObjectMapper assistantObjectMapper;
    private final WizardRegistry wizardRegistry;
    /**
     * Plan v3.6 §Task 3 — primary classifier (Ollama structured outputs).
     * Optional zato sto unit testovi koji instanciraju IntentClassifier
     * direktno bez Spring konteksta ne moraju da setuju nista.
     */
    private final ObjectProvider<StructuredIntentClassifier> structuredClassifier;

    /** Tiny LRU cache — prevents repeating the same classifier call on retry. */
    private final java.util.Map<String, String> cache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                            return size() > 64;
                        }
                    });

    public IntentClassifier(LlmHttpClient llmHttpClient,
                             AssistantProperties properties,
                             @Qualifier("assistantObjectMapper") ObjectMapper assistantObjectMapper,
                             WizardRegistry wizardRegistry,
                             ObjectProvider<StructuredIntentClassifier> structuredClassifier) {
        this.llmHttpClient = llmHttpClient;
        this.properties = properties;
        this.assistantObjectMapper = assistantObjectMapper;
        this.wizardRegistry = wizardRegistry;
        this.structuredClassifier = structuredClassifier;
    }

    /**
     * Classify user message into a tool name from the wizard registry.
     *
     * @param userMessage natural language request ("Plati Milici 100", "Kupi 5 AAPL", ...)
     * @param user        acting user (used to filter role-restricted tools)
     * @return matched tool name (must exist in WizardRegistry), or empty if
     *         LLM unsure / no relevant tool
     */
    public Optional<String> classify(String userMessage, UserContext user) {
        if (userMessage == null || userMessage.isBlank()) return Optional.empty();
        // P0-B8 N3: cache key ukljucuje rolu da employee-only rezultat ne bi
        // procurio CLIENT-u kroz cache (cache-poisoning izmedju rola).
        String role = user == null || user.userRole() == null ? "?" : user.userRole();
        String key = role + "|" + userMessage.toLowerCase().trim();
        String cached = cache.get(key);
        if (cached != null) {
            log.debug("ARBITRO IntentClassifier cache hit: {} -> {}", key, cached);
            return Optional.of(cached);
        }

        // Plan v3.6 §Task 3 — primary primary: StructuredIntentClassifier
        // (Ollama format param sa JSON schema constraint). Pouzdanije od
        // tool_choice="required" na Gemma 4 E2B (~95% vs ~60%).
        StructuredIntentClassifier sic = structuredClassifier != null
                ? structuredClassifier.getIfAvailable() : null;
        if (sic != null) {
            Optional<StructuredIntentClassifier.IntentResult> structuredHit =
                    sic.classify(userMessage, user);
            if (structuredHit.isPresent()) {
                String tool = structuredHit.get().tool();
                // P0-B8 N3: ne dozvoli employee-only intent za CLIENT-a (defense-in-depth).
                if (wizardRegistry.has(tool) && isIntentAllowedForRole(tool, user)) {
                    cache.put(key, tool);
                    log.info("ARBITRO IntentClassifier structured picked tool='{}' conf={}",
                            tool, structuredHit.get().confidence());
                    return Optional.of(tool);
                }
                log.info("ARBITRO IntentClassifier structured picked '{}' but no wizard registered, "
                        + "falling back to tool_choice classifier", tool);
            }
        }

        try {
            List<Map<String, Object>> tools = buildMinimalTools(user);
            if (tools.isEmpty()) return Optional.empty();

            // Lean prompt — ne saljemo master prompt + role overlay + page fragment
            // jer ih Gemma 4 E2B (5.1B params) ne podnosi pouzdano sa
            // tool_choice="required". Ovde nam treba samo intent detection.
            String systemPrompt = """
                    Ti si intent klasifikator za Banka 2 aplikaciju. Korisnik je opisao
                    sta zeli da uradi. Pozovi PRECISNO JEDAN tool koji najbolje matchuje
                    namera. Ne dodaj parametre — samo izaberi tool name. Drugi servis ce
                    voditi korisnika kroz parametre kroz wizard.
                    """;

            List<OpenAiMessage> messages = List.of(
                    OpenAiMessage.system(systemPrompt),
                    OpenAiMessage.user(userMessage)
            );

            // NOTE: max_tokens=2048 (NE 256!) — Gemma 4 E2B emit-uje
            // ekstenzivan reasoning trace u `reasoning` polje pre tool_call-a
            // (~600-1500 tokena za 15 tools). Sa 256 cap-om finish_reason="length"
            // i tool_calls ostaju undefined. 2048 daje dovoljno headroom-a.
            OpenAiChatRequest req = new OpenAiChatRequest(
                    properties.getModel(),
                    messages,
                    tools,
                    "required",
                    Boolean.FALSE,
                    0.0,                  // deterministicki — minimum varijacije
                    0.95,
                    properties.getTopK(),
                    2048
            );

            OpenAiChatResponse resp = llmHttpClient.chatNonStream(req);
            String picked = extractFirstToolCallName(resp);
            if (picked == null) {
                // [P2-input-validation-1 / R4 1782] sanitize CRLF iz user-input pre logovanja.
                log.info("ARBITRO IntentClassifier no tool_calls in response for: {}",
                        LogSanitizer.sanitize(userMessage));
                return Optional.empty();
            }
            if (!wizardRegistry.has(picked)) {
                log.info("ARBITRO IntentClassifier picked unknown tool '{}' for: {}",
                        LogSanitizer.sanitize(picked), LogSanitizer.sanitize(userMessage));
                return Optional.empty();
            }
            // P0-B8 N3: defense-in-depth — i ako model ipak emit-uje employee-only
            // intent za klijenta (mada ga ne nudimo), odbij ga.
            if (!isIntentAllowedForRole(picked, user)) {
                log.info("ARBITRO IntentClassifier blocked employee-only intent '{}' for role {}",
                        picked, user == null ? "?" : user.userRole());
                return Optional.empty();
            }
            cache.put(key, picked);
            // [P2-input-validation-1 / R4 1782] sanitize CRLF + trunc na 60.
            log.info("ARBITRO IntentClassifier picked tool='{}' for msg='{}'",
                    LogSanitizer.sanitize(picked), LogSanitizer.sanitize(userMessage, 60));
            return Optional.of(picked);
        } catch (Exception e) {
            log.warn("ARBITRO IntentClassifier failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * P0-B8 N3: true ako data namera sme za korisnikovu rolu. EMPLOYEE-only
     * intent-i ({@link #EMPLOYEE_ONLY_INTENTS}) su dozvoljeni samo zaposlenima.
     */
    private boolean isIntentAllowedForRole(String tool, UserContext user) {
        if (!EMPLOYEE_ONLY_INTENTS.contains(tool)) return true;
        return user != null && UserRole.isEmployee(user.userRole());
    }

    private String extractFirstToolCallName(OpenAiChatResponse resp) {
        if (resp == null || resp.choices() == null || resp.choices().isEmpty()) return null;
        OpenAiMessage msg = resp.choices().get(0).message();
        if (msg == null) return null;
        // 1. Prefer native tool_calls (Ollama OpenAI-compat happy path)
        List<OpenAiToolCall> calls = msg.toolCalls();
        if (calls != null && !calls.isEmpty() && calls.get(0).function() != null) {
            String name = calls.get(0).function().name();
            if (name != null && !name.isBlank()) return name;
        }
        // 2. Fallback — Gemma 4 cesto emit-uje tool poziv kao tekst u
        //    [tool_code]name(args)[/tool_code] ili [name(args)] formatu.
        //    Ovde nam treba samo `name`, parsiramo ga sa kratkog regex-a.
        String content = msg.effectiveContent();
        if (content == null) return null;
        java.util.regex.Pattern[] patterns = {
                java.util.regex.Pattern.compile("\\[tool_code\\]\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\("),
                java.util.regex.Pattern.compile("\\[\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\("),
                java.util.regex.Pattern.compile("tool_call\\s*:\\s*([a-zA-Z_][a-zA-Z0-9_]*)")
        };
        for (var p : patterns) {
            var m = p.matcher(content);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * Build minimal OpenAI tool schemas — only name + 1-sentence description,
     * with empty parameter object. Wizard handles parameters interactively.
     *
     * <p>P0-B8 N3: EMPLOYEE-only intent-i ({@link #EMPLOYEE_ONLY_INTENTS}) se
     * NE nude CLIENT-u — klasifikator ih ne sme izabrati za klijenta, pa
     * klijent ne moze da pokrene employee akciju kroz agenta.</p>
     */
    private List<Map<String, Object>> buildMinimalTools(UserContext user) {
        boolean isEmployee = user != null && UserRole.isEmployee(user.userRole());
        List<Map<String, Object>> tools = new ArrayList<>(INTENT_DESCRIPTIONS.size());
        for (Map.Entry<String, String> e : INTENT_DESCRIPTIONS.entrySet()) {
            String name = e.getKey();
            // P0-B8 N3: sakrij employee-only intent-e od klijenta.
            if (!isEmployee && EMPLOYEE_ONLY_INTENTS.contains(name)) continue;
            // Filtruj na tool-ove koji imaju wizard template (sve ostale ce LLM
            // ipak ignorisati ali zasto da ih ucitavamo).
            if (!wizardRegistry.has(name)) continue;

            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", name);
            fn.put("description", e.getValue());
            fn.put("parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "additionalProperties", true
            ));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", fn);
            tools.add(tool);
        }
        return tools;
    }

    private static Map<String, String> buildIntentDescriptions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("create_payment",
                "Korisnik zeli da posalje novac drugoj osobi (placanje, uplata).");
        m.put("create_transfer_internal",
                "Korisnik zeli da prebaci novac izmedju svojih racuna iste valute.");
        m.put("create_transfer_fx",
                "Korisnik zeli FX konverziju izmedju svojih deviznih racuna.");
        m.put("add_payment_recipient",
                "Korisnik zeli da doda novog primaoca u listu primalaca.");
        m.put("create_buy_order",
                "Korisnik zeli da kupi hartije od vrednosti (akcije, futures).");
        m.put("create_sell_order",
                "Korisnik zeli da proda hartije iz svog portfolija.");
        m.put("cancel_order",
                "Korisnik zeli da otkaze postojeci nalog (order).");
        m.put("block_card",
                "Korisnik zeli da blokira karticu.");
        m.put("unblock_card",
                "Korisnik zeli da odblokira (otkljuca) karticu.");
        m.put("change_account_name",
                "Korisnik zeli da promeni ime/naziv racuna.");
        m.put("invest_in_fund",
                "Korisnik zeli da ulozi novac u investicioni fond.");
        m.put("withdraw_from_fund",
                "Korisnik zeli da povuce novac iz fonda.");
        m.put("accept_otc_offer",
                "Korisnik zeli da prihvati OTC ponudu.");
        m.put("decline_otc_offer",
                "Korisnik zeli da odbije OTC ponudu.");
        m.put("exercise_otc_contract",
                "Korisnik zeli da iskoristi OTC ugovor (exercise option).");
        return m;
    }

    /**
     * Helper za smoke testing — vraca koliko tool-ova klasifikator nudi modelu.
     */
    int registeredIntentCount() {
        return INTENT_DESCRIPTIONS.size();
    }
}
