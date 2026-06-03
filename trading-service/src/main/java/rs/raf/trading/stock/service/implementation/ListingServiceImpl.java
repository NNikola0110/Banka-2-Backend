package rs.raf.trading.stock.service.implementation;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import rs.raf.banka2.contracts.internal.FxRateDto;
import rs.raf.trading.berza.model.Exchange;
import rs.raf.trading.berza.repository.ExchangeRepository;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.pricealert.service.PriceAlertService;
import rs.raf.trading.stock.dto.ListingDailyPriceDto;
import rs.raf.trading.stock.dto.ListingDto;
import rs.raf.trading.stock.mapper.ListingMapper;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingDailyPriceInfo;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingDailyPriceInfoRepository;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.repository.ListingSpec;
import rs.raf.trading.stock.service.ListingService;
import rs.raf.trading.timeseries.ListingPriceRecorder;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Slf4j
public class ListingServiceImpl implements ListingService {

    private final ListingRepository listingRepository;
    private final Random random = new Random();
    private final ListingDailyPriceInfoRepository dailyPriceRepository;
    private final RestTemplate restTemplate;
    private final BankaCoreClient bankaCoreClient;
    private final ExchangeRepository exchangeRepository;
    private final ObjectProvider<ListingPriceRecorder> priceRecorderProvider;
    /**
     * B5 — Price alert hook. ObjectProvider izbegava cirkularnu zavisnost u
     * Spring DI grafu (PriceAlertService cita ListingRepository, sto je OK;
     * lazy lookup garantuje da provajder bude potpuno inicijalizovan pre
     * prvog poziva). Primarni mehanizam je {@code PriceAlertScheduler} koji
     * radi na 60s, ali ovaj hook daje brzu reakciju cim se cena osvezi
     * (15min cron) i ne zahteva polling kad nista nije promenjeno.
     */
    private final ObjectProvider<PriceAlertService> priceAlertServiceProvider;

    @Value("${stock.api.keys:demo}")
    private String stockApiKeys;

    private final AtomicInteger keyIndex = new AtomicInteger(0);

    /**
     * R1-729: imenovane konstante za blok cenovne simulacije koji se ranije
     * ponavljao 4× (test-mode + 3 fallback grane kad eksterni API nije dostupan).
     * Simulirana cena = trenutna × [0.98, 1.02) (±2% random walk); ask/bid su
     * trenutna ± 0.2% spread; volumen = prethodni × [0.9, 1.1) (±10% sum).
     * {@link #ASK_BID_SPREAD} se koristi i na granama sa REALNIM podacima.
     */
    private static final double SIM_PRICE_MIN_FACTOR = 0.98;
    private static final double SIM_PRICE_RANGE = 0.04;
    private static final BigDecimal ASK_BID_SPREAD = BigDecimal.valueOf(0.002);
    private static final double SIM_VOLUME_MIN_FACTOR = 0.9;
    private static final double SIM_VOLUME_RANGE = 0.2;
    private static final long DEFAULT_SIM_VOLUME = 100000L;

    /**
     * Mali nosac rezultata cenovnog tika (svi izvedeni iz nove cene).
     */
    private record PriceTick(BigDecimal price, BigDecimal ask, BigDecimal bid,
                             BigDecimal priceChange, long volume) {}

    /** Ask = cena × (1 + spread), zaokruzeno na 4 decimale. */
    private static BigDecimal askFor(BigDecimal price) {
        return price.multiply(BigDecimal.ONE.add(ASK_BID_SPREAD)).setScale(4, RoundingMode.HALF_UP);
    }

