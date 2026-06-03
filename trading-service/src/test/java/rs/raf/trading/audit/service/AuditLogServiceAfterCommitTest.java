package rs.raf.trading.audit.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.model.AuditLog;
import rs.raf.trading.audit.repository.AuditLogRepository;
import rs.raf.trading.client.BankaCoreClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P2-audit-coverage-1 (R4 1780 + R1 394): verifikuje afterCommit + swallow dizajn za
 * trading audit hook-ove.
 *
 * <p>{@code recordAfterCommit} registruje {@link TransactionSynchronization} kad je tx
 * aktivna; audit upis se desava SAMO ako outer tx commit-uje (afterCommit), i pad
 * audit-a NE propagira (best-effort). Bez aktivne tx pise odmah.</p>
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceAfterCommitTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private BankaCoreClient bankaCoreClient;

    @InjectMocks
    private AuditLogService auditLogService;

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private List<TransactionSynchronization> registeredSyncs() {
        return TransactionSynchronizationManager.getSynchronizations();
    }

    @Test
    void recordAfterCommit_noActiveTx_writesImmediately() {
        // self-proxy je null u unit-u → safeRecord pada na this.record (i dalje persistuje).
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        auditLogService.recordAfterCommit(7L, "EMPLOYEE", AuditActionType.ORDER_APPROVED,
                "Order approved: 1", "ORDER", 1L);

        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    void recordAfterCommit_activeTx_doesNotWriteBeforeCommit_thenWritesOnCommit() {
        // Self-proxy referenca → da afterCommit poziva record kroz "proxy".
        ReflectionTestUtils.setField(auditLogService, "self", auditLogService);
        TransactionSynchronizationManager.initSynchronization();
        try {
            auditLogService.recordAfterCommit(7L, "EMPLOYEE", AuditActionType.ORDER_DECLINED,
                    "Order declined: 2", "ORDER", 2L);

            // PRE commit-a: nista nije upisano (nema phantom audit), ali je sync registrovan.
            verify(auditLogRepository, never()).save(any(AuditLog.class));
            assertThat(registeredSyncs()).hasSize(1);

            // Simuliraj uspesan commit → afterCommit pise audit.
            registeredSyncs().forEach(TransactionSynchronization::afterCommit);

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getActionType()).isEqualTo(AuditActionType.ORDER_DECLINED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recordAfterCommit_activeTx_rollbackPath_doesNotWriteAudit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            auditLogService.recordAfterCommit(7L, "EMPLOYEE", AuditActionType.ORDER_APPROVED,
                    "Order approved: 3", "ORDER", 3L);

            // Rollback path: afterCommit se NIKAD ne poziva (samo afterCompletion sa STATUS_ROLLED_BACK).
            registeredSyncs().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            // Phantom audit fix: na rollback NEMA audit upisa.
            verify(auditLogRepository, never()).save(any(AuditLog.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void record_descriptionOver512_truncatedNotRejected_R1_395() {
        String longDesc = "z".repeat(700);

        auditLogService.record(7L, "EMPLOYEE", AuditActionType.ORDER_APPROVED,
                longDesc, "ORDER", 5L);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getDescription().length()).isEqualTo(512);
        assertThat(captor.getValue().getDescription()).endsWith("...");
    }

    @Test
    void recordAfterCommit_auditSaveThrows_doesNotPropagate_R1_394() {
        ReflectionTestUtils.setField(auditLogService, "self", auditLogService);
        doThrow(new RuntimeException("DB constraint")).when(auditLogRepository).save(any(AuditLog.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            auditLogService.recordAfterCommit(7L, "EMPLOYEE", AuditActionType.ORDER_APPROVED,
                    "Order approved: 4", "ORDER", 4L);

            // Pad audit-a u afterCommit NE sme da propagira (poslovna operacija je vec gotova).
            assertThatCode(() -> registeredSyncs().forEach(TransactionSynchronization::afterCommit))
                    .doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
