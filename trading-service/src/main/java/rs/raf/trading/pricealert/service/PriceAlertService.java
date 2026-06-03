package rs.raf.trading.pricealert.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.pricealert.dto.CreatePriceAlertDto;
import rs.raf.trading.pricealert.dto.PriceAlertDto;
import rs.raf.trading.pricealert.model.PriceAlert;
import rs.raf.trading.pricealert.model.PriceAlertCondition;
import rs.raf.trading.pricealert.repository.PriceAlertRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * [B5 - Cenovni alarmi] Servisni sloj za CRUD alarma + scheduler okidanje.
 *
 * <p>Mikroservisi: listing je LOKALAN ({@code rs.raf.trading.stock}); notifikacija
 * okidanog alarma ide preko RabbitMQ-a ka {@code notification-service}-u kroz
 * {@link NotificationService#notify}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriceAlertService {

    /** [P2-input-validation-1 / R1 517] gornja granica aktivnih alarma po korisniku (DoS guard). */
    public static final int MAX_ACTIVE_ALERTS_PER_USER = 50;

    private final PriceAlertRepository alertRepository;
    private final ListingRepository listingRepository;
    private final TradingUserResolver userResolver;
    private final NotificationService notificationService;

    /**
     * Kreira novi cenovni alarm za tekuceg korisnika (klijent ili zaposleni).
     */
    @Transactional
    public PriceAlertDto createAlert(CreatePriceAlertDto dto) {
        UserContext me = userResolver.resolveCurrent();
        String ownerType = resolveOwnerType(me);

        // R1 810: HTTP put vec validira preko @Valid na CreatePriceAlertDto
        // (@NotNull + @DecimalMin), pa je null-grana nedostizna kroz kontroler.
        // Zadrzano kao defense-in-depth za direktne (non-@Valid) pozivaoce —
        // pinovano direktnim service testovima (createAlert_thresholdZero/Negative).
        if (dto.getThreshold() == null || dto.getThreshold().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("threshold mora biti veci od 0");
        }
        if (dto.getCondition() == null) {
            throw new IllegalArgumentException("condition je obavezan (ABOVE ili BELOW)");
        }

        // [P2-input-validation-1 / R1 517] limit broja aktivnih alarma po korisniku (DoS guard).
        if (alertRepository.countByOwnerIdAndOwnerTypeAndActiveTrue(me.userId(), ownerType)
                >= MAX_ACTIVE_ALERTS_PER_USER) {
            throw new IllegalArgumentException(
                    "Dostigli ste maksimalan broj aktivnih alarma (" + MAX_ACTIVE_ALERTS_PER_USER + ").");
        }

        Listing listing = listingRepository.findById(dto.getListingId())
                .orElseThrow(() -> new EntityNotFoundException("Hartija ne postoji: id=" + dto.getListingId()));

        alertRepository
                .findByOwnerIdAndOwnerTypeAndListingIdAndConditionAndActiveTrue(
                        me.userId(), ownerType, dto.getListingId(), dto.getCondition())
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(
                            "Alarm za ovu hartiju i uslov vec postoji (id=" + existing.getId() + ")");
                });

        PriceAlert alert = PriceAlert.builder()
                .ownerId(me.userId())
                .ownerType(ownerType)
                .listingId(dto.getListingId())
                .condition(dto.getCondition())
                .threshold(dto.getThreshold())
                .active(true)
                .build();

        PriceAlert saved = alertRepository.save(alert);
        log.info("PriceAlert created: id={}, ownerId={}, ownerType={}, listingId={}, ticker={}, condition={}, threshold={}",
                saved.getId(), saved.getOwnerId(), saved.getOwnerType(), saved.getListingId(),
                listing.getTicker(), saved.getCondition(), saved.getThreshold());
        return toDto(saved, listing);
    }

    /**
     * Lista alarme tekuceg korisnika. Ako je {@code activeFilter != null}, filtrira po njemu.
     */
    @Transactional(readOnly = true)
    public List<PriceAlertDto> listMyAlerts(Boolean activeFilter) {
        UserContext me = userResolver.resolveCurrent();
        String ownerType = resolveOwnerType(me);

        List<PriceAlert> alerts = activeFilter == null
                ? alertRepository.findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(me.userId(), ownerType)
                : alertRepository.findByOwnerIdAndOwnerTypeAndActiveOrderByCreatedAtDesc(
                        me.userId(), ownerType, activeFilter);

        if (alerts.isEmpty()) {
            return List.of();
        }

        // Batch lookup ticker-a za sve referencirane listinge (bez N+1).
        List<Long> listingIds = alerts.stream().map(PriceAlert::getListingId).distinct().toList();
        Map<Long, Listing> listingsById = listingRepository.findAllById(listingIds).stream()
                .collect(Collectors.toMap(Listing::getId, l -> l));

        return alerts.stream()
                .map(a -> toDto(a, listingsById.get(a.getListingId())))
                .toList();
    }

    /**
     * Brise alarm; 403 ako ne pripada tekucem korisniku, 404 ako ne postoji.
     */
    @Transactional
    public void deleteAlert(Long alertId) {
        UserContext me = userResolver.resolveCurrent();
        String ownerType = resolveOwnerType(me);

        PriceAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new EntityNotFoundException("Alarm ne postoji: id=" + alertId));

        if (!alert.getOwnerId().equals(me.userId()) || !alert.getOwnerType().equals(ownerType)) {
            throw new AccessDeniedException("Alarm ne pripada tekucem korisniku");
        }

        alertRepository.delete(alert);
        log.info("PriceAlert deleted: id={}, ownerId={}", alertId, me.userId());
    }

    /**
     * Evaluira sve aktivne alarme za date listinge. Za svaki listing prolazi
     * kroz aktivne alarme i okida one ciji je uslov ispunjen. Best-effort
     * per-alarm (greska na jednom alarmu ne zaustavlja ostatak batch-a).
     *
     * <p>Poziva se iz {@code PriceAlertScheduler} sa SVEZE ucitanih listinga
     * (tj. cene su trenutno aktuelne u DB).
     *
     * <p><b>Concurrency:</b> Scheduler (60s) i {@code ListingServiceImpl} hook
     * pozivaju ovaj metod paralelno. Da bi se izbeglo dvostruko publish-ovanje
     * notifikacije, deaktivacija koristi atomicni JPQL UPDATE
     * ({@code deactivateAlertIfActive}) — samo prvi pozivalac dobija
     * {@code rowsAffected == 1} i publish-uje notifikaciju.
     */
    @Transactional
    public int checkAlerts(List<Listing> updatedListings) {
        if (updatedListings == null || updatedListings.isEmpty()) {
            return 0;
        }
        Map<Long, Listing> byId = updatedListings.stream()
                .collect(Collectors.toMap(Listing::getId, l -> l));
        return evaluate(byId);
    }

    /**
     * R2-1384 (stale-price) — varijanta za scheduler: prima {@code listingId}-eve i
     * SAMA cita svezu cenu listinga UNUTAR ove ({@code @Transactional}) tx, umesto da
     * radi nad detached {@code Listing} entitetima procitanim u zasebnoj scheduler tx
     * (gde je cena vec mogla da zastari). Hook iz {@code ListingServiceImpl} i dalje
     * koristi {@link #checkAlerts(List)} jer prosledjuje sveze-perzistovane listinge.
     */
    @Transactional
    public int checkAlertsForListings(List<Long> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            return 0;
        }
        Map<Long, Listing> byId = listingRepository.findAllById(listingIds).stream()
                .collect(Collectors.toMap(Listing::getId, l -> l));
        if (byId.isEmpty()) {
            return 0;
        }
        return evaluate(byId);
    }

    /**
     * Zajednicka evaluacija aktivnih alarma za dati skup listinga (po id-u → entitet
     * sa SVEZOM cenom). Atomicna deaktivacija ({@code deactivateAlertIfActive}) +
     * R2-1382 re-aktivacija na publish-fail.
     */
    private int evaluate(Map<Long, Listing> byId) {
        List<PriceAlert> candidates = alertRepository.findByActiveTrueAndListingIdIn(
                byId.keySet().stream().toList());

        int triggered = 0;
        for (PriceAlert alert : candidates) {
            try {
                Listing listing = byId.get(alert.getListingId());
                if (listing == null || listing.getPrice() == null) {
                    continue;
                }
                if (shouldTrigger(alert.getCondition(), listing.getPrice(), alert.getThreshold())) {
                    LocalDateTime triggeredAt = LocalDateTime.now();
                    int rowsAffected = alertRepository
                            .deactivateAlertIfActive(alert.getId(), triggeredAt);
                    if (rowsAffected != 1) {
                        // Drugi worker (scheduler / refresh hook) je vec deaktivirao alarm.
                        // Ne publish-uj notifikaciju ponovo — sprecavamo duplikate.
                        log.debug("PriceAlert id={} vec deaktiviran u medjuvremenu — preskacem publish",
                                alert.getId());
                        continue;
                    }
                    // Lokalno reflektuj stanje za publishTriggerNotification consumer.
                    alert.setActive(false);
                    alert.setTriggeredAt(triggeredAt);
                    boolean published = publishTriggerNotification(alert, listing);
                    if (!published) {
                        // R2-1382: deaktivacija uspela ali notifikacija nije isporucena
                        // (npr. RabbitMQ down). RE-AKTIVIRAJ alarm da bi se ponovo okidao
                        // sledeci ciklus — inace bi alarm bio tiho izgubljen (deaktiviran
                        // bez ijedne notifikacije korisniku).
                        alertRepository.reactivateAlert(alert.getId());
                        alert.setActive(true);
                        alert.setTriggeredAt(null);
                        log.warn("PriceAlert id={} re-aktiviran — notifikacija nije isporucena, okida se ponovo",
                                alert.getId());
                        continue;
                    }
                    triggered++;
                    log.info("PriceAlert triggered: id={}, ownerId={}, ticker={}, price={}, threshold={}, condition={}",
                            alert.getId(), alert.getOwnerId(), listing.getTicker(),
                            listing.getPrice(), alert.getThreshold(), alert.getCondition());
                }
            } catch (RuntimeException ex) {
                log.warn("PriceAlert evaluacija pukla (id={}): {}", alert.getId(), ex.getMessage());
            }
        }
        return triggered;
    }

    /**
     * R1 813 — granicna semantika (Sc26): okidanje je INKLUZIVNO. ABOVE okida kad
     * je {@code price >= threshold} (dostigao ILI presao prag), BELOW kad je
     * {@code price <= threshold}. Pinovano testovima
     * {@code checkAlerts_priceEqualToThresholdAbove_triggers} (price == threshold → okida).
     */
    private boolean shouldTrigger(PriceAlertCondition condition, BigDecimal price, BigDecimal threshold) {
        if (price == null || threshold == null) {
            return false;
        }
        return switch (condition) {
            case ABOVE -> price.compareTo(threshold) >= 0;
            case BELOW -> price.compareTo(threshold) <= 0;
        };
    }

    /**
     * Publish-uje {@code PRICE_ALERT_TRIGGERED} notifikaciju.
     *
     * @return {@code true} ako je notifikacija uspesno predata; {@code false} ako je
     *         publish pukao (R2-1382: pozivalac tada re-aktivira alarm da se ne izgubi).
     */
    private boolean publishTriggerNotification(PriceAlert alert, Listing listing) {
        String ticker = listing.getTicker() != null ? listing.getTicker() : "?";
        String body = "Cena hartije " + ticker + " je "
                + (alert.getCondition() == PriceAlertCondition.ABOVE ? "presla iznad" : "pala ispod")
                + " praga " + alert.getThreshold() + " (trenutna cena " + listing.getPrice() + ").";
        try {
            notificationService.notify(
                    alert.getOwnerId(),
                    alert.getOwnerType(),
                    NotificationType.PRICE_ALERT_TRIGGERED,
                    "Cenovni alarm okidan: " + ticker,
                    body,
                    "PRICE_ALERT",
                    alert.getId()
            );
            return true;
        } catch (RuntimeException ex) {
            log.warn("Slanje notifikacije za PriceAlert id={} pukla: {}", alert.getId(), ex.getMessage());
            return false;
        }
    }

    private String resolveOwnerType(UserContext me) {
        return me.isClient() ? UserRole.CLIENT : UserRole.EMPLOYEE;
    }

    /**
     * Mapira entitet u DTO. {@code listing} moze biti null (npr. obrisana hartija u medjuvremenu) —
     * u tom slucaju ticker/type ostaju null.
     */
    private PriceAlertDto toDto(PriceAlert alert, Listing listing) {
        return PriceAlertDto.builder()
                .id(alert.getId())
                .ownerId(alert.getOwnerId())
                .ownerType(alert.getOwnerType())
                .listingId(alert.getListingId())
                .listingTicker(listing != null ? listing.getTicker() : null)
                .listingType(listing != null && listing.getListingType() != null
                        ? listing.getListingType().name() : null)
                .condition(alert.getCondition() != null ? alert.getCondition().name() : null)
                .threshold(alert.getThreshold())
                .active(alert.getActive())
                .createdAt(alert.getCreatedAt())
                .triggeredAt(alert.getTriggeredAt())
                .build();
    }
}
