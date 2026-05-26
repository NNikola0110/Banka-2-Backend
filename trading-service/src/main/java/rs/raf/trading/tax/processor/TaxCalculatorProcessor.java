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
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.stock.util.ListingCurrencyResolver;
import rs.raf.trading.tax.model.TaxRecord;
import rs.raf.trading.tax.model.TaxRecordBreakdown;
import rs.raf.trading.tax.repository.TaxRecordBreakdownRepository;
import rs.raf.trading.tax.repository.TaxRecordRepository;
import rs.raf.trading.tax.service.TaxCalculationException;
import rs.raf.trading.tax.util.TaxConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // Racunamo profit per-asset: za svaki listing posebno racunamo sell - buy
        // pa sabiramo samo pozitivne profite (kapitalna dobit).
        // S80: Svi iznosi se konvertuju u RSD pre agregacije, jer orderi mogu
        // biti u razlicitim valutama (USD, EUR, RSD...).
        Map<Long, BigDecimal> buyByListing = new HashMap<>();
        Map<Long, BigDecimal> sellByListing = new HashMap<>();
        Map<Long, String> currencyByListing = new HashMap<>();

        for (Order order : userOrders) {
            Long listingId = order.getListing().getId();
            BigDecimal orderValue = order.getPricePerUnit()
                    .multiply(BigDecimal.valueOf(order.getQuantity()))
                    .multiply(BigDecimal.valueOf(order.getContractSize()));

            currencyByListing.putIfAbsent(listingId, resolveOrderCurrency(order));

            if (order.getDirection() == OrderDirection.SELL) {
                sellByListing.merge(listingId, orderValue, BigDecimal::add);
            } else {
                buyByListing.merge(listingId, orderValue, BigDecimal::add);
            }
        }

        // OTC EXERCISED kontribucije za ovog korisnika.
        if (otcSellByListing != null) {
            otcSellByListing.forEach((listingId, value) -> {
                sellByListing.merge(listingId, value, BigDecimal::add);
                currencyByListing.putIfAbsent(listingId,
                        otcListingCurrency != null
                                ? otcListingCurrency.getOrDefault(listingId, "RSD")
                                : "RSD");
            });
        }
        if (otcBuyByListing != null) {
            otcBuyByListing.forEach((listingId, value) -> {
                buyByListing.merge(listingId, value, BigDecimal::add);
                currencyByListing.putIfAbsent(listingId,
                        otcListingCurrency != null
                                ? otcListingCurrency.getOrDefault(listingId, "RSD")
                                : "RSD");
            });
        }

        // Za svaki listing: profit = sell - buy, konvertuj u RSD, akumuliraj.
        // NET dobit/gubitak se racuna preko svih listinga; porez je 0 ako je total <= 0.
        // P2.4 — biljezimo per-listing breakdown za prikaz/audit.
        //
        // Spec §517 + bug prijavljen 12.05.2026: pre fix-a, listings sa SAMO buy
        // (bez sell) su davali profit = 0 - buy = -buy → laznja indikacija "gubitka".
        // Porez se po spec-u racuna SAMO na REALIZOVANU dobit (kad je prodaja
        // izvrsena). Sad ukljucujemo samo listings sa sell > 0.
        BigDecimal totalProfit = BigDecimal.ZERO;
        Set<Long> realizedListings = new HashSet<>(sellByListing.keySet());
        java.util.List<PerListingProfit> perListingProfits = new java.util.ArrayList<>();
        for (Long listingId : realizedListings) {
            BigDecimal sell = sellByListing.getOrDefault(listingId, BigDecimal.ZERO);
            BigDecimal buy = buyByListing.getOrDefault(listingId, BigDecimal.ZERO);
            BigDecimal assetProfit = sell.subtract(buy);
            String listingCurrency = currencyByListing.getOrDefault(listingId, "RSD");
            BigDecimal profitInRsd = convertToRsd(assetProfit, listingCurrency);
            totalProfit = totalProfit.add(profitInRsd);
            perListingProfits.add(new PerListingProfit(
                    listingId, listingCurrency, assetProfit, profitInRsd));
        }
        BigDecimal taxOwed = totalProfit.compareTo(BigDecimal.ZERO) > 0
                ? totalProfit.multiply(TaxConstants.TAX_RATE).setScale(4, RoundingMode.HALF_UP)
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

        // Naplati neplaceni porez sa korisnikovog racuna
        BigDecimal previouslyPaid = record.getTaxPaid() != null ? record.getTaxPaid() : BigDecimal.ZERO;
        BigDecimal unpaidTax = taxOwed.subtract(previouslyPaid);

        if (unpaidTax.compareTo(BigDecimal.ZERO) > 0) {
            boolean collected = collectTaxFromUser(userId, userType, unpaidTax, now);
            if (collected) {
                record.setTaxPaid(taxOwed);
                log.info("Tax collected from user {} ({}): {} RSD", userName, userType, unpaidTax);
            } else {
                log.warn("Could not collect tax from user {} ({}): no RSD account or insufficient funds",
                        userName, userType);
            }
        }

        taxRecordRepository.save(record);

        // P2.4 — perzistiraj per-listing breakdown stavke. Brisemo
        // postojeci breakdown pa ga regenerisemo iz svezih agregata.
        if (record.getId() != null) {
            taxRecordBreakdownRepository.deleteByTaxRecordId(record.getId());
            Map<Long, String> tickerCache = new HashMap<>();
            for (PerListingProfit p : perListingProfits) {
                if (p.listingId() == null) {
                    continue;
                }
                BigDecimal listingTaxOwed = p.profitRsd().compareTo(BigDecimal.ZERO) > 0
                        ? p.profitRsd().multiply(TaxConstants.TAX_RATE)
                                .setScale(4, RoundingMode.HALF_UP)
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
     * <p>Idempotency key ukljucuje iznos naplate (holisticki I-2 fix) tako da
     * intra-mesecni re-run sa novim trgovinama generise razlicit kljuc.
     */
    private boolean collectTaxFromUser(Long userId, String userType, BigDecimal amount, LocalDateTime calculatedAt) {
        if (UserRole.isClient(userType)) {
            String idempotencyKey = "tax-" + userId + "-" + YearMonth.from(calculatedAt)
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
     * Resolve-uje ISO kod valute za listing ordera. Tax modul koristi RSD
     * kao fallback (sve se svodi na RSD pri obracunu poreza).
     */
    private String resolveOrderCurrency(Order order) {
        if (order == null || order.getListing() == null) {
            return "RSD";
        }
        return ListingCurrencyResolver.resolveSafe(order.getListing(), "RSD");
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
