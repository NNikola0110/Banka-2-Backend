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
import rs.raf.banka2_bek.monitoring.BusinessMetrics;

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
    private final BusinessMetrics businessMetrics;


    public Optional<String> findCachedResponse(IdempotenceKey key) {

        Optional<InterbankMessage> messageOpt = repository.findBySenderRoutingNumberAndLocallyGeneratedKey(
                key.routingNumber(),
                key.locallyGeneratedKey()
        );
        return messageOpt.map(InterbankMessage::getResponseBody);
    }

    /**
     * 1377 — type-aware idempotency cache lookup. §2.2 idempotency kljuc je
     * (senderRoutingNumber, locallyGeneratedKey) i posiljalac generise SVEZ kljuc po
     * poruci (vidi {@code generateKey} — random 64-hex), pa svaki messageType ima
     * distinktan kljuc. Ako ipak stigne poruka cija se vrsta NE poklapa sa vec
     * kesiranom vrstom pod istim kljucem (key-collision: npr. COMMIT_TX pod kljucem
     * koji vec ima kesiran NEW_TX vote), vracanje sirovog kesiranog body-ja bi bilo
     * pogresno — COMMIT_TX bi dobio kesiran TransactionVote umesto da commit-uje, i
     * commit se NIKAD ne bi izvrsio (novac zaglavljen u rezervaciji).
     *
     * <p>Vraca kesirani {@link InterbankMessage} (ako postoji) tako da pozivalac
     * (inbound controller) moze da uporedi {@code getMessageType()} sa dolazecom
     * vrstom i da odbije neuskladjenost kao protocol violation, umesto da slepo
     * vrati kesiran odgovor.
     */
    public Optional<InterbankMessage> findCachedMessage(IdempotenceKey key) {
        return repository.findBySenderRoutingNumberAndLocallyGeneratedKey(
                key.routingNumber(),
                key.locallyGeneratedKey());
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

    /**
     * 1977 — outbound poruka je u stanju koje NE sme da se menja zakasnelim
     * markOutboundSent/markOutboundFailed pozivom:
     * <ul>
     *   <li>{@code SENT} — partner potvrdio (200/204), terminalno.</li>
     *   <li>{@code FAILED_PERMANENT} — permanentni 4xx (NEW_TX), terminalno.</li>
     *   <li>{@code SENT_WAITING_ASYNC} — 202; partner obradjuje async, retry-resolvovano
     *       (ne smemo regresirati na PENDING ni SENT).</li>
     * </ul>
     * {@code PENDING} i {@code STUCK} se i dalje smeju menjati (PENDING je aktivan
     * retry, STUCK eskalacija moze biti dodatno logovana po potrebi).
     */
    private static boolean isTerminalSentState(InterbankMessageStatus status) {
        return status == InterbankMessageStatus.SENT
                || status == InterbankMessageStatus.FAILED_PERMANENT
                || status == InterbankMessageStatus.SENT_WAITING_ASYNC;
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

        // 1977 — terminal-state guard. SENT i FAILED_PERMANENT su TERMINALNI;
        // SENT_WAITING_ASYNC (202) je vec resolvovan kao "ne retry-uj". Bez ovog
        // guard-a, zakasneli/duplirani markOutboundSent poziv (npr. iz race-a
        // izmedju sendPhase2Network i retry scheduler-a) bi mogao da pregazi vec-SENT
        // poruku — npr. SENT_WAITING_ASYNC→SENT regres, ili da resetuje response body.
        // Idempotentno: za vec-terminalni red, no-op.
        if (isTerminalSentState(ibMessage.getStatus())) {
            log.debug("markOutboundSent no-op: message key={} already in terminal/resolved status {}",
                    key, ibMessage.getStatus());
            return;
        }

        if (httpStatus.equals(HttpStatus.OK.value()) || httpStatus.equals(HttpStatus.NO_CONTENT.value())) {
            ibMessage.setStatus(InterbankMessageStatus.SENT);
            ibMessage.setHttpStatus(httpStatus);
            ibMessage.setResponseBody(responseBody);
            ibMessage.setLastAttemptAt(LocalDateTime.now());
            repository.save(ibMessage);
            // R7 observability: partner potvrdio prijem (banka2_interbank_outbound_total{status="sent"}).
            businessMetrics.recordInterbankOutboundSent();
        } else if (httpStatus.equals(HttpStatus.ACCEPTED.value())) {
            // BE-INT-02 fix: 202 Accepted znaci da je partner prihvatio poruku i
            // obradjuje je asinhrono. Pre fix-a status je ostajao PENDING, sto je
            // gurnulo poruku u retry ciklus → MAX_RETRY=5 → STUCK (lazno alarm).
            // Sad markiramo SENT_WAITING_ASYNC — partner ce nas obavestiti kasnije
            // sopstvenim COMMIT_TX/ROLLBACK_TX porukom; retry scheduler ovo preskace.
            ibMessage.setStatus(InterbankMessageStatus.SENT_WAITING_ASYNC);
            ibMessage.setHttpStatus(httpStatus);
            ibMessage.setResponseBody(responseBody);
            ibMessage.setLastAttemptAt(LocalDateTime.now());
            repository.save(ibMessage);
        } else if (httpStatus >= 400 && httpStatus < 500 && !isTransient4xx(httpStatus)
                && ibMessage.getMessageType() != MessageType.COMMIT_TX
                && ibMessage.getMessageType() != MessageType.ROLLBACK_TX) {
            // Permanent 4xx: the partner rejected our message due to a protocol/content error.
            // Retrying will not help — mark terminal so the retry scheduler skips it.
            //
            // N2 exception: phase-2 (COMMIT_TX / ROLLBACK_TX) is NEVER abandoned on a
            // permanent 4xx. Per §2.9 the outcome must be retransmitted until the
            // recipient acknowledges with 200/204 — a 4xx here (e.g. partner has not
            // yet recorded the NEW_TX, or a transient validation state) must not strand
            // a recipient that already voted YES (PREPARED). Phase-2 falls through to
            // markOutboundFailed → stays PENDING → keeps retrying.
            ibMessage.setStatus(InterbankMessageStatus.FAILED_PERMANENT);
            ibMessage.setHttpStatus(httpStatus);
            ibMessage.setLastAttemptAt(LocalDateTime.now());
            ibMessage.setLastError("Partner returned permanent " + httpStatus + " — will not retry.");
            log.warn("Interbank outbound message FAILED_PERMANENT for key={}, HTTP {}", key, httpStatus);
            repository.save(ibMessage);
            // R7 observability: trajni partner-reject (banka2_interbank_outbound_total{status="failed"}, OtcInterbankFailures alert).
            businessMetrics.recordInterbankOutboundFailed();
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

        // 1977 — terminal-state guard. Poruka koja je vec SENT (partner potvrdio
        // 200/204) ili FAILED_PERMANENT ne sme da regresira na PENDING zbog
        // zakasnelog/dupliranog failed signala (race izmedju glavnog send-a i retry
        // scheduler-a) — to bi je gurnulo u retransmisiju i lazni STUCK alarm
        // (NEW_TX) odnosno duplu dostavu (phase-2). SENT_WAITING_ASYNC (202) je
        // takodje terminalno-resolvovan za retry svrhe. No-op za vec-resolvovan red.
        if (isTerminalSentState(ibMessage.getStatus())) {
            log.debug("markOutboundFailed no-op: message key={} already in terminal/resolved status {}",
                    key, ibMessage.getStatus());
            return;
        }

        // R7 observability: neuspela outbound dostava (komunikacioni/5xx/transient).
        // banka2_interbank_outbound_total{status="failed"} → OtcInterbankFailures alert.
        businessMetrics.recordInterbankOutboundFailed();

        ibMessage.setRetryCount(ibMessage.getRetryCount() + 1);
        ibMessage.setLastError(errorMessage);
        ibMessage.setLastAttemptAt(LocalDateTime.now());

        // N2 (§2.9 "retransmit until acknowledged" + §2.8.7 presumed-abort):
        // Faza-2 poruke (COMMIT_TX / ROLLBACK_TX) MORAJU se retransmitovati
        // NEOGRANICENO dok recipient ne potvrdi (200/204). Posle YES vote-a
        // recipient je u PREPARED i ceka ishod od koordinatora; ako COMMIT_TX/
        // ROLLBACK_TX izadje iz retry pool-a (STUCK), recipient nikad ne sazna
        // ishod → rezervacija zakljucana / novac unisten. Zato STUCK eskalacija
        // vazi SAMO za fazu-1 (NEW_TX) — gde koordinator jos nije doneo odluku i
        // gde je odustajanje (presumed-abort koordinatora) bezbedno.
        boolean isPhaseTwo = ibMessage.getMessageType() == MessageType.COMMIT_TX
                || ibMessage.getMessageType() == MessageType.ROLLBACK_TX;

        if (!isPhaseTwo && ibMessage.getRetryCount() >= MAX_RETRIES) {
            ibMessage.setStatus(InterbankMessageStatus.STUCK);

            log.error("Interbank outbound message STUCK after {} for key={}, error message: {} ", MAX_RETRIES, key, errorMessage);

        } else if (isPhaseTwo && ibMessage.getRetryCount() >= MAX_RETRIES) {
            // Phase-2 ostaje PENDING (retry-uje se i dalje); logujemo da operativci
            // znaju da partner dugo ne potvrdjuje, ali NE napustamo retransmisiju.
            log.warn("Interbank phase-2 {} for key={} still unacknowledged after {} attempts — "
                            + "continuing unbounded retransmission per §2.9 (NOT escalating to STUCK).",
                    ibMessage.getMessageType(), key, ibMessage.getRetryCount());
        }

        repository.save(ibMessage);

    }

}
