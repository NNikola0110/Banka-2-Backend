package rs.raf.banka2.contracts.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * P2-tax-interbank-otc-1 — jedan EXERCISED inter-bank OTC opcioni ugovor sa
 * LOKALNOM stranom (klijent/zaposleni nase banke), izlozen trading-service
 * tax engine-u preko {@code GET /internal/interbank-otc/exercised}.
 *
 * <p><b>Zasto postoji:</b> inter-bank OTC ugovori zive u banka-core tabeli
 * {@code interbank_otc_contracts} ({@code InterbankOtcContract}), a banka-core
 * NEMA tax modul. Tax engine je u trading-service-u i video je samo svoj
 * {@code otc_contracts} (intra-bank). Posledica: lokalni CLIENT koji exercise-uje
 * inter-bank opciju realizuje kapitalnu dobit koju NIJEDAN sistem nije
 * oporezovao (real under-taxation). Ovaj DTO premoscava granicu: banka-core
 * izlaze EXERCISED inter-bank ugovore, trading-service ih ukljucuje u 15%
 * obracun kapitalne dobiti istom logikom kao intra-OTC.
 *
 * <p><b>Strana ugovora:</b> {@code localPartyType} je {@code "SELLER"} ili
 * {@code "BUYER"} — koju ulogu IGRA lokalna strana (druga strana je u
 * partnerskoj banci). {@code localPartyRole} je {@code "CLIENT"}/{@code "EMPLOYEE"}.
 * Tax engine oporezuje samo CLIENT (EMPLOYEE = bank actuary, BE-ORD-06 izuzet).
 *
 * <p><b>Polja za obracun (mirror intra-OTC):</b> SELLER proceeds =
 * {@code strikePrice × quantity + premium}; BUYER cost-basis =
 * {@code strikePrice × quantity} (premija NIJE na kupcevoj strani — isti R1-432
 * fix kao intra). {@code strikeCurrency} → FX u RSD; {@code ticker} →
 * trading-service razresava {@code listingId} (banka-core ga nema).
 *
 * @param id                  PK ugovora u banka-core (audit/dedup; tax ne kljucuje po njemu)
 * @param localPartyId        clients.id / employees.id lokalne strane
 * @param localPartyRole      "CLIENT" ili "EMPLOYEE"
 * @param localPartyType      "SELLER" ili "BUYER" — uloga lokalne strane u ugovoru
 * @param ticker              ticker hartije (trading-service razresava listingId)
 * @param quantity            broj akcija (integer > 0 po protokolu §2.7.2)
 * @param strikePrice         strike cena po jedinici
 * @param strikeCurrency      ISO valuta strike-a (FX u RSD)
 * @param premium             placena/primljena premija (u {@code premiumCurrency})
 * @param premiumCurrency     ISO valuta premije
 * @param exercisedAt         kada je opcija iskoriscena (period obracuna)
 */
public record InterbankOtcExercisedDto(Long id,
                                       Long localPartyId,
                                       String localPartyRole,
                                       String localPartyType,
                                       String ticker,
                                       BigDecimal quantity,
                                       BigDecimal strikePrice,
                                       String strikeCurrency,
                                       BigDecimal premium,
                                       String premiumCurrency,
                                       LocalDateTime exercisedAt) {
}
