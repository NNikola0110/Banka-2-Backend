package rs.raf.banka2_bek.interbank.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.interbank.model.InterbankMessage;
import rs.raf.banka2_bek.interbank.model.InterbankMessageDirection;
import rs.raf.banka2_bek.interbank.model.InterbankMessageStatus;
import rs.raf.banka2_bek.interbank.protocol.IdempotenceKey;
import rs.raf.banka2_bek.interbank.protocol.MessageType;
import rs.raf.banka2_bek.interbank.repository.InterbankMessageRepository;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class InterbankMessageService {

    private static final int MAX_RETRIES = 5;

    private final InterbankMessageRepository repository;
    private final BankRoutingService bankRoutingService;


    public Optional<String> findCachedResponse(IdempotenceKey key) {

        Optional<InterbankMessage> messageOpt = repository.findBySenderRoutingNumberAndLocallyGeneratedKey(
                key.routingNumber(),
                key.locallyGeneratedKey()
        );
        return messageOpt.map(InterbankMessage::getResponseBody);
    }

    @Transactional
    public void recordInboundResponse(IdempotenceKey key,
                                       MessageType messageType,
                                       String requestBody,
                                       Integer httpStatus,
                                       String responseBody,
                                      String transactionId) {

        repository.save(
                InterbankMessage.builder()
                        .transactionId(transactionId)
                        .direction(InterbankMessageDirection.INBOUND)
                        .status(InterbankMessageStatus.INBOUND)
                        .senderRoutingNumber(key.routingNumber())
                        .locallyGeneratedKey(key.locallyGeneratedKey())
                        .messageType(messageType)
                        .requestBody(requestBody)
                        .responseBody(responseBody)
                        .httpStatus(httpStatus)
                        .peerRoutingNumber(key.routingNumber())
                        .createdAt(LocalDateTime.now())
                        .lastAttemptAt(LocalDateTime.now())
                        .retryCount(0).build()
        );

    }

    public IdempotenceKey generateKey() {

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for(byte b : bytes) sb.append(String.format("%02x", b));
        return new IdempotenceKey(bankRoutingService.myRoutingNumber(), sb.toString());
    }

    /**
     * §2.11 — Logs an outbound message with status=PENDING so the retry scheduler can pick it up.
     * Must be called inside the same @Transactional as the business operation that triggered the send
     * (e.g. prepareLocal) so that the log entry and the reservation commit or rollback together.
     */
    @Transactional
    public InterbankMessage recordOutbound(IdempotenceKey key,
                                            int targetRouting,
                                            MessageType type,
                                            String body,
                                            String transactionId) {

        return repository.save(
                InterbankMessage.builder()
                    .direction(InterbankMessageDirection.OUTBOUND)
                    .status(InterbankMessageStatus.PENDING)
                    .senderRoutingNumber(key.routingNumber())
                    .locallyGeneratedKey(key.locallyGeneratedKey())
                    .messageType(type)
                    .requestBody(body)
                    .transactionId(transactionId)
                    .peerRoutingNumber(targetRouting)
                    .createdAt(LocalDateTime.now())
                    .lastAttemptAt(LocalDateTime.now())
                    .retryCount(0).build()
        );
    }

    /**
     * Returns true for 4xx status codes that are transient / retry-able.
     * 408 Request Timeout, 425 Too Early, 429 Too Many Requests — these are
     * temporary conditions where a retry may succeed.
     * All other 4xx codes indicate a permanent protocol or content error —
     * retrying will not help and the message should be marked FAILED_PERMANENT.
     */
    private static boolean isTransient4xx(int status) {
        return status == 408 || status == 425 || status == 429;
    }

    @Transactional
    public void markOutboundSent(IdempotenceKey key, Integer httpStatus, String responseBody) {
        InterbankMessage ibMessage =
                repository.findBySenderRoutingNumberAndLocallyGeneratedKey(
                        key.routingNumber(), key.locallyGeneratedKey()
                ).orElseThrow(() ->
                        new InterbankExceptions.InterbankProtocolException(
                                "No outbound message for key " + key + " was found."
                        )
                );

        if (httpStatus.equals(HttpStatus.OK.value()) || httpStatus.equals(HttpStatus.NO_CONTENT.value())) {
            ibMessage.setStatus(InterbankMessageStatus.SENT);
            ibMessage.setHttpStatus(httpStatus);
            ibMessage.setResponseBody(responseBody);
            ibMessage.setLastAttemptAt(LocalDateTime.now());
            repository.save(ibMessage);
        } else if (httpStatus.equals(HttpStatus.ACCEPTED.value())) {
            ibMessage.setRetryCount(ibMessage.getRetryCount() + 1);
            ibMessage.setLastAttemptAt(LocalDateTime.now());
            repository.save(ibMessage);
        } else if (httpStatus >= 400 && httpStatus < 500 && !isTransient4xx(httpStatus)) {
            // Permanent 4xx: the partner rejected our message due to a protocol/content error.
            // Retrying will not help — mark terminal so the retry scheduler skips it.
            ibMessage.setStatus(InterbankMessageStatus.FAILED_PERMANENT);
            ibMessage.setHttpStatus(httpStatus);
            ibMessage.setLastAttemptAt(LocalDateTime.now());
            ibMessage.setLastError("Partner returned permanent " + httpStatus + " — will not retry.");
            log.warn("Interbank outbound message FAILED_PERMANENT for key={}, HTTP {}", key, httpStatus);
            repository.save(ibMessage);
        } else {
            // 5xx or transient 4xx — keep existing retry behaviour
            markOutboundFailed(key, "Outbound message sending failed with HTTP " + httpStatus + ".");
        }

    }

    @Transactional
    public void markOutboundFailed(IdempotenceKey key, String errorMessage) {
        InterbankMessage ibMessage =
                repository.findBySenderRoutingNumberAndLocallyGeneratedKey(
                        key.routingNumber(), key.locallyGeneratedKey()
                ).orElseThrow(() ->
                        new InterbankExceptions.InterbankProtocolException(
                                "No outbound message for the key " + key + " was found."
                        )
                );
        ibMessage.setRetryCount(ibMessage.getRetryCount() + 1);
        ibMessage.setLastError(errorMessage);
        ibMessage.setLastAttemptAt(LocalDateTime.now());

        if (ibMessage.getRetryCount() >= MAX_RETRIES) {
            ibMessage.setStatus(InterbankMessageStatus.STUCK);

            log.error("Interbank outbound message STUCK after {} for key={}, error message: {} ", MAX_RETRIES, key, errorMessage);

        }

        repository.save(ibMessage);

    }

}
