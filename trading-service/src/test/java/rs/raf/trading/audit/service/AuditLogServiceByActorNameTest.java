package rs.raf.trading.audit.service;

import org.junit.jupiter.api.DisplayName;
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
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.audit.dto.AuditLogDto;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.model.AuditLog;
import rs.raf.trading.audit.repository.AuditLogRepository;
import rs.raf.trading.client.BankaCoreClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sc45 (TODO_testovi) — filter audita po IMENU aktera (supervizora). trading-service
 * nema Employee tabelu (zivi u banka-core), pa {@code queryByActorName} razresi ime
 * → skup {@code actorId}-eva preko {@link BankaCoreClient#findEmployees} i filtrira
 * audit po njima. Ovi testovi zakljucavaju to ponasanje:
 * <ul>
 *   <li>pun-ime tokenizacija (firstName/lastName) i filter po razresenim id-evima;</li>
 *   <li>jedna rec → dva lookup-a (firstName ILI lastName), unija id-eva;</li>
 *   <li>ime bez poklapanja → PRAZNA strana (NE ceo nefiltriran log);</li>
 *   <li>banka-core lookup pad → prazna strana (best-effort, ne 500).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceByActorNameTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private BankaCoreClient bankaCoreClient;

    @InjectMocks
    private AuditLogService auditLogService;

    private final Pageable pageable = PageRequest.of(0, 20);

    private InternalUserDto employee(long id, String first, String last) {
        return new InternalUserDto(id, "EMPLOYEE", first.toLowerCase() + "@banka.rs",
                first, last, true, "Supervizor");
    }

    private AuditLog limitChange(long actorId) {
        return AuditLog.builder()
                .id(actorId * 10)
                .actorId(actorId)
                .actorType("EMPLOYEE")
                .actionType(AuditActionType.LIMIT_CHANGED)
                .description("Agent limit updated")
                .targetType("EMPLOYEE")
                .targetId(99L)
                .oldValue("100000")
                .newValue("150000")
                .build();
    }

    @Test
    @DisplayName("Sc45 — pun ime (firstName lastName): split + filter po razresenim actorId-evima")
    void fullName_splitsAndFiltersByResolvedIds() {
        when(bankaCoreClient.findEmployees(eq("Nikola"), eq("Milenkovic"), isNull(), isNull()))
                .thenReturn(List.of(employee(5L, "Nikola", "Milenkovic")));
        when(auditLogRepository.findFilteredByActorIds(isNull(), eq(List.of(5L)),
                isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(limitChange(5L))));

        Page<AuditLogDto> result = auditLogService.queryByActorName(
                null, "Nikola Milenkovic", null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getActorId()).isEqualTo(5L);
        assertThat(result.getContent().get(0).getActionType()).isEqualTo("LIMIT_CHANGED");
        verify(auditLogRepository).findFilteredByActorIds(isNull(), eq(List.of(5L)),
                isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("Sc45 — jedna rec: dva lookup-a (firstName ILI lastName), unija id-eva bez duplikata")
    void singleToken_unionOfFirstAndLastNameMatches() {
        when(bankaCoreClient.findEmployees(eq("Milenkovic"), isNull(), isNull(), isNull()))
                .thenReturn(List.of()); // nema imena "Milenkovic"
        when(bankaCoreClient.findEmployees(isNull(), eq("Milenkovic"), isNull(), isNull()))
                .thenReturn(List.of(employee(5L, "Nikola", "Milenkovic")));
        when(auditLogRepository.findFilteredByActorIds(isNull(), eq(List.of(5L)),
                isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(limitChange(5L))));

        Page<AuditLogDto> result = auditLogService.queryByActorName(
                null, "Milenkovic", null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(auditLogRepository).findFilteredByActorIds(isNull(), idsCaptor.capture(),
                isNull(), isNull(), any(Pageable.class));
        assertThat(idsCaptor.getValue()).containsExactly(5L);
    }

    @Test
    @DisplayName("Sc45 — ime bez poklapanja -> PRAZNA strana (ne ceo nefiltriran log)")
    void noMatchingName_returnsEmptyPage_notWholeLog() {
        when(bankaCoreClient.findEmployees(eq("Nepostojeci"), eq("Korisnik"), isNull(), isNull()))
                .thenReturn(List.of());

        Page<AuditLogDto> result = auditLogService.queryByActorName(
                null, "Nepostojeci Korisnik", null, null, pageable);

        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
        // KLJUCNO: kad ime ne pripada nikome, NE smemo da pozovemo repo filter
        // (inace bi prazna IN-lista ili greska vratila tudje akcije).
        verify(auditLogRepository, never()).findFilteredByActorIds(any(), any(), any(), any(), any());
        verify(auditLogRepository, never()).findFiltered(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Sc45 — banka-core lookup pad -> prazna strana (best-effort, ne 500)")
    void lookupFailure_returnsEmptyPage() {
        when(bankaCoreClient.findEmployees(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("banka-core down"));

        Page<AuditLogDto> result = auditLogService.queryByActorName(
                null, "Nikola Milenkovic", null, null, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(auditLogRepository, never()).findFilteredByActorIds(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Sc44+Sc45 kombinovano — actionType filter se prosledjuje uz name filter")
    void actionTypeIsForwardedWithNameFilter() {
        when(bankaCoreClient.findEmployees(eq("Nikola"), eq("Milenkovic"), isNull(), isNull()))
                .thenReturn(List.of(employee(5L, "Nikola", "Milenkovic")));
        when(auditLogRepository.findFilteredByActorIds(eq(AuditActionType.LIMIT_CHANGED), eq(List.of(5L)),
                isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(limitChange(5L))));

        Page<AuditLogDto> result = auditLogService.queryByActorName(
                AuditActionType.LIMIT_CHANGED, "Nikola Milenkovic", null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(auditLogRepository).findFilteredByActorIds(eq(AuditActionType.LIMIT_CHANGED),
                eq(List.of(5L)), isNull(), isNull(), any(Pageable.class));
    }
}
