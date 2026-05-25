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

        if (dto.getThreshold() == null || dto.getThreshold().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("threshold mora biti veci od 0");
        }
        if (dto.getCondition() == null) {
            throw new IllegalArgumentException("condition je obavezan (ABOVE ili BELOW)");
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
     */
    @Transactional
    public int checkAlerts(List<Listing> updatedListings) {
        if (updatedListings == null || updatedListings.isEmpty()) {
            return 0;
        }
        Map<Long, Listing> byId = updatedListings.stream()
                .collect(Collectors.toMap(Listing::getId, l -> l));

        List<PriceAlert> candidates = alertRepository.findByActiveTrueAndListingIdIn(
                updatedListings.stream().map(Listing::getId).toList());

        int triggered = 0;
        for (PriceAlert alert : candidates) {
            try {
                Listing listing = byId.get(alert.getListingId());
                if (listing == null || listing.getPrice() == null) {
                    continue;
                }
                if (shouldTrigger(alert.getCondition(), listing.getPrice(), alert.getThreshold())) {
                    alert.setActive(false);
                    alert.setTriggeredAt(LocalDateTime.now());
                    alertRepository.save(alert);
                    publishTriggerNotification(alert, listing);
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

    private boolean shouldTrigger(PriceAlertCondition condition, BigDecimal price, BigDecimal threshold) {
        if (price == null || threshold == null) {
            return false;
        }
        return switch (condition) {
            case ABOVE -> price.compareTo(threshold) >= 0;
            case BELOW -> price.compareTo(threshold) <= 0;
        };
    }

    private void publishTriggerNotification(PriceAlert alert, Listing listing) {
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
        } catch (RuntimeException ex) {
            log.warn("Slanje notifikacije za PriceAlert id={} pukla: {}", alert.getId(), ex.getMessage());
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
