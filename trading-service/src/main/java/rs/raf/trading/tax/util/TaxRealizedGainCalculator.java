package rs.raf.trading.tax.util;

import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.stock.util.ListingCurrencyResolver;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0-B3: deljeni obracun realizovane kapitalne dobiti po listingu, sa
 * <b>FIFO cost-basis lot-matching</b> i <b>mesecnim periodom</b>.
 *
 * <p>Spec (Celina 3 §517-523):
 * <ul>
 *   <li>§517-518: porez = 15% na <em>kapitalnu dobit</em> = proceeds(prodate
 *       kolicine) − cost-basis(prodate kolicine). Cost-basis se matchuje po
 *       konkretno prodatoj kolicini, NE po ukupno kupljenoj. Pre P0-B3 kod je
 *       racunao {@code sum(SELL) − sum(BUY)} → laznja dobit/gubitak kad qty(BUY)
 *       ≠ qty(SELL) (npr. BUY 20 / SELL 15 nosio cost svih 20 protiv 15 prodatih).</li>
 *   <li>§520: obracunski period je <b>mesecni</b> — porez se naplacuje na kraju
 *       svakog meseca na dobit realizovanu <em>u tom mesecu</em>. Pre P0-B3 kod je
 *       bio lifetime-kumulativan (svaki SELL ikad je ulazio), pa je dobit iz proslih
 *       meseci nosila carry-forward gresku.</li>
 * </ul>
 *
 * <p><b>Model:</b> za svaki listing gradi FIFO red kupovnih lot-ova
 * (chronoloski, po {@code createdAt} → {@code lastModification} → {@code id}).
 * Na svaki SELL matchuje prodatu kolicinu protiv najstarijih lot-ova i racuna
 * realizovanu dobit. <b>Cost-basis lot-ovi mogu poticati iz BILO kog meseca</b>
 * (kupovina u martu, prodaja u maju je legitimna), ali se u {@code totalGain}
 * akumulira <b>samo dobit SELL-ova ciji {@code createdAt} pada u settlement
 * {@code period}</b>. SELL bez timestamp-a (legacy/test orderi) se tretira kao
 * in-period (backward-compat).
 *
 * <p>Kolicina u akcijama = {@code quantity × contractSize} (verno starom
 * {@code orderValue = price × qty × contractSize}); jedinicna cena = {@code pricePerUnit}.
 *
 * <p><b>Intra-bank OTC</b> (EXERCISED ugovori) ulazi kao vec-matchovan par
 * (buy+sell iz jednog izvrsenog ugovora) — njegova neto realizovana vrednost
 * ({@code otcSell − otcBuy}) se dodaje direktno na period gain (ne prolazi kroz
 * FIFO jer su to agregirane vrednosti bez per-lot kolicine). OTC EXERCISED se
 * desava u tekucem run-u pa pripada tekucem periodu.
 *
 * <p><b>PRIHVACENA DEVIACIJA (OTC-net van FIFO):</b> OTC exercise NE kreira
 * Order red (vidi {@code OtcExerciseSagaOrchestrator} — nema {@code orderRepository.save}),
 * pa je {@code otcSell}/{@code otcBuy} jedini poreski signal za isporucene
 * akcije. Po-korisniku se prodavcu prosledi samo sell-strana ({@code strike+premium})
 * a kupcu samo buy-strana — net za prodavca je {@code (strike+premija) − 0} jer
 * prodavac nema OTC buy na tom listingu. To znaci da se prodavcu NE oduzima
 * <em>nabavna cena akcije</em> koju isporucuje (FIFO cost-basis underlying-a),
 * pa je prodavceva dobit <b>precenjena</b> (pun strike kao proceeds bez troska).
 * Korektan obracun bi oduzeo prodavcev FIFO cost-basis isporucenih akcija, ali
 * te kolicine/nabavne cene nisu prosledjene u ovaj util (samo agregat
 * {@code strike+premium}). Posledica je <em>over-taxation</em> prodavca (u korist
 * drzave), NE under-taxation — bezbedna strana sa fiskalne tacke. Wire-ovanje
 * prodavcevog portfolio cost-basis-a u OTC tax putanju je zaseban feature
 * (dodirivao bi OTC saga + portfolio snapshot), van scope-a B-1 fix-a. Kupac
 * dobija negativan net ({@code 0 − (strike+premija)}) tretiran kao trosak/gubitak.
 */
