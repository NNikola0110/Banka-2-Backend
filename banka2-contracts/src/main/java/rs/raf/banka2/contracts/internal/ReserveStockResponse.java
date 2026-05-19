package rs.raf.banka2.contracts.internal;

/** Odgovor na rezervaciju hartija — stanje portfolija posle rezervacije. */
public record ReserveStockResponse(Long portfolioId, Long listingId, String ticker,
                                   int reservedQuantity, int availableQuantity) {
}
