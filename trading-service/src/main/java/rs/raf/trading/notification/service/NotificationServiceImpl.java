package rs.raf.trading.notification.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import rs.raf.banka2.contracts.NotificationKind;
import rs.raf.banka2.contracts.NotificationMessage;
import rs.raf.banka2.contracts.NotificationRabbit;
import rs.raf.banka2.contracts.internal.InternalNotificationRequest;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.notification.model.NotificationType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * [B4 — port iz banka2_bek] Trgovinski {@link NotificationService}.
 *
 * <p>Dva nezavisna kanala notifikacija:
 * <ol>
 *   <li><b>Email</b> — RabbitMQ {@code IN_APP_GENERIC} poruka ka
 *       {@code notification-service}. Aktivno samo kad
 *       {@code NotificationType.sendsEmail() == true}. Email primaoca razresava
 *       preko {@link BankaCoreClient#getUserById(String, Long)}; ako razresenje
 *       padne (banka-core nedostupan / nepoznat id), email se preskace.</li>
 *   <li><b>In-app</b> — async POST {@code /internal/notifications} ka banka-core
 *       da bi se notifikacija pojavila u FE NotificationBell-u i u
 *       {@code notifications} tabeli. Aktivno samo kad
 *       {@code NotificationType.sendsInApp() == true}. Async kroz
 *       {@link CompletableFuture#runAsync} — ne blokira trgovinski poziv.</li>
 * </ol>
 *
 * <p>Best-effort: bilo koja greska (broker pad, banka-core 5xx) se loguje na WARN
 * i NE rolluje back trgovinsku transakciju.
 */
@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private final RabbitTemplate rabbitTemplate;
    private final BankaCoreClient bankaCoreClient;

    /**
     * <b>P2-notif-reliability-2 (R3 1593): dedicated bounded executor.</b> Stari
     * kod je radio {@code CompletableFuture.runAsync(...)} na common ForkJoinPool
     * BEZ timeout-a/{@code exceptionally}: svaka greska u in-app POST-u (banka-core
     * 5xx, timeout) bi se progutala (ForkJoinPool guta exception iz async task-a
     * koji se nikad ne join-uje), i — gore — dugi REST pozivi bi gladovali
     * deljeni ForkJoinPool koji koriste paralelni stream-ovi/scheduler-i. Sada se
     * koristi nas mali bounded pool sa eksplicitnim {@code exceptionally} log-om i
     * caller-runs politikom (kad je red pun, in-app POST se izvrsi sinhrono umesto
     * da se tiho odbaci → notifikacija se ne gubi).
     */
    private final ExecutorService inAppExecutor;

    /**
     * <b>C-notif-email blocker #2 (03.06): email-channel idempotency.</b> Vremenski
     * ograniceni dedup za EMAIL kanal, keyed IDENTICNO kao banka-core in-app kljuc
     * ({@link #buildIdempotencyKey}). In-app kanal je dedup-ovan banka-core
     * UNIQUE(idempotency_key) constraint-om, ali email kanal (RabbitMQ publish) nije
     * imao NIKAKAV dedup — pa kad dva puta okinu isti logicki event za isti resurs
     * (npr. {@code ORDER_CANCELLED} za isti orderId iz OBE settlement-expiry decline
     * putanje: {@code OrderCleanupScheduler} @ 01:00 + {@code SingleOrderExecutor} u
     * istom prozoru), klijent bi dobio DVA email-a. Ovaj cache suprimuje drugi email
     * istog kljuca unutar {@link #EMAIL_DEDUP_WINDOW_MS}.
     *
     * <p>Bounded (best-effort): kad mapa predje {@link #EMAIL_DEDUP_MAX_ENTRIES},
     * istekli unosi se cisti lenjo (pri svakom upisu) pa se prune-uje; ovo NIJE
     * trajni store (process-local) — pravi cross-instance dedup je in-app kljuc.
     * Email je best-effort kanal pa je process-local prozor adekvatan za realan
     * scenario (oba decline puta rade u istoj instanci, isti 01:00 prozor).
     */
    private final Map<String, Long> emailDedupCache = new ConcurrentHashMap<>();

    /**
     * Prozor unutar kog se duplikat email-a istog idempotency kljuca suprimuje
     * (10 min). Dovoljno sirok da pokrije 01:00 settlement-cleanup vs executor
     * preklapanje; dovoljno uzak da legitiman ponovljen event istog resursa
     * mnogo kasnije (npr. retry posle sati) ipak prodje.
     */
    private static final long EMAIL_DEDUP_WINDOW_MS = 10 * 60 * 1000L;

    /** Cap velicine dedup mape pre lenjog prune-a (zastita od neogranicenog rasta). */
    private static final int EMAIL_DEDUP_MAX_ENTRIES = 10_000;

    public NotificationServiceImpl(RabbitTemplate rabbitTemplate, BankaCoreClient bankaCoreClient) {
        this.rabbitTemplate = rabbitTemplate;
        this.bankaCoreClient = bankaCoreClient;
        this.inAppExecutor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                runnable -> {
                    Thread t = new Thread(runnable, "trading-inapp-notify");
                    t.setDaemon(true);
                    return t;
                },
                // Red pun → izvrsi u pozivnoj niti (ne odbacuj notifikaciju).
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    void shutdownExecutor() {
        inAppExecutor.shutdown();
        try {
            if (!inAppExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                inAppExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            inAppExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void notify(Long recipientId,
                       String recipientType,
                       NotificationType notificationType,
                       String title,
                       String body,
                       String referenceType,
                       Long referenceId) {
        if (recipientId == null || recipientType == null) {
            log.warn("Notification preskocena: recipientId/recipientType=null (type={}, title={})",
                    notificationType, title);
            return;
        }

        // Email kanal — samo ako tip eksplicitno trazi email.
        // C-notif-email blocker #2: dedup keyed identicno kao in-app kljuc. Duplikat
        // email-a istog logickog eventa (isti recipient/type/reference) unutar prozora
        // se preskace (npr. ORDER_CANCELLED iz oba settlement-expiry decline puta).
        if (notificationType != null && notificationType.isSendsEmail()) {
            String emailKey = buildIdempotencyKey(recipientId, recipientType, notificationType,
                    referenceType, referenceId, title, body);
            if (shouldSendEmail(emailKey)) {
                publishEmail(recipientId, recipientType, notificationType, title, body);
            } else {
                log.debug("Email duplikat suprimovan (key={}, type={}, recipient={} {})",
                        emailKey, notificationType, recipientType, recipientId);
            }
        }

        // In-app kanal — samo ako tip eksplicitno trazi in-app perzistenciju.
        // Async na dedicated bounded executoru (R3 1593) — ne blokira pozivnu
        // trgovinsku transakciju; bankaCoreClient.postNotification je vec
        // best-effort (sam swallow-uje sve greske), a {@code exceptionally}
        // dodatno loguje svaku greska koja bi se inace progutala u ForkJoinPool-u.
        if (notificationType != null && notificationType.isSendsInApp()) {
            InternalNotificationRequest internalReq = new InternalNotificationRequest(
                    recipientId,
                    recipientType,
                    notificationType.name(),
                    title,
                    body,
                    referenceType,
                    referenceId,
                    buildIdempotencyKey(recipientId, recipientType, notificationType,
                            referenceType, referenceId, title, body)
            );
            CompletableFuture
                    .runAsync(() -> bankaCoreClient.postNotification(internalReq), inAppExecutor)
                    .exceptionally(ex -> {
                        log.warn("In-app notify async task pao (recipient={} {}, type={}): {}",
                                recipientType, recipientId, notificationType, ex.getMessage());
                        return null;
                    });
        }
    }

    /**
     * OT-1061: razresava sve aktivne supervizore preko banka-core seam-a i salje
     * svakom in-app notifikaciju. Best-effort na oba nivoa: ako lista supervizora
     * ne moze da se dohvati (banka-core down), loguje i izadje; ako pojedinacni
     * {@link #notify} padne, hvata se po-supervizoru pa jedan pad ne sprecava
     * obavestavanje ostalih. Reuse-uje {@link #notify} (in-app kanal) — tip mora
     * biti in-app-sending (npr. {@code TAX_CALCULATION_FAILED}).
     */
    @Override
    public void notifySupervisors(NotificationType notificationType,
                                  String title,
                                  String body,
                                  String referenceType,
                                  Long referenceId) {
        java.util.List<Long> supervisorIds;
        try {
            supervisorIds = bankaCoreClient.getSupervisorIds();
        } catch (Exception ex) {
            log.warn("Razresenje supervizora palo — notifikacija '{}' nije isporucena (type={}): {}",
                    title, notificationType, ex.getMessage());
            return;
        }
        if (supervisorIds.isEmpty()) {
            log.warn("Nema aktivnih supervizora za notifikaciju '{}' (type={}) — preskacem",
                    title, notificationType);
            return;
        }
        for (Long supervisorId : supervisorIds) {
            try {
                notify(supervisorId, "EMPLOYEE", notificationType,
                        title, body, referenceType, referenceId);
            } catch (Exception ex) {
                log.warn("Supervisor notify pao za EMPLOYEE {} (type={}): {}",
                        supervisorId, notificationType, ex.getMessage());
            }
        }
    }

    /**
     * <b>P2-notif-reliability-2 (R1 384): STABILAN idempotency kljuc.</b> Stari kod
     * je generisao svez {@code UUID.randomUUID()} po svakom {@code notify()} pozivu
     * → kljuc nikad nije bio stabilan pa banka-core dedup (UNIQUE idempotency_key)
     * NIKAD nije mogao da prepozna retry (npr. SAGA recovery / scheduler re-fire
     * istog logickog eventa → duplikat in-app notifikacije).
     *
     * <p>Sada se kljuc izvodi DETERMINISTICKI iz sadrzaja notifikacije:
     * {@code (recipientType, recipientId, type, referenceType, referenceId)} kad
     * postoji reference (stabilan resurs-vezan kljuc), inace fallback na hash
     * {@code (... + title + body)} da bi dva razlicita ad-hoc eventa istom
     * korisniku ipak imala razlicite kljuceve. Retry istog logickog eventa daje
     * isti kljuc → banka-core ga prepozna i ne upise duplikat.
     */
    static String buildIdempotencyKey(Long recipientId, String recipientType,
                                      NotificationType type, String referenceType,
                                      Long referenceId, String title, String body) {
        String base = recipientType + ":" + recipientId + ":" + type.name()
                + ":" + referenceType + ":" + referenceId;
        if (referenceType == null || referenceId == null) {
            // Bez stabilne reference — vezi i naslov/telo da razdvojimo ad-hoc evente.
            base = base + ":" + (title != null ? title : "") + ":" + (body != null ? body : "");
        }
        // Deterministicki UUID iz sadrzaja (name-based) → <= 100 chars (kolona limit).
        return UUID.nameUUIDFromBytes(base.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * C-notif-email blocker #2: vraca {@code true} ako email sa datim idempotency
     * kljucem treba poslati (prvi put unutar prozora), {@code false} ako je duplikat
     * koji treba suprimovati. Atomicno: koristi {@link Map#merge} da prvi pozivalac
     * "rezervise" kljuc, a paralelni/naredni pozivi unutar {@link #EMAIL_DEDUP_WINDOW_MS}
     * dobiju {@code false}. Istekli unos (stariji od prozora) tretira se kao nov →
     * dozvoljava legitiman ponovljen event mnogo kasnije.
     *
     * <p>Best-effort prune: kad mapa naraste preko cap-a, lenjo se uklone istekli
     * unosi (zastita od neogranicenog rasta bez dedicated reaper thread-a).
     */
    private boolean shouldSendEmail(String idempotencyKey) {
        long now = System.currentTimeMillis();
        if (emailDedupCache.size() > EMAIL_DEDUP_MAX_ENTRIES) {
            emailDedupCache.values().removeIf(ts -> now - ts > EMAIL_DEDUP_WINDOW_MS);
        }
        // compute() je atomicno po kljucu (ConcurrentHashMap drzi bin-lock): tacno
        // JEDAN pozivalac vidi prazan/istekao slot i "rezervise" ga. Koristimo
        // boolean[] kao izlazni kanal jer compute vraca novu vrednost mape, ne odluku.
        // Robustno i na isti-milisekund duplikat (oldTs == now → svez → suppress).
        boolean[] send = {false};
        emailDedupCache.compute(idempotencyKey, (key, oldTs) -> {
            if (oldTs == null || now - oldTs > EMAIL_DEDUP_WINDOW_MS) {
                send[0] = true;      // prvi (ili posle isteka prozora) → posalji
                return now;          // (re)postavi timestamp
            }
            send[0] = false;         // svez duplikat → suprimuj
            return oldTs;            // zadrzi originalni timestamp (prozor se ne pomera)
        });
        return send[0];
    }

    /**
     * Publishuje email notifikaciju kao RabbitMQ {@code IN_APP_GENERIC} poruku.
     * Razresava email primaoca preko banka-core /internal/users RPC-a; ako
     * razresenje padne, email se preskace (ali in-app kanal nezavisno nastavlja).
     */
    private void publishEmail(Long recipientId,
                              String recipientType,
                              NotificationType notificationType,
                              String title,
                              String body) {
        try {
            String email;
            String firstName;
            try {
                var userDto = bankaCoreClient.getUserById(recipientType, recipientId);
                email = userDto.email();
                firstName = userDto.firstName();
            } catch (BankaCoreClientException ex) {
                log.warn("Email notifikacija preskocena: banka-core lookup pao za {} {} (type={}): {}",
                        recipientType, recipientId, notificationType, ex.getMessage());
                return;
            }

            if (email == null || email.isBlank()) {
                log.warn("Email notifikacija preskocena: nema email-a za {} {} (type={})",
                        recipientType, recipientId, notificationType);
                return;
            }

            Map<String, String> data = new HashMap<>();
            data.put("email", email);
            data.put("firstName", firstName != null ? firstName : "");
            data.put("title", title != null ? title : "");
            data.put("body", body != null ? body : "");

            rabbitTemplate.convertAndSend(
                    NotificationRabbit.EXCHANGE,
                    NotificationRabbit.EMAIL_ROUTING_KEY,
                    new NotificationMessage(NotificationKind.IN_APP_GENERIC, data));
        } catch (RuntimeException ex) {
            log.warn("Neuspeh email publish-a (type={}, recipient={} {}): {}",
                    notificationType, recipientType, recipientId, ex.getMessage());
        }
    }
}
