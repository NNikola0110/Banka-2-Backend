package rs.raf.banka2.contracts.internal;

/**
 * Zahtev za commit kretanja hartija (inter-bank OTC 2PC — faza commit-a).
 *
 * <p>{@code debit=true} → vlasnik DOBIJA hartije: trading-service povecava
 * {@code Portfolio.quantity} (ili kreira novi portfolio ako ne postoji,
 * sa {@code averageBuyPrice} iz trenutne cene listinga).
 * <p>{@code debit=false} → vlasnik PREDAJE hartije: trading-service smanjuje
 * {@code Portfolio.quantity} i {@code reservedQuantity}.
 */
public record CommitStockRequest(Long userId, String userRole, String ticker,
                                 int quantity, boolean debit) {
}
