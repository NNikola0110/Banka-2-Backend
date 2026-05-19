package rs.raf.banka2.contracts.internal;

import java.math.BigDecimal;

/**
 * Direktan prenos novca izmedju dva racuna (OTC premija, dividenda, porez, fond).
 * {@code commission} (opciono) — provizija koja se uz prenos kreditira bankinom
 * BANK_TRADING racunu u {@code currencyCode}; {@code null}/0 znaci bez provizije.
 */
public record TransferFundsRequest(Long fromAccountId, Long toAccountId,
                                   BigDecimal amount, String currencyCode,
                                   BigDecimal commission, String description) {

    /** Backwards-kompatibilan konstruktor bez provizije ({@code commission = null}). */
    public TransferFundsRequest(Long fromAccountId, Long toAccountId,
                                BigDecimal amount, String currencyCode, String description) {
        this(fromAccountId, toAccountId, amount, currencyCode, null, description);
    }
}