public final class TaxRealizedGainCalculator {

    private TaxRealizedGainCalculator() {}

    /** Realizovana dobit za jedan listing u native valuti listinga. */
    public record ListingRealizedGain(Long listingId, String listingCurrency, BigDecimal gainNative) {}

    private record BuyLot(BigDecimal shares, BigDecimal unitCost) {}

    /**
     * Izvedi settlement {@link YearMonth} iz {@code now}. Scheduler okida cron
     * {@code 0 0 0 1 * *} (prvi dan meseca tacno u <b>00:00:00</b>) da naplati
     * <b>prethodni</b> mesec ("na kraju svakog meseca"). Rucni supervizorski
     * okidac (ili bilo koji {@code now}-vodjen poziv) naplacuje <b>tekuci</b>
     * mesec.
     *
     * <p><b>Cron granica = TACNO {@code 00:00:00} 1. u mesecu</b> (sekunda i
     * minut == 0). Ranija heuristika je gledala samo {@code hour == 0}, pa je
     * <em>svaki</em> {@code now}-vodjen poziv u prvom satu 1. dana (npr. rucni
     * okidac ili {@code calculateTaxForAllUsers()} pozvan u 00:51) bio pogresno
     * tretiran kao backdated cron prethodnog meseca. To je u sprezi sa
     * null-timestamp {@link #inPeriod} heuristikom ({@code period == nowMonth})
     * izbacivalo SVE legacy/null-timestamp SELL-ove iz obracuna ({@code gain 0})
     * — under-taxation za sve takve ordere kad god run padne u prvi sat 1. u
     * mesecu. Spring {@code @Scheduled(cron = "0 0 0 1 * *")} uvek okida na
     * sekundi 0/minutu 0, pa vezivanje za {@code 00:00:00} precizno razdvaja
     * pravi cron (settle prethodnog meseca) od {@code now}-poziva (tekuci mesec).
     */
    public static YearMonth settlementPeriod(LocalDateTime now) {
        boolean cronBoundary = now.getDayOfMonth() == 1 && now.getHour() == 0
                && now.getMinute() == 0 && now.getSecond() == 0;
        YearMonth ym = YearMonth.from(now);
        return cronBoundary ? ym.minusMonths(1) : ym;
    }

    /**
     * P0-B3 (B-1 fix): mesecni neplaceni porez = {@code taxOwed(mesecni) −
     * placeno-u-tom-mesecu}. Pre B-1 fix-a se {@code taxOwed} (mesecni) oduzimao
     * od {@code taxPaid} (lifetime-kumulativan) → cross-month under-taxation
     * (maj naplata "kvarila" jun owed, jun se nikad nije naplatio).
     *
     * <p>Mesecni paid ({@code record.taxPaidInPeriod}) i mesecni owed su sad u
     * istoj dimenziji. Ako se settlement {@code period} promenio od poslednje
     * naplate (novi mesec), mesecni paid bucket je 0 (resetuje se lenjo u
     * {@link #applyMonthlyTaxPayment}). Vraca iznos koji treba naplatiti u ovom
     * run-u (≥ 0); {@code taxOwed − placeno} ako je pozitivan, inace 0.
     */
    public static BigDecimal monthlyUnpaidTax(BigDecimal taxOwedMonthly,
                                              String recordPaidPeriod,
                                              BigDecimal recordPaidInPeriod,
                                              YearMonth period) {
        BigDecimal owed = taxOwedMonthly != null ? taxOwedMonthly : BigDecimal.ZERO;
        BigDecimal paidThisPeriod = samePeriod(recordPaidPeriod, period) && recordPaidInPeriod != null
                ? recordPaidInPeriod
                : BigDecimal.ZERO;
        BigDecimal unpaid = owed.subtract(paidThisPeriod);
        return unpaid.signum() > 0 ? unpaid : BigDecimal.ZERO;
    }

