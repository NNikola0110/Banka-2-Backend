package rs.raf.banka2_bek.notification.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import rs.raf.banka2.contracts.internal.InternalNotificationRequest;
import rs.raf.banka2_bek.auth.util.UserRole;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.notification.dto.NotificationDto;
import rs.raf.banka2_bek.notification.exception.InAppNotificationException;
import rs.raf.banka2_bek.notification.mapper.NotificationObjectMapper;
import rs.raf.banka2_bek.notification.model.Notification;
import rs.raf.banka2_bek.notification.model.NotificationType;
import rs.raf.banka2_bek.notification.repository.NotificationRepository;

/**
 * Default implementation of {@link NotificationService}.
 *
 * <p>Each call to {@link #notify} atomically:
 * <ol>
 *   <li>Persists the in-app notification to the database.</li>
 *   <li>If the notification type has {@code sendsEmail = true}, resolves the
 *       recipient's contact details and publishes an
 *       {@code IN_APP_GENERIC NotificationMessage} on RabbitMQ via
 *       {@link NotificationPublisher#sendInAppGenericMail}. The
 *       {@code notification-service} consumer routes it through the branded
 *       in-app email template.</li>
 * </ol>
 *
 * <p>E-mail dispatch failures are logged and swallowed by the publisher: the
 * in-app record is already persisted and must not be rolled back due to an
 * SMTP or broker problem. Any failure during contact resolution (unknown user
 * id / type) is also logged and swallowed here for the same reason.
 *
 * <p><b>OT-1819:</b> when invoked inside a transaction, the actual broker publish
 * is deferred to {@code afterCommit} (see {@link #queueEmail}) so an email is sent
 * only if the business transaction commits — a rollback never leaves an email sent
 * for a notification that does not exist in the DB.
 */
