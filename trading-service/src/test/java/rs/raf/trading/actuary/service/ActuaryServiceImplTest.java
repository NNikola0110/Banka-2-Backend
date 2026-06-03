package rs.raf.trading.actuary.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.actuary.dto.ActuaryInfoDto;
import rs.raf.trading.actuary.dto.UpdateActuaryLimitDto;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.actuary.service.implementation.ActuaryServiceImpl;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * NAPOMENA (copy-first ekstrakcija, faza 2c): monolitni test je mockovao
 * {@code findByEmployee_Email} za razresavanje trenutnog korisnika i gradio
 * {@code ActuaryInfo.setEmployee(Employee)}. Ovde:
 *  - {@code ActuaryInfo} se gradi sa {@code setEmployeeId(Long)} (soft id);
 *  - trenutni korisnik se razresava preko {@link TradingUserResolver};
 *  - identitet zaposlenog (ime/email za DTO) se razresava preko
 *    {@link BankaCoreClient#getUserById}, filtriranje agenata preko
 *    {@link BankaCoreClient#findEmployees}.
 * Lenient strictness — neki testovi (greske pre razresavanja imena) ne dotaknu
 * sve stub-ove.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActuaryServiceImplTest {

    @Mock
    private ActuaryInfoRepository actuaryInfoRepository;

    @Mock
    private BankaCoreClient bankaCoreClient;

    @Mock
    private TradingUserResolver userResolver;

    @Mock
    private rs.raf.trading.audit.service.AuditLogService auditLogService;

    @InjectMocks
    private ActuaryServiceImpl actuaryService;

    private final Long RESET_EMPLOYEE_ID = 1L;

    // ──────────────────────────────────────────────────────────────────
    //  Helperi za kreiranje test podataka
    // ──────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        // R2-1357: updateAgentLimit sada koristi saveAndFlush (da OLE iskoci
        // sinhrono za retry). Globalni lenient stub vraca prosledjeni entitet —
        // success-path testovi verifikuju saveAndFlush, validation-fail testovi
        // verifikuju never().save()/saveAndFlush().
        org.mockito.Mockito.lenient()
                .when(actuaryInfoRepository.saveAndFlush(any(ActuaryInfo.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private InternalUserDto employee(Long id, String firstName, String lastName, String email) {
        return new InternalUserDto(id, "EMPLOYEE", email, firstName, lastName, true, "Agent");
    }

    private ActuaryInfo createAgentInfo(Long id, Long employeeId,
                                        BigDecimal dailyLimit, BigDecimal usedLimit,
                                        boolean needApproval) {
        ActuaryInfo info = new ActuaryInfo();
        info.setId(id);
        info.setEmployeeId(employeeId);
        info.setActuaryType(ActuaryType.AGENT);
        info.setDailyLimit(dailyLimit);
        info.setUsedLimit(usedLimit);
        info.setNeedApproval(needApproval);
        return info;
    }

    private ActuaryInfo createSupervisorInfo(Long id, Long employeeId) {
        ActuaryInfo info = new ActuaryInfo();
        info.setId(id);
        info.setEmployeeId(employeeId);
        info.setActuaryType(ActuaryType.SUPERVISOR);
        info.setDailyLimit(null);
        info.setUsedLimit(null);
        info.setNeedApproval(false);
        return info;
    }

    /** Stubuje resolver da vrati EMPLOYEE identitet sa datim employeeId-em. */
    private void setAuthenticatedEmployee(Long employeeId) {
        when(userResolver.resolveCurrent()).thenReturn(new UserContext(employeeId, "EMPLOYEE"));
    }

    // ══════════════════════════════════════════════════════════════════
    //  getAgents
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAgents")
    class GetAgents {

        @Test
        @DisplayName("vraca sve agente bez filtera")
        void returnsAllAgents() {
            ActuaryInfo agent1 = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);
            ActuaryInfo agent2 = createAgentInfo(2L, 11L,
                    new BigDecimal("50000"), BigDecimal.ZERO, true);

            when(actuaryInfoRepository.findAllByActuaryType(ActuaryType.AGENT))
                    .thenReturn(List.of(agent1, agent2));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko.markovic@banka.rs"));
            when(bankaCoreClient.getUserById("EMPLOYEE", 11L))
                    .thenReturn(employee(11L, "Jelena", "Jovanovic", "jelena.jovanovic@banka.rs"));

            List<ActuaryInfoDto> result = actuaryService.getAgents(null, null, null, null);

            assertEquals(2, result.size());
            assertEquals("Marko Markovic", result.get(0).getEmployeeName());
            assertEquals("Jelena Jovanovic", result.get(1).getEmployeeName());
            // bez filtera ne ide na banka-core findEmployees
            verify(bankaCoreClient, never()).findEmployees(any(), any(), any(), any());
        }

        @Test
        @DisplayName("vraca praznu listu ako nema agenata")
        void returnsEmptyList() {
            when(actuaryInfoRepository.findAllByActuaryType(ActuaryType.AGENT))
                    .thenReturn(Collections.emptyList());

            List<ActuaryInfoDto> result = actuaryService.getAgents(null, null, null, null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("sa filterom razresava employeeId-eve preko banka-core i suzava agente")
        void filtersByEmployeeAttributesViaBankaCore() {
            // banka-core vraca zaposlene koji odgovaraju filteru po email-u
            when(bankaCoreClient.findEmployees(null, null, "marko", null))
                    .thenReturn(List.of(employee(10L, "Marko", "Markovic", "marko.markovic@banka.rs")));

            ActuaryInfo agent1 = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);
            when(actuaryInfoRepository.findByActuaryTypeAndEmployeeIdIn(ActuaryType.AGENT, List.of(10L)))
                    .thenReturn(List.of(agent1));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko.markovic@banka.rs"));

            List<ActuaryInfoDto> result = actuaryService.getAgents("marko", null, null, null);

            assertEquals(1, result.size());
            assertEquals("Marko Markovic", result.get(0).getEmployeeName());
            verify(actuaryInfoRepository, never()).findAllByActuaryType(any());
        }

        @Test
        @DisplayName("sa filterom koji nema pogodaka u banka-core vraca praznu listu")
        void filterWithNoMatchingEmployeesReturnsEmpty() {
            when(bankaCoreClient.findEmployees(null, null, "nepostoji", null))
                    .thenReturn(Collections.emptyList());

            List<ActuaryInfoDto> result = actuaryService.getAgents("nepostoji", null, null, null);

            assertTrue(result.isEmpty());
            verify(actuaryInfoRepository, never()).findByActuaryTypeAndEmployeeIdIn(any(), any());
        }
    }

    @Nested
    @DisplayName("getActuaryInfo")
    class GetActuaryInfo {

        @Test
        @DisplayName("vraca aktuarske podatke za postojeceg zaposlenog")
        void returnsActuaryInfo() {
            ActuaryInfo agentInfo = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);

            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agentInfo));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko.markovic@banka.rs"));

            ActuaryInfoDto result = actuaryService.getActuaryInfo(10L);

            assertEquals(10L, result.getEmployeeId());
            assertEquals("AGENT", result.getActuaryType());
        }

        @Test
        @DisplayName("R1-186: baca EntityNotFound (404) ako zapis ne postoji")
        void notFound() {
            when(actuaryInfoRepository.findByEmployeeId(999L)).thenReturn(Optional.empty());

            jakarta.persistence.EntityNotFoundException ex = assertThrows(
                    jakarta.persistence.EntityNotFoundException.class,
                    () -> actuaryService.getActuaryInfo(999L));

            assertTrue(ex.getMessage().contains("999"));
        }

        @Test
        @DisplayName("DTO se popunjava i kada banka-core ne razresi zaposlenog")
        void resilientWhenEmployeeLookupFails() {
            ActuaryInfo agentInfo = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);

            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agentInfo));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenThrow(new RuntimeException("banka-core down"));

            ActuaryInfoDto result = actuaryService.getActuaryInfo(10L);

            assertEquals(10L, result.getEmployeeId());
            assertNull(result.getEmployeeName());
        }
    }

    @Nested
    @DisplayName("updateAgentLimit")
    class UpdateAgentLimit {

        @Test
        @DisplayName("supervizor moze da promeni dailyLimit i needApproval")
        void supervisorCanUpdateAgent() {
            setAuthenticatedEmployee(20L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(5L, 20L);
            ActuaryInfo agentInfo = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("250000"));
            dto.setNeedApproval(true);

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agentInfo));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko@banka.rs"));

            ActuaryInfoDto result = actuaryService.updateAgentLimit(10L, dto);

            assertEquals(new BigDecimal("250000"), result.getDailyLimit());
            assertTrue(result.isNeedApproval());
            assertEquals(new BigDecimal("15000"), result.getUsedLimit());
            verify(actuaryInfoRepository).saveAndFlush(agentInfo);
        }

        @Test
        @DisplayName("supervizor zaposleni moze da menja samo prosledjena polja")
        void supervisorEmployeeCanPartiallyUpdateAgent() {
            setAuthenticatedEmployee(20L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(5L, 20L);
            ActuaryInfo agentInfo = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setNeedApproval(true);

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agentInfo));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko@banka.rs"));

            ActuaryInfoDto result = actuaryService.updateAgentLimit(10L, dto);

            assertEquals(new BigDecimal("100000"), result.getDailyLimit());
            assertTrue(result.isNeedApproval());
            verify(actuaryInfoRepository).saveAndFlush(agentInfo);
        }

        @Test
        @DisplayName("agent ne sme da menja tudje limite")
        void agentCannotUpdateAgentLimit() {
            setAuthenticatedEmployee(20L);

            ActuaryInfo agentOwnInfo = createAgentInfo(5L, 20L,
                    new BigDecimal("50000"), new BigDecimal("1000"), false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("1"));

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(agentOwnInfo));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> actuaryService.updateAgentLimit(10L, dto));

            assertEquals("Only supervisors can update agent limits.", ex.getMessage());
            verify(actuaryInfoRepository, never()).save(any());
        }

        @Test
        @DisplayName("R1-744: cilj nije agent (supervizor) → IllegalArgumentException (400), ne bare RuntimeException (500)")
        void cannotUpdateSupervisor() {
            setAuthenticatedEmployee(20L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(5L, 20L);
            ActuaryInfo targetSupervisorInfo = createSupervisorInfo(6L, 30L);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("999"));

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(30L)).thenReturn(Optional.of(targetSupervisorInfo));

            // R1-744: domenski tip je IllegalArgumentException (mapira na 400 u
            // ActuaryExceptionHandler), a NE goli RuntimeException (koji bi pao na
            // globalni handler → 500). assertThrows na konkretan tip pinuje to.
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> actuaryService.updateAgentLimit(30L, dto));

            assertTrue(ex.getMessage().contains("only be updated for agents"));
            verify(actuaryInfoRepository, never()).save(any());
        }

        @Test
        @DisplayName("ako nema autentifikacije baca exception")
        void unauthenticatedUpdateFails() {
            when(userResolver.resolveCurrent())
                    .thenThrow(new IllegalStateException("Nema autentifikovanog korisnika u security context-u"));

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setNeedApproval(true);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> actuaryService.updateAgentLimit(10L, dto));

            assertTrue(ex.getMessage().contains("autentifikovanog korisnika"));
        }

        // ── P2-9 (Celina 3 S3): novi limit ne sme biti ispod usedLimit ─────

        @Test
        @DisplayName("P2-9 S3: odbija novi dailyLimit ispod vec potrosenog usedLimit-a (400)")
        void rejectsNewLimitBelowUsedLimit() {
            setAuthenticatedEmployee(20L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(5L, 20L);
            // agent je vec potrosio 15000 od limita
            ActuaryInfo agentInfo = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("10000")); // ispod usedLimit=15000

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agentInfo));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> actuaryService.updateAgentLimit(10L, dto));

            assertTrue(ex.getMessage().contains("below already used limit"));
            verify(actuaryInfoRepository, never()).save(any());
        }

        @Test
        @DisplayName("P2-9 S3: prihvata novi dailyLimit tacno jednak usedLimit-u")
        void acceptsNewLimitEqualToUsedLimit() {
            setAuthenticatedEmployee(20L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(5L, 20L);
            ActuaryInfo agentInfo = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("15000")); // == usedLimit

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agentInfo));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko@banka.rs"));

            ActuaryInfoDto result = actuaryService.updateAgentLimit(10L, dto);

            assertEquals(new BigDecimal("15000"), result.getDailyLimit());
            verify(actuaryInfoRepository).saveAndFlush(agentInfo);
        }

        @Test
        @DisplayName("P2-9 S3: prihvata novi dailyLimit iznad usedLimit-a")
        void acceptsNewLimitAboveUsedLimit() {
            setAuthenticatedEmployee(20L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(5L, 20L);
            ActuaryInfo agentInfo = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("20000")); // iznad usedLimit

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agentInfo));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko@banka.rs"));

            ActuaryInfoDto result = actuaryService.updateAgentLimit(10L, dto);

            assertEquals(new BigDecimal("20000"), result.getDailyLimit());
            verify(actuaryInfoRepository).saveAndFlush(agentInfo);
        }

        @Test
        @DisplayName("R2-1357: optimistic-lock konflikt → retry sa svezim usedLimit-om")
        void retriesOnOptimisticLockConflict() {
            setAuthenticatedEmployee(20L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(5L, 20L);
            // prvi read: usedLimit=15000
            ActuaryInfo firstRead = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);
            // posle konkurentnog increment-a: usedLimit=18000 (svez red)
            ActuaryInfo secondRead = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("18000"), false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("250000"));

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisorInfo));
            // initial lookup (u updateAgentLimit) → firstRead; retry re-read → secondRead
            when(actuaryInfoRepository.findByEmployeeId(10L))
                    .thenReturn(Optional.of(firstRead))
                    .thenReturn(Optional.of(secondRead));
            // prvi saveAndFlush puca OLE, drugi prolazi
            when(actuaryInfoRepository.saveAndFlush(any(ActuaryInfo.class)))
                    .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ActuaryInfo.class, 10L))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko@banka.rs"));

            ActuaryInfoDto result = actuaryService.updateAgentLimit(10L, dto);

            assertEquals(new BigDecimal("250000"), result.getDailyLimit());
            // posle retry-ja persistovan je svez red (usedLimit=18000 ocuvan).
            assertEquals(new BigDecimal("18000"), result.getUsedLimit());
            verify(actuaryInfoRepository, times(2)).saveAndFlush(any(ActuaryInfo.class));
        }

        @Test
        @DisplayName("R2-1357: konkurentni increment digao usedLimit iznad novog limita → 400 na retry-u")
        void retryRevalidatesBelowUsedLimit() {
            setAuthenticatedEmployee(20L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(5L, 20L);
            // prvi read: usedLimit=15000 (novi limit 20000 je iznad → prosao bi)
            ActuaryInfo firstRead = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000"), false);
            // svez red posle konkurentnog increment-a: usedLimit=25000 (iznad novog limita)
            ActuaryInfo secondRead = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("25000"), false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("20000"));

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(10L))
                    .thenReturn(Optional.of(firstRead))
                    .thenReturn(Optional.of(secondRead));
            when(actuaryInfoRepository.saveAndFlush(any(ActuaryInfo.class)))
                    .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(ActuaryInfo.class, 10L));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> actuaryService.updateAgentLimit(10L, dto));

            assertTrue(ex.getMessage().contains("below already used limit"));
        }

        @Test
        @DisplayName("P2-9 S3: tretira null usedLimit kao 0 (bilo koji pozitivan limit prolazi)")
        void treatsNullUsedLimitAsZero() {
            setAuthenticatedEmployee(20L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(5L, 20L);
            ActuaryInfo agentInfo = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), null, false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("1"));

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agentInfo));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko@banka.rs"));

            ActuaryInfoDto result = actuaryService.updateAgentLimit(10L, dto);

            assertEquals(new BigDecimal("1"), result.getDailyLimit());
        }
    }

    @Nested
    @DisplayName("resetUsedLimit")
    class ResetUsedLimit {

        @Test
        @DisplayName("moze rucno da resetuje usedLimit agenta")
        void adminCanResetAgentLimit() {
            ActuaryInfo agentInfo = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000.50"), false);

            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agentInfo));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko@banka.rs"));

            ActuaryInfoDto result = actuaryService.resetUsedLimit(10L);

            assertEquals(BigDecimal.ZERO, result.getUsedLimit());
            verify(actuaryInfoRepository).save(agentInfo);
        }

        @Test
        @DisplayName("ne moze da resetuje supervizora")
        void cannotResetSupervisor() {
            ActuaryInfo supervisorInfo = createSupervisorInfo(5L, 20L);

            when(actuaryInfoRepository.findByEmployeeId(20L)).thenReturn(Optional.of(supervisorInfo));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> actuaryService.resetUsedLimit(20L));

            assertTrue(ex.getMessage().contains("only allowed for Agents"));
            verify(actuaryInfoRepository, never()).save(any());
        }

        @Test
        @DisplayName("reset je idempotentan kada je usedLimit vec nula")
        void resetIsIdempotentWhenAlreadyZero() {
            ActuaryInfo agentInfo = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), BigDecimal.ZERO, false);

            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agentInfo));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(inv -> inv.getArgument(0));
            when(bankaCoreClient.getUserById("EMPLOYEE", 10L))
                    .thenReturn(employee(10L, "Marko", "Markovic", "marko@banka.rs"));

            ActuaryInfoDto result = actuaryService.resetUsedLimit(10L);

            assertEquals(BigDecimal.ZERO, result.getUsedLimit());
            verify(actuaryInfoRepository).save(agentInfo);
        }
    }

    @Nested
    @DisplayName("resetAllUsedLimits")
    class ResetAllUsedLimits {

        @Test
        @DisplayName("resetuje usedLimit na 0 za sve agente")
        void resetsAllAgentsToZero() {
            ActuaryInfo agent1 = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), new BigDecimal("15000.50"), false);
            ActuaryInfo agent2 = createAgentInfo(2L, 11L,
                    new BigDecimal("50000"), new BigDecimal("999.99"), true);

            when(actuaryInfoRepository.findAllByActuaryType(ActuaryType.AGENT))
                    .thenReturn(List.of(agent1, agent2));
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agent1));
            when(actuaryInfoRepository.findByEmployeeId(11L)).thenReturn(Optional.of(agent2));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(inv -> inv.getArgument(0));

            actuaryService.resetAllUsedLimits();

            assertEquals(BigDecimal.ZERO, agent1.getUsedLimit());
            assertEquals(BigDecimal.ZERO, agent2.getUsedLimit());
            assertEquals(new BigDecimal("100000"), agent1.getDailyLimit());
            assertTrue(agent2.isNeedApproval());

            verify(actuaryInfoRepository).save(agent1);
            verify(actuaryInfoRepository).save(agent2);
        }

        @Test
        @DisplayName("ako nema agenata ne baca exception")
        void doesNothingWhenNoAgentsExist() {
            when(actuaryInfoRepository.findAllByActuaryType(ActuaryType.AGENT))
                    .thenReturn(Collections.emptyList());

            assertDoesNotThrow(() -> actuaryService.resetAllUsedLimits());

            verify(actuaryInfoRepository).findAllByActuaryType(ActuaryType.AGENT);
            verify(actuaryInfoRepository, never()).save(any());
        }

        @Test
        @DisplayName("resetuje i null usedLimit na nulu")
        void resetsNullUsedLimitToZero() {
            ActuaryInfo agent = createAgentInfo(1L, 10L,
                    new BigDecimal("100000"), null, false);

            when(actuaryInfoRepository.findAllByActuaryType(ActuaryType.AGENT)).thenReturn(List.of(agent));
            when(actuaryInfoRepository.findByEmployeeId(10L)).thenReturn(Optional.of(agent));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(inv -> inv.getArgument(0));

            actuaryService.resetAllUsedLimits();

            assertEquals(BigDecimal.ZERO, agent.getUsedLimit());
            verify(actuaryInfoRepository).save(agent);
        }

        @Test
        @DisplayName("uvek trazi samo AGENT zapise iz repository-ja")
        void queriesOnlyAgentType() {
            when(actuaryInfoRepository.findAllByActuaryType(ActuaryType.AGENT))
                    .thenReturn(Collections.emptyList());

            actuaryService.resetAllUsedLimits();

            verify(actuaryInfoRepository).findAllByActuaryType(ActuaryType.AGENT);
            verify(actuaryInfoRepository, never()).findByEmployeeId(anyLong());
        }
    }

    @Nested
    @DisplayName("updateAgentLimit - supervisor flow")
    class UpdateAgentLimitSupervisorFlow {

        @Test
        @DisplayName("supervizor moze da azurira limit i needApproval za agenta")
        void updatesLimitAndNeedApproval() {
            setAuthenticatedEmployee(1L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(100L, 1L);
            ActuaryInfo agentInfo = createAgentInfo(200L, 2L,
                    new BigDecimal("100000.00"), new BigDecimal("1000.00"), false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("250000.00"));
            dto.setNeedApproval(true);

            when(actuaryInfoRepository.findByEmployeeId(1L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(agentInfo));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(bankaCoreClient.getUserById("EMPLOYEE", 2L))
                    .thenReturn(employee(2L, "Marko", "Markovic", "marko@banka.rs"));

            ActuaryInfoDto result = actuaryService.updateAgentLimit(2L, dto);

            assertEquals(new BigDecimal("250000.00"), result.getDailyLimit());
            assertTrue(result.isNeedApproval());
            assertEquals("AGENT", result.getActuaryType());
            verify(actuaryInfoRepository).saveAndFlush(agentInfo);
        }

        @Test
        @DisplayName("azurira samo prosledjeno polje, ostala ostaju nepromenjena")
        void updatesOnlyProvidedField() {
            setAuthenticatedEmployee(1L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(100L, 1L);
            ActuaryInfo agentInfo = createAgentInfo(201L, 2L,
                    new BigDecimal("50000.00"), BigDecimal.ZERO, false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(null);
            dto.setNeedApproval(true);

            when(actuaryInfoRepository.findByEmployeeId(1L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(agentInfo));
            when(actuaryInfoRepository.save(any(ActuaryInfo.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(bankaCoreClient.getUserById("EMPLOYEE", 2L))
                    .thenReturn(employee(2L, "Jelena", "Jovanovic", "jelena@banka.rs"));

            ActuaryInfoDto result = actuaryService.updateAgentLimit(2L, dto);

            assertEquals(new BigDecimal("50000.00"), result.getDailyLimit());
            assertTrue(result.isNeedApproval());
        }

        @Test
        @DisplayName("baca izuzetak kada nema autentifikacije")
        void throwsWhenNoAuthentication() {
            when(userResolver.resolveCurrent())
                    .thenThrow(new IllegalStateException("Nema autentifikovanog korisnika u security context-u"));

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("1"));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> actuaryService.updateAgentLimit(2L, dto));

            assertTrue(ex.getMessage().contains("autentifikovanog korisnika"));
            verifyNoInteractions(actuaryInfoRepository);
        }

        @Test
        @DisplayName("baca izuzetak kada ulogovani korisnik nije aktuar")
        void throwsWhenCurrentUserIsNotActuary() {
            setAuthenticatedEmployee(99L);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setNeedApproval(true);

            when(actuaryInfoRepository.findByEmployeeId(99L)).thenReturn(Optional.empty());

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> actuaryService.updateAgentLimit(2L, dto));

            assertTrue(ex.getMessage().contains("not an actuary"));
        }

        @Test
        @DisplayName("baca izuzetak kada autentifikovani korisnik nije zaposleni (CLIENT rola)")
        void throwsWhenCurrentUserIsNotEmployee() {
            when(userResolver.resolveCurrent()).thenReturn(new UserContext(7L, "CLIENT"));

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setNeedApproval(true);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> actuaryService.updateAgentLimit(2L, dto));

            assertTrue(ex.getMessage().contains("not an actuary"));
        }

        @Test
        @DisplayName("baca izuzetak kada aktuar nije supervizor")
        void throwsWhenCurrentUserIsNotSupervisor() {
            setAuthenticatedEmployee(1L);

            ActuaryInfo currentAgentInfo = createAgentInfo(300L, 1L,
                    new BigDecimal("10000.00"), BigDecimal.ZERO, false);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setNeedApproval(true);

            when(actuaryInfoRepository.findByEmployeeId(1L)).thenReturn(Optional.of(currentAgentInfo));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> actuaryService.updateAgentLimit(2L, dto));

            assertTrue(ex.getMessage().contains("Only supervisors can update agent limits"));
        }

        @Test
        @DisplayName("baca izuzetak kada supervizor pokusa da menja sebe")
        void throwsWhenSupervisorUpdatesSelf() {
            setAuthenticatedEmployee(77L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(700L, 77L);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("100"));

            when(actuaryInfoRepository.findByEmployeeId(77L)).thenReturn(Optional.of(supervisorInfo));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> actuaryService.updateAgentLimit(77L, dto));

            assertTrue(ex.getMessage().contains("Cannot change own actuary info"));
        }

        @Test
        @DisplayName("baca izuzetak kada ciljani zaposleni nije aktuar")
        void throwsWhenTargetDoesNotExist() {
            setAuthenticatedEmployee(1L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(100L, 1L);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("100000"));

            when(actuaryInfoRepository.findByEmployeeId(1L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(5L)).thenReturn(Optional.empty());

            // R1-186: ciljani ne postoji → EntityNotFound (404), ne IAE.
            jakarta.persistence.EntityNotFoundException ex = assertThrows(
                    jakarta.persistence.EntityNotFoundException.class,
                    () -> actuaryService.updateAgentLimit(5L, dto));

            assertTrue(ex.getMessage().contains("isn't an actuary"));
        }

        @Test
        @DisplayName("baca izuzetak kada cilj nije agent")
        void throwsWhenTargetIsNotAgent() {
            setAuthenticatedEmployee(1L);

            ActuaryInfo supervisorInfo = createSupervisorInfo(100L, 1L);
            ActuaryInfo secondSupervisorInfo = createSupervisorInfo(200L, 2L);

            UpdateActuaryLimitDto dto = new UpdateActuaryLimitDto();
            dto.setDailyLimit(new BigDecimal("90000"));

            when(actuaryInfoRepository.findByEmployeeId(1L)).thenReturn(Optional.of(supervisorInfo));
            when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(secondSupervisorInfo));

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> actuaryService.updateAgentLimit(2L, dto));

            assertTrue(ex.getMessage().contains("only be updated for agents"));
        }
    }
}
