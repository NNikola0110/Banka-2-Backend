package rs.raf.banka2_bek.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import rs.raf.banka2_bek.audit.dto.AuditLogDto;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.model.AuditLog;
import rs.raf.banka2_bek.audit.repository.AuditLogRepository;
import rs.raf.banka2_bek.audit.service.AuditLogService;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private ClientRepository clientRepository;

    @InjectMocks
    private AuditLogService auditLogService;

    @Test
    void record_savesAuditLogWithCorrectFields() {
        auditLogService.record(1L, "EMPLOYEE", AuditActionType.PERMISSIONS_CHANGED,
                "desc", "EMPLOYEE", 5L, "old", "new");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertThat(saved.getActorId()).isEqualTo(1L);
        assertThat(saved.getActorType()).isEqualTo("EMPLOYEE");
        assertThat(saved.getActionType()).isEqualTo(AuditActionType.PERMISSIONS_CHANGED);
        assertThat(saved.getDescription()).isEqualTo("desc");
        assertThat(saved.getTargetType()).isEqualTo("EMPLOYEE");
        assertThat(saved.getTargetId()).isEqualTo(5L);
        assertThat(saved.getOldValue()).isEqualTo("old");
        assertThat(saved.getNewValue()).isEqualTo("new");
    }

    @Test
    void record_withNullOldAndNewValue_savesSuccessfully() {
        auditLogService.record(2L, "EMPLOYEE", AuditActionType.PAYMENT_CREATED,
                "created", "PAYMENT", 10L, null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        assertThat(captor.getValue().getOldValue()).isNull();
        assertThat(captor.getValue().getNewValue()).isNull();
    }

    @Test
    void record_withoutTarget_savesWithoutTargetId() {
        auditLogService.record(3L, "EMPLOYEE", AuditActionType.SAVINGS_OPENED,
                "savings opened", null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        assertThat(captor.getValue().getTargetId()).isNull();
        assertThat(captor.getValue().getTargetType()).isNull();
    }

    @Test
    void query_withAllFiltersNull_callsRepositoryWithNullParams() {
        Pageable pageable = PageRequest.of(0, 20);
        when(auditLogRepository.findFiltered(null, null, null, null, pageable))
                .thenReturn(Page.empty());

        auditLogService.query(null, null, null, null, pageable);

        verify(auditLogRepository).findFiltered(null, null, null, null, pageable);
    }

    @Test
    void query_withActionTypeFilter_passesEnumToRepository() {
        Pageable pageable = PageRequest.of(0, 10);
        when(auditLogRepository.findFiltered(AuditActionType.PERMISSIONS_CHANGED, null, null, null, pageable))
                .thenReturn(Page.empty());

        auditLogService.query(AuditActionType.PERMISSIONS_CHANGED, null, null, null, pageable);

        verify(auditLogRepository).findFiltered(AuditActionType.PERMISSIONS_CHANGED, null, null, null, pageable);
    }

    @Test
    void query_withDateRange_passesFromAndToToRepository() {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 12, 31, 23, 59);
        Pageable pageable = PageRequest.of(0, 10);
        when(auditLogRepository.findFiltered(null, null, from, to, pageable))
                .thenReturn(Page.empty());

        auditLogService.query(null, null, from, to, pageable);

        verify(auditLogRepository).findFiltered(null, null, from, to, pageable);
    }

    @Test
    void query_mapsEntityToDtoCorrectly() {
        AuditLog log = AuditLog.builder()
                .id(1L)
                .actorId(10L)
                .actorType("EMPLOYEE")
                .actionType(AuditActionType.PAYMENT_CREATED)
                .description("test")
                .targetType("PAYMENT")
                .targetId(42L)
                .oldValue("PENDING")
                .newValue("APPROVED")
                .createdAt(LocalDateTime.of(2026, 3, 1, 12, 0))
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        when(auditLogRepository.findFiltered(null, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(log)));
        when(employeeRepository.findById(10L)).thenReturn(Optional.empty());

        Page<AuditLogDto> result = auditLogService.query(null, null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        AuditLogDto dto = result.getContent().get(0);
        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getActorId()).isEqualTo(10L);
        assertThat(dto.getActionType()).isEqualTo("PAYMENT_CREATED");
        assertThat(dto.getTargetType()).isEqualTo("PAYMENT");
        assertThat(dto.getTargetId()).isEqualTo(42L);
        assertThat(dto.getOldValue()).isEqualTo("PENDING");
        assertThat(dto.getNewValue()).isEqualTo("APPROVED");
    }

    @Test
    void query_actorNameLookup_employee() {
        Employee emp = Employee.builder()
                .id(5L)
                .firstName("Stasa")
                .lastName("Draskovic")
                .build();

        AuditLog log = AuditLog.builder()
                .id(2L)
                .actorId(5L)
                .actorType("EMPLOYEE")
                .actionType(AuditActionType.PERMISSIONS_CHANGED)
                .description("perm change")
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        when(auditLogRepository.findFiltered(null, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(log)));
        when(employeeRepository.findById(5L)).thenReturn(Optional.of(emp));

        Page<AuditLogDto> result = auditLogService.query(null, null, null, null, pageable);

        assertThat(result.getContent().get(0).getActorName()).isEqualTo("Stasa Draskovic");
    }

    @Test
    void query_actorNameLookup_client_R1392() {
        // R1 392: pre fix-a CLIENT aktor je uvek vracao "ID:42" (resolveActorName
        // gledao samo EMPLOYEE tabelu). Sad resolvuje ime/prezime iz CLIENT tabele.
        Client client = new Client();
        client.setId(42L);
        client.setFirstName("Stefan");
        client.setLastName("Jovanovic");

        AuditLog log = AuditLog.builder()
                .id(3L)
                .actorId(42L)
                .actorType("CLIENT")
                .actionType(AuditActionType.CARD_BLOCKED)
                .description("blocked")
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        when(auditLogRepository.findFiltered(null, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(log)));
        when(clientRepository.findById(42L)).thenReturn(Optional.of(client));

        Page<AuditLogDto> result = auditLogService.query(null, null, null, null, pageable);

        assertThat(result.getContent().get(0).getActorName()).isEqualTo("Stefan Jovanovic");
    }

    @Test
    void record_descriptionOver512_truncatedNotRejected_R1395() {
        // R1 395: description kolona je length=512 NOT NULL — neskracen tekst >512
        // baca DataIntegrityViolation i obara ceo audit upis. Sad se skraca na 512.
        String longDesc = "x".repeat(600);

        auditLogService.record(1L, "EMPLOYEE", AuditActionType.PAYMENT_CREATED,
                longDesc, "PAYMENT", 7L, null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        String saved = captor.getValue().getDescription();
        assertThat(saved.length()).isEqualTo(512);
        assertThat(saved).endsWith("...");
    }

    @Test
    void record_descriptionExactly512_notTruncated_R1395() {
        String desc512 = "y".repeat(512);

        auditLogService.record(1L, "EMPLOYEE", AuditActionType.PAYMENT_CREATED,
                desc512, "PAYMENT", 8L, null, null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        assertThat(captor.getValue().getDescription()).isEqualTo(desc512);
    }

    @Test
    void findByResource_returnsAllRecordsForTarget() {
        AuditLog log1 = AuditLog.builder().id(1L).actorId(1L).actorType("EMPLOYEE")
                .actionType(AuditActionType.PAYMENT_CREATED).description("a").build();
        AuditLog log2 = AuditLog.builder().id(2L).actorId(1L).actorType("EMPLOYEE")
                .actionType(AuditActionType.PAYMENT_ABORTED).description("b").build();

        when(auditLogRepository.findByTargetTypeAndTargetId("PAYMENT", 42L))
                .thenReturn(List.of(log1, log2));
        when(employeeRepository.findById(any())).thenReturn(Optional.empty());

        List<AuditLogDto> result = auditLogService.findByResource("PAYMENT", 42L);

        assertThat(result).hasSize(2);
    }
}
