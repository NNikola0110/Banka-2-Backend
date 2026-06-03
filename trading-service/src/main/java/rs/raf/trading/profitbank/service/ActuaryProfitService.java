package rs.raf.trading.profitbank.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.profitbank.dto.ProfitBankDtos.ActuaryProfitDto;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.util.ListingCurrencyResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P6 — Spec Celina 4 (Nova) §4393-4505 (Stranica: Profit aktuara).
 *
 * Za svakog aktuara (supervizor + agent) racuna OSTVAREN (realized) profit u RSD:
 *   - Per-listing: FIFO sparivanje BUY→SELL lotova (samo done orderi)
 *   - Realizovan P/L = Σ (sellCena − buyCena) × sparena kolicina za zatvorene lotove
 *   - Konvertuj u RSD po srednjem kursu (bez komisije)
 *   - Sumiraj kroz sve listinge
 *
 * <p><b>R1 507 (FIFO / nema lazni gubitak za otvorenu poziciju):</b> spec
 * ("ostvareni profit") = REALIZOVAN P/L za ZATVORENE (BUY→SELL) lotove, ne
 * mark-to-market neostvarenih pozicija. Stari kod je radio prost
 * {@code Σ SELL − Σ BUY} po listingu, pa je OTVORENA pozicija (samo BUY, jos
 * neprodato) prikazivana kao OGROMAN LAZNI GUBITAK (npr. BUY 10×100 bez SELL →
 * −1000). FIFO sparivanje resava to: nesparen BUY (otvorena duga pozicija) ne
 * realizuje nista (0), a nesparen SELL (prodaja bez evidentirane kupovine —
 * legacy/seed pozicija) realizuje pun prihod (zero cost-basis, paritet sa
 * starom SELL-only semantikom). Tako BUY-only pozicija daje 0 umesto laznog
 * gubitka, a sparen BUY→SELL lot daje tacan realizovan P/L.</p>
 *
 * Cache: Caffeine sa TTL 5 min (vidi {@link ProfitBankCacheConfig}).
 * Iteracija po svim DONE orderima + per-order FX konverzija je O(n) u
 * broju ordera; posle 1000+ ordera u bazi sirov racun traje ~1-2s. Cache
 * smanjuje na ~5ms na ponovljene pozive sa istim ulazom.
 *
 * <p>NAPOMENA (copy-first ekstrakcija, faza 2d-E): {@code Order}/{@code Listing}
 * su lokalni trading-service entiteti (faza 2b/2c). Identitet aktuara
 * (zaposlenog) je banka-core domen — monolitni {@code EmployeeRepository}
 * lookup je zamenjen {@link BankaCoreClient#getUserById(String, Long)}
 * (ime + prezime) i {@link BankaCoreClient#getUserPermissions(String)}
 * (SUPERVISOR vs AGENT pozicija). Servis nista ne mutira — nema novcanog
 * seam-a; samo cita i racuna.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActuaryProfitService {

    private final OrderRepository orderRepository;
    private final BankaCoreClient bankaCoreClient;
    private final CurrencyConversionService currencyConversionService;

    @Cacheable(value = ProfitBankCacheConfig.ACTUARY_PROFIT_CACHE, sync = true)
    public List<ActuaryProfitDto> listAllActuariesProfit() {
        // 1) Skupi sve DONE ordere koje su inicirali zaposleni (userRole=EMPLOYEE),
        //    hronoloski (FIFO red sparivanja BUY->SELL). createdAt je vreme
        //    kreiranja naloga; null-ovi (legacy/seed) idu na kraj stabilno.
        List<Order> doneEmployeeOrders = orderRepository.findByIsDoneTrue().stream()
                .filter(o -> UserRole.isEmployee(o.getUserRole()))
                .filter(o -> o.getListing() != null)
                .sorted(Comparator.comparing(Order::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(o -> o.getId() == null ? Long.MAX_VALUE : o.getId()))
                .toList();

        // 2) Per-aktuar: per-listing lista lotova (u hronoloskom redu) + valuta + broj naloga.
        Map<Long, Map<Long, List<Lot>>> lotsByActuarPerListing = new HashMap<>();
        Map<Long, Map<Long, String>> currencyByActuarPerListing = new HashMap<>();
        Map<Long, Integer> ordersDoneCount = new HashMap<>();

        for (Order order : doneEmployeeOrders) {
            Long actuarId = order.getUserId();
            Long listingId = order.getListing().getId();
            BigDecimal unitPrice = nullSafe(order.getPricePerUnit());
            long qty = (long) order.getQuantity() * order.getContractSize();

            currencyByActuarPerListing
                    .computeIfAbsent(actuarId, k -> new HashMap<>())
                    .putIfAbsent(listingId, resolveOrderCurrency(order.getListing()));

            lotsByActuarPerListing
                    .computeIfAbsent(actuarId, k -> new HashMap<>())
                    .computeIfAbsent(listingId, k -> new ArrayList<>())
                    .add(new Lot(order.getDirection(), qty, unitPrice));

            ordersDoneCount.merge(actuarId, 1, Integer::sum);
        }

        // 3) Per-aktuar: sum REALIZOVANOG profita u RSD (FIFO po listingu).
        Set<Long> allActuarIds = new HashSet<>(lotsByActuarPerListing.keySet());

        return allActuarIds.stream()
                .map(actuarId -> buildActuaryProfit(
                        actuarId,
                        lotsByActuarPerListing.getOrDefault(actuarId, Map.of()),
                        currencyByActuarPerListing.getOrDefault(actuarId, Map.of()),
                        ordersDoneCount.getOrDefault(actuarId, 0)))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ActuaryProfitDto::getTotalProfitRsd).reversed())
                .toList();
    }

    /** Jedan zatvoren/otvoren lot trgovine (smer, kolicina, jedinicna cena). */
    private record Lot(OrderDirection direction, long quantity, BigDecimal unitPrice) {}

    /**
     * R1 507 — FIFO realizovan P/L za jedan listing.
     *
     * <p>BUY lotovi se redaju u FIFO red. SELL sparuje najstarije otvorene BUY
     * lotove: realizovan = (sellCena − buyCena) × sparena kolicina. Nesparen
     * BUY (otvorena DUGA pozicija) na kraju NE realizuje nista (0) — kljucna
     * razlika od starog {@code Σ SELL − Σ BUY} (koji ju je prikazivao kao
     * gubitak). Nesparen SELL (prodaja bez evidentirane kupovine — seed/legacy)
     * realizuje pun prihod {@code sellCena × kolicina} (zero cost-basis),
     * zadrzavajuci staru SELL-only semantiku da postojeci testovi/seed ostanu
     * konzistentni.</p>
     */
    private BigDecimal computeRealizedProfit(List<Lot> lots) {
        // FIFO red otvorenih BUY lotova: [remainingQty, unitPrice].
        java.util.ArrayDeque<long[]> openBuysQty = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<BigDecimal> openBuysPrice = new java.util.ArrayDeque<>();
        BigDecimal realized = BigDecimal.ZERO;

        for (Lot lot : lots) {
            if (lot.direction() == OrderDirection.BUY) {
                if (lot.quantity() > 0) {
                    openBuysQty.addLast(new long[]{lot.quantity()});
                    openBuysPrice.addLast(lot.unitPrice());
                }
                continue;
            }
            // SELL: sparuj sa najstarijim otvorenim BUY lotovima (FIFO).
            long sellRemaining = lot.quantity();
            BigDecimal sellPrice = lot.unitPrice();
            while (sellRemaining > 0 && !openBuysQty.isEmpty()) {
                long[] head = openBuysQty.peekFirst();
                BigDecimal buyPrice = openBuysPrice.peekFirst();
                long matched = Math.min(sellRemaining, head[0]);
                // realizovan P/L za sparenu kolicinu = (sell − buy) × matched
                realized = realized.add(
                        sellPrice.subtract(buyPrice).multiply(BigDecimal.valueOf(matched)));
                head[0] -= matched;
                sellRemaining -= matched;
                if (head[0] == 0) {
                    openBuysQty.removeFirst();
                    openBuysPrice.removeFirst();
                }
            }
            // Nesparen SELL (nema evidentiranog BUY-a): zero cost-basis → pun prihod.
            if (sellRemaining > 0) {
                realized = realized.add(sellPrice.multiply(BigDecimal.valueOf(sellRemaining)));
            }
        }
        // Nespareni BUY lotovi (otvorena DUGA pozicija) NE realizuju nista (0) — R1 507.
        return realized;
    }

    private ActuaryProfitDto buildActuaryProfit(
            Long actuarId,
            Map<Long, List<Lot>> lotsByListing,
            Map<Long, String> currencyByListing,
            int ordersDone) {
        // Identitet aktuara razresava banka-core. Ako zaposleni vise ne postoji
        // (banka-core vrati 404 -> BankaCoreClientException), aktuar se izostavlja
        // iz liste — verno monolitnom `employeeRepository.findById(...).orElse(null)`.
        InternalUserDto emp;
        try {
            emp = bankaCoreClient.getUserById(UserRole.EMPLOYEE, actuarId);
        } catch (BankaCoreClientException e) {
            log.warn("Aktuar #{} nije razresiv preko banka-core ({}); izostavljam iz liste",
                    actuarId, e.getMessage());
            return null;
        }
        if (emp == null) {
            return null;
        }

        BigDecimal totalProfitRsd = BigDecimal.ZERO;
        for (Map.Entry<Long, List<Lot>> entry : lotsByListing.entrySet()) {
            Long listingId = entry.getKey();
            BigDecimal realized = computeRealizedProfit(entry.getValue());
            String ccy = currencyByListing.getOrDefault(listingId, "RSD");
            // R1 509: ako FX konverzija padne, leg se ISKLJUCUJE iz RSD sume (sa
            // jasnom indikacijom u logu) umesto da se sirov strani iznos tiho sabere
            // kao RSD (sto je davalo pogresan RSD broj — mesanje valuta).
            BigDecimal legRsd = convertToRsd(realized, ccy, actuarId, listingId);
            if (legRsd != null) {
                totalProfitRsd = totalProfitRsd.add(legRsd);
            }
        }

        Set<String> perms = resolvePermissions(emp.email());
        // Admin je uvek supervisor (po Celini 3); ako nema ni jedno ni drugo, AGENT.
        String position = (perms.contains("SUPERVISOR") || perms.contains("ADMIN"))
                ? "SUPERVISOR" : "AGENT";

        String name = (nullSafeStr(emp.firstName()) + " " + nullSafeStr(emp.lastName())).trim();

        return new ActuaryProfitDto(
                actuarId,
                name.isEmpty() ? "Unknown" : name,
                position,
                totalProfitRsd.setScale(2, RoundingMode.HALF_UP),
                ordersDone);
    }

    /**
     * Razresava permisije zaposlenog preko banka-core. Permisije odredjuju samo
     * prikaznu poziciju (SUPERVISOR / AGENT) — ako banka-core lookup padne,
     * gracefully vracamo prazan skup (sto monolitno mapira na AGENT default).
     */
    private Set<String> resolvePermissions(String email) {
        if (email == null || email.isBlank()) {
            return Set.of();
        }
        try {
            return new HashSet<>(bankaCoreClient.getUserPermissions(email));
        } catch (BankaCoreClientException e) {
            log.warn("Permisije za {} nisu razresive preko banka-core ({}); "
                    + "podrazumevam AGENT poziciju", email, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Konvertuje realizovan P/L jedne hartije u RSD.
     *
     * <p>R1 509: ako FX konverzija padne (kurs nedostupan / banka-core pad),
     * vraca {@code null} — leg se ISKLJUCUJE iz RSD sume sa jasnom indikacijom
     * u logu. Stari kod je u tom slucaju TIHO sabirao sirov strani iznos kao da
     * je RSD (npr. 300 USD → 300 RSD), proizvodeci pogresan ukupan RSD broj
     * (mesanje valuta bez indikacije). Bolje je izostaviti taj leg nego
     * prikazati netacan, valutno-pomesan total.</p>
     *
     * @return RSD iznos, ili {@code null} ako FX konverzija nije uspela (leg se preskace)
     */
    private BigDecimal convertToRsd(BigDecimal amount, String fromCurrency, Long actuarId, Long listingId) {
        if (amount == null || amount.signum() == 0) return BigDecimal.ZERO;
        if (fromCurrency == null || "RSD".equalsIgnoreCase(fromCurrency)) return amount;
        try {
            return currencyConversionService.convert(amount, fromCurrency, "RSD");
        } catch (RuntimeException e) {
            log.warn("FX konverzija {}->RSD nije uspela za aktuara #{} listing #{} ({}); "
                            + "leg ISKLJUCEN iz RSD profita (ne mesam valute u total)",
                    fromCurrency, actuarId, listingId, e.getMessage());
            return null;
        }
    }

    private String resolveOrderCurrency(Listing listing) {
        return ListingCurrencyResolver.resolveSafe(listing, "RSD");
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private String nullSafeStr(String value) {
        return value != null ? value : "";
    }
}
