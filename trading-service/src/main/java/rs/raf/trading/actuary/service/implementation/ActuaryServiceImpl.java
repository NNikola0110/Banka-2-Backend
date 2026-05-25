package rs.raf.trading.actuary.service.implementation;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.actuary.dto.ActuaryInfoDto;
import rs.raf.trading.actuary.dto.UpdateActuaryLimitDto;
import rs.raf.trading.actuary.mapper.ActuaryMapper;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.actuary.service.ActuaryService;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.service.AuditLogService;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/*
 * B7 — Audit log integration points (port iz main PR #86, Stasa Dragovic).
 *
 * Audit hooks dodati u:
 *   - updateAgentLimit() -> AuditActionType.LIMIT_CHANGED (stari/novi dailyLimit + needApproval)
 *   - resetUsedLimit()   -> AuditActionType.USED_LIMIT_RESET (stari usedLimit -> 0)
 *
 * NAPOMENA (mikroservisi): koristimo trading-service lokalni AuditLogService
 * (rs.raf.trading.audit.*) — duplicirano iz banka-core jer je audit cross-cutting
 * i svaki servis pise u svoju bazu po servisu (CLAUDE.md 19.05.2026 2e).
 */

/**
 * NAPOMENA (copy-first ekstrakcija, faza 2c): monolitna implementacija je
 * citala podatke o zaposlenom direktno preko {@code ActuaryInfo.getEmployee()}
 * ({@code @OneToOne Employee} veza). U trading-service-u {@code ActuaryInfo}
 * drzi samo soft {@code employeeId}; identitet zaposlenog (ime/email) se
 * razresava preko {@code BankaCoreClient} ka banka-core internom API-ju, a
 * trenutni autentifikovani korisnik preko {@code TradingUserResolver}.
 */
@Service
@RequiredArgsConstructor
public class ActuaryServiceImpl implements ActuaryService {

    private final ActuaryInfoRepository actuaryInfoRepository;
    private final BankaCoreClient bankaCoreClient;
    private final TradingUserResolver userResolver;
    private final AuditLogService auditLogService;

    @Override
    public List<ActuaryInfoDto> getAgents(String email, String firstName, String lastName, String position) {
        List<ActuaryInfo> agents;
        if (allBlank(email, firstName, lastName, position)) {
            // Bez filtera — svi agenti.
            agents = actuaryInfoRepository.findAllByActuaryType(ActuaryType.AGENT);
        } else {
            // Sa filterima — banka-core filtrira zaposlene po atributima
            // (email/firstName/lastName/position), pa po razresenim id-evima
            // suzavamo aktuarske zapise.
            List<Long> employeeIds = bankaCoreClient.findEmployees(firstName, lastName, email, position)
                    .stream()
                    .map(InternalUserDto::userId)
                    .collect(Collectors.toList());
            if (employeeIds.isEmpty()) {
                return List.of();
            }
            agents = actuaryInfoRepository.findByActuaryTypeAndEmployeeIdIn(ActuaryType.AGENT, employeeIds);
        }
        return agents.stream()
                .map(a -> ActuaryMapper.toDto(a, resolveEmployee(a.getEmployeeId())))
                .collect(Collectors.toList());
    }

