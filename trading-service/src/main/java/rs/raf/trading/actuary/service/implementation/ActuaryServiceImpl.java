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
        // R1-186: genuini "ne postoji" → EntityNotFoundException (404), ne IAE.
        ActuaryInfo info = actuaryInfoRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new EntityNotFoundException(
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

        // R1-186: ciljani zaposleni ne postoji kao aktuar → 404 (EntityNotFound),
        // ne 400/404-IAE. Validaciona greska (below-used-limit) i dalje 400-IAE.
        ActuaryInfo targetUserInfo = actuaryInfoRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("User does not exist or isn't an actuary."));

        if(targetUserInfo.getActuaryType() != ActuaryType.AGENT) {
            // R1-744: pre je bio bare RuntimeException → fall-through na globalni handler
            // (HTTP 500). Ovo je VALIDACIONA greska pozivaoca (cilj je supervizor, ne
            // agent), pa domenski tip {@link IllegalArgumentException} mapira na 400
            // (ActuaryExceptionHandler), paritet sa below-used-limit validacijom.
            throw new IllegalArgumentException("Limits can only be updated for agents.");
        }

        // R2-1357: supervizorov save dailyLimit-a/needApproval-a moze da se preplete
        // sa konkurentnim order-increment-om usedLimit-a (OrderServiceImpl
        // mutateActuaryWithRetry). Bez retry-ja, @Version-protected save puca
        // OptimisticLockingFailureException ILI (bez svezeg read-a) pregazi
        // konkurentni increment (lost update). Retry: re-citaj svez red, re-validiraj
        // below-used-limit (usedLimit se mogao promeniti), pa re-apply + save.
        SavedLimit saved = updateAgentLimitWithRetry(employeeId, targetUserInfo, dto);

        // B7 audit hook (port iz main PR #86): actorId iz trenutnog autentifikovanog supervizora.
        // R4 1780 + R1 394 (P2-audit-coverage-1): afterCommit + best-effort. Audit se pise
        // SAMO ako updateAgentLimit tx commit-uje, i pad audit-a NE obara izmenu limita.
        // (old/new vrednosti su u opisu — recordAfterCommit nosi single description string.)
        auditLogService.recordAfterCommit(
                currentUserInfo.getEmployeeId(), "EMPLOYEE", AuditActionType.LIMIT_CHANGED,
                "Agent limit updated for employee " + employeeId
                        + " (old: dailyLimit=" + saved.getOldLimitAudit()
                        + ",needApproval=" + saved.getOldNeedApprovalAudit()
                        + " -> new: dailyLimit=" + saved.getInfo().getDailyLimit()
                        + ",needApproval=" + saved.getInfo().isNeedApproval() + ")",
                "ACTUARY", employeeId
        );

        ActuaryInfoDto response = ActuaryMapper.toDto(saved.getInfo(), resolveEmployee(saved.getInfo().getEmployeeId()));
        return response;
    }

    /** Max broj pokusaja za optimistic-lock retry pri updateAgentLimit. */
    private static final int LIMIT_UPDATE_MAX_RETRIES = 3;

    /**
     * R2-1357: primenjuje supervizorovu izmenu (dailyLimit/needApproval) sa
     * optimistic-lock retry-em. Na {@link org.springframework.dao.OptimisticLockingFailureException}
     * re-citamo svez red (svez usedLimit) i ponavljamo — tako konkurentni
     * order-increment ne biva pregazen, a below-used-limit validacija koristi
     * najsveziji usedLimit. Validaciona greska (below-used-limit) NE retry-uje se.
     */
    private SavedLimit updateAgentLimitWithRetry(Long employeeId, ActuaryInfo initial, UpdateActuaryLimitDto dto) {
        ActuaryInfo target = initial;
        org.springframework.dao.OptimisticLockingFailureException last = null;
        for (int attempt = 0; attempt < LIMIT_UPDATE_MAX_RETRIES; attempt++) {
            BigDecimal oldLimit = target.getDailyLimit();
            Boolean oldNeedApproval = target.isNeedApproval();

            // P2-9 (Celina 3 S3): novi dnevni limit ne sme biti ispod vec
            // potrosenog (usedLimit) iznosa agenta — IllegalArgumentException -> 400.
            if (dto.getDailyLimit() != null) {
                BigDecimal used = target.getUsedLimit() != null ? target.getUsedLimit() : BigDecimal.ZERO;
                if (dto.getDailyLimit().compareTo(used) < 0) {
                    throw new IllegalArgumentException(
                            "New daily limit (" + dto.getDailyLimit()
                                    + ") cannot be below already used limit (" + used + ").");
                }
            }

            target.setDailyLimit(dto.getDailyLimit() != null ? dto.getDailyLimit() : target.getDailyLimit());
            target.setNeedApproval(dto.getNeedApproval() != null ? dto.getNeedApproval() : target.isNeedApproval());

            try {
                ActuaryInfo persisted = actuaryInfoRepository.saveAndFlush(target);
                return new SavedLimit(persisted, oldLimit, oldNeedApproval);
            } catch (org.springframework.dao.OptimisticLockingFailureException ole) {
                last = ole;
                // Re-citaj svez red (svez usedLimit + version) i ponovi.
                target = actuaryInfoRepository.findByEmployeeId(employeeId)
                        .orElseThrow(() -> new EntityNotFoundException(
                                "User does not exist or isn't an actuary."));
            }
        }
        throw last != null ? last
                : new IllegalStateException("Failed to update agent limit after retries.");
    }

    /** Nosi perzistovan ActuaryInfo + stare vrednosti za audit log. */
    private static final class SavedLimit {
        private final ActuaryInfo info;
        private final BigDecimal oldLimitAudit;
        private final Boolean oldNeedApprovalAudit;

        SavedLimit(ActuaryInfo info, BigDecimal oldLimitAudit, Boolean oldNeedApprovalAudit) {
            this.info = info;
            this.oldLimitAudit = oldLimitAudit;
            this.oldNeedApprovalAudit = oldNeedApprovalAudit;
        }

        ActuaryInfo getInfo() { return info; }
        BigDecimal getOldLimitAudit() { return oldLimitAudit; }
        Boolean getOldNeedApprovalAudit() { return oldNeedApprovalAudit; }
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

        // R4 1780 + R1 394 (P2-audit-coverage-1): afterCommit + best-effort. (old/new
        // u opisu — recordAfterCommit nosi jedan description string.)
        auditLogService.recordAfterCommit(
                actorId, actorType, AuditActionType.USED_LIMIT_RESET,
                "Used limit reset for agent " + employeeId + " (oldUsed=" + oldUsed + " -> 0)",
                "ACTUARY", employeeId
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
