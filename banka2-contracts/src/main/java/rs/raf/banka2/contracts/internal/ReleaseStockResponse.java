package rs.raf.banka2.contracts.internal;

/** Odgovor na oslobadjanje rezervisanih hartija — stanje portfolija posle oslobadjanja. */
public record ReleaseStockResponse(Long portfolioId, String ticker, int reservedQuantity) {
}
