package rs.raf.banka2.contracts.internal;

/** Odgovor na commit kretanja hartija — stanje portfolija posle commit-a. */
public record CommitStockResponse(Long portfolioId, Long listingId, String ticker, int quantity) {
}
