package rs.raf.trading.tax.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import rs.raf.banka2.contracts.internal.InterbankOtcExercisedDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2.contracts.internal.TaxCollectRequest;
import rs.raf.banka2.contracts.internal.TaxCollectResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.util.ListingCurrencyResolver;
import rs.raf.trading.tax.dto.TaxBreakdownItemDto;
import rs.raf.trading.tax.dto.TaxRecordDto;
import rs.raf.trading.tax.model.TaxRecord;
import rs.raf.trading.tax.processor.TaxCalculatorProcessor;
import rs.raf.trading.tax.repository.TaxRecordBreakdownRepository;
import rs.raf.trading.tax.repository.TaxRecordRepository;
import rs.raf.trading.tax.util.TaxRealizedGainCalculator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * NAPOMENA (copy-first ekstrakcija, faza 2c):
 * <ul>
 *   <li>Naplata poreza ({@code collectTaxFromUser}) je u monolitu direktno
 *       zaduzivala klijentov RSD racun i kreditirala drzavni RSD racun preko
 *       {@code AccountRepository}. U trading-service-u racuni i novac pripadaju
 *       banka-core domenu — CLIENT grana sada zove
 *       {@link BankaCoreClient#collectTax} (banka-core sam razresava klijentov
 *       RSD racun i drzavni racun, radi dvojno knjizenje + audit).</li>
 *   <li>Identitet korisnika ({@code getMyTaxRecord}, {@code resolveUserName})
 *       razresava banka-core interni API ({@link BankaCoreClient#getUserByEmail}
 *       /{@link BankaCoreClient#getUserById}) umesto lokalnih
 *       {@code UserRepository}/{@code EmployeeRepository}.</li>
 * </ul>
 */
@Slf4j
@Service
public class TaxService {


    private static final Set<ListingType> TAXABLE_LISTING_TYPES =
            EnumSet.of(ListingType.STOCK, ListingType.FOREX, ListingType.FUTURES);

    private final TaxRecordRepository taxRecordRepository;
    private final TaxRecordBreakdownRepository taxRecordBreakdownRepository;
    private final OrderRepository orderRepository;
    private final CurrencyConversionService currencyConversionService;
    private final OtcContractRepository otcContractRepository;
    private final ListingRepository listingRepository;
    private final BankaCoreClient bankaCoreClient;

    /**
     * BE-PAY-04 (paritet sa BE-PAY-02/03): per-user processor sa
     * {@code @Transactional(REQUIRES_NEW)} izolacijom. Field injection sa
     * {@link Lazy} jer postojeci unit testovi (TaxServiceTest/CoverageTest)
     * koriste {@code @InjectMocks} koji ne moze da injektuje processor —
     * u tim slucajevima orkestrator fall-back-uje na inline kalkulaciju koja
     * je ekvivalentna processor-ovoj (samo bez REQUIRES_NEW Tx izolacije).
     */
    @Autowired(required = false)
    @Lazy
    private TaxCalculatorProcessor calculatorProcessor;

    public TaxService(TaxRecordRepository taxRecordRepository,
                      TaxRecordBreakdownRepository taxRecordBreakdownRepository,
                      OrderRepository orderRepository,
                      CurrencyConversionService currencyConversionService,
                      OtcContractRepository otcContractRepository,
                      ListingRepository listingRepository,
                      BankaCoreClient bankaCoreClient) {
        this.taxRecordRepository = taxRecordRepository;
        this.taxRecordBreakdownRepository = taxRecordBreakdownRepository;
        this.orderRepository = orderRepository;
        this.currencyConversionService = currencyConversionService;
        this.otcContractRepository = otcContractRepository;
        this.listingRepository = listingRepository;
        this.bankaCoreClient = bankaCoreClient;
    }

    /** Test seam — koristi se u TaxCalculatorProcessorTest da injektuje pravi processor. */
    void setCalculatorProcessor(TaxCalculatorProcessor processor) {
        this.calculatorProcessor = processor;
    }

    /**
     * Vraca filtrirane tax recorde za admin/employee portal.
     */
    public List<TaxRecordDto> getTaxRecords(String name, String userType) {
        List<TaxRecord> records = taxRecordRepository.findByFilters(
                (name != null && !name.isBlank()) ? name : null,
                (userType != null && !userType.isBlank()) ? userType : null
        );
        return records.stream().map(this::toDto).collect(Collectors.toList());
    }

    /**
     * Vraca tax record za konkretnog korisnika (autentifikovanog).
     */
    public TaxRecordDto getMyTaxRecord(String email) {
        // Razresi identitet korisnika preko banka-core internog API-ja.
        InternalUserDto user;
        try {
            user = bankaCoreClient.getUserByEmail(email);
        } catch (BankaCoreClientException e) {
            // 404 (ili druga greska) — korisnik nije pronadjen.
            return emptyDto(0L, "Nepoznat", UserRole.CLIENT);
        }

        String userType = UserRole.isEmployee(user.userRole()) ? UserRole.EMPLOYEE : UserRole.CLIENT;
        Optional<TaxRecord> record = taxRecordRepository.findByUserIdAndUserType(user.userId(), userType);
        return record.map(this::toDto).orElseGet(() -> emptyDto(user.userId(),
                user.firstName() + " " + user.lastName(), userType));
    }

    /**
     * Pokrece obracun i naplatu poreza za sve korisnike koji imaju ordere.
     *
     * Spec (Celina 3 — Porez): porez na kapitalnu dobit prilikom prodaje
     * akcija "preko berze i OTC trgovinom". Profesorovo pojasnjenje
     * (RAF Discord, 2026-04-26): porez se obracunava i za FOREX (slicno
     * kao stock) i opciono za FUTURES (komplicirano kod isteka jer fizicki
     * dospeva roba, ali u nasem sistemu ne hendlamo dospece — tretiramo
     * ga kao stock). Zato OrderRepository.findByIsDoneTrue() ulazi u
     * obracun za sve trgovacke tipove (STOCK, FOREX, FUTURES). OPCIJE se
     * ne kupuju kroz Order entitet, ne ulaze ovde.
     *
     * Za svakog korisnika: totalProfit = sum(SELL value - BUY cost) po listingu,
     * konvertovano u RSD po srednjem kursu (bez provizije) — spec, Napomena 2.
     * Porez = 15% * totalProfit ako je pozitivan, inace 0.
     * Neplaceni deo se skida sa korisnikovog RSD racuna i ide na drzavni RSD racun.
     *
     * OTC trgovina (Celina 4): EXERCISED ugovor tretiramo kao prodaju akcija po
     * strikePrice za prodavca i kao kupovinu po strikePrice za kupca; dodatno
     * primljena/placena premija ulazi u sell/buy stranu kao realizovani prihod
     * odnosno trosak vezan za listing. Intra-bank OTC pokriva samo akcije.
     *
     * FUND orderi (Celina 4 - Nova): orderi sa fundId != null se preskacu jer
     * fondovi ne ulaze u licnu kapitalnu dobit supervizora.
     *
     * <p>BE-PAY-04 (paritet sa BE-PAY-02/03): orkestrator vise NEMA outer
     * {@code @Transactional}. Per-user obracun delegira na
     * {@link TaxCalculatorProcessor#processOne} koji ima
     * {@code @Transactional(REQUIRES_NEW)} → svaki korisnik dobija svoju
     * nezavisnu transakciju. Pad jednog korisnika (npr. FX rate nedostupan)
     * NE rollback-uje vec persistovane {@code TaxRecord}-e drugih korisnika
     * iz istog batch-a. Greske se agregiraju u {@code perUserFailures} i
     * scheduler dobija agregatni {@link TaxCalculationException} za supervisor
     * notifikaciju, ali svi uspeli korisnici su persistovani.
     */
    public void calculateTaxForAllUsers() {
        LocalDateTime now = LocalDateTime.now();
        // P2-tax OTC period-scoping (01.06): settlement period i kalendarski mesec
        // run-a su run-globalni (zavise samo od {@code now} — settlementPeriod nije
        // per-user). Izracunavamo ih JEDNOM ovde i koristimo da period-gate-ujemo
        // OTC EXERCISED kontribucije (intra + inter) po {@code exercisedAt} — TACNO
        // isti period model kao order SELL leg (TaxRealizedGainCalculator.inPeriod).
        YearMonth period = TaxRealizedGainCalculator.settlementPeriod(now);
        YearMonth nowMonth = YearMonth.from(now);
        // BE-ORD-06: spec Celina 3 Sc 58 — bank actuaries (zaposleni) trguju sa
        // bankinih racuna i NE placaju licni porez na kapitalnu dobit. Pre fix-a
        // filter je samo preskakao FUND ordere, pa su EMPLOYEE orderi padali u
        // tax obracun (lazno se obracunao porez supervizoru/agentu kao klijentu).
        // Sada uzimamo SAMO CLIENT ordere bez fundId — EMPLOYEE + FUND orderi se
        // preskacu (bank profit ide kroz Profit Banke portal, ne licni porez).
        List<Order> allDoneOrders = orderRepository.findByIsDoneTrue().stream()
                .filter(o -> o.getListing() != null
                        && TAXABLE_LISTING_TYPES.contains(o.getListing().getListingType())
                        && o.getFundId() == null                    // preskoci fond-ordere
                        && UserRole.isClient(o.getUserRole()))      // BE-ORD-06: samo CLIENT placa licni porez
                .collect(Collectors.toList());

        // R1 430 (PUT/CALL exercise opcija — ACCEPTED-AS-CORRECT, NE TODO):
        // Izvrsavanje opcija ({@code OptionService.exerciseOption}) je iskljucivo
        // EMPLOYEE/aktuar/admin operacija — {@code ensureUserCanExerciseOptions}
        // odbija sve sem aktuara, a vlasnik portfolija posle exercise-a je uvek
        // {@code UserRole.EMPLOYEE} (novcana noga ide na/sa BANKINOG trading racuna).
        // Po BE-ORD-06 (spec Celina 3 Sc 58) bank actuaries NE placaju licni porez na
        // kapitalnu dobit — bankin profit ide kroz Profit Banke portal. Order filter
        // iznad vec iskljucuje EMPLOYEE, pa je opciona noga DOSLEDNO iskljucena iz
        // licnog poreza (NIJE propust). Ne postoji klijentski put izvrsavanja opcije.
        //
        // R1 431 / P2-tax-interbank-otc-1 (inter-bank OTC oporezivanje):
        // {@code otc_contracts} tabela (trading-service) sadrzi ISKLJUCIVO intra-bank
        // ugovore. INTER-bank OTC ugovori zive u banka-core ({@code interbank_otc_contracts},
        // {@code InterbankOtcContract}) koji NEMA tax modul. Pre P2-tax-interbank-otc-1
        // fix-a posledica je bila: lokalni CLIENT koji exercise-uje inter-bank opciju
        // realizuje kapitalnu dobit koju NIJEDAN sistem ne oporezuje (under-taxation).
        // Sad ih dohvatamo preko banka-core internog endpoint-a
        // ({@code GET /internal/interbank-otc/exercised}) i merge-ujemo u ISTE
        // per-user OTC mape (po listingId-u, razresenom iz ticker-a) sa ISTOM logikom
        // kao intra: seller proceeds = strike×qty + premium, buyer cost-basis = strike×qty
        // (premija NIJE na kupcevoj strani — R1-432), EMPLOYEE izuzet (BE-ORD-06).
        // DEDUP: inter-bank ugovori su odvojeni entiteti/tabela od intra (ne mogu se
        // preklopiti) — merge je aditivan; korisnik sa intra+inter exercised na istom
        // listingu dobija ZBIR (svaka strana po jednom, bez duplog brojanja).

        // Grupisemo ordere po userId + userRole
        Map<String, List<Order>> grouped = allDoneOrders.stream()
                .collect(Collectors.groupingBy(o -> o.getUserId() + ":" + o.getUserRole()));

        // R5 1901: EXERCISED STOCK ugovori se ucitavaju DB-filtriranim upitom
        // (status + listingType) umesto findAll()+in-memory filter (pun table-scan
        // koji raste neograniceno jer EXERCISED ostaje zauvek). Svaki ugovor utice
        // na dva korisnika (kupca i prodavca), pa ne mozemo direktno groupingBy.
        //
        // P2-tax OTC period-scoping (01.06): EXERCISED status je TRAJAN — ugovor ostaje
        // EXERCISED u tabeli zauvek. Bez ovog period-gate-a isti jednokratni OTC dobitak
        // bi se re-oporezovao na SVAKOM mesecnom run-u (over-taxation: idempotency kljuc
        // ukljucuje period → svaki mesec daje NOV kljuc → svez realan debit u banka-core).
        // Filtriramo po {@code exercisedAt} u settlement {@code period} — TACNO isti
        // period model kao order SELL leg (TaxRealizedGainCalculator.inPeriod). Ugovor
        // exercise-ovan u mesecu M oporezuje se SAMO u run-u koji settle-uje mesec M, i
        // doprinosi 0 svakom kasnijem run-u.
        List<OtcContract> exercisedContracts = otcContractRepository.findExercisedStockContracts().stream()
                .filter(c -> c.getListing() != null)
                .filter(c -> TaxRealizedGainCalculator.inPeriod(c.getExercisedAt(), period, nowMonth))
                .collect(Collectors.toList());

        // userKey -> listingId -> akumulirana vrednost
        Map<String, Map<Long, BigDecimal>> otcSellByUser = new HashMap<>();
        Map<String, Map<Long, BigDecimal>> otcBuyByUser = new HashMap<>();
        Map<Long, String> otcListingCurrency = new HashMap<>();
        Set<String> otcUserKeys = new HashSet<>();

        for (OtcContract c : exercisedContracts) {
            Long listingId = c.getListing().getId();
            otcListingCurrency.putIfAbsent(listingId,
                    ListingCurrencyResolver.resolveSafe(c.getListing(), "RSD"));

            BigDecimal qty = BigDecimal.valueOf(c.getQuantity());
            BigDecimal strikeTotal = c.getStrikePrice().multiply(qty);
            BigDecimal premium = c.getPremium() != null ? c.getPremium() : BigDecimal.ZERO;

            // R1 432 (premija samo na PRODAVCEVOJ strani — money-fix):
            // U OTC opcionom modelu (Celina 4 §76-77) kupac placa premiju PRODAVCU pri
            // sklapanju ugovora, a pri exercise-u placa strike × qty i DOBIJA akcije.
            //   • PRODAVAC: realizovan dogadjaj sada = otudjenje akcija po strike-u +
            //     premija kao prihod → proceeds = strikeTotal + premium. (Premija JE
            //     prodavcev prihod.) Zadrzano.
            //   • KUPAC: sticanje akcija NIJE realizovan kapitalni dogadjaj — kupceva
            //     dobit/gubitak se realizuje TEK pri buducoj prodaji tih akcija.
            //     strike + premija su kupcev COST-BASIS za stecene akcije, NE trenutni
            //     gubitak. Pre fix-a se premija dodavala i na kupcevu buy-agregaciju, pa
            //     kad bi isti korisnik bio i prodavac na istom listingu (sell − buy net),
            //     premija (prodavcev prihod) bi se DRUGI PUT oduzela kao kupcev trosak →
            //     premija dva puta u sistemu = under-taxation. Kupceva buy-agregacija sad
            //     nosi SAMO strikeTotal (stvarni trosak sticanja), bez premije.
            if (UserRole.isClient(c.getSellerRole())) {
                String sellerKey = c.getSellerId() + ":" + c.getSellerRole();
                otcUserKeys.add(sellerKey);
                otcSellByUser.computeIfAbsent(sellerKey, k -> new HashMap<>())
                        .merge(listingId, strikeTotal.add(premium), BigDecimal::add);
            }
            if (UserRole.isClient(c.getBuyerRole())) {
                String buyerKey = c.getBuyerId() + ":" + c.getBuyerRole();
                otcUserKeys.add(buyerKey);
                otcBuyByUser.computeIfAbsent(buyerKey, k -> new HashMap<>())
                        .merge(listingId, strikeTotal, BigDecimal::add);
            }
        }

        // P2-tax-interbank-otc-1: merge INTER-bank OTC EXERCISED ugovore u iste mape.
        // P2-tax OTC period-scoping (01.06): isti exercisedAt period-gate kao intra.
        mergeInterbankExercised(otcSellByUser, otcBuyByUser, otcListingCurrency, otcUserKeys,
                period, nowMonth);

        Set<String> allKeys = new HashSet<>(grouped.keySet());
        allKeys.addAll(otcUserKeys);

        // BE-ORD-08 + BE-PAY-04: TaxCalculationException uhvacen po-korisniku —
        // re-baca se sa userId/userType popunjenim da TaxScheduler moze da identifikuje
        // koji je korisnik preskocen + posalje notifikaciju supervizoru.
        //
        // BE-PAY-04: per-user obracun se delegira na TaxCalculatorProcessor.processOne
        // koji ima @Transactional(REQUIRES_NEW) → pad jednog korisnika NE rollback-uje
        // vec persistovane TaxRecord-e drugih korisnika.
        java.util.List<TaxCalculationException> perUserFailures = new java.util.ArrayList<>();
        for (String key : allKeys) {
            String[] parts = key.split(":");
            Long userId = Long.parseLong(parts[0]);
            String userRole = parts[1];
            String userType = UserRole.isEmployee(userRole) ? UserRole.EMPLOYEE : UserRole.CLIENT;
            List<Order> userOrders = grouped.getOrDefault(key, List.of());
            Map<Long, BigDecimal> userOtcSell = otcSellByUser.getOrDefault(key, Map.of());
            Map<Long, BigDecimal> userOtcBuy = otcBuyByUser.getOrDefault(key, Map.of());
            try {
                processOneUserInternal(userId, userType, userRole, userOrders,
                        userOtcSell, userOtcBuy, otcListingCurrency, now);
            } catch (TaxCalculationException txEx) {
                // BE-ORD-08: re-baca exception sa userId/userType da TaxScheduler moze
                // da posalje notifikaciju supervizoru sa konkretnim korisnikom.
                TaxCalculationException enriched = new TaxCalculationException(
                        userId, userType, txEx.getMessage(), txEx.getCause());
                log.error("Tax calculation skipped for user {} ({}): {}", userId, userType, txEx.getMessage());
                perUserFailures.add(enriched);
            } catch (RuntimeException ex) {
                // BE-PAY-04: bilo koja druga greska (DB constraint, optimistic lock,
                // banka-core 5xx) je takodje per-user izolovana — ne rollback-uje
                // ostatak batch-a. Belezimo kao failure ali ne propagiramo specijalan
                // tip (TaxCalculationException je rezervisan za FX/conversion).
                log.error("Unexpected error during tax calculation for user {} ({}): {}",
                        userId, userType, ex.getMessage(), ex);
                perUserFailures.add(new TaxCalculationException(userId, userType,
                        "Unexpected: " + ex.getMessage(), ex));
            }
        }
        // BE-ORD-08: ako je makar jedan korisnik preskocen, propagiraj agregatni
        // TaxCalculationException pa scheduler obavestava supervizora. Obracun ostalih
        // korisnika je vec persistovan (TaxRecord.save() je u try grani po-korisniku).
        if (!perUserFailures.isEmpty()) {
            TaxCalculationException first = perUserFailures.get(0);
            String summary = perUserFailures.size() == 1
                    ? first.getMessage()
                    : "Tax calculation failed for " + perUserFailures.size() + " users (first: "
                            + first.getMessage() + ")";
            throw new TaxCalculationException(first.getUserId(), first.getUserType(), summary, first.getCause());
        }
    }

    /**
     * P2-tax-interbank-otc-1 — dohvata EXERCISED inter-bank OTC ugovore iz banka-core
     * i merge-uje ih u iste per-user OTC sell/buy mape (po listingId-u) kao intra-OTC.
     *
     * <p><b>Conservation / dedup garancije:</b>
     * <ul>
     *   <li><b>Taxed exactly once:</b> svaki inter-bank EXERCISED ugovor ima TACNO
     *       jednu lokalnu stranu (druga je u partnerskoj banci). SELLER doprinosi
     *       {@code strike×qty + premium} prodavcevoj sell-mapi; BUYER doprinosi
     *       {@code strike×qty} kupcevoj buy-mapi — jednom, na lokalnu stranu.</li>
     *   <li><b>No intra/inter double-count:</b> inter-bank ugovori su odvojeni
     *       entitet ({@code InterbankOtcContract}, banka-core) od intra
     *       ({@code OtcContract}, trading) — ne mogu se preklopiti. Merge je aditivan
     *       po listingId-u: korisnik sa intra+inter na istom listingu dobija ZBIR
     *       proceeds/cost-basis (svaka kontribucija po jednom).</li>
     *   <li><b>EMPLOYEE izuzet (BE-ORD-06):</b> samo {@code localPartyRole == CLIENT}
     *       ulazi (bank actuaries ne placaju licni porez).</li>
     *   <li><b>Premija samo na seller strani (mirror R1-432):</b> kupcev cost-basis
     *       je goli {@code strike×qty} — premija je iskljucivo prodavcev prihod, da se
     *       ne bi dvaput knjizila ako je isti korisnik i prodavac i kupac.</li>
     * </ul>
     *
     * <p>banka-core nema trading {@code listingId}, samo {@code ticker} — razresavamo
     * ga preko {@link ListingRepository#findByTicker}. Nepoznat ticker (nema trading
     * listinga) se preskace. Best-effort: ako je banka-core nedostupan, loguje WARN i
     * nastavlja (intra OTC + orderi se i dalje oporezuju) — paritet sa ostalim
     * cross-service best-effort pozivima.
     */
    private void mergeInterbankExercised(Map<String, Map<Long, BigDecimal>> otcSellByUser,
                                         Map<String, Map<Long, BigDecimal>> otcBuyByUser,
                                         Map<Long, String> otcListingCurrency,
                                         Set<String> otcUserKeys,
                                         YearMonth period,
                                         YearMonth nowMonth) {
        List<InterbankOtcExercisedDto> interbank;
        try {
            interbank = bankaCoreClient.getExercisedInterbankOtc();
        } catch (RuntimeException e) {
            log.warn("Inter-bank OTC tax fetch failed (banka-core unavailable): {} — "
                    + "inter-bank exercised contracts NOT taxed this run (intra OTC + orders still computed)",
                    e.getMessage());
            return;
        }
        if (interbank == null || interbank.isEmpty()) {
            return;
        }

        // Ticker→listingId kes (vise ugovora na isti ticker → jedan repo poziv).
        Map<String, Long> tickerToListingId = new HashMap<>();

        for (InterbankOtcExercisedDto c : interbank) {
            // BE-ORD-06: samo lokalni CLIENT placa licni porez; EMPLOYEE (bank actuary) izuzet.
            if (c == null || !UserRole.isClient(c.localPartyRole())) {
                continue;
            }
            if (c.ticker() == null || c.localPartyId() == null
                    || c.strikePrice() == null || c.quantity() == null) {
                continue;
            }
            // P2-tax OTC period-scoping (01.06): period-gate po exercisedAt — TACNO isti
            // model kao intra. Inter-bank EXERCISED ugovor je TRAJAN (banka-core ga ne
            // brise), pa bi bez ovog gate-a isti dobitak bio re-oporezovan svakog meseca
            // (over-taxation). Ugovor exercise-ovan u mesecu M oporezuje se SAMO u run-u
            // koji settle-uje M, i doprinosi 0 svakom kasnijem run-u.
            if (!TaxRealizedGainCalculator.inPeriod(c.exercisedAt(), period, nowMonth)) {
                continue;
            }

            // Razresi trading listingId iz ticker-a (banka-core ima samo ticker).
            Long listingId = tickerToListingId.computeIfAbsent(c.ticker(), this::resolveListingIdByTicker);
            if (listingId == null) {
                log.debug("Inter-bank OTC ticker {} has no trading listing — skipping tax leg for contract {}",
                        c.ticker(), c.id());
                continue;
            }

            String currency = c.strikeCurrency() != null ? c.strikeCurrency() : "RSD";
            otcListingCurrency.putIfAbsent(listingId, currency);

            BigDecimal strikeTotal = c.strikePrice().multiply(c.quantity());
            BigDecimal premium = c.premium() != null ? c.premium() : BigDecimal.ZERO;

            String userKey = c.localPartyId() + ":" + UserRole.CLIENT;
            if ("SELLER".equals(c.localPartyType())) {
                // Prodavac: proceeds = strike×qty + premija (premija je prodavcev prihod).
                otcUserKeys.add(userKey);
                otcSellByUser.computeIfAbsent(userKey, k -> new HashMap<>())
                        .merge(listingId, strikeTotal.add(premium), BigDecimal::add);
            } else if ("BUYER".equals(c.localPartyType())) {
                // Kupac: cost-basis = strike×qty (BEZ premije — mirror R1-432).
                otcUserKeys.add(userKey);
                otcBuyByUser.computeIfAbsent(userKey, k -> new HashMap<>())
                        .merge(listingId, strikeTotal, BigDecimal::add);
            }
        }
    }

    /** Razresi trading {@code listingId} iz ticker-a; {@code null} ako listing ne postoji. */
    private Long resolveListingIdByTicker(String ticker) {
        if (ticker == null) {
            return null;
        }
        return listingRepository.findByTicker(ticker).map(Listing::getId).orElse(null);
    }

    /**
     * BE-PAY-04: per-user delegate. U produkciji {@link #calculatorProcessor}
     * je auto-wired i izvrsava obracun u {@code REQUIRES_NEW} Tx — pad ne
     * rollback-uje vec persistovane TaxRecord-e iz batch-a. Legacy unit testovi
     * ({@code TaxServiceTest}, {@code TaxServiceCoverageTest}) koji koriste
     * {@code @InjectMocks} bez Spring kontexta nemaju processor — fall-back-uje
     * na inline kalkulaciju koja je <b>byte-identicna</b> processor-ovoj samo
     * bez Tx izolacije (testovi i dalje rade jer su sve mockane).
     *
     * @param userRole originalni userRole iz key-a (ne userType — moze razlikovati
     *                 ako spec doda nove role; trenutno {@code resolveUserName}
     *                 ga koristi za employee/client granu).
     */
    private void processOneUserInternal(Long userId,
                                        String userType,
                                        String userRole,
                                        List<Order> userOrders,
                                        Map<Long, BigDecimal> otcSellByListing,
                                        Map<Long, BigDecimal> otcBuyByListing,
                                        Map<Long, String> otcListingCurrency,
                                        LocalDateTime now) {
        if (calculatorProcessor != null) {
            calculatorProcessor.processOne(userId, userType, userOrders,
                    otcSellByListing, otcBuyByListing, otcListingCurrency, now);
            return;
        }
        // Legacy inline path — koristi se SAMO u unit testovima sa @InjectMocks
        // koji ne aktiviraju Spring kontekst. U produkciji se ne dosegne.
        processOneUserInline(userId, userType, userRole, userOrders,
                otcSellByListing, otcBuyByListing, otcListingCurrency, now);
    }

    /**
     * Legacy inline implementacija per-user obracuna. Ista logika kao
     * {@link TaxCalculatorProcessor#processOne}, samo bez {@code @Transactional}
     * Tx izolacije. Postoji za kompatibilnost sa postojecim Mockito unit
     * testovima koji koriste {@code @InjectMocks TaxService} (Spring kontekst
     * nije bootstrap-ovan pa processor ne moze biti injektovan).
     */
    private void processOneUserInline(Long userId,
                                      String userType,
                                      String userRole,
                                      List<Order> userOrders,
                                      Map<Long, BigDecimal> otcSellByListing,
                                      Map<Long, BigDecimal> otcBuyByListing,
                                      Map<Long, String> otcListingCurrency,
                                      LocalDateTime now) {
        // P0-B3: realizovana kapitalna dobit po listingu sa FIFO cost-basis
        // lot-matching-om i mesecnim periodom (spec Celina 3 §517-523). Identicno
        // {@link TaxCalculatorProcessor#processOne} preko deljenog
        // {@link TaxRealizedGainCalculator} — ovo je legacy inline put (samo unit
        // testovi sa @InjectMocks; produkcija ide kroz processor sa REQUIRES_NEW).
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
                ? rs.raf.trading.tax.util.TaxConstants.computeTax(totalProfit)
                : BigDecimal.ZERO;

        String userName = resolveUserName(userId, userRole);

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

        // B-1 fix (byte-identicno TaxCalculatorProcessor.processOne): mesecni
        // neplaceni porez = taxOwed(mesecni) − placeno-u-tom-mesecu. Mesecni owed
        // i mesecni paid u istoj dimenziji; godisnji kumulativ (taxPaid) odvojen.
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

        if (record.getId() != null) {
            taxRecordBreakdownRepository.deleteByTaxRecordId(record.getId());
            // R2 1420: ticker kes je METHOD-LOCAL (ne instance field na @Service
            // singleton-u) — instance-field kes nikad se nije cistio (memory leak +
            // stale ticker preko run-ova). Paritet sa TaxCalculatorProcessor.processOne.
            // R1-736: pre-popuni kes iz korisnikovih ordera (Listing vec ucitan) —
            // DB-skenirajuci resolveTicker se zove SAMO za OTC-only listinge.
            Map<Long, String> listingTickerCache = buildTickerMapFromOrders(userOrders);
            for (PerListingProfit p : perListingProfits) {
                if (p.listingId() == null) {
                    continue;
                }
                BigDecimal listingTaxOwed = p.profitRsd().compareTo(BigDecimal.ZERO) > 0
                        ? rs.raf.trading.tax.util.TaxConstants.computeTax(p.profitRsd())
                        : BigDecimal.ZERO;
                String ticker = listingTickerCache.computeIfAbsent(p.listingId(),
                        id -> resolveTicker(p.listingId()));
                rs.raf.trading.tax.model.TaxRecordBreakdown breakdown =
                        rs.raf.trading.tax.model.TaxRecordBreakdown.builder()
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

    /**
     * P2.4 — vraca per-listing breakdown stavke za TaxRecord. Vraca
     * praznu listu ako TaxRecord ne postoji ili jos nije izracunat.
     */
    public List<TaxBreakdownItemDto> getTaxBreakdownForUser(Long userId, String userType) {
        Optional<TaxRecord> recordOpt = taxRecordRepository.findByUserIdAndUserType(userId, userType);
        return breakdownItems(recordOpt.orElse(null));
    }

    /**
     * R2-1448: vraca per-listing breakdown za autentifikovanog korisnika u
     * <b>jednom</b> identitet-lookup-u + jednom record-lookup-u. Pre fix-a je
     * {@code TaxController.getMyBreakdown} radio 3 lookupa: {@code getMyTaxRecord}
     * (banka-core getUserByEmail + findByUserIdAndUserType) pa zatim
     * {@code getTaxBreakdownForUser} (drugi findByUserIdAndUserType). Sad se identitet
     * razresi jednom, TaxRecord se ucita jednom, i breakdown se mapira iz njega.
     */
    public List<TaxBreakdownItemDto> getMyTaxBreakdown(String email) {
        InternalUserDto user;
        try {
            user = bankaCoreClient.getUserByEmail(email);
        } catch (BankaCoreClientException e) {
            return List.of();
        }
        String userType = UserRole.isEmployee(user.userRole()) ? UserRole.EMPLOYEE : UserRole.CLIENT;
        TaxRecord record = taxRecordRepository.findByUserIdAndUserType(user.userId(), userType).orElse(null);
        return breakdownItems(record);
    }

    /** Mapira breakdown stavke datog {@link TaxRecord}-a (prazna lista ako je {@code null}). */
    private List<TaxBreakdownItemDto> breakdownItems(TaxRecord record) {
        if (record == null || record.getId() == null) return List.of();
        return taxRecordBreakdownRepository
                .findByTaxRecordIdOrderByTaxOwedDesc(record.getId())
                .stream()
                .map(b -> new TaxBreakdownItemDto(
                        b.getListingId(),
                        b.getTicker(),
                        b.getListingCurrency(),
                        b.getProfitNative(),
                        b.getProfitRsd(),
                        b.getTaxOwed()))
                .collect(Collectors.toList());
    }

    /**
     * R1-736: gradi {@code listingId -> ticker} mapu iz korisnikovih ordera
     * (Listing je vec ucitan na Order entitetu — nula DB poziva). Paritet sa
     * {@link TaxCalculatorProcessor}. Sluzi kao pre-popunjen kes za breakdown
     * petlju da {@link #resolveTicker} (DB scan) ne radi za listinge iz ordera.
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

    private String resolveTicker(Long listingId) {
        if (listingId == null) return null;
        try {
            List<Order> orders = orderRepository.findByIsDoneTrue();
            if (orders == null) return null;
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

    /** Internal helper za prenos profita per-listing izmedju petlji. */
    private record PerListingProfit(Long listingId, String listingCurrency,
                                    BigDecimal profitNative, BigDecimal profitRsd) {}

    /**
     * Naplata neplacenog poreza sa korisnikovog RSD racuna preko banka-core
     * internog seam-a. Vraca {@code true} ako je naplata uspela.
     *
     * <p>CLIENT grana: banka-core ({@link BankaCoreClient#collectTax}) razresava
     * klijentov RSD racun (klijent moze imati vise racuna — bira RSD racun sa
     * dovoljno sredstava) i drzavni RSD racun, radi dvojno knjizenje i audit.
     * {@code collected=false} ako klijent nema RSD racun sa dovoljno sredstava —
     * verno monolitovom {@code collectTaxFromUser}: naplata se preskace, TaxRecord
     * ostaje neplacen, sledeci obracun ce pokusati ponovo.
     *
     * <p>EMPLOYEE grana: zaposleni trguju sa bankinih racuna; porez se samo
     * belezi, novac se interno prebacuje (no-op u banka-core seam-u, verno
     * monolitu koji je za zaposlene odmah vracao {@code true}).
     *
     * <p>Idempotency key {@code "tax-" + userId + "-" + period + "-" + amount} se
     * vezuje za settlement {@code period} (NE run-mesec — B-1: cron 1. u mesecu
     * naplacuje prethodni mesec) i ukljucuje iznos naplate. Razlog za iznos: kljuc
     * samo po korisnik-mesecu se sudara pri intra-mesecnom ponovnom obracunu —
     * drugi pokretanje (sa novim trgovinama, pa vecim neplacenim porezom) bi
     * reuse-ovao isti kljuc, banka-core bi replay-ovao prvu kesiranu naplatu
     * ({@code collected=true}) i {@code TaxService} bi sad-veci porez obelezio kao
     * placen iako nije naplacen. Sa iznosom u kljucu: pravi SAGA retry iste naplate
     * vidi isti {@code unpaidTax} → isti kljuc → banka-core bezbedno replay-uje
     * (nema dvostruke naplate); razlicit intra-mesecni re-run ima razlicit mesecni
     * inkrement {@code unpaidTax} → razlicit kljuc → korektno naplacuje samo nov deo.
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
        // Za zaposlene: koriste bankin racun — porez se interno prebacuje.
        // Zaposleni trguju sa bankinih racuna, porez se samo belezi.
        return true;
    }

    /**
     * Resolve-uje ISO kod valute za listing ordera. Tax modul koristi RSD
     * kao fallback (sve se svodi na RSD pri obracunu poreza), sto je
     * razlicito od order flow-a koji padne na USD.
     *
     * @see ListingCurrencyResolver#resolveSafe(rs.raf.trading.stock.model.Listing, String)
     */
    private String resolveOrderCurrency(Order order) {
        if (order == null || order.getListing() == null) {
            return "RSD";
        }
        return ListingCurrencyResolver.resolveSafe(order.getListing(), "RSD");
    }

    /**
     * Konvertuje iznos u RSD. Ako je vec u RSD, vraca isti iznos.
     * Koristi CurrencyConversionService (srednji kurs, bez provizije) — S80.
     *
     * <p>BE-ORD-08: pre fix-a, na FX neuspeh ({@code currencyConversionService.convert}
     * baci exception zbog banka-core /internal/fx/rates 5xx/timeout) metoda je tisko
     * fallback-ovala na sirovi iznos i tretirala npr. USD 1000 kao 1000 RSD —
     * severe under-taxation. Sad propagiramo izvornu gresku kao
     * {@link TaxCalculationException} pozivaocu, koji moze da zaustavi obracun za
     * tog korisnika (TaxScheduler hvata exception po korisniku i nastavlja sa
     * ostalima + emituje notifikaciju supervizoru). {@code userId/userType} su
     * setovani kasnije u catch grani kalkulacije.
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
     * Rezilijentno — na gresku vraca placeholder (verno monolitovom
     * {@code resolveUserName}).
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

    private TaxRecordDto toDto(TaxRecord record) {
        return new TaxRecordDto(
                record.getId(),
                record.getUserId(),
                record.getUserName(),
                record.getUserType(),
                record.getTotalProfit(),
                record.getTaxOwed(),
                record.getTaxPaid(),
                record.getCurrency()
        );
    }

    private TaxRecordDto emptyDto(Long userId, String userName, String userType) {
        return new TaxRecordDto(null, userId, userName, userType,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "RSD");
    }
}
