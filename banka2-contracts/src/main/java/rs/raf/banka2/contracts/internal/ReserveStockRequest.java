package rs.raf.banka2.contracts.internal;

/**
 * Zahtev za rezervaciju hartija u portfoliju vlasnika (inter-bank OTC 2PC —
 * faza rezervacije pri prepare-u). trading-service razresava listing po
 * {@code ticker}-u i povecava {@code Portfolio.reservedQuantity} za
 * {@code quantity}, uz proveru raspolozive kolicine.
 *
 * <p>{@code userRole} je "CLIENT" ili "EMPLOYEE" — odvaja preklapajuce
 * id prostore (clients.id vs employees.id).
 */
public record ReserveStockRequest(Long userId, String userRole, String ticker, int quantity) {
}
