package rs.raf.banka2.contracts.internal;

import java.math.BigDecimal;

/** Odgovor na jednostrani kredit racuna (SELL prihod, dividende). */
public record CreditFundsResponse(Long accountId, BigDecimal creditedAmount,
                                  BigDecimal balanceAfter) {
}
