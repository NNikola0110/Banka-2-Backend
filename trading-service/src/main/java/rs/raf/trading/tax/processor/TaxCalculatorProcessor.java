package rs.raf.trading.tax.processor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2.contracts.internal.TaxCollectRequest;
import rs.raf.banka2.contracts.internal.TaxCollectResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.tax.model.TaxRecord;
import rs.raf.trading.tax.model.TaxRecordBreakdown;
import rs.raf.trading.tax.repository.TaxRecordBreakdownRepository;
import rs.raf.trading.tax.repository.TaxRecordRepository;
import rs.raf.trading.tax.service.TaxCalculationException;
import rs.raf.trading.tax.util.TaxConstants;
import rs.raf.trading.tax.util.TaxRealizedGainCalculator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BE-PAY-04 (paritet sa BE-PAY-02 {@code InstallmentProcessor} i BE-PAY-03
 * {@code VariableRateProcessor}): per-user transaction processor za obracun
 * poreza na kapitalnu dobit.
 *
 * <p>Razlog izdvajanja: {@code TaxService.calculateTaxForAllUsers()} ranije je
 * imao outer {@code @Transactional} pa je <em>jedna</em> losa obrada (neocekivana
 * SQL greska mid-loop, OptimisticLockException, ili re-throw agregatnog
 * {@link TaxCalculationException} na kraju metode) povlacila rollback svih
 * prethodno uspesno persistovanih {@code TaxRecord}-a u istom batch-u. To je
 * suprotno SavingsScheduler / LoanInstallmentScheduler obrascu i intent-u
 * "obracunaj sto vise korisnika, izoluj greske po-korisniku".</p>
 *
 * <p>Resenje: ovaj bean ima {@link Propagation#REQUIRES_NEW} na
 * {@link #processOne(Long, String, List, Map, Map, Map, LocalDateTime)} —
 * svaki korisnik dobija svoju nezavisnu transakciju. Orkestrator
 * ({@code TaxService.calculateTaxForAllUsers}) iterira + delegira, hvata
 * exception per-user i nastavlja sa ostalima.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaxCalculatorProcessor {

    private final TaxRecordRepository taxRecordRepository;
    private final TaxRecordBreakdownRepository taxRecordBreakdownRepository;
    private final OrderRepository orderRepository;
    private final CurrencyConversionService currencyConversionService;
    private final BankaCoreClient bankaCoreClient;

    /**
     * BE-PAY-04: Obracun + naplata poreza za jednog korisnika u nezavisnoj
     * transakciji. Greska/rollback ne utice na druge korisnike u batch-u.
     *
     * <p>Logika je verno premestena iz {@code TaxService.calculateTaxForAllUsers}:
     * per-listing buy/sell agregacija (sa OTC kontribucijama), FX-u-RSD konverzija,
     * 15% taxOwed na pozitivan profit, naplata neplacenog preko banka-core
     * {@code collectTax}, per-listing breakdown persistencija.</p>
     *
     * <p>Bacanje {@link TaxCalculationException} (najcesce FX rate nedostupan)
     * propagira do orkestratora koji belezi pad u {@code perUserFailures} i
     * nastavlja sa sledecim korisnikom.</p>
     *
     * @param userId korisnik za kojeg se racuna porez
     * @param userType {@code CLIENT} ili {@code EMPLOYEE}
     * @param userOrders done STOCK/FOREX/FUTURES orderi ovog korisnika
     *                   (FUND orderi su filtrirani u orkestratoru)
     * @param otcSellByListing OTC EXERCISED prodaja po listingu (intra-bank)
     * @param otcBuyByListing OTC EXERCISED kupovina po listingu (intra-bank)
     * @param otcListingCurrency ISO kod valute po OTC listingu (RSD fallback)
     * @param now timestamp za {@code calculatedAt}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOne(Long userId,
                           String userType,
                           List<Order> userOrders,
                           Map<Long, BigDecimal> otcSellByListing,
                           Map<Long, BigDecimal> otcBuyByListing,
                           Map<Long, String> otcListingCurrency,
                           LocalDateTime now) {

        // P0-B3: realizovana kapitalna dobit po listingu sa FIFO cost-basis
        // lot-matching-om i mesecnim periodom (spec Celina 3 §517-523). Pre P0-B3
        // kod je radio sum(SELL)−sum(BUY) per-listing (laznja dobit kad qty(BUY)≠qty(SELL))
        // i bio lifetime-kumulativan (carry-forward greska iz proslih meseci).
        //
        // FIFO matchuje cost-basis po stvarno prodatoj kolicini; period broji SAMO
        // SELL-ove u tekucem settlement mesecu (cost-basis lot-ovi mogu poticati iz
        // ranijih meseci — kupovina u martu, prodaja u maju je legitimna).
        // S80: svaki listing se posle konvertuje u RSD pre agregacije.
        YearMonth period = TaxRealizedGainCalculator.settlementPeriod(now);
        YearMonth nowMonth = YearMonth.from(now);
        java.util.List<TaxRealizedGainCalculator.ListingRealizedGain> listingGains =
                TaxRealizedGainCalculator.computePeriodGains(
                        userOrders, otcSellByListing, otcBuyByListing, otcListingCurrency, period, nowMonth);

        BigDecimal totalProfit = BigDecimal.ZERO;
        java.util.List<PerListingProfit> perListingProfits = new java.util.ArrayList<>();
        for (TaxRealizedGainCalculator.ListingRealizedGain g : listingGains) {
            BigDecimal profitInRsd = convertToRsd(g.gainNative(), g.listingCurrency());
            totalProfit = totalProfit.add(profitInRsd);
            perListingProfits.add(new PerListingProfit(
                    g.listingId(), g.listingCurrency(), g.gainNative(), profitInRsd));
        }
        BigDecimal taxOwed = totalProfit.compareTo(BigDecimal.ZERO) > 0
                ? TaxConstants.computeTax(totalProfit)
                : BigDecimal.ZERO;

        String userName = resolveUserName(userId, userType);

        TaxRecord record = taxRecordRepository.findByUserIdAndUserType(userId, userType)
                .orElse(TaxRecord.builder()
                        .userId(userId)
                        .userType(userType)
                        .currency("RSD")
                        .taxPaid(BigDecimal.ZERO)
                        .build());

        record.setUserName(userName);
        record.setTotalProfit(totalProfit);
        record.setTaxOwed(taxOwed);
        record.setCalculatedAt(now);

        // B-1 fix: mesecni neplaceni porez = taxOwed(mesecni) − placeno-u-tom-mesecu.
        // Pre fix-a previouslyPaid = record.getTaxPaid() (lifetime-kumulativan) se
        // mesao sa mesecnim taxOwed → cross-month under-taxation. Sad su mesecni
        // owed i mesecni paid u istoj dimenziji; godisnji kumulativ (taxPaid) je
        // odvojen i prikazuje "otplaceno za tekucu kalendarsku godinu".
        BigDecimal unpaidTax = TaxRealizedGainCalculator.monthlyUnpaidTax(
                taxOwed, record.getTaxPaidPeriod(), record.getTaxPaidInPeriod(), period);

        if (unpaidTax.compareTo(BigDecimal.ZERO) > 0) {
            boolean collected = collectTaxFromUser(userId, userType, unpaidTax, period);
            if (collected) {
                TaxRealizedGainCalculator.MonthlyTaxState state =
                        TaxRealizedGainCalculator.applyMonthlyTaxPayment(
                                record.getTaxPaidPeriod(), record.getTaxPaidInPeriod(),
                                record.getTaxPaidYear(), record.getTaxPaid(),
                                period, unpaidTax);
                record.setTaxPaidPeriod(state.paidPeriod());
                record.setTaxPaidInPeriod(state.paidInPeriod());
                record.setTaxPaidYear(state.paidYear());
                record.setTaxPaid(state.paidAnnual());
                log.info("Tax collected from user {} ({}): {} RSD (period {}, annual paid {})",
                        userName, userType, unpaidTax, period, state.paidAnnual());
            } else {
                log.warn("Could not collect tax from user {} ({}): no account (RSD or foreign via FX) with sufficient funds",
                        userName, userType);
            }
        }

        taxRecordRepository.save(record);

        // P2.4 — perzistiraj per-listing breakdown stavke. Brisemo
        // postojeci breakdown pa ga regenerisemo iz svezih agregata.
        if (record.getId() != null) {
            taxRecordBreakdownRepository.deleteByTaxRecordId(record.getId());
            // R1-736: ticker se prvo razresava iz korisnikovih ordera (Listing je
            // vec ucitan na Order entitetu — bez DB poziva). DB-skenirajuci
            // resolveTicker (findByIsDoneTrue) se zove SAMO za OTC-only listinge
            // koje korisnik nema medju svojim orderima. Pre fix-a se pun table-scan
            // izvrsavao po SVAKOM distinktnom listingu (N+1 nad svim done orderima).
            Map<Long, String> tickerCache = buildTickerMapFromOrders(userOrders);
            for (PerListingProfit p : perListingProfits) {
                if (p.listingId() == null) {
                    continue;
                }
                BigDecimal listingTaxOwed = p.profitRsd().compareTo(BigDecimal.ZERO) > 0
                        ? TaxConstants.computeTax(p.profitRsd())
                        : BigDecimal.ZERO;
                String ticker = tickerCache.computeIfAbsent(p.listingId(),
                        id -> resolveTicker(p.listingId()));
                TaxRecordBreakdown breakdown = TaxRecordBreakdown.builder()
                        .taxRecord(record)
                        .listingId(p.listingId())
                        .ticker(ticker != null ? ticker : "?" + p.listingId())
                        .listingCurrency(p.listingCurrency() != null ? p.listingCurrency() : "RSD")
                        .profitNative(p.profitNative())
                        .profitRsd(p.profitRsd())
                        .taxOwed(listingTaxOwed)
                        .calculatedAt(now)
                        .build();
                taxRecordBreakdownRepository.save(breakdown);
            }
        }
    }

    /** Internal helper za prenos profita per-listing izmedju petlji. */
    private record PerListingProfit(Long listingId, String listingCurrency,
                                    BigDecimal profitNative, BigDecimal profitRsd) {}

    /**
     * Naplata neplacenog poreza sa korisnikovog RSD racuna preko banka-core
     * internog seam-a. Vraca {@code true} ako je naplata uspela.
     *
     * <p>CLIENT grana: banka-core razresava klijentov RSD racun + drzavni racun
     * (dvojno knjizenje + audit). {@code collected=false} ako klijent nema RSD
     * racun ili nema dovoljno sredstava.
     *
     * <p>EMPLOYEE grana: bank actuaries trguju sa bankinih racuna; porez se
     * samo belezi (no-op u banka-core seam-u).
     *
     * <p>Idempotency key se vezuje za settlement {@code period} (NE run-mesec) +
     * iznos naplate. B-1: cron run 1. u mesecu naplacuje <em>prethodni</em> mesec,
     * pa {@code YearMonth.from(calculatedAt)} (run-mesec) ne bi odgovarao periodu;
     * sad key koristi {@code period}. Iznos u kljucu (holisticki I-2 fix): pravi
     * SAGA retry iste naplate ima isti {@code unpaidTax} → isti kljuc → bezbedan
     * replay; intra-mesecni re-run sa novim trgovinama ima razlicit inkrement →
     * razlicit kljuc → korektno naplacuje samo nov deo.
     */
    private boolean collectTaxFromUser(Long userId, String userType, BigDecimal amount, YearMonth period) {
        if (UserRole.isClient(userType)) {
            String idempotencyKey = "tax-" + userId + "-" + period
                    + "-" + amount.toPlainString();
            try {
                TaxCollectResponse response = bankaCoreClient.collectTax(idempotencyKey,
                        new TaxCollectRequest(userId, amount, "Porez na kapitalnu dobit"));
                return response != null && response.collected();
            } catch (BankaCoreClientException e) {
                log.warn("Tax collection failed for client #{}: {}", userId, e.getMessage());
                return false;
            }
        }
        // Za zaposlene: koriste bankin racun — porez se samo belezi.
        return true;
    }

    /**
     * Konvertuje iznos u RSD. Ako je vec u RSD, vraca isti iznos.
     * <p>BE-ORD-08: FX failure baca {@link TaxCalculationException} — nikad
     * fallback na sirov iznos (npr. USD 1000 kao 1000 RSD = severe under-tax).
     */
    private BigDecimal convertToRsd(BigDecimal amount, String fromCurrency) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        if (fromCurrency == null || "RSD".equalsIgnoreCase(fromCurrency)) {
            return amount;
        }
        try {
            return currencyConversionService.convert(amount, fromCurrency, "RSD");
        } catch (Exception e) {
            log.error("Currency conversion {} -> RSD failed: {}", fromCurrency, e.getMessage());
            throw new TaxCalculationException(null, null,
                    "FX rate unavailable for " + fromCurrency
                            + ", cannot compute tax in RSD", e);
        }
    }

    /**
     * Razresava ime i prezime korisnika preko banka-core internog API-ja.
     * Rezilijentno — na gresku vraca placeholder.
     */
    private String resolveUserName(Long userId, String userRole) {
        boolean employee = UserRole.isEmployee(userRole);
        try {
            InternalUserDto user = bankaCoreClient.getUserById(
                    employee ? UserRole.EMPLOYEE : UserRole.CLIENT, userId);
            return user.firstName() + " " + user.lastName();
        } catch (BankaCoreClientException e) {
            return employee ? "Zaposleni #" + userId : "Klijent #" + userId;
        }
    }

    /**
     * R1-736: gradi {@code listingId -> ticker} mapu iz korisnikovih ordera
     * (Listing je vec ucitan na Order entitetu — nula DB poziva). Sluzi kao
     * pre-popunjen kes za breakdown petlju da DB-skenirajuci {@link #resolveTicker}
     * ne mora da se zove za listinge koje korisnik ima medju svojim orderima.
     */
    private static Map<Long, String> buildTickerMapFromOrders(List<Order> userOrders) {
        Map<Long, String> map = new HashMap<>();
        if (userOrders == null) {
            return map;
        }
        for (Order o : userOrders) {
            if (o != null && o.getListing() != null && o.getListing().getId() != null
                    && o.getListing().getTicker() != null) {
                map.putIfAbsent(o.getListing().getId(), o.getListing().getTicker());
            }
        }
        return map;
    }

    /** Resolva ticker iz orderRepository (single per-listing query). */
    private String resolveTicker(Long listingId) {
        if (listingId == null) {
            return null;
        }
        try {
            List<Order> orders = orderRepository.findByIsDoneTrue();
            if (orders == null) {
                return null;
            }
            return orders.stream()
                    .filter(o -> o != null && o.getListing() != null
                            && listingId.equals(o.getListing().getId()))
                    .map(o -> o.getListing().getTicker())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.debug("resolveTicker failed for listing {}: {}", listingId, e.getMessage());
            return null;
        }
    }
}
