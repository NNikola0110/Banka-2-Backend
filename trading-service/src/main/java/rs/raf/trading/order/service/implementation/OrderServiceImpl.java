package rs.raf.trading.order.service.implementation;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.trading.actuary.model.ActuaryInfo;
import rs.raf.trading.actuary.model.ActuaryType;
import rs.raf.trading.actuary.repository.ActuaryInfoRepository;
import rs.raf.trading.audit.model.AuditActionType;
import rs.raf.trading.audit.service.AuditLogService;
import rs.raf.trading.berza.service.ExchangeManagementService;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.OrderCommissionPolicy;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.repository.MarginAccountRepository;
import rs.raf.trading.margin.service.MarginOrderSettlementService;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.dto.CreateOrderDto;
import rs.raf.trading.order.dto.OrderDto;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.exception.InsufficientHoldingsException;
import rs.raf.trading.order.mapper.OrderMapper;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.repository.OrderSpecification;
import rs.raf.trading.order.service.BankTradingAccountResolver;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.order.service.FundReservationService;
import rs.raf.trading.order.service.ListingPriceService;
import rs.raf.trading.order.service.OrderService;
import rs.raf.trading.order.service.OrderStatusService;
import rs.raf.trading.order.service.OrderValidationService;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.ContractSize;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.util.ListingCurrencyResolver;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

