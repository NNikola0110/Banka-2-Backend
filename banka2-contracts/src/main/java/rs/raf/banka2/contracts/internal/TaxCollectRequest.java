package rs.raf.banka2.contracts.internal;

import java.math.BigDecimal;

/**
 * Naplata poreza na kapitalnu dobit: debit RSD racuna klijenta, credit drzavnog
 * RSD racuna. Ako klijent nema dovoljno sredstava, naplata se preskace
 * (vidi {@link TaxCollectResponse#collected()}).
 */
public record TaxCollectRequest(Long payerClientId, BigDecimal amount, String description) {
}
