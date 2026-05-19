package rs.raf.banka2.contracts.internal;

import java.math.BigDecimal;

/**
 * Odgovor na naplatu poreza. {@code collected=false} (uz {@code collectedAmount=0})
 * znaci da klijent nema RSD racun sa dovoljno sredstava — naplata je preskocena.
 */
public record TaxCollectResponse(Long payerClientId, BigDecimal collectedAmount,
                                 boolean collected) {
}
