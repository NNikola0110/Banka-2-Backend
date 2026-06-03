package rs.raf.banka2.contracts.internal;

import java.math.BigDecimal;

/**
 * Citanje racuna za prikaz u trading-service (broj racuna, vlasnik, stanje).
 *
 * <p>{@code ownerClientId}/{@code ownerEmployeeId}/{@code accountCategory} nose
 * vlasnistvo racuna — trading-service ih koristi da reprodukuje monolitovu
 * proveru da li racun pripada akteru (npr. {@code InvestmentFundService}
 * provera da uplatni/isplatni racun pripada ulogovanom klijentu, odnosno da je
 * supervizorski izbor bankin {@code BANK_TRADING} racun).
 *
 * <p>{@code reservedAmount} je interni 2PC/OTC tracker iz banka-core
 * ({@code Account.reservedAmount}) — trenutno se serijalizuje kao snapshot na
 * granici (producer {@code InternalLookupService} ga puni), ali trading-service
 * za svoje odluke koristi {@code availableBalance} (= balance − reserved). Polje
 * je deo wire ugovora radi potpunosti stanja racuna; ako mu ni dugorocno ne
 * dodjemo do consumer-a, kandidat je za uklanjanje.
 */
public record InternalAccountDto(Long id, String accountNumber, String ownerName,
                                 BigDecimal balance, BigDecimal availableBalance,
                                 BigDecimal reservedAmount, String currencyCode,
                                 String status,
                                 Long ownerClientId, Long ownerEmployeeId,
                                 String accountCategory) {
}
