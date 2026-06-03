package rs.raf.trading.order.service;

import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.service.FundLiquidationService;
import rs.raf.trading.margin.service.MarginOrderSettlementService;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.event.OrderCompletedEvent;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * P2-3: izvrsava obradu JEDNOG ordera u sopstvenoj transakciji
 * ({@link Propagation#REQUIRES_NEW}).
 *
 * <p>Ranije je {@code OrderExecutionService.executeOrders()} bila JEDNA velika
 * {@code @Transactional} metoda koja petlja preko SVIH izvrsivih ordera. Ako bi
 * neki ugnjezdeni {@code @Transactional(REQUIRED)} poziv (npr.
 * {@code FundReservationService.consumeForBuyFill}) bacio, oznacio bi DELJENU
 * transakciju kao rollback-only. Swallow-ovani exception u petlji bi pustio da
 * petlja nastavi, ali bi outer commit bacio {@code UnexpectedRollbackException}
 * → cela mutacija tick-a (portfolio krediti, {@code remainingPortions}
 * dekrementi DRUGIH ordera) bi se rollback-ovala, dok su banka-core novcani
 * pomeraji vec commit-ovani van procesa → privremeno torn state.
 *
 * <p>Resenje: svaki order se obradjuje u svojoj {@code REQUIRES_NEW} transakciji.
 * Posto Spring {@code @Transactional} self-invocation ne pravi novu Tx kroz
 * {@code this}, ovaj poziv mora ici kroz proxy — zato je per-order logika u
 * zasebnom bean-u koji {@link OrderExecutionService} injektuje i poziva.
 * Jedan order koji baci ne truje tick: njegova {@code REQUIRES_NEW} Tx se
 * rollback-uje izolovano, a uspesni orderi commit-uju svoje izmene.
 *
 * <p>Idempotency kljucevi ({@code order-{id}-fill-{seq}}) su nepromenjeni —
 * banka-core dedup-uje retry-e.
 *
 * <p>P2-4 (Marzni_Racuni.txt §137): blokiran margin racun odbija SVAKI
 * buy/sell pokusaj. Ako je margin racun ovog ordera BLOCKED, order se cisto
 * declime (DECLINED + release rezervacije + notifikacija) umesto da
 * {@code settleMargin*Fill} baci svaki tick (sto bi bio beskonacni retry).
 */
@Service
@RequiredArgsConstructor
public class SingleOrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(SingleOrderExecutor.class);

    private final OrderRepository orderRepository;
    private final ListingRepository listingRepository;
    private final PortfolioRepository portfolioRepository;
    private final AonValidationService aonValidationService;
    private final FundReservationService fundReservationService;
    private final FundLiquidationService fundLiquidationService;
    private final BankaCoreClient bankaCoreClient;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationService notificationService;
    private final MarginOrderSettlementService marginOrderSettlementService;
    private final Counter ordersExecutedCounter;

    /**
     * Dodatan delay za after-hours naloge (u sekundama). Spec trazi 30 min po
     * svakom fill-u; za demo se moze skratiti. Koristi se u {@link #computeNextFillDelay}.
     */
    @Value("${orders.afterhours.delay-seconds:1800}")
    private long afterHoursDelaySeconds;

    /**
     * Safety cap za spec-izracunati interval izmedju fill-ova kod niskog volumena.
     */
    @Value("${orders.execution.max-fill-interval-seconds:600}")
    private long maxFillIntervalSeconds;

    /**
     * R1-754: minuta u trgovackom danu (24h × 60min). Spec formula za interval
     * izmedju fill-ova: {@code Random(0, TRADING_DAY_MINUTES / (volume/remaining))}.
     * Pre je bila inline magic {@code 1440.0} u {@link #computeNextFillDelay}.
     */
    private static final double TRADING_DAY_MINUTES = 24 * 60;

    /**
     * Provizijske stope/cap-ovi (MARKET min(14%,$7) / LIMIT min(24%,$12)) —
     * R1-718: jedinstven izvor istine {@link rs.raf.trading.common.OrderCommissionPolicy}
     * (deljen sa {@code OrderServiceImpl.calculateCommissionInListingCurrency}).
     */
    private static final BigDecimal MARKET_COMMISSION_RATE = rs.raf.trading.common.OrderCommissionPolicy.MARKET_COMMISSION_RATE;
    private static final BigDecimal MARKET_COMMISSION_CAP = rs.raf.trading.common.OrderCommissionPolicy.MARKET_COMMISSION_CAP;
    private static final BigDecimal LIMIT_COMMISSION_RATE = rs.raf.trading.common.OrderCommissionPolicy.LIMIT_COMMISSION_RATE;
    private static final BigDecimal LIMIT_COMMISSION_CAP = rs.raf.trading.common.OrderCommissionPolicy.LIMIT_COMMISSION_CAP;

    /**
     * Menjacnica marza koja se naplacuje klijentu na SELL kad konvertuje u drugu valutu.
     * P2-profit-fx-fee-1 (R5 1877): jedinstven izvor istine
     * {@link rs.raf.trading.common.FxFeePolicy#FX_FEE_RATE} (vrednost 1% nepromenjena).
     */
    private static final BigDecimal SELL_FX_MARGIN = rs.raf.trading.common.FxFeePolicy.FX_FEE_RATE;

    /**
     * P2-3: obradjuje jedan order u sopstvenoj ({@code REQUIRES_NEW}) transakciji.
     * Poziva se iskljucivo kroz proxy iz {@link OrderExecutionService} (NE kroz
     * {@code this}) da bi Spring zaista otvorio novu transakciju.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(Order staleOrder) {
        // P2-concurrency-locks-1 (R6-1998 / R2-1407): re-fetch + re-check POD LOCKOM.
        // OrderExecutionService.executeOrders() ucita APPROVED ordere VAN transakcije i
        // prosledi DETACHED kopiju ovde. Bez re-fetch-a/re-check-a pod pessimistic lockom,
        // jedan tick moze da radi nad zastarelim snapshotom: ako je u medjuvremenu
        // supervizor declime-ovao/cancel-ovao order (status → DECLINED, rezervacija vec
        // oslobodjena) ili je drugi tick vec fill-ovao, ovaj tick bi prepisao
        // DECLINED→DONE i ponovo rezervisao/naplatio (double reservation / double charge,
        // single-instance decline-vs-fill). findByIdForUpdate serijalizuje protiv
        // approveOrder/declineOrder/cancelOrder (svi koriste isti lock) + @Version, a
        // status-guard odbacuje sve sto vise nije izvrsivo. Od ove tacke radimo iskljucivo
        // nad SVEZIM managed entitetom (ne nad detached parametrom).
        Order order = orderRepository.findByIdForUpdate(staleOrder.getId()).orElse(null);
        if (order == null) {
            log.warn("Order #{} nije pronadjen pod lockom — preskacem tick (verovatno obrisan)",
                    staleOrder.getId());
            return;
        }
        if (order.getStatus() != OrderStatus.APPROVED || order.isDone()) {
            log.debug("Order #{} vise nije izvrsiv pod lockom (status={}, done={}) — preskacem tick "
                    + "(decline-vs-fill / already-filled guard, R6-1998)",
                    order.getId(), order.getStatus(), order.isDone());
            return;
        }
        executeSingleOrderInternal(order);
    }

    private void executeSingleOrderInternal(Order order) {
        // 0a. Settlement-date provera (samo za futures/opcije gde postoji).
        // Ako je datum dospeca prosao, order se auto-declime + oslobodi rezervaciju.
        // P2-3: ovo je per-order posao i radi u REQUIRES_NEW tx (commit izolovan).
        if (order.getListing().getSettlementDate() != null
                && order.getListing().getSettlementDate().isBefore(LocalDate.now())) {
            log.warn("Order #{} auto-declined: settlement date {} has passed",
                    order.getId(), order.getListing().getSettlementDate());
            order.setStatus(OrderStatus.DECLINED);
            order.setDone(true);
            order.setLastModification(LocalDateTime.now());
            releaseReservationSafe(order);
            orderRepository.save(order);
            try {
                notificationService.notify(
                        order.getUserId(),
                        order.getUserRole(),
                        NotificationType.ORDER_CANCELLED,
                        "Nalog otkazan",
                        "Vaš nalog za " + order.getListing().getTicker()
                                + " je automatski otkazan jer je datum dospeća prošao.",
                        "ORDER",
                        order.getId()
                );
            } catch (Exception ex) {
                log.warn("Failed to send order cancelled notification for order #{}: {}",
                        order.getId(), ex.getMessage());
            }
            return;
        }

        // 0b. Legacy guard: APPROVED orderi iz starog seed-a nemaju reservedAccountId
        // ni accountId — ne mogu se izvrsiti. Markiraj ih kao DECLINED da scheduler
        // prekine retry loop.
        if (order.getReservedAccountId() == null && order.getAccountId() == null) {
            log.warn("Order #{} nema ni reservedAccountId ni accountId — oznacavam kao DECLINED (legacy seed)", order.getId());
            order.setStatus(OrderStatus.DECLINED);
            order.setLastModification(LocalDateTime.now());
            orderRepository.save(order);
            return;
        }

        // P2-4 (Marzni_Racuni.txt §137): ako je margin racun ovog ordera BLOCKED,
        // svaki buy/sell pokusaj se odbija. Da order ne bi beskonacno retry-ovao
        // svaki tick (settleMargin*Fill bi bacao IllegalStateException koji
        // OrderExecution.executeOrders guta i ostavlja order APPROVED), ovde ga
        // cisto declime: oslobodi rezervaciju + notifikuj + prekini.
        if (order.isMargin() && marginOrderSettlementService.isMarginAccountBlocked(order)) {
            log.warn("Order #{} declined: margin racun je BLOCKED (Marzni_Racuni.txt §137 — "
                    + "trgovina nije dozvoljena dok se racun ne odblokira uplatom)", order.getId());
            declineBlockedMarginOrder(order);
            return;
        }

        // 1. Dohvatiti ažuriranu cenu listinga
        Listing listing = listingRepository.findById(order.getListing().getId())
                .orElseThrow(() -> new RuntimeException("Listing not found for order #" + order.getId()));

        // P1-dividends-order-1 (1545): null/zero guard na ask/bid PRE bilo kakvog
        // racunanja cene. Bez ovoga, ne-refreshovan listing (ask/bid == null) baca
        // NPE -> order STUCK + retry zauvek; ask/bid == 0 -> fill po ceni 0
        // (besplatna kupovina, krsi konzervaciju). U oba slucaja preskoci ovaj tick
        // (order ostaje za naredni ciklus kad cena postane validna).
        BigDecimal marketPrice = (order.getDirection() == OrderDirection.BUY)
                ? listing.getAsk() : listing.getBid();
        if (marketPrice == null || marketPrice.signum() <= 0) {
            log.warn("Order #{} skipped this tick: {} cena nije validna (ask={}, bid={})",
                    order.getId(), order.getDirection(), listing.getAsk(), listing.getBid());
            return;
        }

        // 2. Odrediti execution price
        BigDecimal executionPrice;
        if (order.getOrderType() == OrderType.MARKET) {
            executionPrice = marketPrice;
        } else { // LIMIT
            if (order.getDirection() == OrderDirection.BUY) {
                if (marketPrice.compareTo(order.getLimitValue()) > 0) return; // Cena previsoka
                executionPrice = marketPrice;
            } else {
                if (marketPrice.compareTo(order.getLimitValue()) < 0) return; // Cena preniska
                executionPrice = marketPrice;
            }
        }

        // 3. Odrediti količinu za fill
        int remaining = order.getRemainingPortions() != null ? order.getRemainingPortions() : order.getQuantity();
        if (remaining <= 0) {
            order.setDone(true);
            order.setStatus(OrderStatus.DONE);
            order.setLastModification(LocalDateTime.now());
            releaseReservationSafe(order);
            orderRepository.save(order);
            publishOrderCompleted(order);
            return;
        }

        // Spec: Random fill quantity between 1 and remaining
        int fillQuantity = ThreadLocalRandom.current().nextInt(1, remaining + 1);
        fillQuantity = Math.min(fillQuantity, remaining);

        // b. AON (All-or-None) provera
        if (!aonValidationService.checkCanExecuteAon(order, fillQuantity)) {
            return;
        }
        if (order.isAllOrNone()) {
            // P1-dividends-order-1 (1320): AON mora popuniti SVU PREOSTALU kolicinu
            // odjednom — to je `remaining`, NE `order.getQuantity()`. Kad je AON
            // nalog parcijalno skracen (cancelOrder umanji remainingPortions ali
            // NE menja quantity), getQuantity() je vise od remaining -> over-fill
            // (remaining ide u negativno, portfolio dobija visak, rezervacija po
            // remaining a kupovina po quantity -> money break). `remaining` je
            // ovde uvek == remainingPortions (AON ne dozvoljava prethodne parcijalne
            // fillove), pa ovo verno postuje AON semantiku.
            fillQuantity = remaining;
        }

        // 4. Izračun ukupne cene i provizije (sve u valuti listinga)
        BigDecimal contractSize = BigDecimal.valueOf(order.getContractSize());
        BigDecimal totalPriceInListing = executionPrice.multiply(BigDecimal.valueOf(fillQuantity))
                .multiply(contractSize)
                .setScale(4, RoundingMode.HALF_UP);

        boolean isEmployee = UserRole.isEmployee(order.getUserRole()) || UserRole.FUND.equals(order.getUserRole());

        // N2 FIX (money): provizija se racuna JEDNOM po celom nalogu (§308/§322) i
        // pro-rata raspodeli po fill-u — NE per-fill min(rate×fillPrice, cap). Pre fix-a
        // je svaki partial fill nezavisno racunao min(14%×fillPrice, $7), pa je Σ provizija
        // svih fill-ova premasivala cap CELOG naloga (rezervacija drzi cap po celom nalogu)
        // → BUY commit > rezervacija (IllegalState → vecni retry / zaglavljen order) ili SELL
        // preplata banke. Pro-rata "kumulativna razlika" garantuje Σ_fill == orderCommission.
        int filledSoFar = order.getQuantity()
                - (order.getRemainingPortions() != null ? order.getRemainingPortions() : order.getQuantity());
        BigDecimal commissionInListing = isEmployee
                ? BigDecimal.ZERO
                : proRataOrderCommission(order, filledSoFar, fillQuantity, totalPriceInListing);

        // Konverzija u valutu racuna. Za single-currency orderi (exchangeRate=1 ili null)
        // se ponasa kao pre.
        BigDecimal midRate = order.getExchangeRate() != null ? order.getExchangeRate() : BigDecimal.ONE;
        BigDecimal totalPriceInAccount = totalPriceInListing.multiply(midRate)
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal commissionInAccount = commissionInListing.multiply(midRate)
                .setScale(4, RoundingMode.HALF_UP);

        // 5. Finansijske operacije preko banka-core internog seam-a (faza 2c rewire).
        //    Exception se propagira i @Transactional radi rollback.
        // BE-STK-05: ako je margin order, settle ide kroz MarginOrderSettlementService
        // (BUY split: bankPart→LoanValue+debit bankin racun, userPart→IM/reservedMargin).
        int buyFillSeq = filledSoFar;
        if (order.getDirection() == OrderDirection.BUY) {
            if (order.isMargin()) {
                // BE-STK-05: margin BUY fill split. P1-margin-1 (R2 1325): prosledjujemo
                // fillQuantity da settle moze osloboditi pro-rata visak rezervacije kad je
                // fill cena niza od approx (inace bi visak ostao zaglavljen u reservedMargin).
                marginOrderSettlementService.settleMarginBuyFill(order, buyFillSeq, fillQuantity, totalPriceInAccount);
                updatePortfolio(order, fillQuantity, executionPrice);
            } else {
                // Pro-rata FX komisija za ovaj fill (iz order.fxCommission koji je bio
                // rezervisan pri kreiranju/odobravanju). 0 za zaposlene ili single-currency.
                BigDecimal fxForFill = proRataFxCommission(order, fillQuantity);
                // Bank prihod = order commission + FX commission (oboje u valuti racuna/banke).
                BigDecimal commissionForFill = commissionInAccount.add(fxForFill)
                        .setScale(4, RoundingMode.HALF_UP);
                // banka-core commit: zaduzi cenu fill-a sa rezervacije (cena -> trziste),
                // kreditira bankin racun proviziom, proporcionalno smanji rezervaciju.
                fundReservationService.consumeForBuyFill(order, fillQuantity, totalPriceInAccount, commissionForFill);
                updatePortfolio(order, fillQuantity, executionPrice);
            }
        } else {
            // SELL: consumeForSellFill skida qty iz portfolia i reservedQuantity.
            // Prihod (totalPrice - commission) ide na racun naloga (reservedAccountId).
            // Za klijenta sa razlicitom valutom racuna, jos 1% bankovske menjacnice
            // se skida pre isplate (spec: "prilikom konverzije uzimamo proviziju").

            // Za fond-ordere: portfolio je u FUND porfoliju, ne u supervizora
            Long sellPortfolioUserId = order.getFundId() != null ? order.getFundId() : order.getUserId();
            String sellPortfolioUserRole = order.getFundId() != null ? UserRole.FUND : order.getUserRole();

            // BE-ORD-05: pessimistic write lock u SELL fill putanji da se spreci
            // race izmedju paralelnih scheduler tick-ova nad istom portfolio pozicijom.
            // Bez lock-a, dva paralelna fill-a istog SELL ordera (npr. mali partial-fill
            // delay-i) bi mogla citati istu reservedQuantity, oba smanjiti, i kreirati
            // negativne reservedQuantity ili double-spend portfolio.quantity.
            //
            // P2-money-tx-1 (R3 1578) LOCK-BUDGET (svesna odluka, NE skidamo lock):
            // ovaj portfolio row-lock se DRZI dok SELL settlement radi sinhroni
            // bankaCoreClient.getAccount/credit poziv nize. Lock je namerno zadrzan radi
            // serijalizacije konkurentnih fill-ova; lock-hold je OGRANICEN
            // BankaCoreClient connect=5s/read=30s timeout-om (BankaCoreClientConfig).
            Portfolio portfolio = portfolioRepository
                    .findByUserIdAndUserRoleAndListingIdForUpdate(
                            sellPortfolioUserId, sellPortfolioUserRole, order.getListing().getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Portfolio nije pronadjen za SELL order #" + order.getId()));

            fundReservationService.consumeForSellFill(order, portfolio, fillQuantity);

            int sellFillSeq = filledSoFar;

            if (order.isMargin()) {
                // BE-STK-05: margin SELL fill split.
                // bankPart → smanjuje LoanValue (floor 0) + credit bankin trading racun
                // userPart → add na IM
                marginOrderSettlementService.settleMarginSellFill(order, sellFillSeq, totalPriceInAccount);
            } else {
                Long receivingAccountId = order.getReservedAccountId() != null
                        ? order.getReservedAccountId()
                        : order.getAccountId();
                String receivingCurrencyCode = bankaCoreClient.getAccount(receivingAccountId).currencyCode();

                BigDecimal netRevenueInAccount = totalPriceInAccount.subtract(commissionInAccount);

                boolean multiCurrency = midRate.compareTo(BigDecimal.ONE) != 0
                        && order.getListing().getListingType() != ListingType.FOREX;
                BigDecimal fxFee = BigDecimal.ZERO;
                if (!isEmployee && multiCurrency) {
                    fxFee = netRevenueInAccount.multiply(SELL_FX_MARGIN).setScale(4, RoundingMode.HALF_UP);
                    netRevenueInAccount = netRevenueInAccount.subtract(fxFee);
                }

                // banka-core credit: prihod ide prodavcu (cena "nastaje" iz trzista,
                // verno monolitu), provizija + FX ide bankinom racunu (banka-core ga
                // sam resolve-uje). banka-core pise audit Transaction.
                bankaCoreClient.creditFunds(
                        "order-" + order.getId() + "-sell-fill-" + sellFillSeq,
                        new CreditFundsRequest(receivingAccountId, netRevenueInAccount,
                                commissionInAccount.add(fxFee), receivingCurrencyCode,
                                "Order #" + order.getId() + " SELL fill " + sellFillSeq
                                        + " (" + fillQuantity + " kom)"));
            }
        }

        // 6. Ažurirati nalog
        order.setRemainingPortions(order.getRemainingPortions() - fillQuantity);
        order.setLastModification(LocalDateTime.now());
        boolean justCompleted = false;
        if (order.getRemainingPortions() <= 0) {
            order.setDone(true);
            order.setStatus(OrderStatus.DONE);
            order.setNextFillAt(null);
            // Ako je ostao visak rezervacije (npr. fill po nizoj ceni od approxPrice)
            // vrati ga na availableBalance / availableQuantity.
            releaseReservationSafe(order);
            justCompleted = true;
        } else {
            // Spec: vremenski interval izmedju fill-ova =
            //   Random(0, 24 * 60 / (volume / remaining)) sekundi
            // + 30 min bonus za after-hours naloge (po svakom fill-u).
            order.setNextFillAt(LocalDateTime.now().plusSeconds(computeNextFillDelay(order, listing)));
        }
        orderRepository.save(order);

        log.info("Order #{} filled {} of {} @ {} (remaining: {}, orderComm: {}, listingCcy)",
                order.getId(), fillQuantity, order.getQuantity(),
                executionPrice, order.getRemainingPortions(), commissionInListing);

        if ("FUND".equals(order.getUserRole())) {
            log.info("T9 Hook: Detektovan nalog fonda #{}. Pokrecem resolve pending transakcija.", order.getUserId());
            fundLiquidationService.onFillCompleted(order.getId());
        }

        if (justCompleted) {
            // W2-T1: brojaj samo kompletno zavrsene (DONE) order-e, ne i parcijalne fill-ove.
            ordersExecutedCounter.increment();
            publishOrderCompleted(order);
            try {
                notificationService.notify(
                        order.getUserId(),
                        order.getUserRole(),
                        NotificationType.ORDER_EXECUTED,
                        "Nalog izvršen",
                        "Vaš nalog za " + order.getListing().getTicker() + " je u potpunosti izvršen.",
                        "ORDER",
                        order.getId()
                );
            } catch (Exception ex) {
                log.warn("Failed to send order executed notification for order #{}: {}", order.getId(), ex.getMessage());
            }
        } else {
            // Sc24 (TODO_testovi): email obavestenje o delimicnom izvrsenju MORA da
            // navede koliko je IZVRSENO i koliko PREOSTAJE. executedSoFar je ukupna
            // popunjena kolicina POSLE ovog fill-a (quantity − remainingPortions).
            int executedSoFar = order.getQuantity() - order.getRemainingPortions();
            try {
                notificationService.notify(
                        order.getUserId(),
                        order.getUserRole(),
                        NotificationType.ORDER_PARTIAL_FILL,
                        "Nalog delimično izvršen",
                        "Vaš nalog za " + order.getListing().getTicker()
                                + " je delimično izvršen. Izvršeno: " + executedSoFar
                                + " / " + order.getQuantity() + " komada. Preostalo: "
                                + order.getRemainingPortions() + " komada.",
                        "ORDER",
                        order.getId()
                );
            } catch (Exception ex) {
                log.warn("Failed to send order partial fill notification for order #{}: {}", order.getId(), ex.getMessage());
            }
        }
    }

    /**
     * P2-4: cisto declime margin order ciji je racun BLOCKED (Marzni_Racuni.txt §137).
     * Oslobodi rezervaciju (BUY: margin reservedMargin; SELL: portfolio qty),
     * markira DECLINED + done i posalje notifikaciju. Order se vise ne retry-uje.
     */
    private void declineBlockedMarginOrder(Order order) {
        order.setStatus(OrderStatus.DECLINED);
        order.setDone(true);
        order.setLastModification(LocalDateTime.now());
        releaseReservationSafe(order);
        orderRepository.save(order);
        try {
            notificationService.notify(
                    order.getUserId(),
                    order.getUserRole(),
                    NotificationType.ORDER_CANCELLED,
                    "Nalog otkazan",
                    "Vaš nalog za " + order.getListing().getTicker()
                            + " je otkazan jer je marzni račun blokiran (margin call). "
                            + "Uplatite sredstva na marzni račun da biste ponovo trgovali.",
                    "ORDER",
                    order.getId()
            );
        } catch (Exception ex) {
            log.warn("Failed to send blocked-margin order cancelled notification for order #{}: {}",
                    order.getId(), ex.getMessage());
        }
    }

    /**
     * Emit-uje {@link OrderCompletedEvent} kad order zavrsi (status DONE).
     * Konzumenti (npr. {@code ProfitBankCacheEvictionListener}) invalidiraju
     * cached izvedena polja.
     */
    private void publishOrderCompleted(Order order) {
        try {
            eventPublisher.publishEvent(new OrderCompletedEvent(
                    order.getId(),
                    order.getUserId(),
                    order.getUserRole(),
                    order.getFundId()));
        } catch (RuntimeException ex) {
            // Ne sme da pukne order fill flow zbog event-a — log i nastavi.
            log.warn("Order #{} completed event publish failed: {}", order.getId(), ex.getMessage());
        }
    }

    /**
     * Izracunava koliko sekundi ceka do sledeceg fill pokusaja po specifikaciji:
     *   Random(0, 24 * 60 / (volume / remaining)) sekundi + 30 min ako je after-hours.
     * Ako je volume nula ili nema remainingPortions, fallback je {@code maxFillIntervalSeconds}.
     * Kompletan rezultat je ogranicen na {@code maxFillIntervalSeconds} + after-hours bonus.
     */
    long computeNextFillDelay(Order order, Listing listing) {
        long afterHoursBonus = order.isAfterHours() ? afterHoursDelaySeconds : 0L;
        Long volume = listing != null ? listing.getVolume() : null;
        int remaining = order.getRemainingPortions() != null ? order.getRemainingPortions() : 0;
        if (volume == null || volume <= 0L || remaining <= 0) {
            return maxFillIntervalSeconds + afterHoursBonus;
        }
        // R1-754: TRADING_DAY_MINUTES (24×60=1440). Kad je volume ogroman
        // (npr. MSFT 50M/day) a remaining 10, formula daje milisekundni delay;
        // zato cap na maxFillIntervalSeconds (default 10 min).
        double maxSeconds = TRADING_DAY_MINUTES / ((double) volume / remaining);
        long capped = Math.max(0L, Math.min((long) Math.ceil(maxSeconds), maxFillIntervalSeconds));
        long randomDelay = capped > 0 ? ThreadLocalRandom.current().nextLong(0L, capped + 1L) : 0L;
        return randomDelay + afterHoursBonus;
    }

    /**
     * Idempotentno oslobadja rezervaciju za order (BUY: funds, SELL: portfolio qty).
     * Loguje i proguta greske da jedan fail ne sruši execution petlju.
     */
    private void releaseReservationSafe(Order order) {
        if (order.isReservationReleased()) {
            return;
        }
        try {
            if (order.getDirection() == OrderDirection.BUY) {
                if (order.isMargin()) {
                    // BE-STK-05: margin BUY rezervacija je u MarginAccount.reservedMargin,
                    // ne u banka-core fund reservation. Rollback userPart koji je ostao u
                    // reservedMargin posle parcijalnih fill-ova (visak).
                    BigDecimal totalRemaining = order.getApproximatePrice() != null
                            && order.getQuantity() != null && order.getRemainingPortions() != null
                            ? order.getApproximatePrice()
                                  .multiply(BigDecimal.valueOf(order.getRemainingPortions()))
                                  .divide(BigDecimal.valueOf(order.getQuantity()), 4, java.math.RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    if (totalRemaining.signum() > 0) {
                        marginOrderSettlementService.releaseMarginBuyReservation(order, totalRemaining);
                    }
                    order.setReservationReleased(true);
                } else {
                    fundReservationService.releaseForBuy(order);
                }
            } else {
                // Za fond-ordere: traži portfolio u FUND portfoliju, ne u supervizora
                Long sellPortfolioUserId = order.getFundId() != null ? order.getFundId() : order.getUserId();
                String sellPortfolioUserRole = order.getFundId() != null ? UserRole.FUND : order.getUserRole();

                Portfolio portfolio = portfolioRepository
                        .findByUserIdAndUserRole(sellPortfolioUserId, sellPortfolioUserRole).stream()
                        .filter(p -> p.getListingId().equals(order.getListing().getId()))
                        .findFirst()
                        .orElse(null);
                if (portfolio != null) {
                    fundReservationService.releaseForSell(order, portfolio);
                } else {
                    order.setReservationReleased(true);
                }
            }
        } catch (Exception e) {
            log.warn("Release reservation failed for order #{}: {}", order.getId(), e.getMessage());
        }
    }

    /**
     * Azurira portfolio nakon BUY fill-a. SELL fillovi NE prolaze ovuda — oni
     * se obradjuju kroz {@link FundReservationService#consumeForSellFill}.
     * Zato ovde tretiramo samo BUY (quantity > 0).
     *
     * Za fond-ordere (fundId != null), hartije se stavljaju u FUND portfolio,
     * ne u portfolio supervizora.
     */
    private void updatePortfolio(Order order, int quantity, BigDecimal price) {
        // Za fond-ordere: koristi fundId i "FUND" ulogu
        Long portfolioUserId = order.getFundId() != null ? order.getFundId() : order.getUserId();
        String portfolioUserRole = order.getFundId() != null ? UserRole.FUND : order.getUserRole();

        Optional<Portfolio> existing = portfolioRepository
                .findByUserIdAndUserRole(portfolioUserId, portfolioUserRole)
                .stream()
                .filter(p -> p.getListingId().equals(order.getListing().getId()))
                .findFirst();

        if (existing.isPresent()) {
            Portfolio portfolio = existing.get();
            int oldQty = portfolio.getQuantity();
            BigDecimal oldTotal = portfolio.getAverageBuyPrice().multiply(BigDecimal.valueOf(oldQty));
            BigDecimal newFillTotal = price.multiply(BigDecimal.valueOf(quantity));
            int newQty = oldQty + quantity;

            BigDecimal newAvg = oldTotal.add(newFillTotal)
                    .divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP);

            portfolio.setQuantity(newQty);
            portfolio.setAverageBuyPrice(newAvg);
            portfolioRepository.save(portfolio);
        } else {
            Portfolio portfolio = new Portfolio();
            portfolio.setUserId(portfolioUserId);
            portfolio.setUserRole(portfolioUserRole);
            portfolio.setListingId(order.getListing().getId());
            portfolio.setListingTicker(order.getListing().getTicker());
            portfolio.setListingName(order.getListing().getName());
            portfolio.setListingType(order.getListing().getListingType().name());
            portfolio.setQuantity(quantity);
            portfolio.setAverageBuyPrice(price);
            portfolio.setPublicQuantity(0);

            portfolioRepository.save(portfolio);
        }
    }

    /**
     * Racuna proviziju: MARKET/STOP min(14% * price, $7), LIMIT/STOP_LIMIT min(24% * price, $12).
     * Spec §308/§322: "u zavisnosti od toga koji iznos je manji". Koristi se za
     * proviziju CELOG naloga (orderCommission) i kao legacy fallback (orderi bez
     * perzistovanog orderCommission polja).
     */
    private BigDecimal calculateCommission(BigDecimal totalPrice, OrderType orderType) {
        return switch (orderType) {
            case MARKET, STOP -> totalPrice.multiply(MARKET_COMMISSION_RATE).min(MARKET_COMMISSION_CAP);
            case LIMIT, STOP_LIMIT -> totalPrice.multiply(LIMIT_COMMISSION_RATE).min(LIMIT_COMMISSION_CAP);
        };
    }

    /**
     * N2 FIX (money): pro-rata deo provizije CELOG naloga ({@code order.orderCommission},
     * §308/§322, listing valuta) za jedan fill — tako da je {@code Σ provizija_fill ==
     * orderCommission} (ne premasuje rezervacioni cap).
     *
     * <p>Koristi se "kumulativna razlika" raspodela:
     * {@code commission(filledSoFar+fill) − commission(filledSoFar)}, gde je
     * {@code commission(n) = orderCommission × n / totalQty}. Suma po svim fill-ovima
     * teleskopira tacno u {@code orderCommission × totalQty/totalQty = orderCommission},
     * pa nema rounding drift-a (poslednji fill prirodno pokupi ostatak). Ovo je tacniji
     * ekvivalent monolitne provizije celog naloga primenjene jednom.
     *
     * <p>Legacy fallback: orderi bez perzistovanog {@code orderCommission} (stari seed /
     * pre-N2 redovi) padaju na staru per-fill {@code calculateCommission(totalPriceFill)}
     * — backward-compat, bez NPE. Novi orderi uvek imaju {@code orderCommission} postavljen.
     *
     * @param order             nalog (cuva orderCommission + totalQty)
     * @param filledSoFar       kolicina vec popunjena PRE ovog fill-a
     * @param fillQuantity      kolicina ovog fill-a
     * @param totalPriceInListing cena ovog fill-a (samo za legacy fallback)
     */
    private BigDecimal proRataOrderCommission(Order order, int filledSoFar, int fillQuantity,
                                              BigDecimal totalPriceInListing) {
        BigDecimal orderCommission = order.getOrderCommission();
        Integer totalQty = order.getQuantity();
        if (orderCommission == null || orderCommission.signum() <= 0
                || totalQty == null || totalQty <= 0) {
            // Legacy fallback (pre-N2 orderi bez orderCommission polja).
            return calculateCommission(totalPriceInListing, order.getOrderType());
        }
        BigDecimal totalQtyBd = BigDecimal.valueOf(totalQty);
        int filledAfter = Math.min(filledSoFar + fillQuantity, totalQty);
        // commission(n) = round4(orderCommission × n / totalQty). Razlika ROUNDED kumulanata
        // garantuje da Σ_fill == round4(orderCommission) BEZ rounding drift-a: kumulanti
        // teleskopiraju (cumBefore sledeceg fill-a == cumAfter ovog), poslednji fill prirodno
        // pokupi ostatak (cumAfter == orderCommission). Round4-na razlika sirovih kumulanata bi
        // mogla da akumulira ±0.00005 po fill-u; round4 SAMIH kumulanata to eliminise.
        BigDecimal cumBefore = orderCommission.multiply(BigDecimal.valueOf(filledSoFar))
                .divide(totalQtyBd, 4, RoundingMode.HALF_UP);
        BigDecimal cumAfter = orderCommission.multiply(BigDecimal.valueOf(filledAfter))
                .divide(totalQtyBd, 4, RoundingMode.HALF_UP);
        return cumAfter.subtract(cumBefore).max(BigDecimal.ZERO);
    }

    /**
     * Pro-rata deo ukupne FX provizije ordera za jedan fill.
     * Vraca ZERO ako FX provizija nije obracunata (zaposleni / iste valute)
     * ili ako je quantity <= 0.
     */
    private BigDecimal proRataFxCommission(Order order, int fillQuantity) {
        BigDecimal totalFx = order.getFxCommission();
        if (totalFx == null || totalFx.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        Integer totalQty = order.getQuantity();
        if (totalQty == null || totalQty <= 0) {
            return BigDecimal.ZERO;
        }
        return totalFx.multiply(BigDecimal.valueOf(fillQuantity))
                .divide(BigDecimal.valueOf(totalQty), 4, RoundingMode.HALF_UP);
    }
}
