package rs.raf.banka2_bek.interbank.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import rs.raf.banka2_bek.exchange.CurrencyConversionService;
import rs.raf.banka2_bek.exchange.CurrencyConversionService.ConversionResult;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * §2.8.7 + Celina 5 §40-50: cross-currency FX quote za inter-bank settlement.
 * Wrapper oko {@link CurrencyConversionService} koji odvaja inter-bank FX
 * obracun od order/payment FX (gde provizija ide klijentu vs banci).
 *
 * Koristi se na strani originator banke pre formiranja {@code Transaction} —
 * povratne vrednosti idu u balansirane double-entry postings.
 *
 * Spec Celina 5 §44-50 (stara): Banka B salje "Ready" sa kursom + provizijom.
 * Profesorov protokol: origin banka radi konverziju pre slanja, postings su
 * balansirani po asset-u (svaka strana u svojoj valuti).
 */
@Service
@RequiredArgsConstructor
public class InterbankFxService {

    /**
     * Inter-bank settlement provizija = <b>0.5%</b> (banka naplacuje za cross-currency
     * inter-bank settlement). R1-675: ovo je RAZLICITA stavka od menjacnicke FX marze
     * (1%, {@code CurrencyConversionService.FX_MARGIN}) — settlement fee ide u inter-bank
     * revenue racun, a menjacnicka marza u FX revenue. Vrednosti (0.5% vs 1%) su namerno
     * razlicite; ovaj komentar ih eksplicitno razdvaja da se ne mesaju.
     */
    private static final BigDecimal INTERBANK_SETTLEMENT_FEE = new BigDecimal("0.005");

    private final CurrencyConversionService currencyConversionService;

