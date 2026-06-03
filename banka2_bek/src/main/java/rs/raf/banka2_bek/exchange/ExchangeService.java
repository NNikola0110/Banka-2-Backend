package rs.raf.banka2_bek.exchange;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import rs.raf.banka2_bek.exchange.dto.CalculateExchangeResponseDto;
import rs.raf.banka2_bek.exchange.dto.ExchangeRateDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ExchangeService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeService.class);

    private final RestTemplate restTemplate;

    @Value("${exchange.api.key:}")
    private String apiKey;

    @Value("${exchange.api.url}")
    private String apiUrl;

    /**
     * BE-PAY-07: snapshot tipa za thread-safe kes.
     *
     * <p>{@code rates} je immutable (kopiramo u List.copyOf pri save-u);
     * {@code timestamp} pamti kad je snapshot napravljen. Cela snapshot se
     * publish-uje atomicno preko {@link AtomicReference#set} pa ne moze
     * doci do delimicnog citanja gde rates pripada novom batch-u a timestamp
     * starom (race u prethodnoj implementaciji sa razdvojenim
     * {@code cachedRates}/{@code cacheTimestamp} field-ovima).</p>
     *
     * @param rates immutable cache-ovani kurs
     * @param timestamp Instant kad je snapshot napravljen
     */
    private record RatesSnapshot(List<ExchangeRateDto> rates, Instant timestamp) {
        RatesSnapshot(List<ExchangeRateDto> rates, Instant timestamp) {
            this.rates = List.copyOf(rates);
            this.timestamp = timestamp;
        }

        boolean isFresh(long ttlMs) {
            return timestamp != null
                    && (Instant.now().toEpochMilli() - timestamp.toEpochMilli()) < ttlMs;
        }
    }

    // BE-PAY-07: thread-safe cache (AtomicReference garantuje atomic publish/read).
    private final AtomicReference<RatesSnapshot> snapshotRef = new AtomicReference<>(null);

    // Cache za kurseve — 5 minuta TTL
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    // R1-650/R1-673: jedinstven izvor istine za FX markup/proviziju. Double-put
    // (display: {@link #calculate}/{@link #convertToRsd}) i BigDecimal-put
    // ({@link #SELL_MARKUP}/{@link #COMMISSION_MULTIPLIER}, booking) MORAJU da koriste
    // istu vrednost — pre je 1.02/0.005 bilo razbacano kao goli literal na 4 mesta.
    /** Prodajni spread banke (+2% na srednji kurs) — double display put. */
    private static final double SELL_MARKUP_FACTOR = 1.02;
    /** Provizija menjacnice (0.5%) — double display put; deli vrednost sa COMMISSION_MULTIPLIER (1 - ovo). */
    static final double COMMISSION_RATE = 0.005;
    /** Decimale za kurs (paritet sa round(...,6)). */
    private static final int DISPLAY_RATE_SCALE = 6;
    /** Decimale za konvertovani iznos (paritet sa round(...,4)). */
    private static final int DISPLAY_AMOUNT_SCALE = 4;

    /**
     * R1-650: provizija menjacnice/FX transfera kao {@link BigDecimal} (0.5%).
     * Koristi je {@code TransferService.fxTransfer} umesto sopstvenog {@code new BigDecimal("0.005")}
     * literala, da bi provizija imala JEDAN izvor istine.
     */
    public static final BigDecimal COMMISSION_RATE_BD = new BigDecimal("0.005");

    public ExchangeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    void warnIfApiKeyMissing() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("SEC-01: exchange.api.key is empty — Fixer.io poziv se nece izvrsavati, "
                    + "ExchangeService koristi fallback (NBS) kurseve. Postavi EXCHANGE_API_KEY "
                    + "env var za live FX kurseve (production deployment MORA da setuje ovaj key).");
        }
    }

    public List<ExchangeRateDto> getAllRates() {
        RatesSnapshot snapshot = snapshotRef.get();
        if (snapshot != null && snapshot.isFresh(CACHE_TTL_MS)) {
            return snapshot.rates();
        }
        return fetchAndCacheRates();
    }

    private synchronized List<ExchangeRateDto> fetchAndCacheRates() {
        // Double-check after acquiring lock
        RatesSnapshot existing = snapshotRef.get();
        if (existing != null && existing.isFresh(CACHE_TTL_MS)) {
            return existing.rates();
        }

        // SEC-01: ne salji zahtev ka Fixer.io bez validnog key-a — odmah fallback.
        if (apiKey == null || apiKey.isBlank()) {
            List<ExchangeRateDto> fallback = getFallbackRates();
            snapshotRef.set(new RatesSnapshot(fallback, Instant.now()));
            return fallback;
        }

        // R1-654 (sec): Fixer.io free-tier autentifikuje ISKLJUCIVO preko `access_key`
        // query parametra (header auth je placeni feature) — zato key MORA ostati u URL-u.
        // Posledica: key ne sme nikad zavrsiti u log-u / proxy access-log-u → svaki log
        // koji dotice ovaj URL ide kroz {@link #maskApiKey} (vidi catch ispod).
        String url = apiUrl + "?access_key=" + apiKey +
                "&symbols=RSD,EUR,CHF,USD,GBP,JPY,CAD,AUD";

        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = restTemplate.getForEntity(url, Map.class).getBody();
            body = responseBody;
        } catch (Exception e) {
            // API nedostupan ili rate limit — koristimo fallback kurseve.
            // R1-654: loguje se MASKIRAN URL (access_key skriven) radi dijagnostike bez leak-a.
            log.warn("Fixer.io poziv neuspesan ({}), prelazim na fallback kurseve. URL={}",
                    e.getClass().getSimpleName(), maskApiKey(url));
            return cacheNegativeAndReturn(existing);
        }

        if (body == null || body.get("rates") == null) {
            return cacheNegativeAndReturn(existing);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rates = (Map<String, Object>) body.get("rates");

        Double eurToRsd = getDouble(rates.get("RSD"));

        if (eurToRsd == null || eurToRsd == 0.0) {
            throw new RuntimeException("RSD rate not found.");
        }

        // R1-672: Fixer odgovor nosi pravi `date` (datum vazenja kursa). Ranije se
        // ignorisao pa je svaki ExchangeRateDto dobijao LocalDate.now() (ctor default)
        // cak i kad kurs nije osvezen danas. Mapiramo pravi datum ako ga API posalje.
        String fixerDate = body.get("date") instanceof String d && !d.isBlank() ? d : null;

        String[] currencies = {"RSD", "EUR", "CHF", "USD", "GBP", "JPY", "CAD", "AUD"};

        List<ExchangeRateDto> result = new ArrayList<>();

        for (String currency : currencies) {

            if ("RSD".equals(currency)) {
                result.add(withDate(new ExchangeRateDto("RSD", 1.0), fixerDate));
                continue;
            }

            if ("EUR".equals(currency)) {
                double rate = round(1.0 / eurToRsd, 6);
                result.add(withDate(new ExchangeRateDto("EUR", rate), fixerDate));
                continue;
            }

            Double eurToTarget = getDouble(rates.get(currency));

            if (eurToTarget != null) {
                double rsdToTarget = eurToTarget / eurToRsd;
                result.add(withDate(new ExchangeRateDto(currency, round(rsdToTarget, 6)), fixerDate));
            }
        }

        // BE-PAY-07: atomic publish — citaoci vide ili stari snapshot ili novi snapshot
        // (List.copyOf u RatesSnapshot ctor-u + AtomicReference.set garantuje
        // happens-before bez race-a izmedju rates i timestamp polja).
        snapshotRef.set(new RatesSnapshot(result, Instant.now()));
        return result;
    }

    private Double getDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    /** R1-672: postavi pravi Fixer datum na DTO ako je dostupan; inace ostaje ctor default (danas). */
    private static ExchangeRateDto withDate(ExchangeRateDto dto, String date) {
        if (date != null) {
            dto.setDate(date);
        }
        return dto;
    }

    /**
     * R1-674: negativni kes — kad je Fixer.io nedostupan (timeout/down/prazno telo),
     * ne pravimo thundering-herd (svaki request pogadja mrtav API). Ako imamo stari
     * snapshot, REPUBLISH-ujemo ga sa SVEZIM timestamp-om (stale-while-error) da
     * {@link RatesSnapshot#isFresh} ostane true do sledeceg TTL prozora; ako nemamo
     * nista, kesiramo fallback (NBS) kurseve. Tako naredni pozivi u TTL prozoru NE
     * udaraju ponovo u API.
     */
    private List<ExchangeRateDto> cacheNegativeAndReturn(RatesSnapshot existing) {
        if (existing != null) {
            // Re-publish postojeci kurs sa svezim timestamp-om (negative cache window).
            snapshotRef.set(new RatesSnapshot(existing.rates(), Instant.now()));
            return existing.rates();
        }
        List<ExchangeRateDto> fallback = getFallbackRates();
        snapshotRef.set(new RatesSnapshot(fallback, Instant.now()));
        return fallback;
    }

    private double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    /**
     * R1-654 (sec): maskira {@code access_key} query parametar pre logovanja URL-a.
     * Fixer.io free-tier prima key SAMO kroz query string, pa se key ne moze prebaciti
     * u header; umesto toga se NIKAD ne loguje u plaintext-u. Vraca {@code access_key=***}.
     *
     * @param url pun URL koji moze sadrzati {@code access_key=<tajna>}
     * @return URL sa vrednoscu access_key-a zamenjenom sa {@code ***}
     */
    public static String maskApiKey(String url) {
        if (url == null) {
            return null;
        }
        // zameni vrednost access_key-a do sledeceg `&` ili kraja stringa
        return url.replaceAll("(access_key=)[^&]*", "$1***");
    }


    public CalculateExchangeResponseDto calculate(double amount, String toCurrency) {

        if ("RSD".equalsIgnoreCase(toCurrency)) {
            return new CalculateExchangeResponseDto(amount, 1.0, "RSD", "RSD");
        }

        List<ExchangeRateDto> rates = getAllRates();

        double rsdToTarget = rates.stream()
                .filter(r -> r.getCurrency().equalsIgnoreCase(toCurrency))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Currency not supported: " + toCurrency))
                .getRate();

        // Prodajni kurs = +2% na srednji kurs (banka prodaje valutu klijentu)
        double sellRate = round(rsdToTarget * SELL_MARKUP_FACTOR, DISPLAY_RATE_SCALE);

        // Provizija 0.5%
        double commission = COMMISSION_RATE;

        double convertedAmount = round((amount * sellRate) * (1 - commission), DISPLAY_AMOUNT_SCALE);

        return new CalculateExchangeResponseDto(convertedAmount, sellRate, "RSD", toCurrency.toUpperCase());
    }


    private double[] convertToRsd(double amount, String fromCurrency, List<ExchangeRateDto> rates) {
        double rsdToFrom = rates.stream()
                .filter(r -> r.getCurrency().equalsIgnoreCase(fromCurrency))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Currency not supported: " + fromCurrency))
                .getRate();

        double sellRate = round((1.0 / rsdToFrom) * SELL_MARKUP_FACTOR, DISPLAY_RATE_SCALE);
        double commission = COMMISSION_RATE;
        double rsdAmount = round((amount * sellRate) * (1 - commission), DISPLAY_AMOUNT_SCALE);

        return new double[]{rsdAmount, sellRate};
    }

    /**
     * Fallback kursevi bazirani na prosecnim vrednostima NBS-a.
     * Koriste se kada fixer.io API nije dostupan (rate limit, downtime).
     * Kursevi su u formatu: koliko strane valute se dobije za 1 RSD.
     */
    private List<ExchangeRateDto> getFallbackRates() {
        List<ExchangeRateDto> rates = new ArrayList<>();
        rates.add(new ExchangeRateDto("RSD", 1.0));
        rates.add(new ExchangeRateDto("EUR", 0.008547));   // ~117 RSD za 1 EUR
        rates.add(new ExchangeRateDto("CHF", 0.007800));   // ~128 RSD za 1 CHF
        rates.add(new ExchangeRateDto("USD", 0.009090));   // ~110 RSD za 1 USD
        rates.add(new ExchangeRateDto("GBP", 0.007350));   // ~136 RSD za 1 GBP
        rates.add(new ExchangeRateDto("JPY", 1.363636));   // ~0.73 RSD za 1 JPY
        rates.add(new ExchangeRateDto("CAD", 0.012500));   // ~80 RSD za 1 CAD
        rates.add(new ExchangeRateDto("AUD", 0.013333));   // ~75 RSD za 1 AUD
        return rates;
    }

    public CalculateExchangeResponseDto calculateCross(double amount, String fromCurrency, String toCurrency) {

        if ("RSD".equalsIgnoreCase(fromCurrency)) {
            return calculate(amount, toCurrency);
        }

        List<ExchangeRateDto> rates = getAllRates();
        double[] conversion = convertToRsd(amount, fromCurrency, rates);
        double rsdAmount = conversion[0];
        double sellRate = conversion[1];

        if ("RSD".equalsIgnoreCase(toCurrency)) {
            return new CalculateExchangeResponseDto(rsdAmount, sellRate, fromCurrency.toUpperCase(), "RSD");
        }

        CalculateExchangeResponseDto step2 = calculate(rsdAmount, toCurrency);
        double crossRate = round(step2.getConvertedAmount() / amount, 6);

        return new CalculateExchangeResponseDto(
                step2.getConvertedAmount(),
                crossRate,
                fromCurrency.toUpperCase(),
                toCurrency.toUpperCase()
        );
    }

    // ===================================================================
    // P0-B4 Nalaz 1: BigDecimal FX put za BOOKING (conservation-exact).
    //
    // Stari double calculate/calculateCross ostaje za display (ExchangeController)
    // i nazadnu kompatibilnost, ali transfer/payment booking MORA da koristi ovaj
    // put da knjige ne bi sadrzale double binarni rep (...0001 / ...9999) niti
    // sub-cent prljavstinu od BigDecimal.valueOf(double).
    // ===================================================================

    /** Banka prodaje valutu klijentu uz +2% na srednji kurs (isto kao double put). */
    private static final BigDecimal SELL_MARKUP = new BigDecimal("1.02");

    /** Provizija menjacnice 0.5% (isto kao double put) — (1 - {@link #COMMISSION_RATE_BD}). */
    private static final BigDecimal COMMISSION_MULTIPLIER = BigDecimal.ONE.subtract(COMMISSION_RATE_BD); // 0.995

    /** Scale za kurs (6 decimala — paritet sa double round(...,6)). */
    private static final int RATE_SCALE = 6;

    /** Scale za novac (fiat) — 2 decimale, bankarsko HALF_EVEN zaokruzivanje. */
    private static final int MONEY_SCALE = 2;

    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;
    private static final RoundingMode RATE_ROUNDING = RoundingMode.HALF_UP;

    /**
     * Rezultat FX konverzije izracunate u {@link BigDecimal} kroz ceo put.
     *
     * @param convertedAmount iznos u ciljnoj valuti, zaokruzen na {@value #MONEY_SCALE}
     *                        decimale ({@link RoundingMode#HALF_EVEN}) — deterministican,
     *                        spreman za direktno bukiranje (bez {@code BigDecimal.valueOf(double)})
     * @param exchangeRate    primenjeni (prodajni) kurs fromCurrency -> toCurrency
     */
    public record FxConversionResult(BigDecimal convertedAmount, BigDecimal exchangeRate) {}

    /**
     * Konvertuje {@code amount} iz {@code fromCurrency} u {@code toCurrency} racunajuci
     * CEO put u {@link BigDecimal} (kurs, mnozenje, +2% prodajni spread, 0.5% provizija,
     * zaokruzivanje {@link RoundingMode#HALF_EVEN} na 2 decimale).
     *
     * <p>Semantika je identicna {@link #calculateCross(double, String, String)} (preko RSD-a
     * za cross parove), ali bez double round-greske, pa je rezultat deterministican i
     * conservation drzi egzaktno kada se direktno bukira u racun.</p>
     */
    public FxConversionResult calculateCrossExact(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return new FxConversionResult(
                    amount.setScale(MONEY_SCALE, MONEY_ROUNDING),
                    BigDecimal.ONE.setScale(RATE_SCALE, RATE_ROUNDING));
        }

        // fromCurrency == RSD: jedan korak RSD -> toCurrency
        if ("RSD".equalsIgnoreCase(fromCurrency)) {
            BigDecimal sellRate = rsdToTargetSellRate(toCurrency);
            BigDecimal converted = amount.multiply(sellRate).multiply(COMMISSION_MULTIPLIER)
                    .setScale(MONEY_SCALE, MONEY_ROUNDING);
            return new FxConversionResult(converted, sellRate);
        }

        // fromCurrency != RSD: prvo fromCurrency -> RSD
        BigDecimal toRsdSellRate = targetToRsdSellRate(fromCurrency);
        BigDecimal rsdAmount = amount.multiply(toRsdSellRate).multiply(COMMISSION_MULTIPLIER)
                .setScale(MONEY_SCALE, MONEY_ROUNDING);

        if ("RSD".equalsIgnoreCase(toCurrency)) {
            return new FxConversionResult(rsdAmount, toRsdSellRate);
        }

        // pa RSD -> toCurrency
        BigDecimal rsdToTargetRate = rsdToTargetSellRate(toCurrency);
        BigDecimal converted = rsdAmount.multiply(rsdToTargetRate).multiply(COMMISSION_MULTIPLIER)
                .setScale(MONEY_SCALE, MONEY_ROUNDING);

        // efektivni cross kurs = converted / amount (samo informativno, paritet sa double put-om)
        BigDecimal crossRate = converted.divide(amount, RATE_SCALE, RATE_ROUNDING);
        return new FxConversionResult(converted, crossRate);
    }

    /** Prodajni kurs RSD -> targetCurrency (+2% na srednji kurs), BigDecimal scale 6. */
    private BigDecimal rsdToTargetSellRate(String targetCurrency) {
        BigDecimal mid = midRateRsdToTarget(targetCurrency);
        return mid.multiply(SELL_MARKUP).setScale(RATE_SCALE, RATE_ROUNDING);
    }

    /** Prodajni kurs fromCurrency -> RSD (+2% na (1/mid)), BigDecimal scale 6. */
    private BigDecimal targetToRsdSellRate(String fromCurrency) {
        BigDecimal mid = midRateRsdToTarget(fromCurrency); // jedinica fromCurrency za 1 RSD
        // 1 fromCurrency = (1 / mid) RSD; prodajni kurs banke = *1.02
        BigDecimal inverse = BigDecimal.ONE.divide(mid, RATE_SCALE + 4, RATE_ROUNDING);
        return inverse.multiply(SELL_MARKUP).setScale(RATE_SCALE, RATE_ROUNDING);
    }

    /** Srednji kurs RSD -> targetCurrency (koliko jedinica target za 1 RSD) iz getAllRates. */
    private BigDecimal midRateRsdToTarget(String targetCurrency) {
        List<ExchangeRateDto> rates = getAllRates();
        double rate = rates.stream()
                .filter(r -> r.getCurrency().equalsIgnoreCase(targetCurrency))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Currency not supported: " + targetCurrency))
                .getRate();
        return BigDecimal.valueOf(rate);
    }

}
