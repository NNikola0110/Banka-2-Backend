package rs.raf.notification.messaging;

/**
 * [P1-notif-svc-1 / 1531] Oznacava da je NotificationMessage payload neispravan
 * (nedostaje obavezan kljuc / malformiran broj / datum) — retry ga nikad nece
 * popraviti, pa ide u DLQ (consumer {@code catch (RuntimeException)} → nack
 * requeue=false). Jasnija klasifikacija od sirovog NPE/NumberFormatException.
 */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message) {
        super(message);
    }

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