@Slf4j
@Service
@AllArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPublisher notificationPublisher;
    private final EmployeeRepository employeeRepository;
    private final ClientRepository clientRepository;

    @Transactional
    @Override
    public void notify(Long recipientId,
                       String recipientType,
                       NotificationType notificationType,
                       String title,
                       String body,
                       String referenceType,
                       Long referenceId) {

        // [BE-NTF-02] In-app persist je gate-ovan {@code sendsInApp} flag-om.
        // Tipovi koji su samo email (npr. eventualno: marketing) ne pune
        // notification bell. Trenutno svi tipovi imaju sendsInApp=true (cuva
        // backward-compat), ali infra je tu za per-type suppression.
        if (notificationType.isSendsInApp()
                && !isDuplicateInApp(recipientId, recipientType, notificationType,
                        referenceType, referenceId)) {
            Notification notification = Notification.builder()
                    .recipientId(recipientId)
                    .recipientType(recipientType)
                    .notificationType(notificationType)
                    .title(title)
                    .body(body)
                    .read(false)
                    .referenceType(referenceType)
                    .referenceId(referenceId)
                    .build();
            notificationRepository.save(notification);
        }

        if (notificationType.isSendsEmail()) {
            queueEmail(recipientId, recipientType, notificationType, title, body);
        }
    }

    /**
     * <b>P2-notif-reliability-2 (R4 1791): in-app dedup.</b> Vraca {@code true}
     * ako vec postoji in-app notifikacija za isti dogadjaj — kljuc je
     * {@code (recipientId, recipientType, type, referenceType, referenceId)}.
     * Primenjuje se SAMO kad postoji reference (oba referenceType/referenceId
     * != null); ad-hoc notifikacije bez reference se ne dedupuju (ne mozemo
     * razlikovati dva razlicita ad-hoc eventa).
     */
    private boolean isDuplicateInApp(Long recipientId, String recipientType,
                                     NotificationType type, String referenceType,
                                     Long referenceId) {
        if (referenceType == null || referenceId == null) {
            return false;
        }
        return notificationRepository
                .existsByRecipientIdAndRecipientTypeAndNotificationTypeAndReferenceTypeAndReferenceId(
                        recipientId, recipientType, type, referenceType, referenceId);
    }

    /**
     * Cross-DB ulaz za trading-service in-app notifikacije. Mapira string
     * {@code type} u {@link NotificationType}; ako vrednost ne postoji u
     * banka-core enum-u, koristi {@link NotificationType#GENERAL} kao fallback
     * (logujemo WARN da znamo sta nedostaje). NE okida email — trading-service
     * paralelno publishuje RabbitMQ event.
     */
    @Transactional
    @Override
    public void createInternalNotification(InternalNotificationRequest request) {
        NotificationType type;
        try {
            type = NotificationType.valueOf(request.type());
        } catch (IllegalArgumentException | NullPointerException ex) {
            log.warn("Unknown NotificationType '{}' from trading-service, falling back to GENERAL",
                    request.type());
            type = NotificationType.GENERAL;
        }

        // R4 1791: in-app dedup i na cross-DB putanji (dodatna odbrana uz
        // controller-level idempotency-key dedup).
        if (isDuplicateInApp(request.recipientId(), request.recipientType(), type,
                request.referenceType(), request.referenceId())) {
            log.debug("In-app notifikacija je duplikat (recipient={} {}, type={}, ref={}:{}) — preskacem",
                    request.recipientType(), request.recipientId(), type,
                    request.referenceType(), request.referenceId());
            return;
        }

        Notification notification = Notification.builder()
                .recipientId(request.recipientId())
                .recipientType(request.recipientType())
                .notificationType(type)
                .title(request.title() != null ? request.title() : "")
                .body(request.message() != null ? request.message() : "")
                .read(false)
                .referenceType(request.referenceType())
                .referenceId(request.referenceId())
                .build();
        notificationRepository.save(notification);
    }

    @Transactional(readOnly = true)
    @Override
    public Page<NotificationDto> getMyNotifications(Long recipientId,
                                                    String recipientType,
                                                    boolean onlyUnread,
                                                    int page,
                                                    int size) {
        // R4 1791: stabilan ordering — createdAt.desc, pa id.desc kao tie-breaker.
        // Bez id tie-breaker-a, notifikacije sa identicnim createdAt (isti
        // milisekund, npr. batch insert) imaju nedeterministican redosled izmedju
        // stranica → ista notifikacija moze da se pojavi/nestane pri paginaciji.
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Notification> result = onlyUnread
                ? notificationRepository.findByRecipientIdAndRecipientTypeAndRead(
                        recipientId, recipientType, false, pageable)
                : notificationRepository.findByRecipientIdAndRecipientType(
                        recipientId, recipientType, pageable);
        return result.map(NotificationObjectMapper::toDto);
    }

    @Transactional(readOnly = true)
    @Override
    public Long getUnreadCount(Long recipientId, String recipientType) {
        return notificationRepository.countByRecipientIdAndRecipientTypeAndRead(
                recipientId, recipientType, false);
    }

    @Transactional
    @Override
    public NotificationDto markOneRead(Long notificationId, Long recipientId, String recipientType) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new InAppNotificationException(
                        "Notification with id " + notificationId + " not found"));

        if (!notification.getRecipientId().equals(recipientId)
                || !notification.getRecipientType().equals(recipientType)) {
            throw new AccessDeniedException(
                    "Notification " + notificationId + " does not belong to the current user");
        }

        notification.setRead(true);
        return NotificationObjectMapper.toDto(notificationRepository.save(notification));
    }

    @Transactional
    @Override
    public void markAllRead(Long recipientId, String recipientType) {
        notificationRepository.markAllReadForRecipient(recipientId, recipientType);
    }

    /**
     * Resolves the recipient's contact details (inside the current transaction so
     * the DB reads are valid) and publishes an {@code IN_APP_GENERIC} message on
     * RabbitMQ via {@link NotificationPublisher}. The {@code notification-service}
     * consumer renders the branded generic email template using the {@code title}
     * and {@code body}.
     *
     * <p><b>OT-1819 (✅ FIXED 02.06):</b> the broker publish now runs in a
     * {@link TransactionSynchronization#afterCommit()} callback (same pattern as the
     * trading-audit / interbank dispatch), so the email is sent ONLY if the surrounding
     * business transaction commits. Previously the publish happened synchronously
     * inside the {@code @Transactional notify(...)} method (publish-before-commit) —
     * a later rollback in the same tx would leave an email sent for a notification
     * that no longer exists in the DB. When there is no active transaction (e.g. a
     * non-transactional caller or a unit test) we publish immediately (best-effort).
     *
     * <p>Any failure (unresolvable recipient, broker error inside the publisher) is
     * logged at WARN level and swallowed — the notification is already persisted and
     * must not be rolled back due to an email problem; a broker error in the
     * afterCommit callback likewise cannot affect the already-committed business op.
     */
    private void queueEmail(Long recipientId,
                            String recipientType,
                            NotificationType notificationType,
                            String title,
                            String body) {
        // Resolve contact eagerly (inside the tx — valid DB reads); only the broker
        // send is deferred to afterCommit.
        final RecipientContact contact;
        try {
            contact = resolveContact(recipientId, recipientType);
        } catch (Exception e) {
            log.warn("Could not resolve notification e-mail recipient recipientId={}, type={}",
                    recipientId, notificationType, e);
            return;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // OT-1819: publish only if the business tx commits (no email for a
            // notification that gets rolled back).
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishEmailSafely(contact, notificationType, title, body, recipientId);
                }
            });
        } else {
            // No active transaction (best-effort) — publish immediately.
            publishEmailSafely(contact, notificationType, title, body, recipientId);
        }
    }

    /**
     * OT-1819: wraps the broker publish so a RabbitMQ failure can never break the
     * already-committed business operation (afterCommit callbacks run outside the
     * transaction). The publisher itself also swallows broker errors, but this is
     * defense-in-depth for the afterCommit path.
     */
    private void publishEmailSafely(RecipientContact contact,
                                    NotificationType notificationType,
                                    String title,
                                    String body,
                                    Long recipientId) {
        try {
            notificationPublisher.sendInAppGenericMail(
                    contact.email(), contact.firstName(), title, body);
        } catch (Exception e) {
            log.warn("Could not publish notification e-mail for recipientId={}, type={}",
                    recipientId, notificationType, e);
        }
    }

    /**
     * Loads the contact details (email, first name) for the given recipient.
     * Forwarded to the {@code IN_APP_GENERIC} RabbitMQ message so that the
     * generic email template can personalise the greeting line.
     *
     * @throws InAppNotificationException if the recipient cannot be found or
     *                                    the recipientType is unrecognised
     */
    private RecipientContact resolveContact(Long recipientId, String recipientType) {
        if (UserRole.EMPLOYEE.equals(recipientType)) {
            Employee employee = employeeRepository.findById(recipientId)
                    .orElseThrow(() -> new InAppNotificationException(
                            "Employee with id " + recipientId + " not found"));
            return new RecipientContact(employee.getEmail(), employee.getFirstName());
        }
        if (UserRole.CLIENT.equals(recipientType)) {
            Client client = clientRepository.findById(recipientId)
                    .orElseThrow(() -> new InAppNotificationException(
                            "Client with id " + recipientId + " not found"));
            return new RecipientContact(client.getEmail(), client.getFirstName());
        }
        throw new InAppNotificationException(
                "recipientType must be \"CLIENT\" or \"EMPLOYEE\", got: " + recipientType);
    }

    private record RecipientContact(String email, String firstName) {
    }
}
