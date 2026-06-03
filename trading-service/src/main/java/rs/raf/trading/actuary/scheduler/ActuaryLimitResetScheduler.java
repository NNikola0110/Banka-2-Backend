package rs.raf.trading.actuary.scheduler;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.service.AuditLogService;

/**
 * Scheduler koji svakodnevno resetuje iskorisceni dnevni limit svih aktuara.
 *
 * Specifikacija: Celina 3 - Aktuari
 *
 * Pokrece se svaki dan u 23:59 i postavlja usedLimit na 0 za sve aktuare,
 * cime se priprema novi dnevni limit za sledeci dan.
 *
 * NAPOMENA (cutover 2f): @Scheduled je AKTIVAN — {@link rs.raf.trading.config.SchedulingConfig}
 * nosi @EnableScheduling gejtovano property-jem {@code trading.scheduling.enabled}
 * (default true). Posle gasenja monolitne kopije, trading-service je jedini koji
 * okida ovaj reset. Jedino je u test profilu uspavan
 * ({@code application-test.properties} postavlja {@code trading.scheduling.enabled=false}),
 * pa {@code @SpringBootTest} kontekst ne okida cron usred testa — scheduler testovi
 * pozivaju {@link #resetDailyLimits()} eksplicitno.
 */
@Component
@RequiredArgsConstructor
public class ActuaryLimitResetScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActuaryLimitResetScheduler.class);

    private final ActuaryInfoRepository actuaryInfoRepository;
    // P2-audit-coverage-1 (R1 439): dnevni cron reset svih limita je bio bez audit traga
    // (bulk UPDATE zaobilazi per-employee ActuaryServiceImpl.resetUsedLimit audit).
    private final AuditLogService auditLogService;

    /**
     * Resetuje usedLimit na 0 za sve aktuare.
     * Cron: svaki dan u 23:59:00.
     */
    @Scheduled(cron = "0 59 23 * * *")
    @Transactional
    public void resetDailyLimits() {
        log.info("Starting daily actuary limit reset");
        try {
            int updatedCount = actuaryInfoRepository.resetAllUsedLimits();
            log.info("Daily actuary limit reset completed — {} actuaries reset", updatedCount);

            // R1 439: audit dnevnog reset-a (aktor = SYSTEM/scheduler; nema targetId — bulk).
            // afterCommit + best-effort: pise se SAMO ako bulk-reset tx commit-uje i pad audit-a
            // NE obara reset.
            auditLogService.recordAfterCommit(
                    0L, "SCHEDULER",
                    AuditActionType.USED_LIMIT_RESET_ALL,
                    "Daily actuary used-limit reset (" + updatedCount + " actuaries)",
                    "ACTUARY", null);
        } catch (Exception e) {
            log.error("Error during daily actuary limit reset: {}", e.getMessage(), e);
        }
    }
}