    /**
     * P0-B3 (B-1 fix): primeni uspesnu naplatu {@code collectedAmount} na record.
     * Azurira <b>mesecni</b> paid bucket ({@code taxPaidInPeriod}/{@code taxPaidPeriod})
     * i <b>godisnji</b> kumulativ ({@code taxPaid}/{@code taxPaidYear}) — dve
     * odvojene velicine (spec Celina 3 §488: "otplacen za tekucu kalendarsku
     * godinu" = godisnji kumulativ; "neplacen za tekuci mesec" = mesecni owed −
     * mesecni paid). Resetuje mesecni bucket pri promeni meseca i godisnji
     * kumulativ pri promeni kalendarske godine, pre dodavanja.
     *
     * @return novi godisnji kumulativ ({@code taxPaid}) posle primene; pozivalac
     *         setuje record polja iz vracenog {@link MonthlyTaxState}.
     */
    public static MonthlyTaxState applyMonthlyTaxPayment(String recordPaidPeriod,
                                                         BigDecimal recordPaidInPeriod,
                                                         Integer recordPaidYear,
                                                         BigDecimal recordPaidAnnual,
                                                         YearMonth period,
                                                         BigDecimal collectedAmount) {
        BigDecimal collected = collectedAmount != null ? collectedAmount : BigDecimal.ZERO;
        // Mesecni bucket: reset na 0 ako je novi mesec.
        BigDecimal monthlyBase = samePeriod(recordPaidPeriod, period) && recordPaidInPeriod != null
                ? recordPaidInPeriod
                : BigDecimal.ZERO;
        BigDecimal newMonthlyPaid = monthlyBase.add(collected);
        // Godisnji kumulativ: reset na 0 ako je nova kalendarska godina.
        int year = period.getYear();
        BigDecimal annualBase = (recordPaidYear != null && recordPaidYear == year && recordPaidAnnual != null)
                ? recordPaidAnnual
                : BigDecimal.ZERO;
        BigDecimal newAnnualPaid = annualBase.add(collected);
        return new MonthlyTaxState(period.toString(), newMonthlyPaid, year, newAnnualPaid);
    }

    /** Rezultat {@link #applyMonthlyTaxPayment}: nova stanja koja pozivalac setuje na TaxRecord. */
    public record MonthlyTaxState(String paidPeriod, BigDecimal paidInPeriod,
                                  int paidYear, BigDecimal paidAnnual) {}

    private static boolean samePeriod(String recordPaidPeriod, YearMonth period) {
        return recordPaidPeriod != null && period != null
                && recordPaidPeriod.equals(period.toString());
    }