// NAPOMENA (copy-first ekstrakcija, faza 2c — money-seam rewiring): u monolitu
// je ovaj servis citao i menjao {@code Account} direktno preko {@code AccountRepository}
// i razresavao identitet preko {@code ClientRepository}/{@code EmployeeRepository}.
// U trading-service-u racuni + identitet zive u banka-core domenu:
//   - identitet -> {@link TradingUserResolver} ({@code /internal/users/**})
//   - metadata racuna -> {@link BankaCoreClient#getAccount} / {@code getBankTradingAccount}
//   - rezervacija/oslobadjanje sredstava -> {@link FundReservationService} ({@code /internal/funds/**})
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ListingRepository listingRepository;
    private final ActuaryInfoRepository actuaryInfoRepository;
    private final OrderValidationService orderValidationService;
    private final ListingPriceService listingPriceService;
    private final OrderStatusService orderStatusService;
    private final ExchangeManagementService exchangeManagementService;
    private final FundReservationService fundReservationService;
    private final BankTradingAccountResolver bankTradingAccountResolver;
    private final CurrencyConversionService currencyConversionService;
    private final PortfolioRepository portfolioRepository;
    private final InvestmentFundRepository investmentFundRepository;
    private final BankaCoreClient bankaCoreClient;
    private final TradingUserResolver tradingUserResolver;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final MarginAccountRepository marginAccountRepository;
    private final MarginOrderSettlementService marginOrderSettlementService;

    /**
     * P2-perf-nplus1-1 (R5 1896): gornja granica page {@code size}-a za listanje
     * ordera. Bez clamp-a klijent moze poslati {@code ?size=10000} pa jedan upit
     * uzme 10k redova → svaki kroz {@code toDtoWithUserName} radi cache-miss REST
     * lookup imena (banka-core) i poplavi Caffeine kes. Clamp na 100 stiti i DB i
     * mrezni seam. Mirror standardnog DoS-guard obrasca (watchlist MAX_ITEMS).
     */
    public static final int MAX_PAGE_SIZE = 100;

    private static int clampPageSize(int size) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * KRITICNO (proxy/tx): javni 1-arg ulaz MORA biti {@code @Transactional} i
     * implementiran u klasi (override interface default-metode). Da je ostao samo
     * interface {@code default createOrder(dto) { return createOrder(dto,false); }},
     * Spring proxy ne bi imao tx-advice za 1-arg poziv kontrolera, a unutrasnji
     * poziv 2-arg metode bio bi self-invocation → {@code @Transactional} se zaobidje
     * → SELL grana ({@code ...ForUpdate}, PESSIMISTIC_WRITE) puca sa
     * "No active transaction". Ovde proxy otvori tx na 1-arg pozivu, pa se 2-arg
     * self-call pridruzi istoj tx (PROPAGATION_REQUIRED).
     */
    @Override
    @Transactional
    public OrderDto createOrder(CreateOrderDto dto) {
        return createOrder(dto, false);
    }

    @Override
    @Transactional
    public OrderDto createOrder(CreateOrderDto dto, boolean internalActor) {
        // ACCEPTED-DEVIATION (user-directed 03.06): OTP gate je UKLONJEN sa
        // kreiranja ordera (OTP ostaje samo na placanja+transfere). Nijedan flow
        // vise ne verifikuje OTP. internalActor flag je zadrzan samo radi log-
        // semantike (system vs public-initiated) i NE menja biznis logiku.
        if (internalActor) {
            org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                    .info("createOrder: internal actor flow (system-initiated)");
        }

        // Step 1: Validate input
        orderValidationService.validate(dto);

        OrderType orderType = orderValidationService.parseOrderType(dto.getOrderType());
        OrderDirection direction = orderValidationService.parseDirection(dto.getDirection());

        // Step 2: Fetch listing
        Listing listing = listingRepository.findById(dto.getListingId())
                .orElseThrow(() -> new EntityNotFoundException("Listing not found"));

        // Sc32 (Celina 3, TestoviCelina3 §32): futures/opcija sa isteklim settlement
        // datumom se odbija PRI KREIRANJU (upfront), a ne tek na prvi izvrsni tick.
        // Ranije je klijentski (auto-APPROVED) order prolazio createOrder i tek ga je
        // SingleOrderExecutor auto-declime-ovao na prvi ciklus — korisnik je dobijao
        // "uspesno kreiran" pa kasnije tihu DECLINED notifikaciju umesto direktnog
        // odbijanja. Guard ovde baca 400 sa porukom da je ugovor istekao, pre nego
        // sto se bilo sta sacuva ili rezervise. SingleOrderExecutor guard ostaje kao
        // bezbednosna mreza za stare ordere koji su istekli posle kreiranja.
        if (listing.getSettlementDate() != null
                && listing.getSettlementDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException(
                    "Ugovor je istekao (datum dospeća " + listing.getSettlementDate()
                            + " je u prošlosti) — nalog nije moguće kreirati.");
        }

        // P2-state-machine-1 (R1 407): contractSize je SVOJSTVO HARTIJE, ne klijentski
        // ulaz. Ranije se uzimao iz DTO-a i nikad uskladjivao sa listing.getContractSize()
        // → klijent je mogao da posalje manji contractSize i tako umanji approxPrice/
        // rezervaciju (i kasniji settlement koristi order.getContractSize()). Sada se
        // efektivni contractSize razresava iz listinga (autoritativno): futures ima
        // listing.contractSize.
        //
        // OT-1048 [BUG-FOUND fix]: kad listing.contractSize izostane, default se
        // razresava PO TIPU HARTIJE preko {@link ContractSize} (FOREX → 1000 per
        // spec §162, STOCK → 1) umesto slepog default-a na 1. Defanzivno: sve
        // proizvodne FOREX hartije nose contract_size=1000 (seed), ali ako FOREX
        // listing ikad dodje bez contractSize-a, raniji default=1 bi mis-pricovao
        // rezervaciju (i kasniji porez, jer order.contractSize ide u FIFO obracun)
        // za faktor 1000. Isti resolver koristi i ListingMapper (display) → margin
        // prikaz se poklapa sa stvarno rezervisanim iznosom.
        int effectiveContractSize = ContractSize.resolve(
                listing.getContractSize(), listing.getListingType());
        dto.setContractSize(effectiveContractSize);

        // Step 3: Determine price
        BigDecimal pricePerUnit = listingPriceService.getPricePerUnit(dto, listing, orderType, direction);
        BigDecimal approximatePrice = listingPriceService.calculateApproximatePrice(
                effectiveContractSize, pricePerUnit, dto.getQuantity());

        // Step 4: Resolve current user
        UserContext userContext = resolveCurrentUser();
        boolean isEmployee = UserRole.isEmployee(userContext.userRole());

        // Step 4b: Trading access gate (spec Celina 3 §6 / Celina 4 §137-141).
        //   - klijent: mora imati TRADE_STOCKS permisiju
        //   - zaposleni: mora biti SUPERVISOR, ADMIN ili AGENT
        // Bez ovog guard-a TradingSecurityConfig.POST /orders je samo authenticated()
        // pa bi klijent bez TRADE_STOCKS, ili agent bez AGENT/SUPERVISOR autoriteta,
        // mogli da prodju do biznis logike i (potencijalno) izvrsavanja ordera.
        ensureTradingAccess(userContext);

        // Step 5: Resolve account.
        //   Klijent: licni racun
        //   Supervizor sa fundId: fond.account (RSD) — P3 / Celina 4 (Nova) §3883-3964
        //   Supervizor/agent bez fundId: bankin trading racun (postojeci flow)
        String listingCurrencyCode = resolveListingCurrency(listing);
        final InvestmentFund fund;
        InternalAccountDto account;
        if (dto.getFundId() != null) {
            if (!isEmployee) {
                throw new AccessDeniedException(
                        "Samo supervizori mogu da kupuju u ime investicionog fonda.");
            }
            fund = investmentFundRepository.findById(dto.getFundId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Investicioni fond ne postoji: " + dto.getFundId()));
            // P5 — proverava se da je supervizor manager fonda; ako nije, 403.
            if (!userContext.userId().equals(fund.getManagerEmployeeId())) {
                throw new AccessDeniedException(
                        "Niste manager fonda " + fund.getName() + " — ne mozete kupovati u njegovo ime.");
            }
            account = getAccountOrThrow(fund.getAccountId(),
                    "Racun fonda ne postoji: " + fund.getAccountId());
        } else {
            fund = null;
            // accountId je opcioni (vec validovan kao null-able na DTO-u zbog
            // XOR sa fundId-jem). resolveTradingAccount handluje:
            //   - accountId != null → konkretan racun
            //   - accountId == null + zaposleni → automatski bankin trading racun u listing valuti
            //   - accountId == null + klijent → 404 "Racun ne postoji: null"
            account = resolveTradingAccount(dto.getAccountId(), isEmployee, listingCurrencyCode);
        }
        Portfolio portfolio = null;
        if (direction == OrderDirection.SELL) {
            // Fund SELL: hartija je u portfoliju sa user_role=FUND i user_id=fund.id,
            // ne pod trenutnim supervizorom. Bez ovog branch-a BE bi rekao
            // "Nemate ovu hartiju u portfoliju" iako fond stvarno ima hartiju.
            Long lookupUserId = fund != null ? fund.getId() : userContext.userId();
            String lookupUserRole = fund != null ? UserRole.FUND : userContext.userRole();
            portfolio = portfolioRepository
                    .findByUserIdAndUserRoleAndListingIdForUpdate(lookupUserId, lookupUserRole, listing.getId())
                    .orElseThrow(() -> new InsufficientHoldingsException(
                            "Nemate ovu hartiju u portfoliju"));
            int available = portfolio.getAvailableQuantity();
            if (available < dto.getQuantity()) {
                throw new InsufficientHoldingsException(
                        "Nedovoljno raspolozivih hartija. Dostupno: " + available
                                + ", traženo: " + dto.getQuantity());
            }
        }

        BigDecimal exchangeRate = null;
        BigDecimal totalReservation = null;
        BigDecimal fxCommission = BigDecimal.ZERO;
        // N2 FIX: provizija CELOG naloga (listing valuta) — racuna se JEDNOM po §308/§322;
        // fill engine je posle pro-rata raspodeli po fill-u (Σ == ova vrednost). Null za zaposlene.
        BigDecimal orderCommissionInListing = null;
        // account je uvek non-null nakon if/else iznad — guard zadrzan iz citljivosti uklonjen
        if (direction == OrderDirection.BUY) {
            // R5 1878: jedinstven obracun novcane noge (deli se sa approveOrder).
            BuyReservation buyRes = computeBuyReservation(
                    approximatePrice, listingCurrencyCode, account, isEmployee, orderType);
            exchangeRate = buyRes.exchangeRate();
            fxCommission = buyRes.fxCommission();
            orderCommissionInListing = buyRes.orderCommissionInListing();
            totalReservation = buyRes.totalReservation();
        } else { // SELL
            // N2 FIX: i za SELL belezimo proviziju celog naloga (listing valuta) da bi fill
            // engine pro-rata raspodelio istu vrednost po fill-u umesto per-fill min(rate×fill, cap).
            if (!isEmployee) {
                orderCommissionInListing = calculateCommissionInListingCurrency(approximatePrice, orderType);
            }
            // Za SELL ne rezervisemo novac; ipak sacuvamo kurs listing→receiving account
            // kako bi fill engine znao u kojoj valuti da prihoduje pare na receiving racun.
            String accountCurrencyCode = account.currencyCode();
            exchangeRate = currencyConversionService.getRate(listingCurrencyCode, accountCurrencyCode);
        }

        // Step 6: Verify funds / holdings
        //   BUY: availableBalance >= totalReservation
        //   SELL: portfolio.availableQuantity >= dto.quantity (provereno iznad pri portfolio lookup-u)
        // BE-STK-05: za margin BUY preskocimo regular available balance check
        // (sredstva ce biti rezervisana na MarginAccount.reservedMargin u Step 10).
        // Marzni_Racuni.txt §137: margin orderi su odbijeni ako je racun blokiran.
        if (direction == OrderDirection.BUY && !dto.isMargin()) {
            if (account.availableBalance().compareTo(totalReservation) < 0) {
                throw new InsufficientFundsException(
                        "Nedovoljno raspolozivih sredstava na racunu " + account.accountNumber());
            }
        }
        if (dto.isMargin()) {
            // BE-STK-05: pre-check da klijent ima ACTIVE margin racun + da BP/IM/MM postoje.
            // Stvarna rezervacija ide u Step 10 kroz MarginOrderSettlementService.
            MarginAccount margin = marginAccountRepository
                    .findFirstByUserIdAndStatus(userContext.userId(), MarginAccountStatus.ACTIVE)
                    .orElseThrow(() -> new InsufficientFundsException(
                            "Klijent #" + userContext.userId() + " nema ACTIVE margin racun za margin trgovinu."));
            if (direction == OrderDirection.BUY) {
                // P2-5: approximatePrice je u valuti LISTINGA (npr. USD), ali margin
                // racun je UVEK RSD (Marzni_Racuni.txt §17), a OrderExecutionService
                // settle-uje u RSD (totalPriceInListing × midRate). Zato pre-check i
                // rezervacija MORAJU koristiti RSD-konvertovan iznos — inace se rezervise
                // USD-magnituda protiv RSD initialMargin (pogresan red velicine) pa pri
                // fill-u IM moze otici u minus. Konvertujemo po mid-rate-u (bez FX marze —
                // paritet sa settlement-om koji ne dodaje FX na margin nogu).
                BigDecimal approxInMarginCurrency = convertApproxToMarginCurrency(
                        approximatePrice, listingCurrencyCode, margin);
                // Pre-check: total × (1 - BP) <= availableInitialMargin (sve u RSD).
                BigDecimal bankPart = approxInMarginCurrency.multiply(margin.getBankParticipation())
                        .setScale(4, RoundingMode.HALF_UP);
                BigDecimal userPart = approxInMarginCurrency.subtract(bankPart)
                        .setScale(4, RoundingMode.HALF_UP);
                if (margin.getAvailableInitialMargin().compareTo(userPart) < 0) {
                    throw new InsufficientFundsException(
                            "Nedovoljno raspolozive marzine: traziti " + userPart
                                    + ", raspolozivo " + margin.getAvailableInitialMargin());
                }
            }
        }

        // Step 7: Determine status
        // §66 / R1-183 / R3-1543: agentski dnevni limit i usedLimit su izrazeni u
        // JEDNOJ valuti (dinarima). Pre poredjenja sa limitom, priblizna cena se
        // konvertuje u RSD po SREDNJEM kursu BEZ provizije (isti princip kao
        // Menjacnica, ali bez fee — spec eksplicitno). Tako agent na USD hartiji
        // ne probija RSD limit ~117× (status bi ostao APPROVED kad bi morao PENDING).
        BigDecimal approxInRsd = convertToRsdForLimit(approximatePrice, listingCurrencyCode);
        OrderStatus status = orderStatusService.determineStatus(userContext.userRole(), userContext.userId(), approxInRsd);
        String approvedBy = (status == OrderStatus.APPROVED) ? "No need for approval" : null;

        // Step 8: Compute afterHours
        boolean afterHours = computeAfterHours(listing);

        // Step 9: Build and save order
        Order order = OrderMapper.fromCreateDto(dto, listing);
        order.setUserId(userContext.userId());
        // S44 fix: eksplicitno setujemo userRole sa resolved userContext-a
        order.setUserRole(userContext.userRole());
        order.setPricePerUnit(pricePerUnit);
        order.setApproximatePrice(approximatePrice);
        order.setStatus(status);
        order.setApprovedBy(approvedBy);
        order.setAfterHours(afterHours);
        if (fund != null) {
            order.setFundId(fund.getId());
            // Za fond-ordere: userId je fundId i userRole je "FUND" (ne supervizora)
            order.setUserId(fund.getId());
            order.setUserRole(UserRole.FUND);
        }

        if (direction == OrderDirection.BUY) {
            order.setReservedAccountId(account.id());
            order.setReservedAmount(totalReservation);
            // P2-5: margin orderi se settle-uju u RSD (margin racun je uvek RSD).
            // exchangeRate mora biti listing→RSD da bi settle magnituda (totalPriceInListing
            // × exchangeRate) odgovarala RSD-konvertovanoj rezervaciji (Step 10). Bez ovoga
            // bi, ako regularni order.account nije RSD, settle racunao u valuti tog racuna a
            // rezervacija u RSD → mismatch reda velicine i IM moze otici u minus pri fill-u.
            if (dto.isMargin()) {
                order.setExchangeRate(currencyConversionService.getRate(listingCurrencyCode, "RSD"));
            } else {
                order.setExchangeRate(exchangeRate);
            }
            order.setFxCommission(fxCommission.compareTo(BigDecimal.ZERO) > 0 ? fxCommission : null);
            // Za agente pisemo bankin racun i na accountId da fill ima referencu
            if (isEmployee) {
                order.setAccountId(account.id());
            }
        } else { // SELL
            // Za SELL "reservedAccountId" drzi receiving account (kuda idu pare po fill-u).
            // reservedAmount ostaje null — nema novcane rezervacije.
            order.setReservedAccountId(account.id());
            order.setExchangeRate(exchangeRate);
            if (isEmployee) {
                order.setAccountId(account.id());
            }
        }

        // N2 FIX: provizija celog naloga (listing valuta) — fill engine je pro-rata
        // raspodeli po fill-u (Σ == orderCommission), ne premasujuci rezervacioni cap.
        order.setOrderCommission(orderCommissionInListing);

        if (status == OrderStatus.APPROVED) {
            order.setApprovedAt(LocalDateTime.now());
        }

        Order savedOrder = orderRepository.save(order);

        // Step 10: Rezervacija (sredstva za BUY, kolicina hartija za SELL) za APPROVED ordere
        // BE-STK-05: za margin BUY rezervacija ide na MarginAccount.reservedMargin
        // (userPart = total - bankPart), ne na banka-core funds reserve.
        if (status == OrderStatus.APPROVED) {
            if (direction == OrderDirection.BUY) {
                if (order.isMargin()) {
                    // P2-5: rezervisi RSD-ekvivalent approximatePrice-a (listing→RSD po
                    // mid-rate-u), NE sirovi USD broj. Margin racun je uvek RSD i settle
                    // ide u RSD; rezervisanje USD-magnitude bi pod-drzalo reservedMargin.
                    MarginAccount marginForReserve = marginAccountRepository
                            .findFirstByUserIdAndStatus(userContext.userId(), MarginAccountStatus.ACTIVE)
                            .orElseThrow(() -> new InsufficientFundsException(
                                    "Klijent #" + userContext.userId()
                                            + " nema ACTIVE margin racun za margin trgovinu."));
                    BigDecimal approxInMarginCurrency = convertApproxToMarginCurrency(
                            savedOrder.getApproximatePrice(), listingCurrencyCode, marginForReserve);
                    boolean reserved = marginOrderSettlementService.reserveForMarginBuy(
                            savedOrder, approxInMarginCurrency);
                    if (!reserved) {
                        throw new InsufficientFundsException(
                                "Nedovoljno raspolozive marzine za margin BUY order #" + savedOrder.getId());
                    }
                } else {
                    fundReservationService.reserveForBuy(savedOrder);
                }
            } else { // SELL — portfolio je uvek non-null nakon SELL grane iznad
                fundReservationService.reserveForSell(savedOrder, portfolio);
            }
        }

        // Step 11: Update agent usedLimit if APPROVED.
        // R2-1355: dnevni limit je kontrola KUPOVINE (§289-291) — SELL nalozi NE
        // troše usedLimit. Inkrement samo za BUY.
        // §66 / R1-183: usedLimit se drži iskljucivo u RSD → inkrementiramo
        // RSD-konvertovanu pribliznu cenu (mid-rate, bez provizije), NE
        // totalReservation (koji je u valuti racuna + ukljucuje FX proviziju).
        // BE-ORD-07: atomic increment kroz optimistic locking retry — bez
        // ovog wrapper-a paralelne BUY ordere istog agenta mogu lost-update
        // istisnuti delta.
        if (status == OrderStatus.APPROVED && isEmployee && direction == OrderDirection.BUY) {
            incUsedLimit(userContext.userId(), approxInRsd);
        }

        // Step 12: Execution handled by OrderScheduler cron job

        if (savedOrder.getStatus() == OrderStatus.PENDING) {
            try {
                notificationService.notify(
                        savedOrder.getUserId(),
                        savedOrder.getUserRole(),
                        NotificationType.ORDER_PENDING,
                        "Nalog čeka odobrenje",
                        "Vaš nalog za " + savedOrder.getListing().getTicker() + " je kreiran i čeka odobrenje supervizora.",
                        "ORDER",
                        savedOrder.getId()
                );
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                        .warn("Failed to send order pending notification: {}", e.getMessage());
            }
        }

        return toDtoWithUserName(savedOrder);
    }

    /**
     * Resolve-uje ISO kod valute za dati listing. Delegira na
     * {@link ListingCurrencyResolver} — jedinstven util koriscen u vise
     * servisa (tax, OTC).
     */
    private String resolveListingCurrency(Listing listing) {
        return ListingCurrencyResolver.resolve(listing);
    }

    /**
     * P2-5: konvertuje {@code approximatePrice} iz valute listinga u valutu margin
     * racuna (uvek RSD po Marzni_Racuni.txt §17) za margin pre-check + rezervaciju.
     *
     * <p>Koristi mid-rate ({@link CurrencyConversionService#getRate}) bez FX marze —
     * paritet sa {@code OrderExecutionService}/{@code SingleOrderExecutor} settle
     * putanjom koja racuna {@code totalPriceInAccount = totalPriceInListing × midRate}
     * i NE dodaje FX proviziju na margin nogu. Za RSD listinge (ista valuta) rate=1
     * pa je putanja nepromenjena.
     */
    private BigDecimal convertApproxToMarginCurrency(BigDecimal approxInListingCurrency,
                                                     String listingCurrencyCode,
                                                     MarginAccount margin) {
        String marginCurrency = margin.getCurrency() != null ? margin.getCurrency() : "RSD";
        return currencyConversionService.convert(
                approxInListingCurrency, listingCurrencyCode, marginCurrency);
    }

    /**
     * §66 / R1-183 / R3-1543: konvertuje pribliznu cenu naloga iz valute listinga
     * u RSD radi poredjenja sa agentskim dnevnim limitom i azuriranja
     * {@code usedLimit}-a. Spec: "Limit i usedLimit su izrazeni u jednoj valuti
     * (dinarima). Kada aktuar trguje u drugoj valuti, koristi se isti princip
     * konverzije kao u Menjacnici, samo BEZ uzimanja provizije" — zato mid-rate
     * ({@link CurrencyConversionService#convert}), ne {@code convertForPurchase}.
     *
     * <p>Za RSD listing (ista valuta) rate=1 → identitet (putanja nepromenjena).
     * Defanzivno: ako kurs nije dostupan (UnsupportedCurrency / banka-core pad),
     * pada na sirovu pribliznu cenu da kreiranje naloga ne pukne (gore je da agent
     * uopste ne moze da trguje nego da limit-check bude konzervativan).
     */
    private BigDecimal convertToRsdForLimit(BigDecimal approxInListingCurrency, String listingCurrencyCode) {
        if (approxInListingCurrency == null) {
            return BigDecimal.ZERO;
        }
        if (listingCurrencyCode == null || "RSD".equals(listingCurrencyCode)) {
            return approxInListingCurrency;
        }
        try {
            return currencyConversionService.convert(approxInListingCurrency, listingCurrencyCode, "RSD");
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                    .warn("FX kurs {}->RSD nedostupan za limit-check, koristim sirovi iznos: {}",
                            listingCurrencyCode, e.getMessage());
            return approxInListingCurrency;
        }
    }

    /**
     * Zajednicka logika za pronalazenje trading racuna za BUY i SELL orderi:
     *  - ako je {@code accountId} eksplicitno prosledjen, load-uje se preko banka-core;
     *  - inace ako je korisnik zaposleni, uzima se bankin racun u valuti hartije;
     *  - inace je to greska (klijent mora navesti racun).
     *
     * NAPOMENA (2c): monolitni {@code findForUpdateById} pessimistic lock vise ne
     * postoji u trading-service-u — racun zivi u banka-core. Provera balansa pre
     * rezervacije je samo metadata; stvarna garancija je banka-core {@code reserve}
     * koji vraca 409.
     */
    private InternalAccountDto resolveTradingAccount(Long accountId, boolean isEmployee, String listingCurrencyCode) {
        if (accountId != null) {
            return getAccountOrThrow(accountId, "Racun ne postoji: " + accountId);
        }
        if (isEmployee) {
            return bankTradingAccountResolver.resolve(listingCurrencyCode);
        }
        throw new EntityNotFoundException("Racun ne postoji: null");
    }

    /**
     * Cita racun preko banka-core internog seam-a; banka-core 404 se prevodi u
     * {@link EntityNotFoundException} (verno monolitovom {@code orElseThrow}).
     */
    private InternalAccountDto getAccountOrThrow(Long accountId, String notFoundMessage) {
        try {
            return bankaCoreClient.getAccount(accountId);
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 404) {
                throw new EntityNotFoundException(notFoundMessage);
            }
            throw ex;
        }
    }

    /**
     * Racuna proviziju u valuti listinga (gde USD cap ima smisla).
     * Spec: Market min(14% * cena, $7), Limit min(24% * cena, $12).
     * Za non-USD listinge, cap od $7/$12 se tretira kao literal iznos
     * u listing valuti — pragmaticna aproksimacija jer se vecina listinga
     * denominuje u USD.
     */
    private BigDecimal calculateCommissionInListingCurrency(BigDecimal approxInListingCurrency, OrderType orderType) {
        // R1-718: stope/cap-ovi iz jedinstvenog OrderCommissionPolicy (deljeno sa SingleOrderExecutor).
        return switch (orderType) {
            case MARKET, STOP -> approxInListingCurrency.multiply(OrderCommissionPolicy.MARKET_COMMISSION_RATE)
                    .min(OrderCommissionPolicy.MARKET_COMMISSION_CAP)
                    .setScale(4, RoundingMode.HALF_UP);
            case LIMIT, STOP_LIMIT -> approxInListingCurrency.multiply(OrderCommissionPolicy.LIMIT_COMMISSION_RATE)
                    .min(OrderCommissionPolicy.LIMIT_COMMISSION_CAP)
                    .setScale(4, RoundingMode.HALF_UP);
        };
    }

    /**
     * R5 1878 — rezultat obracuna BUY rezervacije (FX-konvertovana cena +
     * provizija, u valuti racuna), zajednicki za {@code createOrder} i
     * {@code approveOrder}.
     *
     * @param totalReservation         iznos za rezervaciju u valuti racuna (cena + provizija)
     * @param exchangeRate             mid-rate listing → valuta racuna (za settlement)
     * @param fxCommission             FX menjacnicka provizija u valuti racuna (0 za zaposlene/iste valute)
     * @param orderCommissionInListing provizija celog naloga u listing valuti (N2; null za zaposlene)
     */
    private record BuyReservation(BigDecimal totalReservation, BigDecimal exchangeRate,
                                  BigDecimal fxCommission, BigDecimal orderCommissionInListing) {}

    /**
     * R5 1878 — JEDINSTVEN obracun novcane noge BUY naloga (cena + provizija u
     * valuti racuna), deljen izmedju {@code createOrder} (pri kreiranju) i
     * {@code approveOrder} (pri odobravanju). Pre ekstrakcije su dve putanje
     * imale identican ali DUPLIRAN blok; svaka buduca izmena jedne (npr. P2-5
     * RSD-konverzija / FX cap) mogla je da promakne drugoj → margin/regular
     * order kroz {@code approveOrder} bi rezervisao drugacije od {@code createOrder}.
     * Sada je matematika na jednom mestu pa NE moze da divergira.
     *
     * <p>Semantika (nepromenjena): cena se konvertuje u valutu racuna
     * ({@code convertForPurchase} sa FX marzom ako {@code chargeFx}), provizija
     * celog naloga (§308/§322) se racuna u listing valuti pa konvertuje u valutu
     * racuna (cap $7/$12 ostaje ispravan), FX provizije obe noge se sabiraju.
     * Zaposleni ne placaju proviziju ni FX marzu (0).</p>
     */
    private BuyReservation computeBuyReservation(BigDecimal approxInListing, String listingCurrencyCode,
                                                 InternalAccountDto account, boolean isEmployee,
                                                 OrderType orderType) {
        String accountCurrencyCode = account.currencyCode();
        boolean chargeFx = !isEmployee && !listingCurrencyCode.equals(accountCurrencyCode);

        CurrencyConversionService.ConversionResult priceConv = currencyConversionService
                .convertForPurchase(approxInListing, listingCurrencyCode, accountCurrencyCode, chargeFx);
        BigDecimal exchangeRate = priceConv.midRate();
        BigDecimal approxInAccountCurrency = priceConv.amount();
        BigDecimal fxCommission = priceConv.commission();

        BigDecimal orderCommissionInListing = null;
        BigDecimal commissionInAccountCurrency;
        if (isEmployee) {
            commissionInAccountCurrency = BigDecimal.ZERO;
        } else {
            // Provizija u listing (USD) valuti — cap $7/$12 ostaje ispravan; pa konverzija u valutu racuna.
            orderCommissionInListing = calculateCommissionInListingCurrency(approxInListing, orderType);
            CurrencyConversionService.ConversionResult commConv = currencyConversionService
                    .convertForPurchase(orderCommissionInListing, listingCurrencyCode, accountCurrencyCode, chargeFx);
            commissionInAccountCurrency = commConv.amount();
            fxCommission = fxCommission.add(commConv.commission());
        }
        BigDecimal totalReservation = approxInAccountCurrency.add(commissionInAccountCurrency)
                .setScale(4, RoundingMode.HALF_UP);
        return new BuyReservation(totalReservation, exchangeRate, fxCommission, orderCommissionInListing);
    }

    @Override
    @Transactional
    public OrderDto approveOrder(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found" + orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            // R1 410: state-conflict (ne authz) -> 409.
            throw new rs.raf.trading.order.exception.OrderStateConflictException(
                    "Only PENDING orders can be approved");
        }

        String supervisorName = getSupervisorName();

        Listing listing = order.getListing();
        if (listing.getSettlementDate() != null &&
                listing.getSettlementDate().isBefore(java.time.LocalDate.now())) {
            order.setStatus(OrderStatus.DECLINED);
            order.setApprovedBy(supervisorName);
            order.setLastModification(LocalDateTime.now());
            Order saved = orderRepository.save(order);
            return toDtoWithUserName(saved);
        }

        // Phase 5.1: Rezervacija sredstava / hartija u trenutku odobravanja.
        // Cena se mogla promeniti izmedju PENDING i sada — koristimo
        // order.approximatePrice kao polaznu tacku (vec izracunato pri createOrder).
        boolean isEmployee = UserRole.isEmployee(order.getUserRole());
        String listingCurrencyCode = resolveListingCurrency(listing);
        BigDecimal totalReservation = null;

        if (order.getDirection() == OrderDirection.BUY) {
            InternalAccountDto account;
            Long accountId = order.getReservedAccountId() != null
                    ? order.getReservedAccountId()
                    : order.getAccountId();
            if (accountId != null) {
                account = getAccountOrThrow(accountId, "Racun ne postoji: " + accountId);
            } else if (isEmployee) {
                account = bankTradingAccountResolver.resolve(listingCurrencyCode);
            } else {
                throw new EntityNotFoundException("Order nema povezan racun za rezervaciju");
            }

            BigDecimal approxInListing = order.getApproximatePrice() != null
                    ? order.getApproximatePrice()
                    : BigDecimal.ZERO;

            // R5 1878: jedinstven obracun novcane noge (isti helper kao createOrder)
            // → createOrder i approveOrder NE mogu da divergiraju.
            BuyReservation buyRes = computeBuyReservation(
                    approxInListing, listingCurrencyCode, account, isEmployee, order.getOrderType());
            totalReservation = buyRes.totalReservation();
            // N2 FIX: provizija celog naloga (listing valuta) za pro-rata po fill-u (null za zaposlene).
            order.setOrderCommission(buyRes.orderCommissionInListing());
            BigDecimal fxCommission = buyRes.fxCommission();

            if (account.availableBalance().compareTo(totalReservation) < 0) {
                throw new InsufficientFundsException(
                        "Nedovoljno sredstava u trenutku odobravanja na racunu " + account.accountNumber());
            }

            order.setReservedAccountId(account.id());
            order.setReservedAmount(totalReservation);
            // P2-5 parity: margin orderi se settle-uju u RSD (margin racun je uvek RSD),
            // pa exchangeRate mora biti listing→RSD (isto kao createOrder), ne listing→racun.
            if (order.isMargin()) {
                order.setExchangeRate(currencyConversionService.getRate(listingCurrencyCode, "RSD"));
            } else {
                order.setExchangeRate(buyRes.exchangeRate());
            }
            order.setFxCommission(fxCommission.compareTo(BigDecimal.ZERO) > 0 ? fxCommission : null);
            if (isEmployee) {
                order.setAccountId(account.id());
            }
            // P2-5 parity: margin BUY rezervise RSD-ekvivalent na MarginAccount.reservedMargin
            // (ne banka-core funds reserve); regularni BUY ide na banka-core reserve.
            if (order.isMargin()) {
                MarginAccount marginForReserve = marginAccountRepository
                        .findFirstByUserIdAndStatus(order.getUserId(), MarginAccountStatus.ACTIVE)
                        .orElseThrow(() -> new InsufficientFundsException(
                                "Klijent #" + order.getUserId()
                                        + " nema ACTIVE margin racun za margin trgovinu."));
                BigDecimal approxInMarginCurrency = convertApproxToMarginCurrency(
                        approxInListing, listingCurrencyCode, marginForReserve);
                boolean reserved = marginOrderSettlementService.reserveForMarginBuy(order, approxInMarginCurrency);
                if (!reserved) {
                    throw new InsufficientFundsException(
                            "Nedovoljno raspolozive marzine za margin BUY order #" + order.getId());
                }
            } else {
                fundReservationService.reserveForBuy(order);
            }
        } else { // SELL

            Portfolio portfolio = portfolioRepository
                    .findByUserIdAndUserRoleAndListingIdForUpdate(order.getUserId(), order.getUserRole(), listing.getId())
                    .orElseThrow(() -> new InsufficientHoldingsException(
                            "Nemate ovu hartiju u portfoliju"));
            if (portfolio.getAvailableQuantity() < order.getQuantity()) {
                throw new InsufficientHoldingsException(
                        "Nedovoljno raspolozivih hartija. Dostupno: "
                                + portfolio.getAvailableQuantity() + ", traženo: " + order.getQuantity());
            }
            fundReservationService.reserveForSell(order, portfolio);
        }

        order.setStatus(OrderStatus.APPROVED);
        order.setApprovedBy(supervisorName);
        order.setApprovedAt(LocalDateTime.now());
        order.setLastModification(LocalDateTime.now());

        Order saved = orderRepository.save(order);

        // Update agent usedLimit when supervisor approves.
        // R2-1355: dnevni limit je kontrola KUPOVINE (§289-291) — SELL ne troši
        // usedLimit, pa inkrementiramo samo na BUY odobravanje.
        // §66 / R1-183: usedLimit se drži iskljucivo u RSD → koristimo
        // RSD-konvertovanu pribliznu cenu (mid-rate, bez provizije), ne
        // totalReservation (valuta racuna + FX provizija).
        // BE-ORD-07: atomic increment kroz optimistic locking retry.
        if (isEmployee && order.getDirection() == OrderDirection.BUY) {
            final BigDecimal limitDelta = convertToRsdForLimit(
                    order.getApproximatePrice() != null ? order.getApproximatePrice() : BigDecimal.ZERO,
                    listingCurrencyCode);
            incUsedLimit(order.getUserId(), limitDelta);
        }

        try {
            notificationService.notify(
                    saved.getUserId(),
                    saved.getUserRole(),
                    NotificationType.ORDER_APPROVED,
                    "Nalog odobren",
                    "Vaš nalog za " + saved.getListing().getTicker() + " je odobren i biće izvršen.",
                    "ORDER",
                    saved.getId()
            );
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                    .warn("Failed to send order approved notification: {}", e.getMessage());
        }

        // B7 audit hook (port iz main PR #86, Stasa Dragovic)
        // R4 1780 + R1 394 (P2-audit-coverage-1): afterCommit + best-effort. Audit se
        // pise SAMO ako approveOrder tx commit-uje (nema phantom audit na rollback), i
        // pad audit-a NE obara odobravanje (afterCommit je posle commit-a).
        auditLogService.recordAfterCommit(
                resolveCurrentUser().userId(), "EMPLOYEE",
                AuditActionType.ORDER_APPROVED,
                "Order approved: " + saved.getId(),
                "ORDER", saved.getId());

        return toDtoWithUserName(saved);
    }

    @Override
    @Transactional
    public OrderDto declineOrder(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found " + orderId));

        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.APPROVED) {
            // R1 410: state-conflict (ne authz) -> 409.
            throw new rs.raf.trading.order.exception.OrderStateConflictException(
                    "Only PENDING or APPROVED orders can be declined/cancelled");
        }

        String supervisorName = getSupervisorName();

        // Phase 5.2: Ako je order bio APPROVED, treba osloboditi rezervaciju
        // (novcanu za BUY, kolicinu hartija za SELL) + rollback agent usedLimit.
        boolean hadReservation = order.getStatus() == OrderStatus.APPROVED;
        if (hadReservation && !order.isReservationReleased()) {
            if (order.getDirection() == OrderDirection.BUY) {
                fundReservationService.releaseForBuy(order);
            } else { // SELL
                Portfolio portfolio = portfolioRepository
                        .findByUserIdAndUserRoleAndListingIdForUpdate(order.getUserId(), order.getUserRole(), order.getListing().getId())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Portfolio ne postoji za order " + order.getId()));
                fundReservationService.releaseForSell(order, portfolio);
            }

            // BE-ORD-07: atomic rollback kroz optimistic locking retry.
            // R2-1355: SELL nikad nije trošio usedLimit (BUY-only inkrement), pa se
            // rollback radi samo za BUY. §66 / R1-183: usedLimit je u RSD → rollback
            // mora biti ista RSD vrednost koja je dodata pri kreiranju/odobravanju
            // (convertToRsdForLimit), ne reservedAmount (valuta racuna + FX provizija).
            if (UserRole.isEmployee(order.getUserRole())
                    && order.getDirection() == OrderDirection.BUY
                    && order.getApproximatePrice() != null) {
                final BigDecimal rollbackAmount = convertToRsdForLimit(
                        order.getApproximatePrice(), resolveListingCurrency(order.getListing()));
                decUsedLimit(order.getUserId(), rollbackAmount);
            }
        }

        order.setStatus(OrderStatus.DECLINED);
        order.setApprovedBy(supervisorName);
        order.setLastModification(LocalDateTime.now());

        Order saved = orderRepository.save(order);

        try {
            notificationService.notify(
                    saved.getUserId(),
                    saved.getUserRole(),
                    NotificationType.ORDER_DECLINED,
                    "Nalog odbijen",
                    "Vaš nalog za " + (saved.getListing() != null ? saved.getListing().getTicker() : "") + " je odbijen.",
                    "ORDER",
                    saved.getId()
            );
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                    .warn("Failed to send order declined notification: {}", e.getMessage());
        }

        // B7 audit hook (port iz main PR #86, Stasa Dragovic)
        // R4 1780 + R1 394 (P2-audit-coverage-1): afterCommit + best-effort.
        auditLogService.recordAfterCommit(
                resolveCurrentUser().userId(), "EMPLOYEE",
                AuditActionType.ORDER_DECLINED,
                "Order declined: " + saved.getId(),
                "ORDER", saved.getId());

        return toDtoWithUserName(saved);
    }

    @Override
    @Transactional
    public OrderDto cancelOrder(Long orderId, Integer quantityToCancel) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found " + orderId));

        int remaining = order.getRemainingPortions() != null
                ? order.getRemainingPortions()
                : (order.getQuantity() != null ? order.getQuantity() : 0);

        // Full cancel delegates to declineOrder which handles both PENDING
        // and APPROVED states (releases reservation + rollbackuje usedLimit).
        boolean fullCancel = quantityToCancel == null
                || quantityToCancel <= 0
                || quantityToCancel >= remaining
                || order.getStatus() == OrderStatus.PENDING
                || order.isDone();
        if (fullCancel) {
            return declineOrder(orderId);
        }

        if (order.getStatus() != OrderStatus.APPROVED) {
            throw new IllegalStateException(
                    "Parcijalni cancel je dozvoljen samo za APPROVED ordere.");
        }

        int cancelQty = quantityToCancel;
        int newRemaining = remaining - cancelQty;
        Integer originalQty = order.getQuantity();

        // Oslobodi pro-ratu rezervisanog sredstva / hartija.
        //
        // NAPOMENA (2c rewiring): monolit je u parcijalnom cancel-u direktno
        // umanjivao Account.reservedAmount za pro-rata iznos. banka-core
        // /internal/funds/.../release oslobadja CELU preostalu rezervaciju
        // (nema parcijalnog release-a). Verni ekvivalent koji postuje seam:
        //   1. oslobodi celu trenutnu rezervaciju (releaseForBuy),
        //   2. odmah re-rezervisi pro-rata jos-zeljeni iznos
        //      (reservedAmount * newRemaining / originalQty).
        // Neto efekat = oslobadjanje tacno cancelQty/originalQty dela, isto kao
        // monolitno umanjenje — klijentu se raspoloziva sredstva odmah povecaju.
        // Per-fill commit model i dalje nesmetano radi nad novom rezervacijom.
        // Originalna rezervacija PRE re-rezervacije — agent usedLimit rollback
        // (nize) mora citati ovu vrednost, ne prepisani (umanjeni) order.reservedAmount.
        // Verno monolitu, koji u cancelOrder uopste ne prepisuje order.reservedAmount
        // pa njegov rollback uvek koristi originalnu rezervaciju.
        BigDecimal originalReservedAmount = order.getReservedAmount();
        if (order.getDirection() == OrderDirection.BUY && order.isMargin()) {
            // P1-dividends-order-1 (163): margin BUY parcijalni cancel NE sme da dira
            // banka-core funds reserve — margin rezervacija je u MarginAccount.reservedMargin.
            // Oslobodi pro-rata MARGIN rezervaciju za otkazani deo (approxPrice × cancelQty).
            // Bez ove grane bi kod (ispod) zvao bankaCoreClient.releaseFunds na nepostojecu
            // banka-core rezervaciju (margin order je nema) i potencijalno duplo oslobadjao /
            // pucao. reservedMargin ostane visi nego sto treba (zakljucana margina).
            if (order.getApproximatePrice() != null && originalQty != null && originalQty > 0) {
                BigDecimal cancelTotal = order.getApproximatePrice()
                        .multiply(new BigDecimal(cancelQty))
                        .setScale(4, RoundingMode.HALF_UP);
                if (cancelTotal.signum() > 0) {
                    marginOrderSettlementService.releaseMarginBuyReservation(order, cancelTotal);
                }
            }
        } else if (order.getDirection() == OrderDirection.BUY
                && order.getReservedAccountId() != null
                && order.getReservedAmount() != null
                && originalQty != null && originalQty > 0) {
            BigDecimal fraction = new BigDecimal(newRemaining)
                    .divide(new BigDecimal(originalQty), 10, RoundingMode.HALF_UP);
            BigDecimal newReservation = order.getReservedAmount().multiply(fraction)
                    .setScale(4, RoundingMode.HALF_UP);

            // BE-ORD-04 race window mitigation: cuvamo trag originalne rezervacije
            // (ID + iznos) PRE release-a, da bismo mogli da je restauriramo ako
            // pro-rata re-reserve padne sa 409 zato sto je drugi paralelni order u
            // medjuvremenu uzeo oslobodjena sredstva. Permanent fix (banka-core
            // /internal/funds/release-partial) jos ne postoji; ovo je best-effort
            // kompenzacija unutar trenutnog seam-a.
            String originalReservationId = order.getBankaCoreReservationId();
            BigDecimal originalAmount = order.getReservedAmount();
            boolean wasReleasedBeforeCancel = order.isReservationReleased();

            // 1. oslobodi celu preostalu rezervaciju
            if (!order.isReservationReleased() && order.getBankaCoreReservationId() != null) {
                bankaCoreClient.releaseFunds(
                        order.getBankaCoreReservationId(),
                        "order-" + order.getId() + "-cancel-release",
                        new ReleaseFundsRequest(
                                "Parcijalni cancel ordera " + order.getId()
                                        + " — oslobadjanje pre re-rezervacije"));
            }

            // 2. re-rezervisi pro-rata jos-zeljeni iznos
            if (newReservation.signum() > 0) {
                String currencyCode = bankaCoreClient.getAccount(order.getReservedAccountId())
                        .currencyCode();
                try {
                    ReserveFundsResponse response = bankaCoreClient.reserveFunds(
                            "order-" + order.getId() + "-cancel-rereserve",
                            new ReserveFundsRequest(order.getReservedAccountId(),
                                    newReservation, currencyCode));
                    order.setBankaCoreReservationId(response.reservationId());
                    order.setReservedAmount(newReservation);
                    order.setReservationReleased(false);
                } catch (BankaCoreClientException ex) {
                    if (ex.getHttpStatus() == 409) {
                        // BE-ORD-04: race window — drugi paralelni order je uzeo
                        // oslobodjena sredstva. Kompenzacija: pokusaj da restauriramo
                        // originalnu (vecu) rezervaciju, da klijent ostane sa istim
                        // pre-cancel stanjem. "cancel-restore" je nov idempotency key
                        // (originalni release key vec konzumiran, "cancel-rereserve" 409).
                        if (!wasReleasedBeforeCancel && originalAmount != null
                                && originalAmount.signum() > 0) {
                            try {
                                ReserveFundsResponse restore = bankaCoreClient.reserveFunds(
                                        "order-" + order.getId() + "-cancel-restore",
                                        new ReserveFundsRequest(order.getReservedAccountId(),
                                                originalAmount, currencyCode));
                                order.setBankaCoreReservationId(restore.reservationId());
                                order.setReservedAmount(originalAmount);
                                order.setReservationReleased(false);
                                orderRepository.save(order);
                                org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                                        .warn("BE-ORD-04 race: order {} cancel pro-rata re-reserve failed (409), "
                                                + "original reservation restored (id={}, amount={})",
                                                order.getId(), restore.reservationId(), originalAmount);
                            } catch (BankaCoreClientException restoreEx) {
                                order.setReservationReleased(true);
                                orderRepository.save(order);
                                org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                                        .error("BE-ORD-04 race + restore failure: order {} ostao bez rezervacije "
                                                + "(originalReservationId={}, originalAmount={}, restoreErr={})",
                                                order.getId(), originalReservationId, originalAmount,
                                                restoreEx.getMessage(), restoreEx);
                            }
                        }
                        throw new InsufficientFundsException(
                                "Nedovoljno sredstava za re-rezervaciju posle parcijalnog cancel-a");
                    }
                    throw ex;
                }
            } else {
                order.setReservedAmount(BigDecimal.ZERO);
                order.setReservationReleased(true);
            }
        } else if (order.getDirection() == OrderDirection.SELL) {
            Portfolio portfolio = portfolioRepository
                    .findByUserIdAndUserRoleAndListingIdForUpdate(order.getUserId(), order.getUserRole(), order.getListing().getId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Portfolio ne postoji za order " + order.getId()));
            int releaseQty = Math.min(cancelQty, portfolio.getReservedQuantity());
            portfolio.setReservedQuantity(portfolio.getReservedQuantity() - releaseQty);
            portfolioRepository.save(portfolio);
        }

        // Rollback proporcionalnog usedLimit-a za AGENT-a.
        // VAZNO: rollback se racuna iz originalReservedAmount (rezervacija PRE
        // parcijalnog cancel-a), ne iz order.getReservedAmount() koji je gore vec
        // prepisan na umanjenu re-rezervaciju — inace bi usedLimit bio premalo
        // vracen agentu (npr. order qty 10, original 1000, cancel 4 → treba
        // 1000*4/10 = 400, a sa prepisanom vrednoscu bi bilo samo 600*4/10 = 240).
        // BE-ORD-07: atomic partial rollback kroz optimistic locking retry.
        if (order.getDirection() == OrderDirection.BUY
                && UserRole.isEmployee(order.getUserRole())
                && originalReservedAmount != null
                && originalQty != null && originalQty > 0) {
            BigDecimal fraction = new BigDecimal(cancelQty)
                    .divide(new BigDecimal(originalQty), 10, RoundingMode.HALF_UP);
            BigDecimal rollback = originalReservedAmount.multiply(fraction)
                    .setScale(4, RoundingMode.HALF_UP);
            decUsedLimit(order.getUserId(), rollback);
        }

        order.setRemainingPortions(newRemaining);
        order.setLastModification(LocalDateTime.now());
        order.setApprovedBy(getSupervisorName()); // audit trail ko je skratio order
        Order saved = orderRepository.save(order);
        return toDtoWithUserName(saved);
    }

    @Override
    public Page<OrderDto> getAllOrders(String status, int page, int size, boolean excludeFund) {
        // P2-perf-nplus1-1 (R5 1896): clamp size pre PageRequest-a (DoS guard).
        PageRequest pageable = PageRequest.of(page, clampPageSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));

        OrderStatus orderStatus = null;
        boolean isAllStatus = status == null || status.isBlank() || status.equalsIgnoreCase("ALL");
        if (!isAllStatus) {
            try {
                orderStatus = OrderStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid status: " + status +
                        ". Valid status: ALL, PENDING, APPROVED, DECLINED, DONE");
            }
        }

        // BE-ORD-03: kada je excludeFund=true (default), FUND ordere ne prikazujemo u
        // supervizorskom approval view-u (fund-management ordere uvek pokrece manager
        // fonda, ne prolaze kroz opsti approval queue). Stari ponasanje (sve ordere
        // ukljucujuci FUND) ostaje dostupno preko excludeFund=false (fund admin view).
        if (!excludeFund) {
            if (isAllStatus) {
                return orderRepository.findAll(pageable).map(this::toDtoWithUserName);
            }
            return orderRepository.findByStatus(orderStatus, pageable).map(this::toDtoWithUserName);
        }

        Specification<Order> spec = Specification
                .where(OrderSpecification.excludeFundOrders(true))
                .and(OrderSpecification.hasStatus(orderStatus));
        return orderRepository.findAll(spec, pageable).map(this::toDtoWithUserName);
    }

    @Override
    public Page<OrderDto> getMyOrders(int page, int size, String status, LocalDate dateFrom, LocalDate dateTo, String listingType) {
        UserContext userContext = resolveCurrentUser();
        // P2-perf-nplus1-1 (R5 1896): clamp size pre PageRequest-a (DoS guard).
        PageRequest pageable = PageRequest.of(page, clampPageSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));

        OrderStatus parsedStatus = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                parsedStatus = OrderStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        ListingType parsedListingType = null;
        if (listingType != null && !listingType.isBlank()) {
            try {
                parsedListingType = ListingType.valueOf(listingType.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        Specification<Order> spec = Specification
                .where(OrderSpecification.hasUserId(userContext.userId()))
                .and(OrderSpecification.hasStatus(parsedStatus))
                .and(OrderSpecification.createdAfter(dateFrom))
                .and(OrderSpecification.createdBefore(dateTo))
                .and(OrderSpecification.hasListingType(parsedListingType));

        return orderRepository.findAll(spec, pageable).map(this::toDtoWithUserName);
    }

    @Override
    public OrderDto getOrderById(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order with ID " + orderId + " not found"));

        boolean isSupervisor = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_EMPLOYEE"));

        if (isSupervisor) {
            return toDtoWithUserName(order);
        }

        Long currentUserId = resolveCurrentUser().userId();
        if (!order.getUserId().equals(currentUserId)) {
            throw new IllegalStateException("You dont have access to this account");
        }

        return toDtoWithUserName(order);
    }

    /**
     * Razresava identitet trenutnog korisnika preko {@link TradingUserResolver}
     * ({@code /internal/users/by-email/**}). Zamena za monolitnu
     * {@code ClientRepository}/{@code EmployeeRepository} pretragu.
     */
    private UserContext resolveCurrentUser() {
        return tradingUserResolver.resolveCurrent();
    }

    /**
     * Provera autorizacije za POST /orders (spec Celina 3 §6 / Celina 4 §137-141).
     * <ul>
     *   <li>Klijent mora imati {@code TRADE_STOCKS} permisiju (autoritet).
     *       AuthContext na FE-u mapira {@code Client.canTradeStocks=true} u
     *       {@code TRADE_STOCKS} autoritet. Bez te permisije → 403.</li>
     *   <li>Zaposleni mora imati bar jedan od: {@code SUPERVISOR}, {@code ADMIN},
     *       {@code AGENT} (svi su validni za trgovinu). Zaposleni bez tih
     *       autoriteta (npr. obican operater) → 403.</li>
     * </ul>
     * Paritet sa {@code OtcService.ensureOtcAccess} obrascem, ali za order endpoint.
     */
    private void ensureTradingAccess(UserContext user) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new AccessDeniedException("Niste autentifikovani.");
        }
        String role = user.userRole();
        if (UserRole.isClient(role)) {
            boolean hasTradeStocks = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch("TRADE_STOCKS"::equals);
            if (!hasTradeStocks) {
                throw new AccessDeniedException(
                        "Nemate dozvolu za trgovinu hartijama (TRADE_STOCKS permisija nije dodeljena).");
            }
            return;
        }
        if (UserRole.isEmployee(role)) {
            boolean canTrade = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> UserRole.SUPERVISOR.equals(a)
                            || UserRole.ADMIN.equals(a)
                            || UserRole.AGENT.equals(a)
                            || UserRole.ROLE_ADMIN.equals(a));
            if (!canTrade) {
                throw new AccessDeniedException(
                        "Zaposleni mora imati SUPERVISOR, ADMIN ili AGENT autoritet za trgovinu.");
            }
            return;
        }
        throw new AccessDeniedException("Nepoznata uloga ne moze da trguje hartijama.");
    }

    private boolean computeAfterHours(Listing listing) {
        String exchange = listing.getExchangeAcronym();
        if (exchange == null) return false;

        try {
            // R1-190 (§404): spori fill se primenjuje kad je berza ZATVORENA ILI
            // u after-hours prozoru — ne samo u after-hours-u. Pre fix-a je
            // potpuno zatvorena berza dobijala afterHours=false → fill normalnom
            // brzinom (bug).
            return exchangeManagementService.isClosedOrAfterHours(exchange);
        } catch (Exception e) {
            // Exchange not found or unknown — treat as not after-hours
            return false;
        }
    }

    private String getSupervisorName() {
        UserContext userContext = resolveCurrentUser();
        return tradingUserResolver.resolveName(userContext.userId(), userContext.userRole());
    }

    private OrderDto toDtoWithUserName(Order order) {
        String userName = tradingUserResolver.resolveName(order.getUserId(), order.getUserRole());
        return OrderMapper.toDto(order, userName);
    }

    /**
     * BE-ORD-07: izvrsava mutaciju nad {@link ActuaryInfo} sa optimistic
     * locking retry-em. Pre fix-a, increment je bio non-atomic — paralelne
     * BUY ordere istog agenta mogu lost-update istisnuti delta (agent prelazi
     * dailyLimit unprimetno). Sa {@code @Version} kolonom + retry loop-om,
     * drugi konkurentni commit baca {@link org.springframework.orm.ObjectOptimisticLockingFailureException},
     * ponavljamo ucitavanje svezeg reda i re-primenu mutacije.
     *
     * <p>maxAttempts=3 (initial + 2 retries) sa kratkim backoff-om (50ms baseline,
     * 100ms na drugi pokusaj). Veci retry brojevi bi indikovali sistemski problem
     * (npr. preopterecen agent ili 4xx loop u banka-core seam-u).
     *
     * @param employeeId ID zaposlenog (= ActuaryInfo.employeeId)
     * @param mutator    callback koji prima ActuaryInfo i menja usedLimit (ili ne)
     */
    /**
     * R1-743: retry/backoff parametri za {@link #mutateActuaryWithRetry}.
     * {@code initial + 2 retries} sa linearnim backoff-om ({@code 50ms × attempt}).
     * Veci brojevi indikovali bi sistemski problem (preopterecen agent / 4xx loop).
     */
    private static final int ACTUARY_MUTATE_MAX_ATTEMPTS = 3;
    private static final long ACTUARY_MUTATE_BACKOFF_BASE_MS = 50L;

    /**
     * R1-745: inkrementira {@code usedLimit} agenta za {@code delta} (atomic kroz
     * optimistic-locking retry). Zajednicki helper za BUY create/approve putanje
     * (pre konsolidacije identican lambda blok kopiran na 2 mesta).
     */
    private void incUsedLimit(Long employeeId, BigDecimal delta) {
        mutateActuaryWithRetry(employeeId, actuary -> {
            BigDecimal current = actuary.getUsedLimit() != null ? actuary.getUsedLimit() : BigDecimal.ZERO;
            actuary.setUsedLimit(current.add(delta));
        });
    }

    /**
     * R1-745: dekrementira {@code usedLimit} agenta za {@code amount}, clamp-ovano
     * na 0 (nikad negativan limit). Zajednicki helper za decline/cancel rollback
     * putanje (pre konsolidacije identican lambda blok kopiran na 2 mesta).
     */
    private void decUsedLimit(Long employeeId, BigDecimal amount) {
        mutateActuaryWithRetry(employeeId, actuary -> {
            BigDecimal current = actuary.getUsedLimit() != null ? actuary.getUsedLimit() : BigDecimal.ZERO;
            actuary.setUsedLimit(current.subtract(amount).max(BigDecimal.ZERO));
        });
    }

    private void mutateActuaryWithRetry(Long employeeId, java.util.function.Consumer<ActuaryInfo> mutator) {
        for (int attempt = 1; attempt <= ACTUARY_MUTATE_MAX_ATTEMPTS; attempt++) {
            Optional<ActuaryInfo> actuaryOpt = orderStatusService.getAgentInfo(employeeId);
            if (actuaryOpt.isEmpty()) {
                return; // nije AGENT (supervizor/admin), niti se nista ne menja
            }
            ActuaryInfo actuary = actuaryOpt.get();
            if (actuary.getActuaryType() != ActuaryType.AGENT) {
                return; // samo AGENT-i imaju usedLimit / dailyLimit
            }
            try {
                mutator.accept(actuary);
                actuaryInfoRepository.save(actuary);
                return;
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
                if (attempt == ACTUARY_MUTATE_MAX_ATTEMPTS) {
                    org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                            .error("ActuaryInfo update lost race for employeeId={} after {} attempts",
                                    employeeId, ACTUARY_MUTATE_MAX_ATTEMPTS);
                    throw ex;
                }
                try {
                    Thread.sleep(ACTUARY_MUTATE_BACKOFF_BASE_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }
}
