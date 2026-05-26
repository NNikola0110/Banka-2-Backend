package rs.raf.banka2_bek.audit.model;

public enum AuditActionType {
    // Existing aktuari/orders/tax actions
    LIMIT_CHANGED,
    USED_LIMIT_RESET,
    ORDER_APPROVED,
    ORDER_DECLINED,
    PERMISSIONS_CHANGED,
    TAX_RUN_TRIGGERED,

    // BE-PAY-01 — banking flow audit hooks
    // Loan lifecycle
    LOAN_APPROVED,
    LOAN_REJECTED,
    LOAN_EARLY_REPAYMENT,

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

    // Account management
    ACCOUNT_STATUS_CHANGED,
    ACCOUNT_LIMITS_CHANGED
}
