package rs.raf.banka2_bek.notification.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.notification.model.Notification;
import rs.raf.banka2_bek.notification.model.NotificationType;

/**
 * [B1] Spring Data JPA repository for the {@link Notification} entity.
 * All query methods are derived or custom-JPQL; no changes are needed by
 * dependent tasks — B4/B5/B8 interact exclusively through
 * {@link rs.raf.banka2_bek.notification.service.NotificationService#notify}.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Returns a paginated list of all notifications for the given recipient,
     * sorted by the {@code Pageable} specification (typically {@code createdAt} desc).
     */
    Page<Notification> findByRecipientIdAndRecipientType(Long recipientId, String recipientType, Pageable pageable);

    /**
     * Returns a paginated list of notifications for the given recipient
     * filtered by read status.
     *
     * @param read {@code false} to fetch only unread notifications
     */
    Page<Notification> findByRecipientIdAndRecipientTypeAndRead(Long recipientId, String recipientType, boolean read, Pageable pageable);

    /**
     * Counts notifications for the given recipient filtered by read status.
     *
     * @param read pass {@code false} to count unread notifications
     * @return total matching count (used by the unread-count endpoint)
     */
    long countByRecipientIdAndRecipientTypeAndRead(Long recipientId, String recipientType, boolean read);

    /**
     * Bulk-marks all <b>unread</b> notifications for the given recipient as read.
     * Must be called inside a transaction ({@code @Transactional} on the caller).
     *
     * <p>R4 1817: {@code AND notification.read = false} ogranicava UPDATE samo na
     * neprocitane redove — bez toga bulk UPDATE zakljucava i vec-procitane redove
     * (sire row-lock + nepotrebni dirty-write). R1 691/692:
     * {@code clearAutomatically = true} flush-uje + ocisti persistence context posle
     * UPDATE-a → entiteti ucitani u istoj transakciji pre poziva ne ostaju stale
     * ({@code read=false}) u L1 cache-u (npr. getUnreadCount u istoj tx vraca 0).
     *
     * @return number of rows updated (samo prethodno-neprocitani)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification notification SET notification.read = true " +
            "WHERE notification.recipientId = :recipientId AND notification.recipientType = :recipientType " +
            "AND notification.read = false")
    int markAllReadForRecipient(
            @Param("recipientId") Long recipientId,
            @Param("recipientType") String recipientType
    );

    /**
     * <b>P2-notif-reliability-2 (R4 1791): in-app dedup guard.</b> Da li vec
     * postoji notifikacija za isti dogadjaj — kljuc je
     * {@code (recipientId, recipientType, notificationType, referenceType, referenceId)}.
     * Koristi se da se preskoci dupli in-app red kad isti logicki event (npr.
     * SAGA recovery / scheduler re-fire) ponovo pozove {@code notify()} sa istom
     * referencom. Dedup se primenjuje SAMO kad postoji reference (referenceType i
     * referenceId != null) — ad-hoc notifikacije bez reference se ne dedupuju.
     */
    boolean existsByRecipientIdAndRecipientTypeAndNotificationTypeAndReferenceTypeAndReferenceId(
            Long recipientId,
            String recipientType,
            NotificationType notificationType,
            String referenceType,
            Long referenceId
    );
}
