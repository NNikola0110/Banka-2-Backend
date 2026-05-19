package rs.raf.banka2.contracts.internal;

import java.math.BigDecimal;

/**
 * Jednostrani kredit racuna bez debit kontra-strane (SELL prihod, dividende).
 * Trziste je apstraktan izvor novca — banka-core kreditira {@code accountId} sa
 * {@code amount}; opciona {@code commission} ide bankinom BANK_TRADING racunu u
 * {@code currencyCode}.
 */
public record CreditFundsRequest(Long accountId, BigDecimal amount, BigDecimal commission,
                                 String currencyCode, String description) {
}