    @Override
    public ActuaryInfoDto getActuaryInfo(Long employeeId) {
        ActuaryInfo info = actuaryInfoRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Actuary info for employee with ID " + employeeId + " not found."
                ));

        return ActuaryMapper.toDto(info, resolveEmployee(info.getEmployeeId()));
    }


    @Override
    @Transactional
    public ActuaryInfoDto updateAgentLimit(Long employeeId, UpdateActuaryLimitDto dto) {

        Long currentEmployeeId = getAuthenticatedEmployeeId();
        ActuaryInfo currentUserInfo = actuaryInfoRepository.findByEmployeeId(currentEmployeeId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user is not an actuary."));

        if(currentUserInfo.getActuaryType() != ActuaryType.SUPERVISOR) {
           throw new IllegalStateException("Only supervisors can update agent limits.");
        }

        if(currentUserInfo.getEmployeeId().equals(employeeId)) {
            throw new IllegalStateException("Cannot change own actuary info.");
        }

        ActuaryInfo targetUserInfo = actuaryInfoRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("User does not exist or isn't an actuary."));

        if(targetUserInfo.getActuaryType() != ActuaryType.AGENT) {
            throw new RuntimeException("Limits can only be updated for agents.");
        }

        BigDecimal oldLimit = targetUserInfo.getDailyLimit();
        Boolean oldNeedApproval = targetUserInfo.isNeedApproval();

        targetUserInfo.setDailyLimit(dto.getDailyLimit() != null ? dto.getDailyLimit() : targetUserInfo.getDailyLimit());
        targetUserInfo.setNeedApproval(dto.getNeedApproval() != null ? dto.getNeedApproval() : targetUserInfo.isNeedApproval());

        actuaryInfoRepository.save(targetUserInfo);

        // B7 audit hook (port iz main PR #86): actorId iz trenutnog autentifikovanog supervizora
        auditLogService.record(
                currentUserInfo.getEmployeeId(), "EMPLOYEE", AuditActionType.LIMIT_CHANGED,
                "Agent limit updated for employee " + employeeId,
                "ACTUARY", employeeId,
                "dailyLimit=" + oldLimit + ",needApproval=" + oldNeedApproval,
                "dailyLimit=" + targetUserInfo.getDailyLimit() + ",needApproval=" + targetUserInfo.isNeedApproval()
        );

        ActuaryInfoDto response = ActuaryMapper.toDto(targetUserInfo, resolveEmployee(targetUserInfo.getEmployeeId()));
        return response;
    }


    @Override
    @Transactional
    public ActuaryInfoDto resetUsedLimit(Long employeeId) {

        ActuaryInfo actuary = actuaryInfoRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Actuary record not found for employee ID: " + employeeId));


        if (actuary.getActuaryType() != ActuaryType.AGENT) {
            throw new IllegalStateException("Reset is only allowed for Agents. Supervisors do not have limits.");
        }

        BigDecimal oldUsed = actuary.getUsedLimit();
        actuary.setUsedLimit(BigDecimal.ZERO);
        ActuaryInfo updatedActuary = actuaryInfoRepository.save(actuary);

        // B7 audit hook (port iz main PR #86): actorId iz current user-a (employee/scheduler).
        // U mikroservisnoj arhitekturi userResolver.resolveCurrent() radi samo unutar HTTP
        // request thread-a — za scheduler putanju (ActuaryLimitResetScheduler) fallback je
        // actorId=0 + actorType=SCHEDULER.
        Long actorId;
        String actorType;
        try {
            UserContext ctx = userResolver.resolveCurrent();
            if (ctx != null && UserRole.isEmployee(ctx.userRole())) {
                actorId = ctx.userId();
                actorType = "EMPLOYEE";
            } else {
                actorId = 0L;
                actorType = "SCHEDULER";
            }
        } catch (RuntimeException ignored) {
            actorId = 0L;
            actorType = "SCHEDULER";
        }

        auditLogService.record(
                actorId, actorType, AuditActionType.USED_LIMIT_RESET,
                "Used limit reset for agent " + employeeId,
                "ACTUARY", employeeId,
                String.valueOf(oldUsed),
                "0"
        );

        return ActuaryMapper.toDto(updatedActuary, resolveEmployee(updatedActuary.getEmployeeId()));
    }


    @Override
    @Transactional
    public void resetAllUsedLimits() {
        List<ActuaryInfo> agents = actuaryInfoRepository.findAllByActuaryType(ActuaryType.AGENT);

        for (ActuaryInfo agent : agents) {
            resetUsedLimit(agent.getEmployeeId());
        }
    }

    /**
     * Razresava {@code employeeId} trenutno autentifikovanog korisnika preko
     * {@link TradingUserResolver}. JWT (izdat od banka-core) nosi samo email;
     * resolver ga preslikava u numericki id zaposlenog.
     */
    private Long getAuthenticatedEmployeeId() {
        UserContext ctx = userResolver.resolveCurrent();
        if (!UserRole.isEmployee(ctx.userRole())) {
            throw new IllegalStateException("Authenticated user is not an actuary.");
        }
        return ctx.userId();
    }

    /**
     * Razresava identitet zaposlenog (za popunjavanje DTO-a). Na gresku vraca
     * {@code null} — DTO ostaje bez ime/email polja, mapper to tolerise.
     */
    private InternalUserDto resolveEmployee(Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        try {
            return bankaCoreClient.getUserById(UserRole.EMPLOYEE, employeeId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean allBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
