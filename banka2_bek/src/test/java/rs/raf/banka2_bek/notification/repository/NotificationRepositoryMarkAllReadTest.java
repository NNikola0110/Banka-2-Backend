package rs.raf.banka2_bek.notification.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.notification.model.Notification;
import rs.raf.banka2_bek.notification.model.NotificationType;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4 1817 / R1 691/692 — karakterizacioni test za
 * {@link NotificationRepository#markAllReadForRecipient}:
 *
 * <ul>
 *   <li>R4 1817: bulk UPDATE menja SAMO neprocitane redove ({@code AND read = false})
 *       → povratni count je broj prethodno-neprocitanih (ne ukupan broj redova), i
 *       ne dira tudje (drugi recipient) niti vec-procitane redove.</li>
 *   <li>R1 691/692: {@code clearAutomatically = true} → posle UPDATE-a sve
 *       notifikacije primaoca su {@code read=true} (nema stale L1 keseva).</li>
 * </ul>
 *
 * Spring Boot 4: @SpringBootTest + H2 (application-test.properties).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationRepositoryMarkAllReadTest {

    @Autowired
    private NotificationRepository repository;

    private Notification persist(Long recipientId, String recipientType, boolean read) {
        return repository.save(Notification.builder()
                .recipientId(recipientId)
                .recipientType(recipientType)
                .notificationType(NotificationType.GENERAL)
                .title("t")
                .body("b")
                .read(read)
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Test
    @DisplayName("markAllReadForRecipient menja samo neprocitane primaocu (R4 1817)")
    void marksOnlyUnreadForRecipient() {
        // recipient 1: 2 unread + 1 already read
        persist(1L, "CLIENT", false);
        persist(1L, "CLIENT", false);
        persist(1L, "CLIENT", true);
        // recipient 2 (tudje): 1 unread — NE sme da se dotakne
        persist(2L, "CLIENT", false);

        int updated = repository.markAllReadForRecipient(1L, "CLIENT");

        // R4 1817: count = broj PRETHODNO-neprocitanih (2), ne svih 3 reda primaoca 1.
        assertThat(updated).isEqualTo(2);

        // Svi redovi primaoca 1 su sada read.
        assertThat(repository.countByRecipientIdAndRecipientTypeAndRead(1L, "CLIENT", false))
                .isZero();
        // Tudji (recipient 2) unered ostaje netaknut.
        assertThat(repository.countByRecipientIdAndRecipientTypeAndRead(2L, "CLIENT", false))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("markAllReadForRecipient bez neprocitanih vraca 0 (R4 1817)")
    void noUnread_returnsZero() {
        persist(3L, "CLIENT", true);
        persist(3L, "CLIENT", true);

        int updated = repository.markAllReadForRecipient(3L, "CLIENT");

        assertThat(updated).isZero();
    }
}