    /** Bid = cena × (1 - spread), zaokruzeno na 4 decimale. */
    private static BigDecimal bidFor(BigDecimal price) {
        return price.multiply(BigDecimal.ONE.subtract(ASK_BID_SPREAD)).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * R1-729: jedinstvena cenovna simulacija (±2% random walk + ask/bid spread +
     * ±10% volumen) — zamenjuje 4 identicne kopije. Ponasanje (raspodele, scale,
     * zaokruzivanje) je byte-identicno prethodnim inline blokovima.
     */
    private PriceTick simulateTick(Listing listing, BigDecimal currentPrice) {
        double changePercent = SIM_PRICE_MIN_FACTOR + (SIM_PRICE_RANGE * random.nextDouble());
        BigDecimal newPrice = currentPrice.multiply(BigDecimal.valueOf(changePercent))
                .setScale(4, RoundingMode.HALF_UP);
        long newVolume = listing.getVolume() != null
                ? (long) (listing.getVolume() * (SIM_VOLUME_MIN_FACTOR + (SIM_VOLUME_RANGE * random.nextDouble())))
                : DEFAULT_SIM_VOLUME;
        return new PriceTick(newPrice, askFor(newPrice), bidFor(newPrice),
                newPrice.subtract(currentPrice), newVolume);
    }

    /**
     * Round-robin API key selection across configured keys.
     */
    private String getNextApiKey() {
        String[] keys = stockApiKeys.split(",");
        int idx = keyIndex.getAndIncrement() % keys.length;
        return keys[idx].trim();
    }

    @Value("${stock.api.url:https://www.alphavantage.co/query}")
    private String stockApiUrl;

    /**
     * P2-config-2 (R2 1415): Alpha Vantage {@code demo} kljuc je dvostruki default
     * (application.properties {@code STOCK_API_KEYS:demo} + ovaj {@code @Value}
     * default), a u prod-u vraca SAMO IBM (i to rate-limit-ovano) — refresh tihо
     * servira stale IBM-only podatke bez ijednog signala. Ne fail-FAST-ujemo
     * (lomilo bi lokalni dev/test koji legitimno koriste {@code demo}), ali
     * dizemo glasan startup WARN da prod operater vidi da kljuc nije podesen.
     */
    @PostConstruct
    void warnIfDemoStockApiKey() {
        boolean allDemo = java.util.Arrays.stream(stockApiKeys.split(","))
                .map(String::trim)
                .allMatch(k -> k.isEmpty() || "demo".equalsIgnoreCase(k));
        if (allDemo) {
            log.warn("stock.api.keys je na 'demo' Alpha Vantage kljucu — GLOBAL_QUOTE vraca "
                    + "SAMO IBM i rate-limit-ovan je. Refresh cena ce tiho servirati stale "
                    + "IBM-only podatke. Podesi STOCK_API_KEYS (Secret) za realne kljuceve u prod-u.");
        }
    }

    @Override
    public Page<ListingDto> getListings(String type, String search, int page, int size) {
        return getListings(type, search, null, null, null, null, null, page, size);
    }

    @Override
    public Page<ListingDto> getListings(String type, String search,
                                        String exchangePrefix,
                                        BigDecimal priceMin, BigDecimal priceMax,
                                        LocalDate settlementDateFrom, LocalDate settlementDateTo,
                                        int page, int size) {
        ListingType listingType;
        try {
            listingType = ListingType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Nepoznat tip hartije: " + type);
        }

        if (listingType == ListingType.FOREX && isClient()) {
            throw new IllegalStateException("Klijenti nemaju pristup FOREX hartijama.");
        }

        // Validate price range
        if (priceMin != null && priceMax != null && priceMin.compareTo(priceMax) > 0) {
            throw new IllegalArgumentException("Minimalna cena ne moze biti veca od maksimalne.");
        }

        var pageable = PageRequest.of(page, size, Sort.by("ticker").ascending());
        Map<String, Boolean> testModeByAcronym = loadExchangeTestModeMap();
        // Sc25: orphan-exchange listinge (acronym koji ne postoji u `exchanges`)
        // iskljucujemo na DB nivou, kombinovano sa korisnickim filterima. Tako su
        // paginacija/total tacni i orphan hartije nikad ne dospeju u odgovor.
        Specification<Listing> spec = ListingSpec.withFilters(listingType, search, exchangePrefix,
                        priceMin, priceMax, settlementDateFrom, settlementDateTo)
                .and(ListingSpec.tradeableExchange());
        return listingRepository
                .findAll(spec, pageable)
                .map(l -> ListingMapper.toDto(l, testModeByAcronym.get(l.getExchangeAcronym())));
    }

    /**
     * Ucitava jedan put sve aktivne berze i vraca mapu acronym -> testMode,
     * da ne bismo gadjali bazu po svakom listingu.
     */
    private Map<String, Boolean> loadExchangeTestModeMap() {
        return exchangeRepository.findAll().stream()
                .collect(Collectors.toMap(
                        Exchange::getAcronym,
                        Exchange::isTestMode,
                        (a, b) -> a));
    }

    /**
     * Proverava da li je berza na kojoj se listing trguje u test modu.
     * Ako berza ne postoji u mapi (nepoznata), podrazumevano je nije u test modu.
     * Ocekivani slucaj: svaka listinga je povezana sa seed-ovanom berzom.
     */
    private boolean isListingExchangeInTestMode(Listing listing, Map<String, Boolean> testModeMap) {
        return Boolean.TRUE.equals(testModeMap.get(listing.getExchangeAcronym()));
    }

    private boolean isClient() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_CLIENT".equals(a.getAuthority()));
    }

