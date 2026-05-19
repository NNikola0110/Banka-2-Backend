package rs.raf.banka2.contracts.internal;

/**
 * Citanje portfolio pozicije vlasnika za odredjeni listing (inter-bank OTC
 * validacija). trading-service vraca ovaj DTO za (userId, userRole, ticker);
 * {@code exists=false} znaci da vlasnik nema portfolio za tu hartiju.
 *
 * <p>{@code availableQuantity} = {@code quantity - reservedQuantity} —
 * kolicina slobodna za novu rezervaciju.
 */
public record InternalPortfolioHoldingDto(boolean exists, Long portfolioId, Long listingId,
                                          String ticker, int quantity,
                                          int reservedQuantity, int availableQuantity) {
}
