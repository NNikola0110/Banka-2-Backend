package rs.raf.trading.pricealert.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import rs.raf.trading.pricealert.model.PriceAlert;
import rs.raf.trading.pricealert.model.PriceAlertCondition;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * [B5 - Cenovni alarmi] Spring Data JPA repozitorijum za {@link PriceAlert}.
 */
@Repository
public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {

    /**
     * Vraca sve alarme datog korisnika sortirano od najnovijeg.
     * Koristi se za {@code GET /price-alerts/my} (bez filtera).
     */
    List<PriceAlert> findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(Long ownerId, String ownerType);

    /**
     * Vraca alarme datog korisnika filtrirano po {@code active} flagu, sortirano DESC.
     * Koristi se za {@code GET /price-alerts/my?active=true|false}.
     */
    List<PriceAlert> findByOwnerIdAndOwnerTypeAndActiveOrderByCreatedAtDesc(
            Long ownerId, String ownerType, Boolean active);

    /**
     * Aktivan alarm istog korisnika za isti listing + isti smer. Sprecava duplikate
     * (createAlert baca 400 ako vec postoji).
     */
    Optional<PriceAlert> findByOwnerIdAndOwnerTypeAndListingIdAndConditionAndActiveTrue(
            Long ownerId, String ownerType, Long listingId, PriceAlertCondition condition);

    /** Aktivni alarmi za konkretne listinge — koristi scheduler za batch evaluaciju. */
    List<PriceAlert> findByActiveTrueAndListingIdIn(List<Long> listingIds);

    /** [P2-input-validation-1 / R1 517] broj AKTIVNIH alarma po korisniku (DoS limit guard). */
    long countByOwnerIdAndOwnerTypeAndActiveTrue(Long ownerId, String ownerType);

    /** Distinct listingId-evi sa aktivnim alarmima (efikasno scheduler scan). */
    @Query("SELECT DISTINCT a.listingId FROM PriceAlert a WHERE a.active = true")
    List<Long> findDistinctListingIdsByActiveTrue();

    /**
     * Atomicno deaktivira alarm AKO je trenutno aktivan. Vraca broj redova koji
     * su izmenjeni: 1 ako je ovaj poziv prvi koji je deaktivirao alarm, 0 ako
     * ga je u medjuvremenu drugi worker (scheduler / refresh hook) vec
     * deaktivirao.
     *
     * <p>Spreca dvostruko publish-ovanje {@code PRICE_ALERT_TRIGGERED}
     * notifikacije izmedju scheduler-a (60s) i hook-a iz {@code ListingServiceImpl}
     * (refresh). Klijent pozivalac mora da publish-uje notifikaciju SAMO ako je
     * povratna vrednost == 1.
     */
    @Modifying
    @Query("UPDATE PriceAlert pa SET pa.active = false, pa.triggeredAt = :triggeredAt "
            + "WHERE pa.id = :id AND pa.active = true")
    int deactivateAlertIfActive(@Param("id") Long id,
                                @Param("triggeredAt") LocalDateTime triggeredAt);

    /**
     * R2-1382 — RE-AKTIVIRA alarm i ponistava {@code triggeredAt} (kompenzacija).
     * Koristi se kad atomicna deaktivacija uspe ali isporuka notifikacije
     * ({@code PRICE_ALERT_TRIGGERED}) padne (npr. RabbitMQ down) — alarm se vraca
     * u aktivno stanje da bi se ponovo okidao sledeci ciklus (at-least-once
     * isporuka notifikacije), umesto da ostane tiho deaktiviran bez ikakve
     * notifikacije korisniku.
     */
    @Modifying
    @Query("UPDATE PriceAlert pa SET pa.active = true, pa.triggeredAt = null WHERE pa.id = :id")
    int reactivateAlert(@Param("id") Long id);
}
