package rs.raf.banka2.contracts;

/** Vrsta email notifikacije koja se prenosi kroz RabbitMQ izmedju monolita i notification-service. */
public enum NotificationKind {
    PASSWORD_RESET,
    EMPLOYEE_ACCOUNT_CREATED,
    EMPLOYEE_ACTIVATION_CONFIRMED,
    ACCOUNT_CREATED,
    OTP,
    PAYMENT_CONFIRMED,
    CARD_BLOCKED,
    CARD_UNBLOCKED,
    LOAN_REQUEST_SUBMITTED,
    LOAN_APPROVED,
    LOAN_REJECTED,
    INSTALLMENT_PAID,
    INSTALLMENT_FAILED,
    MARGIN_ACCOUNT_BLOCKED,
    IN_APP_GENERIC
}
