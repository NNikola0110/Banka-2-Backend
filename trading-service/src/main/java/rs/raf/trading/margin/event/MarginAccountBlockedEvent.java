package rs.raf.trading.margin.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Spring {@code ApplicationEvent} koji se publish-uje kad margin racun bude
 * blokiran tokom dnevne provere maintenance margine (margin call).
 *
 * <p><b>Faza 2f-5a (cutover):</b> {@link MarginAccountBlockedNotificationListener}
 * sluša ovaj event i premosti ga na RabbitMQ ka {@code notification-service}
 * (cross-JVM email). Margin-call scheduler je sad ziv ({@code @EnableScheduling}
 * preko {@code SchedulingConfig}), pa se event stvarno emituje.
 *
 * <p><b>P2-notif-reliability-2 (R1 381):</b> {@code ownerUserId} je dodat da bi
 * listener pored email-a mogao da kreira i IN-APP notifikaciju (bell) preko
 * {@code NotificationService.notify(...)} — ranije je margin blokada slala SAMO
 * email, pa korisnik koji ne otvori mejl nije imao nikakav in-app signal.
 */
@Getter
@AllArgsConstructor
public class MarginAccountBlockedEvent {
    private Long ownerUserId;
    private Long marginAccountId;
    private String email;
    private String maintenanceMargin;
    private String initialMargin;
    private String deficit;
}
