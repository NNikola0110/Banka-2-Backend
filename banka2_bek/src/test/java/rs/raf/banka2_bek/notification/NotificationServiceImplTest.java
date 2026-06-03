package rs.raf.banka2_bek.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.notification.dto.NotificationDto;
import rs.raf.banka2_bek.notification.exception.InAppNotificationException;
import rs.raf.banka2_bek.notification.model.Notification;
import rs.raf.banka2_bek.notification.model.NotificationType;
import rs.raf.banka2_bek.notification.repository.NotificationRepository;
import rs.raf.banka2_bek.notification.service.NotificationServiceImpl;
import rs.raf.banka2.contracts.internal.InternalNotificationRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    private static final Long CLIENT_ID = 5L;
    private static final Long EMPLOYEE_ID = 8L;

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private NotificationPublisher notificationPublisher;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private ClientRepository clientRepository;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @Captor
    private ArgumentCaptor<Notification> notificationCaptor;

    @Test
    void notify_persistsNotificationButDoesNotEmailForPaymentType_R1_380() {
        // P2-notif-reliability-2 (R1 380): PAYMENT je sad sendsEmail=false — placanje
        // vec salje DEDIKOVANI sendPaymentConfirmationMail na call-site-u; notify()
        // sme samo da napuni bell (in-app), NE da posalje JOS jedan (generic) email.
        notificationService.notify(CLIENT_ID, "CLIENT", NotificationType.PAYMENT,
                "Placanje", "Vase placanje je izvrseno", "PAYMENT", 99L);

        verify(notificationRepository).save(notificationCaptor.capture());
        Notification saved = notificationCaptor.getValue();
        assertEquals(CLIENT_ID, saved.getRecipientId());
        assertEquals("CLIENT", saved.getRecipientType());
        assertEquals(NotificationType.PAYMENT, saved.getNotificationType());
        assertEquals("Placanje", saved.getTitle());
        assertEquals("Vase placanje je izvrseno", saved.getBody());
        assertFalse(saved.isRead());
        assertEquals("PAYMENT", saved.getReferenceType());
        assertEquals(99L, saved.getReferenceId().longValue());

        // KLJUCNO: NEMA generic email-a (inace bi klijent dobio DVA emaila).
        verify(notificationPublisher, never()).sendInAppGenericMail(any(), any(), any(), any());
    }

    @Test
    void notify_transferAndCardTypes_doNotEmail_R1_380() {
        // TRANSFER / CARD_BLOCKED / CARD_UNBLOCKED / LOAN_* svi imaju dedikovan email
        // na call-site-u → notify() ne sme da salje generic email.
        for (NotificationType t : new NotificationType[]{
                NotificationType.TRANSFER, NotificationType.CARD_BLOCKED,
                NotificationType.CARD_UNBLOCKED, NotificationType.LOAN_CREATED,
                NotificationType.LOAN_APPROVED, NotificationType.LOAN_REJECTED}) {
            assertFalse(t.isSendsEmail(),
                    t + " mora biti sendsEmail=false (dedikovan email na call-site-u)");
        }
    }

    @Test
    void notify_limitChange_stillEmails_R1_380() {
        // LIMIT_CHANGE NEMA dedikovan email → notify() generic email je JEDINI kanal,
        // pa ostaje sendsEmail=true.
        Client client = mock(Client.class);
        when(client.getEmail()).thenReturn("marko@test.rs");
        when(client.getFirstName()).thenReturn("Marko");
        when(clientRepository.findById(CLIENT_ID)).thenReturn(Optional.of(client));

        notificationService.notify(CLIENT_ID, "CLIENT", NotificationType.LIMIT_CHANGE,
                "Limit promenjen", "Novi limit", "CARD", 3L);

        verify(notificationRepository).save(any(Notification.class));
        verify(notificationPublisher).sendInAppGenericMail(
                "marko@test.rs", "Marko", "Limit promenjen", "Novi limit");
    }

    @Test
    void notify_resolvesEmployeeContactForEmailType() {
        Employee employee = mock(Employee.class);
        when(employee.getEmail()).thenReturn("supervizor@banka.rs");
        when(employee.getFirstName()).thenReturn("Nikola");
        when(employeeRepository.findById(EMPLOYEE_ID)).thenReturn(Optional.of(employee));

        // LIMIT_CHANGE je email-sending tip bez reference (dedup se ne primenjuje).
        notificationService.notify(EMPLOYEE_ID, "EMPLOYEE", NotificationType.LIMIT_CHANGE,
                "Limit promenjen", "Novi limit kartice", null, null);

        verify(notificationRepository).save(any(Notification.class));
        verify(notificationPublisher).sendInAppGenericMail(
                "supervizor@banka.rs", "Nikola", "Limit promenjen", "Novi limit kartice");
    }

    @Test
    void notify_contactResolutionFailureDoesNotRollbackPersistence() {
        // Recipient lookup fails (client not found) — the service must swallow the
        // exception and still keep the saved Notification (in-app row already persisted).
        when(clientRepository.findById(CLIENT_ID)).thenReturn(Optional.empty());

        // LIMIT_CHANGE je email-sending tip → trigeruje resolveContact (koji pada).
        notificationService.notify(CLIENT_ID, "CLIENT", NotificationType.LIMIT_CHANGE,
                "Limit", "telo", null, null);

        verify(notificationRepository).save(any(Notification.class));
        verify(notificationPublisher, never()).sendInAppGenericMail(
                any(), any(), any(), any());
    }

    @Test
    void notify_brokerFailureDoesNotRollbackPersistence() {
        // Recipient lookup succeeds, but the publisher (broker) throws a RuntimeException
        // (simulating a RabbitMQ connectivity failure that escaped the publisher's
        // internal catch). The in-app DB row must be persisted and notify() must NOT
        // propagate the broker exception to the caller.
        Client client = mock(Client.class);
        when(client.getEmail()).thenReturn("marko@test.rs");
        when(client.getFirstName()).thenReturn("Marko");
        when(clientRepository.findById(CLIENT_ID)).thenReturn(Optional.of(client));

        doThrow(new RuntimeException("Broker unavailable"))
                .when(notificationPublisher)
                .sendInAppGenericMail(any(), any(), any(), any());

        assertDoesNotThrow(() ->
                notificationService.notify(CLIENT_ID, "CLIENT", NotificationType.LIMIT_CHANGE,
                        "Limit", "telo", null, null));

        verify(notificationRepository).save(any(Notification.class));
    }

    // ── P2-notif-reliability-2 (R4 1791): in-app dedup po (recipient,type,ref) ──

    @Test
    void notify_duplicateInApp_skipsSecondSave_R4_1791() {
        // Postoji vec notifikacija za isti (recipient, type, referenceType, referenceId)
        // → drugi notify() (npr. SAGA recovery / scheduler re-fire) ne pravi dupli red.
        when(notificationRepository
                .existsByRecipientIdAndRecipientTypeAndNotificationTypeAndReferenceTypeAndReferenceId(
                        CLIENT_ID, "CLIENT", NotificationType.ORDER_EXECUTED, "ORDER", 42L))
                .thenReturn(true);

        notificationService.notify(CLIENT_ID, "CLIENT", NotificationType.ORDER_EXECUTED,
                "Order izvrsen", "Body", "ORDER", 42L);

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void notify_noReference_dedupNotApplied_R4_1791() {
        // Bez reference (referenceId==null) dedup se NE primenjuje (ne mozemo
        // razlikovati ad-hoc evente) → notifikacija se uvek upisuje.
        notificationService.notify(CLIENT_ID, "CLIENT", NotificationType.ORDER_EXECUTED,
                "Order izvrsen", "Body", null, null);

        verify(notificationRepository).save(any(Notification.class));
        // exists-provera se ne poziva kad nema reference.
        verify(notificationRepository, never())
                .existsByRecipientIdAndRecipientTypeAndNotificationTypeAndReferenceTypeAndReferenceId(
                        any(), any(), any(), any(), any());
    }

    // [B1 — Test coverage] Verifies that types with sendsEmail=false never trigger the
    // email pipeline. Important for B4 order/OTC types which are in-app only.
    @Test
    void notify_doesNotPublishEmailEventForNonEmailType() {
        notificationService.notify(CLIENT_ID, "CLIENT", NotificationType.ORDER_PENDING,
                "Order na cekanju", "Vas order je kreiran i ceka odobrenje.", null, null);

        verify(notificationRepository).save(any(Notification.class));
        verify(notificationPublisher, never()).sendInAppGenericMail(
                any(), any(), any(), any());
    }

    @Test
    void notify_unrecognisedRecipientTypeDoesNotPublishAndDoesNotPropagate() {
        // Notification still persisted, publisher never invoked because contact
        // resolution throws InAppNotificationException — which the service swallows.
        // LIMIT_CHANGE je email-sending tip → resolveContact se poziva i pada na ROBOT.
        notificationService.notify(CLIENT_ID, "ROBOT", NotificationType.LIMIT_CHANGE,
                "Limit", "telo", null, null);

        verify(notificationRepository).save(any(Notification.class));
        verify(notificationPublisher, never()).sendInAppGenericMail(
                any(), any(), any(), any());
    }

    @Test
    void getMyNotifications_returnsAllWhenOnlyUnreadFalse() {
        Page<Notification> page = new PageImpl<>(List.of(
                notification(false), notification(true), notification(false)));
        when(notificationRepository.findByRecipientIdAndRecipientType(
                eq(CLIENT_ID), eq("CLIENT"), any(Pageable.class))).thenReturn(page);

        Page<NotificationDto> result =
                notificationService.getMyNotifications(CLIENT_ID, "CLIENT", false, 0, 20);

        assertEquals(3, result.getContent().size());
    }

    @Test
    void getMyNotifications_returnsOnlyUnreadWhenFlagTrue() {
        Page<Notification> page = new PageImpl<>(List.of(notification(false)));
        when(notificationRepository.findByRecipientIdAndRecipientTypeAndRead(
                eq(CLIENT_ID), eq("CLIENT"), eq(false), any(Pageable.class))).thenReturn(page);

        Page<NotificationDto> result =
                notificationService.getMyNotifications(CLIENT_ID, "CLIENT", true, 0, 20);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void getUnreadCount_returnsCorrectCount() {
        when(notificationRepository.countByRecipientIdAndRecipientTypeAndRead(EMPLOYEE_ID, "EMPLOYEE", false))
                .thenReturn(7L);

        Long count = notificationService.getUnreadCount(EMPLOYEE_ID, "EMPLOYEE");

        assertEquals(7L, count.longValue());
    }

    @Test
    void markOneRead_updatesReadFlagAndReturnsDto() {
        when(notificationRepository.findById(10L)).thenReturn(Optional.of(notification(false)));
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NotificationDto dto = notificationService.markOneRead(10L, CLIENT_ID, "CLIENT");

        verify(notificationRepository).save(notificationCaptor.capture());
        assertTrue(notificationCaptor.getValue().isRead());
        assertTrue(dto.isRead());
    }

    @Test
    void markOneRead_throwsWhenNotificationBelongsToOtherRecipient() {
        when(notificationRepository.findById(10L)).thenReturn(Optional.of(notification(false)));

        assertThrows(AccessDeniedException.class,
                () -> notificationService.markOneRead(10L, 999L, "CLIENT"));
    }

    @Test
    void markOneRead_throwsWhenRecipientTypeDoesNotMatch() {
        // Notification belongs to CLIENT_ID as a CLIENT; same id but EMPLOYEE type should be denied.
        when(notificationRepository.findById(10L)).thenReturn(Optional.of(notification(false)));

        assertThrows(AccessDeniedException.class,
                () -> notificationService.markOneRead(10L, CLIENT_ID, "EMPLOYEE"));
    }

    @Test
    void markOneRead_throwsWhenNotificationNotFound() {
        when(notificationRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(InAppNotificationException.class,
                () -> notificationService.markOneRead(404L, CLIENT_ID, "CLIENT"));
    }

    @Test
    void markAllRead_delegatesToRepository() {
        notificationService.markAllRead(EMPLOYEE_ID, "EMPLOYEE");

        verify(notificationRepository).markAllReadForRecipient(EMPLOYEE_ID, "EMPLOYEE");
    }

    // ── TEST-notif-bc-1: createInternalNotification (cross-DB ulaz iz trading-service) ──

    @Test
    void createInternalNotification_persistsMappedNotification_TEST_notif_bc_1() {
        InternalNotificationRequest req = new InternalNotificationRequest(
                CLIENT_ID, "CLIENT", "ORDER_EXECUTED", "Order izvrsen",
                "Vas BUY order je popunjen", "ORDER", 42L, "idem-1");

        notificationService.createInternalNotification(req);

        verify(notificationRepository).save(notificationCaptor.capture());
        Notification saved = notificationCaptor.getValue();
        assertEquals(CLIENT_ID, saved.getRecipientId());
        assertEquals("CLIENT", saved.getRecipientType());
        assertEquals(NotificationType.ORDER_EXECUTED, saved.getNotificationType());
        assertEquals("Order izvrsen", saved.getTitle());
        assertEquals("Vas BUY order je popunjen", saved.getBody());
        assertEquals("ORDER", saved.getReferenceType());
        assertEquals(42L, saved.getReferenceId().longValue());
        assertFalse(saved.isRead());
        // Cross-DB ulaz NE okida email — trading-service paralelno publishuje RabbitMQ event.
        verify(notificationPublisher, never()).sendInAppGenericMail(any(), any(), any(), any());
    }

    @Test
    void createInternalNotification_unknownType_fallsBackToGeneral_TEST_notif_bc_1() {
        InternalNotificationRequest req = new InternalNotificationRequest(
                EMPLOYEE_ID, "EMPLOYEE", "THIS_TYPE_DOES_NOT_EXIST", "Test",
                "Body", null, null, "idem-2");

        notificationService.createInternalNotification(req);

        verify(notificationRepository).save(notificationCaptor.capture());
        assertEquals(NotificationType.GENERAL, notificationCaptor.getValue().getNotificationType());
    }

    @Test
    void createInternalNotification_nullType_fallsBackToGeneral_TEST_notif_bc_1() {
        // valueOf(null) baca NullPointerException → fallback GENERAL.
        InternalNotificationRequest req = new InternalNotificationRequest(
                CLIENT_ID, "CLIENT", null, "Test", "Body", null, null, "idem-3");

        notificationService.createInternalNotification(req);

        verify(notificationRepository).save(notificationCaptor.capture());
        assertEquals(NotificationType.GENERAL, notificationCaptor.getValue().getNotificationType());
    }

    @Test
    void createInternalNotification_nullTitleAndMessage_storedAsEmptyStrings_TEST_notif_bc_1() {
        InternalNotificationRequest req = new InternalNotificationRequest(
                CLIENT_ID, "CLIENT", "GENERAL", null, null, null, null, "idem-4");

        notificationService.createInternalNotification(req);

        verify(notificationRepository).save(notificationCaptor.capture());
        Notification saved = notificationCaptor.getValue();
        assertEquals("", saved.getTitle());
        assertEquals("", saved.getBody());
    }

    @Test
    void createInternalNotification_duplicateReference_skipsSave_TEST_notif_bc_1() {
        // In-app dedup i na cross-DB putanji (R4 1791) — postojeci ref → no save.
        when(notificationRepository
                .existsByRecipientIdAndRecipientTypeAndNotificationTypeAndReferenceTypeAndReferenceId(
                        CLIENT_ID, "CLIENT", NotificationType.ORDER_EXECUTED, "ORDER", 42L))
                .thenReturn(true);

        InternalNotificationRequest req = new InternalNotificationRequest(
                CLIENT_ID, "CLIENT", "ORDER_EXECUTED", "Order", "Body", "ORDER", 42L, "idem-5");

        notificationService.createInternalNotification(req);

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    // ── TEST-notif-bc-2 / OT-1819 (✅ FIXED 02.06): queueEmail publish-after-commit.
    // notify() PRVO perzistuje in-app red, PA publishuje email. Kad postoji aktivna
    // transakcija, publish se ODLAZE u TransactionSynchronization.afterCommit (email
    // ide SAMO ako biznis tx commit-uje; rollback → NEMA email-a). Bez aktivne tx-e
    // (npr. ovaj cist Mockito test) publish ide odmah (best-effort), pa redosled
    // save→publish i dalje vazi.

    @Test
    void queueEmail_persistsInAppBeforePublishingEmail_TEST_notif_bc_2() {
        Client client = mock(Client.class);
        when(client.getEmail()).thenReturn("marko@test.rs");
        when(client.getFirstName()).thenReturn("Marko");
        when(clientRepository.findById(CLIENT_ID)).thenReturn(Optional.of(client));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                notificationRepository, notificationPublisher);

        // LIMIT_CHANGE je email-sending tip (jedini kanal je generic email).
        // Bez aktivne tx-e → immediate publish (best-effort).
        notificationService.notify(CLIENT_ID, "CLIENT", NotificationType.LIMIT_CHANGE,
                "Limit", "telo", null, null);

        // in-app save se desava PRE email publish-a.
        inOrder.verify(notificationRepository).save(any(Notification.class));
        inOrder.verify(notificationPublisher).sendInAppGenericMail(
                "marko@test.rs", "Marko", "Limit", "telo");
    }

    // ── OT-1819: publish-after-commit — email ide SAMO posle commit-a, NE pre rollback-a.
    // Ova dva testa rucno aktiviraju TransactionSynchronizationManager (bez full Spring
    // konteksta) i simuliraju commit (triggerAfterCommit) vs rollback (clear bez commit-a).

    @Test
    void queueEmail_inTransaction_publishesOnlyAfterCommit_OT_1819() {
        Client client = mock(Client.class);
        when(client.getEmail()).thenReturn("marko@test.rs");
        when(client.getFirstName()).thenReturn("Marko");
        when(clientRepository.findById(CLIENT_ID)).thenReturn(Optional.of(client));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            notificationService.notify(CLIENT_ID, "CLIENT", NotificationType.LIMIT_CHANGE,
                    "Limit", "telo", null, null);

            // In-app je sacuvan ODMAH (unutar tx-e), ali email JOS NIJE publish-ovan —
            // ceka commit (registrovan je afterCommit hook).
            verify(notificationRepository).save(any(Notification.class));
            verify(notificationPublisher, never()).sendInAppGenericMail(any(), any(), any(), any());

            // Simuliraj COMMIT → afterCommit hook se okida → email se publishuje.
            triggerAfterCommit();
            verify(notificationPublisher).sendInAppGenericMail(
                    "marko@test.rs", "Marko", "Limit", "telo");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void queueEmail_inTransaction_doesNotPublishOnRollback_OT_1819() {
        Client client = mock(Client.class);
        when(client.getEmail()).thenReturn("marko@test.rs");
        when(client.getFirstName()).thenReturn("Marko");
        when(clientRepository.findById(CLIENT_ID)).thenReturn(Optional.of(client));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            notificationService.notify(CLIENT_ID, "CLIENT", NotificationType.LIMIT_CHANGE,
                    "Limit", "telo", null, null);

            // afterCommit registrovan, ali tx se NIKAD ne commit-uje (rollback) →
            // afterCommit se NE poziva → email se NE publishuje.
            verify(notificationPublisher, never()).sendInAppGenericMail(any(), any(), any(), any());
            // Rollback: synchronizacije se odbacuju bez afterCommit poziva.
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        // Posle rollback-a (clear bez triggerAfterCommit) email NIKAD nije poslat.
        verify(notificationPublisher, never()).sendInAppGenericMail(any(), any(), any(), any());
    }

    /**
     * OT-1819: rucno okida sve registrovane {@code afterCommit} hook-ove (simulacija
     * uspesnog tx commit-a) bez bootstrap-ovanja {@code PlatformTransactionManager}-a.
     */
    private static void triggerAfterCommit() {
        for (org.springframework.transaction.support.TransactionSynchronization sync :
                org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    private Notification notification(boolean read) {
        return Notification.builder()
                .id(1L)
                .recipientId(CLIENT_ID)
                .recipientType("CLIENT")
                .notificationType(NotificationType.GENERAL)
                .title("Naslov")
                .body("Telo")
                .read(read)
                .build();
    }
}
