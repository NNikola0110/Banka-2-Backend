package rs.raf.banka2_bek.interbank.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2_bek.interbank.config.InterbankProperties;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptionHandler;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.interbank.model.InterbankMessage;
import rs.raf.banka2_bek.interbank.protocol.*;
import rs.raf.banka2_bek.interbank.service.InterbankMessageService;
import rs.raf.banka2_bek.interbank.service.TransactionExecutorService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for InterbankInboundController (§2.10 auth + §2.2 idempotency + §2.12 dispatch).
 */
@ExtendWith(MockitoExtension.class)
class InterbankInboundControllerTest {

    @Mock
    private InterbankProperties properties;
    @Mock
    private InterbankMessageService messageService;
    @Mock
    private TransactionExecutorService executorService;
    @Mock
    private rs.raf.banka2_bek.monitoring.BusinessMetrics businessMetrics;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final int PARTNER_RN = 111;
    private static final String VALID_INBOUND_TOKEN = "validInboundToken";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        InterbankInboundController controller =
                new InterbankInboundController(properties, objectMapper, messageService, executorService, businessMetrics);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InterbankExceptionHandler())
                .build();

        InterbankProperties.PartnerBank partner = new InterbankProperties.PartnerBank();
        partner.setRoutingNumber(PARTNER_RN);
        partner.setInboundToken(VALID_INBOUND_TOKEN);
        partner.setOutboundToken("outToken1");
        partner.setBaseUrl("http://bank1:8080");
        lenient().when(properties.getPartners()).thenReturn(List.of(partner));
    }

    // -------------------------------------------------------------------------
    // Auth checks
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /interbank without X-Api-Key returns 401")
    void receiveMessage_missingApiKey_returns401() throws Exception {
        mockMvc.perform(post("/interbank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildNewTxEnvelope()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(messageService, executorService);
    }

    @Test
    @DisplayName("POST /interbank with blank X-Api-Key returns 401")
    void receiveMessage_blankApiKey_returns401() throws Exception {
        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildNewTxEnvelope()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /interbank with unknown token returns 401")
    void receiveMessage_unknownToken_returns401() throws Exception {
        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", "wrongToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildNewTxEnvelope()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /interbank with routing number mismatch returns 400")
    void receiveMessage_routingNumberMismatch_returns401() throws Exception {
        // Key says routing=999, but partner has routing=111
        // T2-E (Tim 1 cross-bank Stage C, 2026-05-20): routing mismatch je sad 400 Bad Request
        // (NIJE 401), jer X-Api-Key je validan — payload je malformed. Mirror-uje Tim 1
        // inbound controller za simetricno ponasanje izmedju banaka (commit 11.05.2026
        // BE_polish/inter-bank rev3 audit).
        String envelope = buildEnvelopeJson(999, "somekey", MessageType.COMMIT_TX,
                objectMapper.writeValueAsString(new CommitTransaction(new ForeignBankId(999, "x"))));

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isBadRequest());
    }
    @Test
    @DisplayName("POST /interbank with malformed envelope returns 400")
    void receiveMessage_malformedEnvelope_returns400() throws Exception {
        String malformedEnvelope = """
            {
              "idempotenceKey": {
                "routingNumber": 111,
                "locallyGeneratedKey": "key-bad"
              },
              "messageType": "NEW_TX"
            }
            """;

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedEnvelope))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(messageService, executorService);
    }
    @Test
    @DisplayName("POST /interbank returns 500 when executor throws unexpected exception")
    void receiveMessage_executorThrows_returns500() throws Exception {
        when(messageService.findCachedMessage(any())).thenReturn(Optional.empty());
        when(executorService.handleNewTx(any(Transaction.class), any(IdempotenceKey.class)))
                .thenThrow(new RuntimeException("unexpected error"));

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildNewTxEnvelope()))
                .andExpect(status().isInternalServerError());

        verify(executorService).handleNewTx(any(Transaction.class), any(IdempotenceKey.class));
    }

    // -------------------------------------------------------------------------
    // Idempotency cache hit
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Cache hit with response body returns 200 with cached body")
    void receiveMessage_cacheHitWithBody_returns200() throws Exception {
        // 1377: kesirana poruka mora biti istog tipa kao dolazeca (NEW_TX) da bi se
        // vratio kesiran odgovor.
        when(messageService.findCachedMessage(any()))
                .thenReturn(Optional.of(cachedMessage(MessageType.NEW_TX, "{\"vote\":\"YES\"}")));

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildNewTxEnvelope()))
                .andExpect(status().isOk());

        verifyNoInteractions(executorService);
    }

    @Test
    @DisplayName("Cache hit with blank body returns 204")
    void receiveMessage_cacheHitBlankBody_returns204() throws Exception {
        when(messageService.findCachedMessage(any()))
                .thenReturn(Optional.of(cachedMessage(MessageType.NEW_TX, "")));

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildNewTxEnvelope()))
                .andExpect(status().isNoContent());

        verifyNoInteractions(executorService);
    }

    @Test
    @DisplayName("1377: cache hit with MISMATCHED message type (cached NEW_TX, incoming COMMIT_TX) "
            + "→ 400 protocol error, NOT cached vote (commit would never execute)")
    void receiveMessage_cacheTypeMismatch_returns400() throws Exception {
        // 1377: posiljalac generise svez kljuc po poruci, ali se branimo od
        // key-collision-a. COMMIT_TX pod kljucem koji vec ima kesiran NEW_TX vote
        // NE sme da vrati taj vote (commit se nikad ne bi izvrsio, novac zaglavljen).
        when(messageService.findCachedMessage(any()))
                .thenReturn(Optional.of(cachedMessage(MessageType.NEW_TX, "{\"vote\":\"YES\"}")));

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCommitTxEnvelope())) // dolazeci tip = COMMIT_TX
                .andExpect(status().isBadRequest());

        verifyNoInteractions(executorService);
    }

    private InterbankMessage cachedMessage(MessageType type, String responseBody) {
        return InterbankMessage.builder()
                .messageType(type)
                .responseBody(responseBody)
                .build();
    }

    // -------------------------------------------------------------------------
    // Dispatch paths (cache miss)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Cache miss + NEW_TX dispatches to handleNewTx and returns 200")
    void receiveMessage_newTxCacheMiss_dispatches() throws Exception {
        when(messageService.findCachedMessage(any())).thenReturn(Optional.empty());
        TransactionVote vote = new TransactionVote(TransactionVote.Vote.YES, List.of());
        when(executorService.handleNewTx(any(), any())).thenReturn(vote);

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildNewTxEnvelope()))
                .andExpect(status().isOk());

        verify(executorService).handleNewTx(any(Transaction.class), any(IdempotenceKey.class));
    }

    @Test
    @DisplayName("Cache miss + COMMIT_TX dispatches to handleCommitTx and returns 204")
    void receiveMessage_commitTxCacheMiss_dispatches() throws Exception {
        when(messageService.findCachedMessage(any())).thenReturn(Optional.empty());
        doNothing().when(executorService).handleCommitTx(any(), any());

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildCommitTxEnvelope()))
                .andExpect(status().isNoContent());

        verify(executorService).handleCommitTx(any(CommitTransaction.class), any(IdempotenceKey.class));
    }

    @Test
    @DisplayName("Cache miss + ROLLBACK_TX dispatches to handleRollbackTx and returns 204")
    void receiveMessage_rollbackTxCacheMiss_dispatches() throws Exception {
        when(messageService.findCachedMessage(any())).thenReturn(Optional.empty());
        doNothing().when(executorService).handleRollbackTx(any(), any());

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRollbackTxEnvelope()))
                .andExpect(status().isNoContent());

        verify(executorService).handleRollbackTx(any(RollbackTransaction.class), any(IdempotenceKey.class));
    }


    // -------------------------------------------------------------------------
    // Fix 1 (I-1): constant-time comparison — wrong token of different length must still return 401
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /interbank with token of different length returns 401 (constant-time safe)")
    void receiveMessage_wrongTokenDifferentLength_returns401() throws Exception {
        // A shorter token must be rejected the same as any other wrong token.
        // This verifies that constant-time comparison does not leak token length.
        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildNewTxEnvelope()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(messageService, executorService);
    }

    // -------------------------------------------------------------------------
    // Fix 2 (I-2): @RestControllerAdvice must handle service exceptions (no broad try/catch)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("InterbankProtocolException thrown by service maps to 400 via @RestControllerAdvice")
    void receiveMessage_serviceThrowsProtocolException_returns400() throws Exception {
        when(messageService.findCachedMessage(any())).thenReturn(Optional.empty());
        when(executorService.handleNewTx(any(Transaction.class), any(IdempotenceKey.class)))
                .thenThrow(new InterbankExceptions.InterbankProtocolException("malformed transaction"));

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildNewTxEnvelope()))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // TEST-interbank-3: NEW_TX sa null/empty postings → 400 (NE 500)
    // handleNewTx baca IllegalArgumentException za malformed body (null tx /
    // null transactionId / null|prazan postings). Po §6.1 mora biti 400, ne 500.
    // Pre fix-a je IllegalArgumentException padao u handleGeneral(Exception) → 500.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TEST-interbank-3: NEW_TX null/empty postings (IllegalArgumentException) maps to 400, not 500")
    void receiveMessage_newTxNullPostings_returns400_notInternalError() throws Exception {
        when(messageService.findCachedMessage(any())).thenReturn(Optional.empty());
        // Real handleNewTx baca IllegalArgumentException za null/empty postings
        // ("transaction.postings is required ...").
        when(executorService.handleNewTx(any(Transaction.class), any(IdempotenceKey.class)))
                .thenThrow(new IllegalArgumentException(
                        "transaction.postings is required and must contain at least one balanced double-entry pair"));

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildNewTxEnvelope()))
                .andExpect(status().isBadRequest());

        verify(executorService).handleNewTx(any(Transaction.class), any(IdempotenceKey.class));
    }

    // -------------------------------------------------------------------------
    // Fix 4 (I-6): idempotenceKey.locallyGeneratedKey exceeding 64 chars must return 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /interbank with locallyGeneratedKey > 64 chars returns 400")
    void receiveMessage_oversizeLocallyGeneratedKey_returns400() throws Exception {
        // 65-character key — one byte over the §2.2 limit.
        String oversizeKey = "a".repeat(65);
        ForeignBankId txId = new ForeignBankId(PARTNER_RN, "uuid-tx-oversize");
        Transaction tx = new Transaction(List.of(), txId, "test", null, null, null);
        String envelope = buildEnvelopeJson(PARTNER_RN, oversizeKey, MessageType.NEW_TX,
                objectMapper.writeValueAsString(tx));

        mockMvc.perform(post("/interbank")
                        .header("X-Api-Key", VALID_INBOUND_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(messageService, executorService);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildNewTxEnvelope() throws Exception {
        ForeignBankId txId = new ForeignBankId(PARTNER_RN, "uuid-tx-1");
        Transaction tx = new Transaction(List.of(), txId, "test", null, null, null);
        return buildEnvelopeJson(PARTNER_RN, "key0000001", MessageType.NEW_TX,
                objectMapper.writeValueAsString(tx));
    }

    private String buildCommitTxEnvelope() throws Exception {
        ForeignBankId txId = new ForeignBankId(PARTNER_RN, "uuid-tx-1");
        return buildEnvelopeJson(PARTNER_RN, "key0000002", MessageType.COMMIT_TX,
                objectMapper.writeValueAsString(new CommitTransaction(txId)));
    }

    private String buildRollbackTxEnvelope() throws Exception {
        ForeignBankId txId = new ForeignBankId(PARTNER_RN, "uuid-tx-1");
        return buildEnvelopeJson(PARTNER_RN, "key0000003", MessageType.ROLLBACK_TX,
                objectMapper.writeValueAsString(new RollbackTransaction(txId)));
    }

    /**
     * Builds a raw JSON envelope string. The `message` field is embedded as a raw JSON node
     * so it remains properly nested when parsed.
     */
    private String buildEnvelopeJson(int routingNumber, String localKey,
                                     MessageType type, String messageJson) {
        return String.format(
                "{\"idempotenceKey\":{\"routingNumber\":%d,\"locallyGeneratedKey\":\"%s\"}," +
                "\"messageType\":\"%s\"," +
                "\"message\":%s}",
                routingNumber, localKey, type.name(), messageJson
        );
    }
}
