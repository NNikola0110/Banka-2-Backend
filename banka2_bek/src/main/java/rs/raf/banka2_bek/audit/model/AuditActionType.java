package rs.raf.banka2_bek.audit.model;

public enum AuditActionType {
    // Employee/permission actions emitovani iz banka-core (EmployeeServiceImpl).
    // NAPOMENA: aktuarski/order/tax audit (LIMIT_CHANGED/ORDER_*/USED_LIMIT_RESET/
    // TAX_RUN_TRIGGERED) zivi u trading-service-ovom rs.raf.trading.audit.model
    // .AuditActionType posle mikroservisnog rascepa — gateway rutira goli /audit
    // ka trading-service-u (vidi api-gateway/nginx.conf). Ne dupliramo ih ovde.
    PERMISSIONS_CHANGED,

    // BE-PAY-01 — banking flow audit hooks
    // Loan lifecycle
    LOAN_APPROVED,
    LOAN_REJECTED,
    LOAN_EARLY_REPAYMENT,
    // P2-audit-coverage-1 (R5 1887): mesecna naplata rate (money-moving debit) po ishodu
    LOAN_INSTALLMENT_PAID,
    LOAN_INSTALLMENT_FAILED,

    // Payments
    PAYMENT_CREATED,
    PAYMENT_ABORTED,    // posle 3 neuspela OTP pokusaja
    PAYMENT_QUICK_APPROVED,   // TODO_final Mobile bonus #7 — Quick Approve flow uspesan

    // Transfers
    TRANSFER_INTERNAL,
    TRANSFER_FX,

    // Savings deposits
    SAVINGS_OPENED,
    SAVINGS_WITHDRAWN_EARLY,
    SAVINGS_AUTO_RENEWED,

    // Card management
    CARD_BLOCKED,
    CARD_UNBLOCKED,
    CARD_LIMIT_CHANGED,
    // P2-audit-coverage-1 (R5 1890): trajna (ireverzibilna) deaktivacija kartice
    CARD_DEACTIVATED,

    // Account management
    ACCOUNT_STATUS_CHANGED,
    ACCOUNT_LIMITS_CHANGED,

    // Employee management
    // P2-audit-coverage-1 (R5 1890): deaktivacija naloga zaposlenog
    EMPLOYEE_DEACTIVATED
}
