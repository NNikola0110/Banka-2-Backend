package rs.raf.banka2.contracts.internal;

/**
 * Zahtev za oslobadjanje rezervisanih hartija (inter-bank OTC 2PC — kompenzacija
 * pri rollback-u). trading-service smanjuje {@code Portfolio.reservedQuantity} za
 * {@code quantity} (clamp na 0).
 */
public record ReleaseStockRequest(Long userId, String userRole, String ticker, int quantity) {
}
