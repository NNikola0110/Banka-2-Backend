package rs.raf.trading.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import rs.raf.banka2.contracts.internal.FxRateDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.order.exception.UnsupportedCurrencyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Servis za konverziju iznosa izmedju valuta u okviru order flow-a.
 *
 * NAPOMENA (copy-first ekstrakcija, faza 2c): u monolitu je izvor srednjih
 * kurseva bio lokalni {@code exchange.ExchangeService}. U trading-service-u
 * je menjacnica deo banka-core domena, pa kursevi dolaze preko banka-core
 * internog seam-a — {@link BankaCoreClient#getFxRates()} (isti obrazac kao
 * {@code stock.ListingServiceImpl.fetchForexPrice} iz pod-faze 2b).
 *
 * Kurs u {@link FxRateDto#rate()} je "koliko jedinica target valute za 1 RSD".
 * Zato se konverzija iz A u B racuna kao: amount * (rateB / rateA).
 */
@Service
@RequiredArgsConstructor
public class CurrencyConversionService {

    private static final int SCALE = 4;

    /**
     * Menjacnica marza (spread + provizija) koja se naplacuje kada klijent
     * trguje sa racuna u valuti razlicitoj od valute hartije. Zaposleni
     * ne placaju menjacnicu jer trguju sa bankinih racuna (Celina 3 spec).
     *
     * <p>P2-profit-fx-fee-1 (R5 1877): vrednost (1%) dolazi iz jedinstvenog
     * {@link rs.raf.trading.common.FxFeePolicy#FX_FEE_RATE} izvora istine
     * (ranije je ista konstanta bila nezavisno duplirana na 4 mesta).
     * Primena ostaje multiplikativna na kurs (vidi {@link #convertForPurchase}).</p>
     */
    private static final BigDecimal FX_MARGIN = rs.raf.trading.common.FxFeePolicy.FX_FEE_RATE;

    private final BankaCoreClient bankaCoreClient;

    /**
     * Konvertuje iznos iz jedne valute u drugu koristeci srednji kurs.
     * Ako su valute iste, vraca isti iznos bez ikakvih izmena.
     *
     * @param amount       iznos u izvornoj valuti (ne sme biti null)
     * @param fromCurrency izvorna valuta (ISO kod)
     * @param toCurrency   ciljna valuta (ISO kod)
     * @return iznos u ciljnoj valuti, zaokruzen na 4 decimale (HALF_UP)
     * @throws UnsupportedCurrencyException ako neka od valuta nije podrzana
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return amount;
        }
        BigDecimal rate = getRate(fromCurrency, toCurrency);
        return amount.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Konvertuje iznos uz opcionu primenu menjacnice (FX marzu).
     * Kada je {@code chargeFxCommission=true} vracena suma je veca od
     * mid-rate konverzije za FX_MARGIN procenta i razlika se iskazuje
     * kao {@code commission} u ciljnoj valuti.
     *
     * Za iste valute vraca {@code amount} sa 0 komisijom.
     */
    public ConversionResult convertForPurchase(BigDecimal amount, String fromCurrency,
                                               String toCurrency, boolean chargeFxCommission) {
        if (fromCurrency.equals(toCurrency)) {
            return new ConversionResult(amount, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
        }
        BigDecimal midRate = getRate(fromCurrency, toCurrency);
        BigDecimal midAmount = amount.multiply(midRate).setScale(SCALE, RoundingMode.HALF_UP);
        if (!chargeFxCommission) {
            return new ConversionResult(midAmount, BigDecimal.ZERO, midRate, midRate);
        }
        BigDecimal effectiveRate = midRate.multiply(BigDecimal.ONE.add(FX_MARGIN))
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal grossAmount = amount.multiply(effectiveRate).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal commission = grossAmount.subtract(midAmount).setScale(SCALE, RoundingMode.HALF_UP);
        return new ConversionResult(grossAmount, commission, effectiveRate, midRate);
    }

    /**
     * Rezultat konverzije sa opcionom menjacnickom proviziom.
     *
     * @param amount         iznos u ciljnoj valuti (ukljucuje FX proviziju ako je obracunata)
     * @param commission     FX komisija u ciljnoj valuti (0 za iste valute ili za zaposlene)
     * @param effectiveRate  stvarni kurs primenjen (mid-rate * (1 + FX_MARGIN) za klijenta)
     * @param midRate        srednji kurs (fromCurrency -> toCurrency)
     */
    public record ConversionResult(BigDecimal amount, BigDecimal commission,
                                   BigDecimal effectiveRate, BigDecimal midRate) {}

    /**
     * Vraca kurs za par valuta: koliko jedinica {@code toCurrency} se dobije za 1 {@code fromCurrency}.
     * Za isti par vraca {@link BigDecimal#ONE}.
     */
    public BigDecimal getRate(String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return BigDecimal.ONE;
        }

        List<FxRateDto> rates = bankaCoreClient.getFxRates();

        double fromRate = findRate(rates, fromCurrency);
        double toRate = findRate(rates, toCurrency);

        // rate[X] = jedinica X za 1 RSD; 1 from = (1 / fromRate) RSD = (toRate / fromRate) to
        double crossRate = toRate / fromRate;
        return BigDecimal.valueOf(crossRate).setScale(6, RoundingMode.HALF_UP);
    }

    private double findRate(List<FxRateDto> rates, String currency) {
        return rates.stream()
                .filter(r -> r.currency().equalsIgnoreCase(currency))
                .findFirst()
                .orElseThrow(() -> new UnsupportedCurrencyException(
                        "Valuta nije podrzana od strane exchange servisa: " + currency))
                .rate();
    }
}