    /**
     * Racuna realizovanu dobit po listingu za dati period.
     *
     * @param userOrders        DONE STOCK/FOREX/FUTURES orderi jednog korisnika
     * @param otcSellByListing  OTC EXERCISED prodaja po listingu (native)
     * @param otcBuyByListing   OTC EXERCISED kupovina po listingu (native)
     * @param otcListingCurrency ISO valuta po OTC listingu (RSD fallback)
     * @param period            settlement mesec — broje se SAMO SELL-ovi u ovom mesecu
     * @param nowMonth          mesec od {@code now} (kalendarski mesec run-a). Koristi
     *                          se za null-timestamp backward-compat: SELL bez
     *                          timestamp-a (legacy/test order) je in-period SAMO kad
     *                          je {@code period == nowMonth} (tj. tekuci-mesec run,
     *                          ne backdated cron prethodnog meseca) — vidi {@link #inPeriod}.
     * @return per-listing realizovana dobit (native), redosled deterministican
     */
    public static List<ListingRealizedGain> computePeriodGains(
            List<Order> userOrders,
            Map<Long, BigDecimal> otcSellByListing,
            Map<Long, BigDecimal> otcBuyByListing,
            Map<Long, String> otcListingCurrency,
            YearMonth period,
            YearMonth nowMonth) {

        // listingId -> svi orderi tog listinga (za FIFO)
        Map<Long, List<Order>> ordersByListing = new LinkedHashMap<>();
        Map<Long, String> currencyByListing = new LinkedHashMap<>();
        if (userOrders != null) {
            for (Order o : userOrders) {
                if (o == null || o.getListing() == null) {
                    continue;
                }
                Long listingId = o.getListing().getId();
                ordersByListing.computeIfAbsent(listingId, k -> new ArrayList<>()).add(o);
                currencyByListing.putIfAbsent(listingId,
                        ListingCurrencyResolver.resolveSafe(o.getListing(), "RSD"));
            }
        }

        // listingId -> realizovana dobit u periodu (native)
        Map<Long, BigDecimal> gainByListing = new LinkedHashMap<>();

        for (Map.Entry<Long, List<Order>> e : ordersByListing.entrySet()) {
            Long listingId = e.getKey();
            BigDecimal gain = fifoPeriodGain(e.getValue(), period, nowMonth);
            if (gain.signum() != 0) {
                gainByListing.merge(listingId, gain, BigDecimal::add);
            } else {
                gainByListing.putIfAbsent(listingId, BigDecimal.ZERO);
            }
        }

        // OTC: net (sell - buy) po listingu, dodato na period gain. EXERCISED se
        // desava u tekucem run-u → pripada periodu.
        addOtcNet(gainByListing, currencyByListing, otcSellByListing, otcBuyByListing,
                otcListingCurrency, true);
        addOtcNet(gainByListing, currencyByListing, otcBuyByListing, otcBuyByListing,
                otcListingCurrency, false); // osigurava da i buy-only OTC listinzi imaju valutu

        List<ListingRealizedGain> result = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : gainByListing.entrySet()) {
            // Realizovani listinzi su oni sa nenultim gain-om ILI sa SELL-om u periodu.
            // Da bismo zadrzali raniju semantiku (breakdown samo za realizovane), ukljucujemo
            // listing ako ima nenultu dobit; nula-dobit listinzi (samo buy / van perioda) se
            // izostavljaju iz totala ali se NE prikazuju kao gubitak.
            BigDecimal gain = e.getValue();
            if (gain.signum() == 0 && !hasPeriodSell(ordersByListing.get(e.getKey()), period, nowMonth)
                    && !hasOtc(e.getKey(), otcSellByListing)) {
                continue;
            }
            result.add(new ListingRealizedGain(e.getKey(),
                    currencyByListing.getOrDefault(e.getKey(), "RSD"), gain));
        }
        return result;
    }

    private static boolean hasPeriodSell(List<Order> orders, YearMonth period, YearMonth nowMonth) {
        if (orders == null) {
            return false;
        }
        return orders.stream().anyMatch(o -> o.getDirection() == OrderDirection.SELL
                && inPeriod(orderTimestamp(o), period, nowMonth));
    }

    private static boolean hasOtc(Long listingId, Map<Long, BigDecimal> otcSell) {
        // Null-safe: listingId moze biti null (transient/detached Listing bez
        // perzistovanog id-a, ili legacy/test order). OTC kontribucije se uvek
        // vode po realnom (ne-null) listingId-u — null listing nema OTC ugovor.
        // Dodatni razlog: {@code otcSell} cesto stize kao immutable {@code Map.of()}
        // (TaxService.calculateTaxForAllUsers -> getOrDefault(key, Map.of())), a
        // {@code Map.of().containsKey(null)} baca NPE (immutable mape zabranjuju
        // null kljuc). Kratki spoj na null pre containsKey poziva.
        if (listingId == null) {
            return false;
        }
        return otcSell != null && otcSell.containsKey(listingId);
    }

    private static void addOtcNet(Map<Long, BigDecimal> gainByListing,
                                  Map<Long, String> currencyByListing,
                                  Map<Long, BigDecimal> otcSellByListing,
                                  Map<Long, BigDecimal> otcBuyByListing,
                                  Map<Long, String> otcListingCurrency,
                                  boolean applyNet) {
        if (otcSellByListing == null) {
            return;
        }
        for (Map.Entry<Long, BigDecimal> entry : otcSellByListing.entrySet()) {
            Long listingId = entry.getKey();
            currencyByListing.putIfAbsent(listingId,
                    otcListingCurrency != null
                            ? otcListingCurrency.getOrDefault(listingId, "RSD")
                            : "RSD");
            if (!applyNet) {
                continue;
            }
            BigDecimal sell = entry.getValue() != null ? entry.getValue() : BigDecimal.ZERO;
            BigDecimal buy = (otcBuyByListing != null)
                    ? otcBuyByListing.getOrDefault(listingId, BigDecimal.ZERO)
                    : BigDecimal.ZERO;
            gainByListing.merge(listingId, sell.subtract(buy), BigDecimal::add);
        }
    }

    /**
     * FIFO cost-basis za jedan listing. Vraca zbir realizovane dobiti SELL-ova
     * ciji {@code createdAt} pada u {@code period}. Null-timestamp SELL je
     * in-period samo kad je {@code period == nowMonth} (vidi {@link #inPeriod}).
     */
    private static BigDecimal fifoPeriodGain(List<Order> listingOrders, YearMonth period, YearMonth nowMonth) {
        List<Order> sorted = new ArrayList<>(listingOrders);
        // Hronoloski po timestamp-u; pri jednakom (ili oba null) timestamp-u BUY ide
        // PRE SELL-a (ne moze se prodati pre kupovine u istom trenutku), pa tek onda
        // po id-u. Bez BUY-pre-SELL tiebreaker-a, orderi bez timestamp-a (legacy/test)
        // bi se sortirali po (random) id-u → SELL bi mogao da prethodi svom BUY lot-u
        // i da dobije cost-basis 0.
        sorted.sort(Comparator
                .comparing((Order o) -> orderTimestamp(o),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(o -> o.getDirection() == OrderDirection.BUY ? 0 : 1)
                .thenComparing(o -> o.getId() == null ? Long.MAX_VALUE : o.getId()));

        Deque<BuyLot> lots = new ArrayDeque<>();
        BigDecimal periodGain = BigDecimal.ZERO;

        for (Order o : sorted) {
            BigDecimal shares = shares(o);
            BigDecimal unitPrice = o.getPricePerUnit() != null ? o.getPricePerUnit() : BigDecimal.ZERO;

            if (o.getDirection() == OrderDirection.BUY) {
                if (shares.signum() > 0) {
                    lots.addLast(new BuyLot(shares, unitPrice));
                }
                continue;
            }

            // SELL: matchuj prodatu kolicinu protiv FIFO lot-ova.
            BigDecimal remaining = shares;
            BigDecimal matchedCost = BigDecimal.ZERO;
            while (remaining.signum() > 0 && !lots.isEmpty()) {
                BuyLot lot = lots.peekFirst();
                BigDecimal take = lot.shares().min(remaining);
                matchedCost = matchedCost.add(take.multiply(lot.unitCost()));
                BigDecimal leftover = lot.shares().subtract(take);
                lots.removeFirst();
                if (leftover.signum() > 0) {
                    lots.addFirst(new BuyLot(leftover, lot.unitCost()));
                }
                remaining = remaining.subtract(take);
            }
            // Nepokrivena prodaja (nema buy lot-a) → cost-basis 0 (verno staroj sell-only
            // semantici: proceeds bez troska).
            BigDecimal proceeds = shares.multiply(unitPrice);
            BigDecimal gain = proceeds.subtract(matchedCost);

            if (inPeriod(orderTimestamp(o), period, nowMonth)) {
                periodGain = periodGain.add(gain);
            }
        }
        return periodGain;
    }

    private static BigDecimal shares(Order o) {
        long qty = o.getQuantity() != null ? o.getQuantity() : 0L;
        // OT-1048: default contractSize po tipu hartije (FOREX → 1000 per spec §162),
        // ne slepo 1 — uskladjeno sa OrderServiceImpl rezervacijom i ListingMapper
        // display-em, da realizovana dobit (cost-basis i proceeds) na FOREX nogama
        // ne bude mis-pricovana za faktor 1000 ako order.contractSize izostane.
        rs.raf.trading.stock.model.ListingType type =
                o.getListing() != null ? o.getListing().getListingType() : null;
        long cs = rs.raf.trading.stock.model.ContractSize.resolve(o.getContractSize(), type);
        return BigDecimal.valueOf(qty).multiply(BigDecimal.valueOf(cs));
    }

    private static LocalDateTime orderTimestamp(Order o) {
        if (o.getCreatedAt() != null) {
            return o.getCreatedAt();
        }
        return o.getLastModification();
    }

    /**
     * Da li realizovani dogadjaj (SELL ili OTC exercise) pripada {@code period}-u.
     *
     * <p>Sa timestamp-om: {@code YearMonth.from(ts) == period}.
     *
     * <p><b>Null-timestamp backward-compat (B-1 sprega):</b> SELL bez timestamp-a
     * (samo legacy/test orderi — produkcioni orderi uvek imaju {@code createdAt}
     * ili {@code lastModification}) je in-period SAMO kad je {@code period ==
     * nowMonth}, tj. kad je ovo tekuci-mesec run a NE backdated cron settlement
     * prethodnog meseca. Pre B-1 fix-a null-timestamp je bio "uvek in-period" pa
     * je legacy SELL ulazio u <em>svaki</em> mesecni run → sa mesecnim
     * paid-bucket reset-om (B-1) bi se naplacivao iznova svaki mesec
     * (cross-month duplo oporezivanje). Vezivanjem za {@code nowMonth} legacy
     * SELL se obracuna tacno jednom — u mesecu kad se prvi put procesira —
     * i nikad u automatskom cron prethodnog-meseca run-u (vidi
     * {@code TaxCalculatorProcessorTest#nullTimestampSellNotReTaxedInBackdatedCronRun}).
     *
     * <p><b>P2-tax OTC period-scoping (01.06):</b> ova metoda je {@code public}
     * da je {@code TaxService} koristi za period-gate OTC EXERCISED kontribucija
     * po {@code exercisedAt} — TACNO isti period model kao order SELL leg.
     * EXERCISED ugovor zauvek ostaje EXERCISED u tabeli; bez ovog gate-a isti
     * jednokratni OTC dobitak bi se re-oporezovao na SVAKOM mesecnom run-u
     * (over-taxation). OTC exercise sa null {@code exercisedAt} (legacy) je
     * in-period samo za tekuci-mesec run (isti backward-compat kao null-ts SELL).
     */
    public static boolean inPeriod(LocalDateTime ts, YearMonth period, YearMonth nowMonth) {
        if (ts == null) {
            // Null timestamp: in-period samo za tekuci-mesec (now) run, ne za
            // backdated/cron prethodni mesec → sprecava cross-month duplikaciju.
            return period == null || period.equals(nowMonth);
        }
        if (period == null) {
            return true;
        }
        return YearMonth.from(ts).equals(period);
    }
}