    @Override
    public ListingDto getListingById(Long id) {

        Optional<Listing> listingOptional = listingRepository.findById(id);

        if (listingOptional.isEmpty()) throw new EntityNotFoundException("Listing id: " + id + " not found.");

        Listing listing = listingOptional.get();

        if (isClient() && listing.getListingType() == ListingType.FOREX)
            throw new IllegalStateException("Klijenti nemaju pristup FOREX hartijama.");

        Boolean testMode = exchangeRepository.findByAcronym(listing.getExchangeAcronym())
                .map(Exchange::isTestMode)
                .orElse(null);
        return ListingMapper.toDto(listing, testMode);
    }

    /**
     * P2-perf-nplus1-1 (R1 515): batch-resolve. Jedan {@code findAllById} za sve
     * listinge + jedan batch testMode lookup (distinct exchange acronym), umesto
     * N pojedinacnih {@code getListingById} (DB N+1). FOREX-gate NIJE primenjen
     * ovde — pozivalac (watchlist) ionako ne moze imati FOREX stavke za klijenta
     * (addItem ih odbija preko getListingById), a zaposleni vide FOREX.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, ListingDto> getListingsByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        List<Listing> listings = listingRepository.findAllById(distinct);
        if (listings.isEmpty()) {
            return Map.of();
        }
        // Batch testMode lookup: distinct exchange acronym → isTestMode, jedan upit.
        java.util.Set<String> acronyms = listings.stream()
                .map(Listing::getExchangeAcronym)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Boolean> testModeByAcronym = new java.util.HashMap<>();
        for (String acronym : acronyms) {
            testModeByAcronym.put(acronym,
                    exchangeRepository.findByAcronym(acronym).map(Exchange::isTestMode).orElse(null));
        }
        Map<Long, ListingDto> result = new java.util.HashMap<>();
        for (Listing listing : listings) {
            Boolean testMode = testModeByAcronym.get(listing.getExchangeAcronym());
            result.put(listing.getId(), ListingMapper.toDto(listing, testMode));
        }
        return result;
    }

    @Override
    public List<ListingDailyPriceDto> getListingHistory(Long listingId, String period) {

        Listing listing = listingRepository.findById(listingId).orElse(null);

        if (listing == null)
            throw new EntityNotFoundException("Listing id: " + listingId + " not found.");

        if(listing.getListingType() == ListingType.FOREX && isClient())
            throw new IllegalStateException("Klijenti nemaju pristup FOREX hartijama.");

        LocalDate now = LocalDate.now();
        List<ListingDailyPriceInfo> dailyPrices;

        if ("DAY".equalsIgnoreCase(period)) dailyPrices = dailyPriceRepository.findByListingIdAndDate(listingId, now);

        else if ("WEEK".equalsIgnoreCase(period))
            dailyPrices = dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(
                    listingId, now.minusDays(7)
            );

        else if ("MONTH".equalsIgnoreCase(period))
            dailyPrices = dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(
                    listingId, now.minusDays(30)
            );

        else if ("YEAR".equalsIgnoreCase(period))
            dailyPrices = dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(
                    listingId, now.minusYears(1)
            );

        else if ("FIVE_YEARS".equalsIgnoreCase(period))
            dailyPrices = dailyPriceRepository.findByListingIdAndDateAfterOrderByDateDesc(
                    listingId, now.minusYears(5)
            );

        else if ("ALL".equalsIgnoreCase(period))
            dailyPrices = dailyPriceRepository.findByListingIdOrderByDateDesc(listingId);

        else throw new IllegalArgumentException("Period može biti: DAY, WEEK, MONTH, YEAR, FIVE_YEARS, ALL");

        return dailyPrices.stream().map(ListingMapper::toDailyPriceDto).toList();
    }

    /**
     * Osvezavanje cena svih listinga.
     *
     * <p><b>NE @Transactional na celoj metodi.</b> Razlog: Alpha Vantage besplatni
     * tier dozvoljava 5 poziva/min, pa {@link #fetchAlphaVantagePrice} drzi
     * {@code Thread.sleep(12_000)} izmedju poziva. Sa N STOCK listinga,
     * trajanje petlje je N×12s (1-6 min). Ako je ova metoda jedan veliki
     * {@code @Transactional}, HikariCP connection se drzi celo to vreme i
     * connection pool se iscrpljuje pod manjim opterecenjem.
     *
     * <p>Refaktor (BE-STK-01 fix): split u dve faze:
     * <ol>
     *   <li><b>Phase A (no Tx)</b> — {@link #fetchPricesForListings} cita
     *       sve listing-e (readOnly Tx, drzi se kratko), pa van Tx iterira i
     *       fetch-uje cene sa AV / fixer.io (sa sleep-ovima). Mutira detached
     *       {@code Listing} objekte in-memory.</li>
     *   <li><b>Phase B (writable Tx)</b> — {@link #persistRefreshedPrices}
     *       u jednoj kratkoj transakciji {@code saveAll(listings)} +
     *       {@code saveDailyPriceSnapshot(...)} za svaki ne-null listing.
     *       Tx traje samo dok DB ne potvrdi save (sekunde, ne minute).</li>
     * </ol>
     */
    @Override
    public void refreshPrices() {
        // Phase A: load listings + fetch external prices VAN Tx (Thread.sleep ne blokira DB).
        List<Listing> listings = loadListingsForRefresh();
        Map<String, Boolean> testModeByAcronym = loadExchangeTestModeMap();
        fetchPricesForListings(listings, testModeByAcronym);

        // Phase B: persist u maloj writable Tx. saveAll + dnevni snapshot-i.
        persistRefreshedPrices(listings);

        // B5 — Cenovni alarmi: posle osvezenja cena pozovi PriceAlertService
        // da evaluira sve aktivne alarme za promenjene listinge. Best-effort —
        // greska ne sme da prekine refresh-prices ciklus. PriceAlertScheduler
        // u pozadini i dalje radi na 60s kao primarni mehanizam (ovde je hook
        // za brzu reakciju cim se cena osvezi).
        try {
            PriceAlertService priceAlertService = priceAlertServiceProvider != null
                    ? priceAlertServiceProvider.getIfAvailable() : null;
            if (priceAlertService != null && !listings.isEmpty()) {
                int triggered = priceAlertService.checkAlerts(listings);
                if (triggered > 0) {
                    log.info("B5 PriceAlert hook: triggered {} alerts after price refresh", triggered);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("B5 PriceAlert evaluation hook pukao: {}", ex.getMessage());
        }
    }

    /**
     * Phase A.1: load all listings. Kratak readOnly Tx — drzi konekciju samo
     * dok JPA ne materijalizuje rezultat, pa se vraca pre nego sto pocnu AV
     * sleep-ovi. Objekti se vracaju kao detached entiteti.
     */
    @Transactional(readOnly = true)
    public List<Listing> loadListingsForRefresh() {
        return listingRepository.findAll();
    }

    /**
     * Phase A.2 (NE @Transactional): For each listing, fetch external price
     * data (Alpha Vantage / fixer.io / random) and mutate the {@code Listing}
     * objects in-memory. Thread.sleep poziva BezNet ne drzi DB konekciju.
     *
     * <p>Package-private za testabilnost — direktno se moze pozvati u unit
     * testovima sa unapred pripremljenom listom listing-a (postojeci testovi
     * pozivaju {@link #refreshPrices} koje delega ovde + persistRefreshedPrices).
     */
    void fetchPricesForListings(List<Listing> listings, Map<String, Boolean> testModeByAcronym) {
        for (Listing listing : listings) {
            BigDecimal currentPrice = listing.getPrice();
            if (currentPrice == null) continue;

            BigDecimal newPrice;
            BigDecimal newAsk;
            BigDecimal newBid;
            BigDecimal priceChange;
            long newVolume;

            boolean exchangeInTestMode = isListingExchangeInTestMode(listing, testModeByAcronym);

            if (exchangeInTestMode) {
                // Test mode: simuliramo cene bez trosenja Alpha Vantage / fixer.io kljuceva
                PriceTick tick = simulateTick(listing, currentPrice);
                newPrice = tick.price();
                newAsk = tick.ask();
                newBid = tick.bid();
                priceChange = tick.priceChange();
                newVolume = tick.volume();
                log.debug("Refreshed {} in test-mode (simulation): price={}", listing.getTicker(), newPrice);
            } else if (listing.getListingType() == ListingType.STOCK) {
                // Try Alpha Vantage for stocks
                BigDecimal[] alphaResult = fetchAlphaVantagePrice(listing.getTicker());
                if (alphaResult != null) {
                    newPrice = alphaResult[0];
                    newAsk = askFor(newPrice);
                    newBid = bidFor(newPrice);
                    priceChange = alphaResult[1]; // change from previous close
                    newVolume = alphaResult[2].longValue();
                    // Use real high/low if available
                    if (alphaResult[3] != null) newAsk = alphaResult[3]; // daily high
                    if (alphaResult[4] != null) newBid = alphaResult[4]; // daily low
                    log.info("Refreshed {} from Alpha Vantage: price={}", listing.getTicker(), newPrice);
                } else {
                    log.warn("Alpha Vantage unavailable for {}, using random simulation", listing.getTicker());
                    PriceTick tick = simulateTick(listing, currentPrice);
                    newPrice = tick.price();
                    newAsk = tick.ask();
                    newBid = tick.bid();
                    priceChange = tick.priceChange();
                    newVolume = tick.volume();
                }
            } else if (listing.getListingType() == ListingType.FOREX) {
                // Use BankaCoreClient (fixer.io via banka-core /internal/fx/rates) for forex pairs
                BigDecimal forexPrice = fetchForexPrice(listing.getBaseCurrency(), listing.getQuoteCurrency());
                if (forexPrice != null) {
                    newPrice = forexPrice;
                    newAsk = askFor(newPrice);
                    newBid = bidFor(newPrice);
                    priceChange = newPrice.subtract(currentPrice);
                    newVolume = listing.getVolume() != null
                            ? (long) (listing.getVolume() * (SIM_VOLUME_MIN_FACTOR + (SIM_VOLUME_RANGE * random.nextDouble())))
                            : DEFAULT_SIM_VOLUME;
                    log.info("Refreshed {} from fixer.io: price={}", listing.getTicker(), newPrice);
                } else {
                    log.warn("Fixer.io unavailable for {}, using random simulation", listing.getTicker());
                    PriceTick tick = simulateTick(listing, currentPrice);
                    newPrice = tick.price();
                    newAsk = tick.ask();
                    newBid = tick.bid();
                    priceChange = tick.priceChange();
                    newVolume = tick.volume();
                }
            } else {
                // FUTURES — keep random simulation (no free API)
                PriceTick tick = simulateTick(listing, currentPrice);
                newPrice = tick.price();
                newAsk = tick.ask();
                newBid = tick.bid();
                priceChange = tick.priceChange();
                newVolume = tick.volume();
            }

            listing.setPrice(newPrice);
            listing.setAsk(newAsk);
            listing.setBid(newBid);
            listing.setPriceChange(priceChange);
            listing.setLastRefresh(LocalDateTime.now());
            listing.setVolume(newVolume);

            // Time-series tick u InfluxDB. ObjectProvider gracefuly vraca null
            // ako banka2.influx.enabled=false (trading-service i dalje radi bez Influx-a).
            // Null check za testove koji konstrukcijom rucnim ne prosledjuju provider.
            ListingPriceRecorder recorder = priceRecorderProvider != null
                    ? priceRecorderProvider.getIfAvailable() : null;
            if (recorder != null) {
                BigDecimal high = currentPrice.max(newPrice);
                BigDecimal low  = currentPrice.min(newPrice);
                recorder.recordTick(
                        listing.getTicker(),
                        listing.getExchangeAcronym(),
                        listing.getListingType() != null ? listing.getListingType().name() : "STOCK",
                        currentPrice,
                        high,
                        low,
                        newPrice,
                        newVolume,
                        newAsk,
                        newBid,
                        Instant.now()
                );
            }
        }
    }

    /**
     * Phase B: persist mutated listings + dnevne snapshot-e u jednoj
     * kratkoj writable Tx. Konekcija se drzi samo dok save-ovi ne zavrse —
     * nema Thread.sleep-a u ovom obimu.
     */
    @Transactional
    public void persistRefreshedPrices(List<Listing> listings) {
        listingRepository.saveAll(listings);

        // Daily snapshot tek nakon save-a — saveDailyPriceSnapshot interno radi
        // findByListingIdAndDate + save (insert/update). Ovo je manja Tx pa
        // saveAll prvi da garantujemo listing id (ako je novi listing) — u
        // praksi su svi listings vec persistovani, ovo je samo refresh.
        for (Listing listing : listings) {
            if (listing.getPrice() == null) continue;
            saveDailyPriceSnapshot(listing, listing.getPrice(), listing.getAsk(),
                    listing.getBid(), listing.getPriceChange(),
                    listing.getVolume() != null ? listing.getVolume() : 0L);
        }
    }

    /**
     * Fetches real stock price from Alpha Vantage GLOBAL_QUOTE endpoint.
     * Returns [price, change, volume, high, low] or null on failure.
     * Includes 12-second delay for rate limiting (free tier: 5 calls/min, 25/day).
     */
    private BigDecimal[] fetchAlphaVantagePrice(String ticker) {
        try {
            String apiKey = getNextApiKey();
            String url = stockApiUrl + "?function=GLOBAL_QUOTE&symbol=" + ticker + "&apikey=" + apiKey;

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response == null || !response.containsKey("Global Quote")) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, String> quote = (Map<String, String>) response.get("Global Quote");

            if (quote == null || quote.isEmpty() || quote.get("05. price") == null) {
                return null;
            }

            BigDecimal price = new BigDecimal(quote.get("05. price")).setScale(4, RoundingMode.HALF_UP);
            BigDecimal previousClose = quote.get("08. previous close") != null
                    ? new BigDecimal(quote.get("08. previous close"))
                    : BigDecimal.ZERO;
            BigDecimal change = price.subtract(previousClose).setScale(4, RoundingMode.HALF_UP);
            BigDecimal volume = quote.get("06. volume") != null
                    ? new BigDecimal(quote.get("06. volume"))
                    : BigDecimal.valueOf(100000);
            BigDecimal high = quote.get("03. high") != null
                    ? new BigDecimal(quote.get("03. high")).setScale(4, RoundingMode.HALF_UP)
                    : null;
            BigDecimal low = quote.get("04. low") != null
                    ? new BigDecimal(quote.get("04. low")).setScale(4, RoundingMode.HALF_UP)
                    : null;

            // Rate limiting: 12-second delay between API calls (5 calls/min max)
            sleepQuietly(12000);

            return new BigDecimal[]{price, change, volume, high, low};
        } catch (Exception e) {
            log.warn("Alpha Vantage API error for {}: {}", ticker, e.getMessage());
            return null;
        }
    }

    @lombok.Generated
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Fetches forex rate via BankaCoreClient → banka-core /internal/fx/rates (fixer.io).
     * Calculates cross rate for the given currency pair.
     *
     * NOTE: Uses record-accessor syntax: rate.currency() and rate.rate()
     * (FxRateDto is a Java record in banka2-contracts).
     */
    private BigDecimal fetchForexPrice(String baseCurrency, String quoteCurrency) {
        try {
            if (baseCurrency == null || quoteCurrency == null) return null;
            List<FxRateDto> rates = bankaCoreClient.getFxRates();
            Double baseRate = null;
            Double quoteRate = null;
            for (FxRateDto rate : rates) {
                if (rate.currency().equalsIgnoreCase(baseCurrency)) baseRate = rate.rate();
                if (rate.currency().equalsIgnoreCase(quoteCurrency)) quoteRate = rate.rate();
            }
            if (baseRate == null || quoteRate == null || baseRate == 0.0) return null;
            double crossRate = quoteRate / baseRate;
            return BigDecimal.valueOf(crossRate).setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("Fixer.io error for {}/{}: {}", baseCurrency, quoteCurrency, e.getMessage());
            return null;
        }
    }

    /**
     * Saves or updates the daily price snapshot for the listing history chart.
     */
    private void saveDailyPriceSnapshot(Listing listing, BigDecimal newPrice,
                                        BigDecimal high, BigDecimal low,
                                        BigDecimal priceChange, long volume) {
        LocalDate today = LocalDate.now();
        List<ListingDailyPriceInfo> existingToday = dailyPriceRepository
                .findByListingIdAndDate(listing.getId(), today);
        if (existingToday.isEmpty()) {
            ListingDailyPriceInfo dailyPrice = new ListingDailyPriceInfo();
            dailyPrice.setListing(listing);
            dailyPrice.setDate(today);
            dailyPrice.setPrice(newPrice);
            dailyPrice.setHigh(high);
            dailyPrice.setLow(low);
            dailyPrice.setChange(priceChange);
            dailyPrice.setVolume(volume);
            dailyPriceRepository.save(dailyPrice);
        } else {
            ListingDailyPriceInfo existing = existingToday.get(0);
            existing.setPrice(newPrice);
            // BE-STK-03 fix: null guard pre compareTo da spreci NPE kad prvi
            // refresh ne persistuje high/low ili upstream snapshot ima null vrednosti
            if (high != null && (existing.getHigh() == null || high.compareTo(existing.getHigh()) > 0)) {
                existing.setHigh(high);
            }
            if (low != null && (existing.getLow() == null || low.compareTo(existing.getLow()) < 0)) {
                existing.setLow(low);
            }
            existing.setChange(priceChange);
            existing.setVolume(volume);
            dailyPriceRepository.save(existing);
        }
    }

    /**
     * Scheduled 15-minute price refresh. Live od cutover-a (2f) — trading-service
     * je vlasnik trgovinskih schedulera ({@code SchedulingConfig @EnableScheduling}).
     *
     * <p>P2-perf-nplus1-1 (R5 1904): {@code fixedDelay} (ne {@code fixedRate}) —
     * 15min se broji od KRAJA prethodnog ciklusa, ne od pocetka. {@code fixedRate}
     * dozvoljava preklapanje: ako jedan refresh traje duze od 15min (spor
     * AlphaVantage / mnogo listinga), sledeci tick starta dok prethodni jos traje,
     * pa se na thread-pool-u gomilaju paralelni refresh-evi i medjusobno se utrkuju
     * nad istim {@code listing_daily_prices} redovima. {@code fixedDelay} serijalizuje
     * ciklus po definiciji.
     */
    @Scheduled(fixedDelay = 900000)
    public void scheduledRefresh() {
        // Scheduler samo okida business metodu, ne sadrži logiku direktno
        this.refreshPrices();
    }
}
