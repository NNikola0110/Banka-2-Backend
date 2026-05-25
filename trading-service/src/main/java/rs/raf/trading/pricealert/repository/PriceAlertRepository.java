package rs.raf.trading.pricealert.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import rs.raf.trading.pricealert.model.PriceAlert;
import rs.raf.trading.pricealert.model.PriceAlertCondition;

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

    /** Svi aktivni alarmi — koristi scheduler za scan. */
    List<PriceAlert> findByActiveTrue();

    /** Aktivni alarmi za konkretne listinge — koristi scheduler za batch evaluaciju. */
    List<PriceAlert> findByActiveTrueAndListingIdIn(List<Long> listingIds);

    /** Lookup po id-u uz ownership check (koristi se u DELETE flow-u). */
    Optional<PriceAlert> findByIdAndOwnerIdAndOwnerType(Long id, Long ownerId, String ownerType);

    /** Distinct listingId-evi sa aktivnim alarmima (efikasno scheduler scan). */
    @Query("SELECT DISTINCT a.listingId FROM PriceAlert a WHERE a.active = true")
    List<Long> findDistinctListingIdsByActiveTrue();
}
