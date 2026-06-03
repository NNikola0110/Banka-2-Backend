package rs.raf.trading.audit.model;

/**
 * B7 — Tipovi audit dogadjaja u trading-service domenu
 * (port iz main PR #86; duplicirano u trading-service jer je audit cross-cutting
 * a trading-service ima sopstvenu bazu po servisu — pisemo lokalno za trading
 * dogadjaje, banka-core za bankarske).
 */
public enum AuditActionType {
    LIMIT_CHANGED,
    USED_LIMIT_RESET,
    ORDER_APPROVED,
    ORDER_DECLINED,
    PERMISSIONS_CHANGED,
    TAX_RUN_TRIGGERED,

    // P2-audit-coverage-1 (R5 1888): investicioni fondovi (Celina 4) money/lifecycle dogadjaji
    FUND_CREATED,
    FUND_INVEST,
    FUND_WITHDRAW,

    // P2-audit-coverage-1 (R1 439): dnevni cron reset svih aktuarskih limita
    USED_LIMIT_RESET_ALL
}
