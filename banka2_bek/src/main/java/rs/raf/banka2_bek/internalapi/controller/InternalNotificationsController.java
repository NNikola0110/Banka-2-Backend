package rs.raf.banka2_bek.internalapi.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2.contracts.internal.InternalErrorDto;
import rs.raf.banka2.contracts.internal.InternalNotificationRequest;
import rs.raf.banka2_bek.internalapi.service.InternalIdempotencyService;
import rs.raf.banka2_bek.notification.service.NotificationService;

/**
 * Cross-DB ulaz za in-app notifikacije iz trading-service-a (i drugih
 * mikroservisa) — banka-core je vlasnik {@code notifications} tabele.
 *
 * <p>{@code InternalAuthFilter} stiti {@code /internal/**} rute deljenim
 * {@code X-Internal-Key} kljucem; nije potrebna dodatna autentifikacija.
 *
 * <p><b>P2-notif-reliability-2 (R1 383): ATOMICAN dedup.</b> {@code idempotencyKey}
 * u telu (UUID) sprecava dupli upis pri paralelnim retry-ima. Umesto starog
 * "check-then-act" (koji je pri paralelnim dostavama dozvoljavao da OBE prodju
 * read-proveru i obe upisu notifikaciju), kljuc se ATOMICNO rezervise PRE
 * perzistencije notifikacije preko DB-level {@code UNIQUE(idempotency_key)}
 * ({@link InternalIdempotencyService#tryReserve}). Tacno jedan paralelni
 * pozivalac dobija rezervaciju i upisuje notifikaciju; drugi(i) dobijaju
 * 200 OK bez upisa. Ako kljuc nije prosledjen, dedup se preskace.
 */
@Slf4j
@RestController
@RequestMapping("/internal/notifications")
public class InternalNotificationsController {

    private static final String ENDPOINT_PATH = "/internal/notifications";

    private final NotificationService notificationService;
    private final InternalIdempotencyService idempotencyService;

    public InternalNotificationsController(NotificationService notificationService,
                                           InternalIdempotencyService idempotencyService) {
        this.notificationService = notificationService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> postNotification(@RequestBody InternalNotificationRequest req) {
        if (req == null || req.recipientId() == null || req.recipientType() == null
                || req.recipientType().isBlank()) {
            return ResponseEntity.badRequest().body(
                    new InternalErrorDto("BAD_REQUEST",
                            "recipientId i recipientType su obavezni"));
        }

        String idempotencyKey = req.idempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            // R1 383: atomicno rezervisi kljuc PRE perzistencije. reserveOrThrow je
            // REQUIRES_NEW (pozvan kroz proxy ovde — NIJE self-invocation, pa nova
            // tx zaista vazi). Drugi paralelni/sekvencijalni pozivalac sa istim
            // kljucem dobija unique violation → 200 OK, bez duplog reda. Hvatanje
            // je u ovoj (cistoj) outer tx — rollback je pogodio SAMO rezervacionu tx.
            try {
                idempotencyService.reserveOrThrow(idempotencyKey, ENDPOINT_PATH,
                        HttpStatus.CREATED.value(), "");
            } catch (DataIntegrityViolationException | ConcurrencyFailureException dup) {
                return ResponseEntity.ok().build();
            }
        }

        notificationService.createInternalNotification(req);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