    /**
     * §Celina 5 §40-66: <b>inbound settlement na strani Banke B (primaoca)</b>.
     *
     * <p>Banka B prima "Pocetnu vrednost" (originalni iznos u valuti posiljaoca)
     * preko 2PC NEW_TX poruke, izracunava kurs i proviziju, i kreditira primaocu
     * "Krajnju vrednost" = mid-rate konverzija MINUS inter-bank provizija, u
     * valuti primaocevog racuna.
     *
     * <p>Provizija ide Banci B u <b>target</b> valuti i smanjuje iznos koji
     * primalac dobija — tacno kako spec definise "Krajnju vrednost".
     *
     * <p>Same-currency je no-op: rate=1, fee=0, recipient dobija tacno {@code amount}
     * (regression-safe — odgovara postojecem same-currency commit ponasanju).
     *
     * @param amountSourceCurrency  "Pocetna vrednost" — iznos u valuti posiljaoca
     * @param sourceCurrency        valuta posiljaoca (wire valuta)
     * @param targetCurrency        valuta primaocevog racuna kod Banke B
     * @return quote gde {@code targetAmount} = "Krajnja vrednost" (recipient credit),
     *         {@code commission} = Banka-B provizija u target valuti
     */
    public InterbankFxQuote quoteInboundSettlement(BigDecimal amountSourceCurrency,
                                                   String sourceCurrency,
                                                   String targetCurrency) {
        if (amountSourceCurrency == null || amountSourceCurrency.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (sourceCurrency == null || targetCurrency == null) {
            throw new IllegalArgumentException("Source/target currency must not be null");
        }

        // Same-currency: nema FX, nema provizije — byte-identicno starom ponasanju.
        if (sourceCurrency.equalsIgnoreCase(targetCurrency)) {
            return new InterbankFxQuote(
                    amountSourceCurrency,
                    amountSourceCurrency,
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    sourceCurrency.toUpperCase(),
                    targetCurrency.toUpperCase());
        }

        // Cross-currency: mid-rate konverzija (bez FX marze — provizija je
        // zaseban inter-bank settlement fee, ne menjacnicki spread).
        ConversionResult result = currencyConversionService.convertForPurchase(
                amountSourceCurrency, sourceCurrency, targetCurrency, false);
        return buildInboundQuote(amountSourceCurrency, sourceCurrency, targetCurrency,
                result.amount(), result.midRate());
    }

    /**
     * N5 — FX-pinned varijanta {@link #quoteInboundSettlement}. Umesto da povuce
     * live mid-rate, koristi <b>zakljucan (pinned)</b> kurs iz VOTE faze. Tako commit
     * uvek koristi isti kurs kao i provera stanja pri glasanju — FX drift izmedju
     * vote-a i commit-a ne moze da pomeri isplatu izvan provere (overdraft / leak).
     *
     * @param amountSourceCurrency  "Pocetna vrednost" — iznos u valuti posiljaoca
     * @param sourceCurrency        valuta posiljaoca (wire valuta)
     * @param targetCurrency        valuta primaocevog racuna kod Banke B
     * @param pinnedMidRate         zakljucan mid-rate source→target iz VOTE faze
     */
    public InterbankFxQuote quoteInboundSettlementWithRate(BigDecimal amountSourceCurrency,
                                                           String sourceCurrency,
                                                           String targetCurrency,
                                                           BigDecimal pinnedMidRate) {
        if (amountSourceCurrency == null || amountSourceCurrency.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (sourceCurrency == null || targetCurrency == null) {
            throw new IllegalArgumentException("Source/target currency must not be null");
        }
        if (pinnedMidRate == null || pinnedMidRate.signum() <= 0) {
            throw new IllegalArgumentException("Pinned FX rate must be positive");
        }

        if (sourceCurrency.equalsIgnoreCase(targetCurrency)) {
            return new InterbankFxQuote(
                    amountSourceCurrency, amountSourceCurrency, BigDecimal.ZERO,
                    BigDecimal.ONE, BigDecimal.ONE,
                    sourceCurrency.toUpperCase(), targetCurrency.toUpperCase());
        }

        BigDecimal converted = amountSourceCurrency.multiply(pinnedMidRate)
                .setScale(4, RoundingMode.HALF_UP);
        return buildInboundQuote(amountSourceCurrency, sourceCurrency, targetCurrency,
                converted, pinnedMidRate);
    }

    /** Zajednicki obracun inbound quote-a iz konvertovanog iznosa + mid-rate-a. */
    private InterbankFxQuote buildInboundQuote(BigDecimal amountSourceCurrency,
                                               String sourceCurrency, String targetCurrency,
                                               BigDecimal converted, BigDecimal midRate) {
        BigDecimal fee = converted.multiply(INTERBANK_SETTLEMENT_FEE)
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal recipientCredit = converted.subtract(fee).setScale(4, RoundingMode.HALF_UP);

        return new InterbankFxQuote(
                amountSourceCurrency,
                recipientCredit,
                fee,
                midRate,
                midRate,
                sourceCurrency.toUpperCase(),
                targetCurrency.toUpperCase());
    }

    /**
     * §2.6 + Celina 5: rezultat FX kvotacije za inter-bank settlement.
     *
     * @param sourceAmount         koliko sa posiljaocevog racuna ide ukupno
     *                             (ukljucuje commission ako je chargeClientFee=true)
     * @param targetAmount         koliko stize na primaocev racun (uvek bez fee-a)
     * @param commission           inter-bank settlement fee (u target valuti)
     * @param midRate              srednji kurs source→target
     * @param effectiveRate        stvarni primenjeni kurs (mid * (1 + fee))
     * @param sourceCurrency       ISO kod posiljaoceve valute
     * @param targetCurrency       ISO kod primaoceve valute
     */
    public record InterbankFxQuote(
            BigDecimal sourceAmount,
            BigDecimal targetAmount,
            BigDecimal commission,
            BigDecimal midRate,
            BigDecimal effectiveRate,
            String sourceCurrency,
            String targetCurrency) {
    }
}
