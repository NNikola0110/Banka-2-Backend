package rs.raf.trading.dividend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.dividend.dto.DividendPayoutDto;
import rs.raf.trading.dividend.model.DividendPayout;
import rs.raf.trading.dividend.repository.DividendPayoutRepository;
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.investmentfund.service.FundDividendService;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.util.ListingCurrencyResolver;
import rs.raf.trading.tax.util.TaxConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Servis za kvartalnu isplatu dividendi na akcije (B9).
 *
 * <p>Domenski split:
 * <ul>
 *   <li>Portfolio + Listing su LOKALNI — citaju se iz trading_db.</li>
 *   <li>Racun + knjizenje su BANKARSKI — idu preko {@link BankaCoreClient}.</li>
 * </ul>
 *
 * <p>Poziva ga {@code DividendScheduler} jednom kvartalno. Svaka pojedinacna
 * isplata je izolovana u {@link #payDividendForOwner} koji nosi
 * {@code @Transactional}, cime se osigurava da Spring AOP proxy prode
 * kroz odgovarajuci bean (intra-class self-invoke bi bio zaobidjen).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DividendService {

    // R1 798: stopa poreza je centralizovana u TaxConstants (deljena sa TaxService);
    // tax = TaxConstants.computeTax(grossAmount).
    // Kvartalni delilac (godisnji yield / 4) — dividendna domena, nije poreska konstanta.
    private static final BigDecimal QUARTERS_PER_YEAR = new BigDecimal("4");

    private final DividendPayoutRepository dividendPayoutRepository;
    private final PortfolioRepository portfolioRepository;
    private final ListingRepository listingRepository;
    private final BankaCoreClient bankaCoreClient;
    private final TradingUserResolver userResolver;
    private final CurrencyConversionService currencyConversionService;
    private final FundDividendService fundDividendService;
    private final InvestmentFundRepository investmentFundRepository;

    /**
     * Self-injection (lazy) sluzi da AOP proxy uhvati {@code @Transactional} u
     * {@link #payDividendForOwner}. Bez ovoga, intra-class poziv iz
     * {@link #processQuarterlyDividends} bi zaobisao proxy → ako banka-core
     * kredit uspe ali repo.save fail-uje, izostao bi rollback i sledeci kvartal
     * bi platili dva puta (BE-FND-03).
     */
    @Lazy
    @Autowired
    private DividendService self;

    // ── Kvartalna obrada ──────────────────────────────────────────────────────

    /**
     * Glavna metoda – poziva je {@code DividendScheduler}.
     * <p>
     * Algoritam:
     * <ol>
     *   <li>Ucitaj sve Portfolio pozicije tipa STOCK sa quantity &gt; 0.</li>
     *   <li>Za svaku poziciju provjeri idempotentnost (ne isplacuj duplo).</li>
     *   <li>Pozovi {@link #payDividendForOwner} koji radi stvarno knjizenje.</li>
     * </ol>
     *
     * @param paymentDate datum isplate (vec adjustovan na radni dan u scheduler-u)
     */
    public void processQuarterlyDividends(LocalDate paymentDate) {
        List<Portfolio> positions = portfolioRepository.findAllStockPositionsWithQuantity();
        log.info("DividendService: pocinje kvartalna isplata za {} — {} STOCK pozicija",
                paymentDate, positions.size());

        // Grupisemo po (ownerId, ownerType, listingId) — jedan payout po vlasniku per hartiji,
        // cak i ako vlasnik ima vise Portfolio redova za istu hartiju (parcijalni fillovi).
        Map<String, List<Portfolio>> grouped = positions.stream()
                .collect(Collectors.groupingBy(
                        p -> p.getUserId() + "_" + p.getUserRole() + "_" + p.getListingId()));

        int paid = 0;
        int skipped = 0;

        for (Map.Entry<String, List<Portfolio>> entry : grouped.entrySet()) {
            List<Portfolio> group = entry.getValue();
            Portfolio position = group.get(0);
            Long listingId = position.getListingId();
            Long ownerId = position.getUserId();
            String ownerType = position.getUserRole();

            // P1-dividends-order-1 (R1 227): vlasnik moze imati VISE Portfolio redova za
            // istu hartiju (parcijalni fillovi) — dividenda se placa na UKUPNU kolicinu.
            // Pre fix-a: placalo se samo group.get(0).getQuantity() -> underpay. Sumiramo
            // sve redove grupe u jednu reprezentativnu poziciju (na kopiji, da ne mutiramo
            // perzistovan entitet).
            if (group.size() > 1) {
                int totalQty = group.stream().mapToInt(Portfolio::getQuantity).sum();
                position = aggregatePosition(position, totalQty);
            }

            // Idempotentnost per-vlasnik: proveravamo da li je ovaj (owner, listing, date)
            // vec isplacen, a ne samo da li postoji bilo koji payout za taj listing+datum.
            List<DividendPayout> existing =
                    dividendPayoutRepository.findByStockListingIdAndPaymentDate(listingId, paymentDate);
            boolean alreadyPaid = existing.stream()
                    .anyMatch(ep -> ep.getOwnerId().equals(ownerId)
                            && ep.getOwnerType().equalsIgnoreCase(ownerType));
            if (alreadyPaid) {
                log.debug("DividendService: preskacam listing={} owner={}/{} — dividenda za {} vec postoji",
                        listingId, ownerType, ownerId, paymentDate);
                skipped++;
                continue;
            }

            try {
                // KRITICNO (BE-FND-03): pozivamo preko self proxy-ja da bi
                // {@code @Transactional} na payDividendForOwner bio aktivan.
                // Direktan poziv (this.payDividendForOwner) bi bio intra-class
                // self-invoke koji zaobilazi Spring AOP, pa rollback ne bi radio.
                DividendPayout result = self.payDividendForOwner(position, paymentDate);
                if (result != null) paid++;
            } catch (Exception ex) {
                log.error("DividendService: greska pri isplati dividende listing={} owner={}/{}: {}",
                        listingId, ownerType, ownerId, ex.getMessage(), ex);
                // Nastavljamo — jedna greska ne sme da blokira ostatak isplate.
            }
        }

        log.info("DividendService: kvartalna isplata zavrsena — isplaceno={}, preskoceno={}",
                paid, skipped);

        // TODO_final C4 #14 / Sc 70: posle DIVIDEND_INFLOW kreditiranja, dispatch
        // svake fondovske dividende po politici fonda (reinvest vs distribute).
        dispatchFundDividendsByPolicy();
    }

    /**
     * TODO_final C4 #14 / Sc 70: itera kroz sve aktivne fondove i, za svaki sa
     * pending DIVIDEND_INFLOW transakcijama, poziva odgovarajuci handler:
     * <ul>
     *   <li>{@code fund.reinvestDividends == true} —
     *       {@link FundDividendService#reinvestDividends(Long)} kreira auto-BUY
     *       ordere za top holdings fonda.</li>
     *   <li>{@code fund.reinvestDividends == false} (default) —
     *       {@link FundDividendService#distributeDividendsToClients(Long)}
     *       prebacuje cash klijentima srazmerno totalInvested.</li>
     * </ul>
     *
     * <p>Best-effort per fond — greska u jednom fondu ne blokira ostale.
     * Idempotency je pokriven u FundDividendService preko ClientFundTransaction
     * status tranzicije (DIVIDEND_INFLOW → REINVESTED/DISTRIBUTED).
     */
    public void dispatchFundDividendsByPolicy() {
        List<InvestmentFund> activeFunds = investmentFundRepository.findByActiveTrueOrderByNameAsc();
        for (InvestmentFund fund : activeFunds) {
            try {
                // R1 796 — deljeni per-fund reinvest-vs-distribute switch zivi u FundDividendService.
                fundDividendService.dispatchByPolicy(fund);
            } catch (Exception ex) {
                log.error("TODO_final C4 #14: dispatch dividendi za fond #{} ({}) propao: {}",
                        fund.getId(), fund.getName(), ex.getMessage(), ex);
                // Nastavljamo — jedna greska ne sme da blokira ostale fondove.
            }
        }
    }

    /**
     * P1-dividends-order-1 (R1 227): pravi plitku kopiju Portfolio pozicije sa
     * agregiranom (sumiranom) kolicinom svih redova vlasnika za istu hartiju.
     * Kopija se NE perzistuje (nije managed) — sluzi samo kao nosilac ukupne
     * kolicine za obracun dividende. Ostala polja (owner, listing, ticker) se
     * preuzimaju iz reprezentativnog reda.
     */
    private Portfolio aggregatePosition(Portfolio representative, int totalQuantity) {
        Portfolio agg = new Portfolio();
        agg.setUserId(representative.getUserId());
        agg.setUserRole(representative.getUserRole());
        agg.setListingId(representative.getListingId());
        agg.setListingTicker(representative.getListingTicker());
        agg.setListingName(representative.getListingName());
        agg.setListingType(representative.getListingType());
        agg.setQuantity(totalQuantity);
        return agg;
    }

    // ── Jedna transakciona isplata ────────────────────────────────────────────

    /**
     * Izvrsava kvartalnu isplatu dividende za jednu Portfolio poziciju.
     * <p>
     * Mora biti pozvan iz drugog Spring bean-a (ne intra-class self-invoke) da bi
     * {@code @Transactional} prosao kroz AOP proxy.
     *
     * @param portfolio   pozicija vlasnika (ownerId + ownerType + listingId)
     * @param paymentDate datum isplate
     * @return perzistirani {@link DividendPayout}
     */
    @Transactional
    public DividendPayout payDividendForOwner(Portfolio portfolio, LocalDate paymentDate) {
        Long ownerId = portfolio.getUserId();
        String ownerType = portfolio.getUserRole();
        Long listingId = portfolio.getListingId();
        int quantity = portfolio.getQuantity();

        // 1. Ucitaj Listing da dobijemo cenu i godisnji prinos.
        Listing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Listing " + listingId + " nije pronadjen"));

        BigDecimal price = listing.getPrice();
        BigDecimal annualYield = listing.getDividendYield();

        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(
                    "Listing " + listingId + " nema vazece price polje — ne mogu obracunati dividendu");
        }
        if (annualYield == null || annualYield.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("DividendService: preskacam listing={} ticker={} — dividendYield={} (nema dividende, silent skip)",
                    listingId, listing.getTicker(), annualYield);
            return null;
        }

        // 2. Izracunaj iznose.
        // P1-dividends-order-1 (R5 1843): gross se racuna u JEDNOM lancu
        // (qty × price × annualYield / 4) sa visokom preciznoscu, pa se tek
        // grossAmount zaokruzi na money scale. Pre fix-a se quarterlyYield
        // zaokruzivao na scale-6 PRE mnozenja sa (qty×price) — za veliki quantity
        // (npr. 1.000.000 akcija) ta truncation se mnozi i akumulira gresku do
        // celih jedinica valute. quarterlyYield se i dalje cuva na DividendPayout
        // kao prikazna vrednost (scale-6), ali se NE koristi u obracunu.
        BigDecimal quarterlyYield = annualYield
                .divide(QUARTERS_PER_YEAR, 6, RoundingMode.HALF_UP);
        BigDecimal grossAmount = BigDecimal.valueOf(quantity)
                .multiply(price)
                .multiply(annualYield)
                .divide(QUARTERS_PER_YEAR, java.math.MathContext.DECIMAL128)
                .setScale(4, RoundingMode.HALF_UP);

        // 2.5. B11 — FUND-vlasnistvo dispatch: ne knjizimo direktno, vec
        // delegiramo na FundDividendService koji kreditira fond racun (RSD)
        // i dalje orkestrira reinvestiranje / raspodelu klijentima.
        if ("FUND".equalsIgnoreCase(ownerType)) {
            return payFundDividend(portfolio, listing, paymentDate, quantity, price,
                    quarterlyYield, grossAmount);
        }

        boolean taxExempt = "EMPLOYEE".equals(ownerType);
        BigDecimal tax = taxExempt
                ? BigDecimal.ZERO
                : TaxConstants.computeTax(grossAmount);
        BigDecimal netAmount = grossAmount.subtract(tax).setScale(4, RoundingMode.HALF_UP);

        // 3. Odredi valutu listinga.
        String listingCurrency = ListingCurrencyResolver.resolve(listing);

        // 4. Razresi ciljni racun + eventualni RSD fallback.
        boolean usedRsdFallback = false;
        BigDecimal amountToCredit = netAmount;
        String creditedCurrency = listingCurrency;
        InternalAccountDto targetAccount;

        if (taxExempt) {
            // EMPLOYEE (aktuar) — bankin trading racun.
            targetAccount = resolveBankTradingAccount(listingCurrency);
            if (targetAccount == null) {
                usedRsdFallback = true;
                amountToCredit = currencyConversionService.convert(netAmount, listingCurrency, "RSD");
                creditedCurrency = "RSD";
                targetAccount = bankaCoreClient.getBankTradingAccount("RSD");
            }
        } else {
            // CLIENT — preferiran klijentov aktivan racun u valuti listinga.
            try {
                targetAccount = bankaCoreClient.getPreferredAccount("CLIENT", ownerId, listingCurrency);
            } catch (BankaCoreClientException ex) {
                if (ex.getHttpStatus() >= 500) {
                    throw ex; // 5xx: banka-core je dole — ne gutati, propagovati
                }
                log.warn("DividendService: preferiran {} racun ne postoji za klijenta {} — prelazim na RSD fallback",
                        listingCurrency, ownerId);
                usedRsdFallback = true;
                amountToCredit = currencyConversionService.convert(netAmount, listingCurrency, "RSD");
                creditedCurrency = "RSD";
                targetAccount = bankaCoreClient.getPreferredAccount("CLIENT", ownerId, "RSD");
            }
        }

        if (usedRsdFallback) {
            log.info("DividendService: RSD fallback za owner={}/{} listing={} — {} {} -> {} RSD",
                    ownerType, ownerId, listingId, netAmount, listingCurrency, amountToCredit);
        }

        // 5. Knjizi neto iznos na racun (deterministicki idempotency kljuc).
        String idempotencyKey = "dividend-" + ownerType + "-" + ownerId + "-"
                + listingId + "-" + paymentDate;
        String description = "Kvartalna dividenda: " + portfolio.getListingTicker()
                + " x" + quantity + " @ " + paymentDate;

        bankaCoreClient.creditFunds(
                idempotencyKey,
                new CreditFundsRequest(
                        targetAccount.id(),
                        amountToCredit,
                        BigDecimal.ZERO,
                        creditedCurrency,
                        description));

        // 6. Sacuvaj DividendPayout u trading_db.
        DividendPayout payout = DividendPayout.builder()
                .ownerId(ownerId)
                .ownerType(ownerType)
                .stockListingId(listingId)
                .stockTicker(portfolio.getListingTicker() != null
                        ? portfolio.getListingTicker()
                        : listing.getTicker())
                .quantity(quantity)
                .priceOnDate(price)
                .dividendYieldRate(quarterlyYield)
                .grossAmount(grossAmount)
                .tax(tax)
                .netAmount(netAmount)
                .creditedAccountId(targetAccount.id())
                .currencyCode(creditedCurrency)
                .paymentDate(paymentDate)
                .taxExempt(taxExempt)
                .build();

        DividendPayout saved = dividendPayoutRepository.save(payout);
        log.info("DividendService: isplaceno — owner={}/{} listing={} ticker={} gross={} tax={} net={} {}",
                ownerType, ownerId, listingId, saved.getStockTicker(),
                grossAmount, tax, amountToCredit, creditedCurrency);
        return saved;
    }

    /**
     * B11 — Isplata dividende za FUND-vlasnistvo: delegira na
     * {@link FundDividendService#creditDividendToFund} koji RSD-konvertuje
     * iznos (ako je listing u stranoj valuti), kreditira fond racun preko
     * banka-core seam-a, i upisuje {@link ClientFundTransaction} sa statusom
     * {@code DIVIDEND_INFLOW}.
     */
    private DividendPayout payFundDividend(Portfolio portfolio, Listing listing,
                                           LocalDate paymentDate, int quantity,
                                           BigDecimal price, BigDecimal quarterlyYield,
                                           BigDecimal grossAmount) {
        String listingCurrency = ListingCurrencyResolver.resolve(listing);
        BigDecimal amountForFund = "RSD".equalsIgnoreCase(listingCurrency)
                ? grossAmount
                : currencyConversionService.convert(grossAmount, listingCurrency, "RSD");

        ClientFundTransaction fundTx = fundDividendService.creditDividendToFund(
                portfolio.getUserId(),
                portfolio.getListingId(),
                amountForFund);

        DividendPayout payout = DividendPayout.builder()
                .ownerId(portfolio.getUserId())
                .ownerType("FUND")
                .stockListingId(portfolio.getListingId())
                .stockTicker(portfolio.getListingTicker() != null
                        ? portfolio.getListingTicker()
                        : listing.getTicker())
                .quantity(quantity)
                .priceOnDate(price)
                .dividendYieldRate(quarterlyYield)
                .grossAmount(amountForFund)
                .tax(BigDecimal.ZERO)
                .netAmount(amountForFund)
                .creditedAccountId(fundTx.getSourceAccountId())
                .currencyCode("RSD")
                .paymentDate(paymentDate)
                .taxExempt(true)
                .build();

        DividendPayout saved = dividendPayoutRepository.save(payout);
        log.info("B11: dividenda za ticker {} uplacena fondu #{} — iznos={} RSD (gross {} {})",
                portfolio.getListingTicker(), portfolio.getUserId(), amountForFund,
                grossAmount, listingCurrency);
        return saved;
    }

    // ── Query metode za korisnike ─────────────────────────────────────────────

    /**
     * Vraca istoriju dividendi za trenutno ulogovanog klijenta ili zaposlenog.
     */
    @Transactional(readOnly = true)
    public List<DividendPayoutDto> getMyDividendHistory() {
        UserContext ctx = userResolver.resolveCurrent();
        return dividendPayoutRepository
                .findByOwnerIdAndOwnerTypeOrderByPaymentDateDesc(ctx.userId(), ctx.userRole())
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Vraca istoriju dividendi za konkretnu Portfolio poziciju.
     * <p>
     * Ulogovani korisnik mora biti vlasnik te Portfolio pozicije.
     *
     * @param portfolioId id Portfolio entiteta
     * @throws AccessDeniedException ako pozivac nije vlasnik portfolio pozicije
     */
    @Transactional(readOnly = true)
    public List<DividendPayoutDto> getDividendHistoryByPosition(Long portfolioId) {
        UserContext ctx = userResolver.resolveCurrent();

        Portfolio portfolio = portfolioRepository.findById(portfolioId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Portfolio pozicija " + portfolioId + " nije pronadjena"));

        if (!portfolio.getUserId().equals(ctx.userId())
                || !portfolio.getUserRole().equals(ctx.userRole())) {
            throw new AccessDeniedException(
                    "Nemate pravo na dividendu pozicije " + portfolioId);
        }

        return dividendPayoutRepository
                .findByOwnerIdAndOwnerTypeAndStockListingId(
                        ctx.userId(), ctx.userRole(), portfolio.getListingId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Admin pregled svih isplata dividendi, paginiran, sa opcionim filterom datuma.
     *
     * <p>Ako je zadan samo {@code from}, {@code to} se defaultuje na danasnji datum.
     * Ako je zadan samo {@code to}, {@code from} se defaultuje na 1970-01-01.
     * Ako nijedan nije zadat, vraca se kompletna istorija sortirano po datumu DESC.
     *
     * @param from opcionalni pocetni datum (inclusive); null = bez donje granice
     * @param to   opcionalni krajnji datum (inclusive); null = bez gornje granice
     */
    @Transactional(readOnly = true)
    public Page<DividendPayoutDto> getAdminDividendHistory(LocalDate from, LocalDate to,
                                                           Pageable pageable) {
        Page<DividendPayout> page;
        if (from != null) {
            LocalDate effectiveTo = (to != null) ? to : LocalDate.now(ZoneOffset.UTC);
            page = dividendPayoutRepository.findByPaymentDateBetween(from, effectiveTo, pageable);
        } else if (to != null) {
            LocalDate effectiveFrom = LocalDate.of(1970, 1, 1);
            page = dividendPayoutRepository.findByPaymentDateBetween(effectiveFrom, to, pageable);
        } else {
            page = dividendPayoutRepository.findAllByOrderByPaymentDateDesc(pageable);
        }
        return page.map(this::toDto);
    }

    // ── Pomocne metode ────────────────────────────────────────────────────────

    /**
     * Pokusava da dobavi bankin trading racun za datu valutu.
     * Vraca {@code null} za 4xx (signal za RSD fallback).
     * Propaguje izuzetak za 5xx (banka-core je nedostupna — ne gutati).
     */
    private InternalAccountDto resolveBankTradingAccount(String currencyCode) {
        try {
            return bankaCoreClient.getBankTradingAccount(currencyCode);
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() >= 500) {
                throw ex; // 5xx: propagovati, ne gutati
            }
            log.warn("DividendService: bankin trading racun za {} nije pronadjen — prelazim na RSD fallback ({})",
                    currencyCode, ex.getMessage());
            return null;
        }
    }

    private DividendPayoutDto toDto(DividendPayout p) {
        return DividendPayoutDto.builder()
                .id(p.getId())
                .ownerId(p.getOwnerId())
                .ownerType(p.getOwnerType())
                .stockListingId(p.getStockListingId())
                .stockTicker(p.getStockTicker())
                .quantity(p.getQuantity())
                .priceOnDate(p.getPriceOnDate())
                .dividendYieldRate(p.getDividendYieldRate())
                .grossAmount(p.getGrossAmount())
                .tax(p.getTax())
                .netAmount(p.getNetAmount())
                .creditedAccountId(p.getCreditedAccountId())
                .currencyCode(p.getCurrencyCode())
                .paymentDate(p.getPaymentDate())
                .taxExempt(p.getTaxExempt())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
