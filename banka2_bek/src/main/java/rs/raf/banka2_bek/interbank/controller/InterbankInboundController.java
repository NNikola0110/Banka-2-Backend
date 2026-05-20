package rs.raf.banka2_bek.interbank.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rs.raf.banka2_bek.interbank.config.InterbankProperties;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.interbank.protocol.*;
import rs.raf.banka2_bek.interbank.service.InterbankMessageService;
import rs.raf.banka2_bek.interbank.service.TransactionExecutorService;
import rs.raf.banka2_bek.interbank.util.SecurityUtils;
import java.util.Optional;


@RestController
@RequestMapping("/interbank")
@RequiredArgsConstructor
public class InterbankInboundController {


    private final InterbankProperties properties;
    private final ObjectMapper objectMapper;
    private final InterbankMessageService interbankMessageService;
    private final TransactionExecutorService transactionExecutorService;

    /**
     * Glavni endpoint po §2.11. Body je Message<Type> envelope sa
     * idempotenceKey + messageType + message (Transaction / CommitTransaction
     * / RollbackTransaction).
     * <p>
     * Vraca:
     * - 200 OK + TransactionVote za NEW_TX
     * - 204 No Content za COMMIT_TX, ROLLBACK_TX
     * - 202 Accepted ako poruka nije jos obradena (npr. backoff)
     */
    /**
     * Max length for idempotence key per spec §2.2 — "max 64 bytes".
     * Validated manually after objectMapper.convertValue (Jakarta @Valid
     * does not fire on converter-deserialized objects).
     */
    private static final int MAX_KEY_LENGTH = 64;

    @PostMapping
    public ResponseEntity<Object> receiveMessage(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody String rawBody) throws Exception {

        // §2.10 — X-Api-Key header is mandatory. Return 401 immediately without
        // revealing any information about which keys are valid.
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(401).build();
        }

        // Constant-time comparison to prevent timing side-channel attacks (Fix I-1).
        Optional<InterbankProperties.PartnerBank> partnerBankOpt = properties.getPartners()
                .stream()
                .filter(partner -> SecurityUtils.constantTimeEquals(partner.getInboundToken(), apiKey))
                .findFirst();

        if (partnerBankOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }

        InterbankProperties.PartnerBank partnerBank = partnerBankOpt.get();

        // Parse envelope — throws JsonProcessingException (→ 400 via advice) on malformed JSON.
        JsonNode envelope = objectMapper.readTree(rawBody);

        if (!envelope.hasNonNull("idempotenceKey")
                || !envelope.hasNonNull("messageType")
                || !envelope.hasNonNull("message")) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Missing required envelope fields: idempotenceKey, messageType, message.");
        }

        IdempotenceKey idempotenceKey =
                objectMapper.convertValue(envelope.get("idempotenceKey"), IdempotenceKey.class);
        MessageType messageType =
                objectMapper.convertValue(envelope.get("messageType"), MessageType.class);
        JsonNode messageNode = envelope.get("message");

        // §2.2 — locallyGeneratedKey must be at most 64 bytes (Fix I-6).
        if (idempotenceKey.locallyGeneratedKey() == null
                || idempotenceKey.locallyGeneratedKey().length() > MAX_KEY_LENGTH) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "idempotenceKey.locallyGeneratedKey exceeds maximum length of " + MAX_KEY_LENGTH + " characters.");
        }

        if (idempotenceKey.routingNumber() != partnerBank.getRoutingNumber()) {
            // Routing mismatch — partner pokusava da impersonira drugu banku.
            // Spec: ovo NIJE autentifikacijski failure (X-Api-Key je validan),
            // vec malformed payload — vraca se 400 Bad Request (mirror-uje
            // Tim 1 inbound controller za simetricno ponasanje izmedju banaka).
            throw new InterbankExceptions.InterbankProtocolException(
                    "idempotenceKey.routingNumber mismatches X-Api-Key sender");
        }

        Optional<String> cachedResponseOpt = interbankMessageService.findCachedResponse(idempotenceKey);

        if (cachedResponseOpt.isPresent()) {
            String cachedResponse = cachedResponseOpt.get();

            if (cachedResponse == null || cachedResponse.isBlank()) {
                return ResponseEntity.noContent().build();
            }

            // Idempotency cache replay (live test fix, 2026-05-20): parse u Object umesto JsonNode,
            // jer Spring serializuje JsonNode preko bean-getters (isArray/isBigDecimal/...),
            // sto rezultuje introspekcijom umesto pravog body-ja. Object (Map/List/String/Number)
            // se serijalizuje korektno kao originalni JSON.
            return ResponseEntity.ok(objectMapper.readValue(cachedResponse, Object.class));
        }

        return dispatchByMessageType(messageType, idempotenceKey, messageNode);
    }

    private ResponseEntity<Object> dispatchByMessageType(
            MessageType messageType,
            IdempotenceKey idempotenceKey,
            JsonNode message
    ) {
        return switch (messageType) {
            case NEW_TX -> {
                Transaction tx = objectMapper.convertValue(message, Transaction.class);
                TransactionVote vote = transactionExecutorService.handleNewTx(tx, idempotenceKey);
                yield ResponseEntity.ok(vote);
            }
            case COMMIT_TX -> {
                CommitTransaction body = objectMapper.convertValue(message, CommitTransaction.class);
                transactionExecutorService.handleCommitTx(body, idempotenceKey);
                yield ResponseEntity.noContent().build();
            }
            case ROLLBACK_TX -> {
                RollbackTransaction body = objectMapper.convertValue(message, RollbackTransaction.class);
                transactionExecutorService.handleRollbackTx(body, idempotenceKey);
                yield ResponseEntity.noContent().build();
            }


        };
    }
}
