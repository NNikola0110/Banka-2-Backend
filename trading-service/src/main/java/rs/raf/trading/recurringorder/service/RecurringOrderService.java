package rs.raf.trading.recurringorder.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.recurringorder.dto.CreateRecurringOrderDto;
import rs.raf.trading.recurringorder.dto.RecurringOrderDto;
import rs.raf.trading.recurringorder.model.RecurringCadence;
import rs.raf.trading.recurringorder.model.RecurringMode;
import rs.raf.trading.recurringorder.model.RecurringOrder;
import rs.raf.trading.recurringorder.repository.RecurringOrderRepository;
import rs.raf.trading.security.TradingAccessGuard;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// ============================================================
// [B8 - Trajni nalozi (DCA / RecurringOrder) | Nosilac: Nikola Djurovic] - DONE
//
// Poslovni servis za upravljanje trajnim nalozima i njihovo izvrsavanje.
//
// Mikroservisi varijanta:
//  - Order/Listing su LOKALNI u trading-service (rs.raf.trading.order/stock)
//  - Racun je u banka-core — razresava se preko BankaCoreClient-a (HTTP RPC)
//  - In-app notifikacije ne perzistuju lokalno — publish-uju se preko RabbitMQ
//    kroz trading NotificationService (IN_APP_GENERIC). Banka-core je vlasnik
//    `notifications` tabele; trading-service salje samo event.
//
// Spec: Zadaci_Backend.pdf, zadatak B8.
// ============================================================
@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringOrderService {

    private final RecurringOrderRepository recurringOrderRepo;
    private final TradingUserResolver userResolver;
    private final ListingRepository listingRepository;
    private final BankaCoreClient bankaCoreClient;
    private final NotificationService notificationService;
    // R1-242 FIX: trading-access gate (TRADE_STOCKS za klijenta / SUPERVISOR-AGENT-ADMIN
    // za zaposlenog) primenjen pri KREIRANJU trajnog naloga (fail-fast), paritet sa
    // order-endpoint-om.
    private final TradingAccessGuard tradingAccessGuard;
    // N1 FIX: order-placement (sa sistemskim SecurityContext-om vlasnika) ide kroz
    // zaseban REQUIRES_NEW bean da njegov rollback ne poisonuje executeOne tx
    // (koja MORA da pomeri nextRun bez obzira na ishod kreiranja ordera).
    private final RecurringOrderPlacementService placementService;

    @Transactional
    public RecurringOrderDto create(CreateRecurringOrderDto dto) {
        UserContext me = userResolver.resolveCurrent();

        // R1-242: fail-fast trading-access gate PRE bilo kakvog banka-core/listing rada.
        // Bez ovoga klijent bez TRADE_STOCKS pravi "trajni" nalog koji bi na svakom
        // scheduler ciklusu pucao na placement-time AccessDenied i nikad ne kupio.
        tradingAccessGuard.ensureTradingAccess(me);

        // [P2-input-validation-1 / R1 527] BY_QUANTITY value mora biti ceo broj —
        // executeOne radi value.longValue() koji bi 2.7 tiho trunc-ovao na 2 (kupac
        // bi dobio manje akcija nego sto je zadao). Odbij necelobrojni quantity sa 400.
        if (dto.getMode() == RecurringMode.BY_QUANTITY
                && dto.getValue() != null
                && dto.getValue().stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "Za BY_QUANTITY rezim, kolicina mora biti ceo broj (bez decimala).");
        }

        // Verifikuj racun preko banka-core RPC-a (racun nije lokalan u trading-service-u)
        InternalAccountDto account;
        try {
            account = bankaCoreClient.getAccount(dto.getAccountId());
        } catch (BankaCoreClientException ex) {
            throw new IllegalArgumentException("Racun ne postoji");
        }
        if (account == null) {
            throw new IllegalArgumentException("Racun ne postoji");
        }

        if (me.isClient()) {
            if (account.ownerClientId() == null || !account.ownerClientId().equals(me.userId())) {
                throw new AccessDeniedException("Racun ne pripada klijentu.");
            }
        } else if (me.isEmployee()) {
            // Za zaposlene: trebalo bi proveriti da li mogu koristiti taj racun.
            // Ako je racun klijenta, zaposleni ne moze da ga koristi za trajne naloge.
            if (account.ownerClientId() != null) {
                throw new AccessDeniedException("Zaposleni ne moze koristiti klijentske racune za trajne naloge.");
            }
        }

        // Verifikuj hartiju (lokalno)
        Listing listing = listingRepository.findById(dto.getListingId())
                .orElseThrow(() -> new IllegalArgumentException("Hartija od vrednosti ne postoji"));

        // Odredi nextRun
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime nextRun;
        if (dto.getFirstRun() != null && dto.getFirstRun().isAfter(now)) {
            nextRun = dto.getFirstRun();
        } else {
            nextRun = advanceNextRun(now, dto.getCadence());
        }

        RecurringOrder order = RecurringOrder.builder()
                .ownerId(me.userId())
                .ownerType(me.userRole())
                .listingId(dto.getListingId())
                .direction(dto.getDirection())
                .mode(dto.getMode())
                .value(dto.getValue())
                .accountId(dto.getAccountId())
                .cadence(dto.getCadence())
                .nextRun(nextRun)
                .active(true)
                .build();

        order = recurringOrderRepo.save(order);

        log.info("Trajni nalog kreiran: id={}, owner={}, listing={} ({}), cadence={}",
                order.getId(), me.userId(), dto.getListingId(), listing.getTicker(), dto.getCadence());

        return toDto(order);
    }

    @Transactional(readOnly = true)
    public List<RecurringOrderDto> listMy() {
        UserContext me = userResolver.resolveCurrent();
        List<RecurringOrder> orders =
                recurringOrderRepo.findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(me.userId(), me.userRole());
        if (orders.isEmpty()) {
            return List.of();
        }
        // R1 818: batch-fetch ticker-a za sve referencirane listinge (jedan IN upit)
        // umesto findById po nalogu (N+1) u toDto.
        List<Long> listingIds = orders.stream().map(RecurringOrder::getListingId).distinct().toList();
        Map<Long, Listing> listingsById = listingRepository.findAllById(listingIds).stream()
                .collect(Collectors.toMap(Listing::getId, l -> l));
        return orders.stream()
                .map(o -> toDto(o, listingsById.get(o.getListingId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RecurringOrderDto getById(Long id) {
        RecurringOrder order = recurringOrderRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Trajni nalog ne postoji"));

        ensureOwner(order);

        return toDto(order);
    }

    /**
     * R1 523: vlasnistvo se proverava po (ownerId, ownerType) paru — NE samo
     * ownerId. Soft-reference id-evi se NE dele namespace izmedju CLIENT i
     * EMPLOYEE prostora (CLIENT #5 i EMPLOYEE #5 su razliciti korisnici); provera
     * samo po ownerId bi dozvolila CLIENT-u #5 da cita/pauzira/otkaze EMPLOYEE #5
     * trajni nalog (i obrnuto).
     */
    private void ensureOwner(RecurringOrder order) {
        UserContext me = userResolver.resolveCurrent();
        boolean sameOwner = order.getOwnerId() != null
                && order.getOwnerId().equals(me.userId())
                && order.getOwnerType() != null
                && order.getOwnerType().equals(me.userRole());
        if (!sameOwner) {
            throw new AccessDeniedException("Trajni nalog ne pripada korisniku.");
        }
    }

    @Transactional
    public RecurringOrderDto pause(Long id) {
        RecurringOrder order = recurringOrderRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Trajni nalog ne postoji"));

        ensureOwner(order);

        order.setActive(false);
        order = recurringOrderRepo.save(order);

        log.info("Trajni nalog pauziran: id={}", id);

        return toDto(order);
    }

    @Transactional
    public RecurringOrderDto resume(Long id) {
        RecurringOrder order = recurringOrderRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Trajni nalog ne postoji"));

        ensureOwner(order);

        order.setActive(true);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        order.setNextRun(advanceNextRun(now, order.getCadence()));
        order = recurringOrderRepo.save(order);

        log.info("Trajni nalog reaktiviran: id={}, nextRun={}", id, order.getNextRun());

        return toDto(order);
    }

    @Transactional
    public void cancel(Long id) {
        RecurringOrder order = recurringOrderRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Trajni nalog ne postoji"));

        ensureOwner(order);

        recurringOrderRepo.deleteById(id);

        log.info("Trajni nalog obrisan: id={}", id);
    }

    /**
     * Izvrsava jedan trajni nalog. Poziva se iz {@link rs.raf.trading.recurringorder.scheduler.RecurringOrderScheduler}.
     * REQUIRES_NEW da bi se greska jednog naloga izolovala od ostatka batch-a.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void executeOne(RecurringOrder recurringOrder) {
        try {
            // a. Dohvati trenutnu cenu (lokalno)
            Listing listing = listingRepository.findById(recurringOrder.getListingId())
                    .orElseThrow(() -> new IllegalArgumentException("Hartija od vrednosti ne postoji"));

            if (listing.getPrice() == null || listing.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Scheduler: Hartija {} nema validnu cenu, preskacem nalog id={}",
                        listing.getTicker(), recurringOrder.getId());
                advanceAndSave(recurringOrder);
                return;
            }

            // b. Izracunaj kolicinu
            long quantity;
            if (recurringOrder.getMode() == RecurringMode.BY_QUANTITY) {
                quantity = recurringOrder.getValue().longValue();
            } else {
                // BY_AMOUNT
                BigDecimal qtyDecimal = recurringOrder.getValue()
                        .divide(listing.getPrice(), RoundingMode.FLOOR);
                quantity = qtyDecimal.longValue();
            }

            // c. Provera kolicine >= 1
            if (quantity < 1) {
                log.warn("Scheduler: Izracunata kolicina < 1 za nalog id={}, preskacem",
                        recurringOrder.getId());
                advanceAndSave(recurringOrder);
                return;
            }

            // d. Verifikuj dostupna sredstva preko banka-core RPC-a.
            //    R1-240 FIX: kes-balans pre-check VAZI SAMO za BUY. SELL ne trosi kes
            //    (prodaja hartija puni racun), pa balans pre-check ne sme da preskoci
            //    SELL recurring nalog kad korisnik nema kesa. Pokriće hartija (poseduje
            //    li dovoljno akcija) je odgovornost order-engine-a (createOrder odbije
            //    SELL bez pozicije) — ne dupliramo je ovde.
            if ("BUY".equals(recurringOrder.getDirection())) {
                InternalAccountDto account;
                try {
                    account = bankaCoreClient.getAccount(recurringOrder.getAccountId());
                } catch (BankaCoreClientException ex) {
                    log.warn("Scheduler: banka-core lookup pao za nalog id={}: {}",
                            recurringOrder.getId(), ex.getMessage());
                    advanceAndSave(recurringOrder);
                    return;
                }
                if (account == null) {
                    log.warn("Scheduler: Racun {} ne postoji za nalog id={}, preskacem",
                            recurringOrder.getAccountId(), recurringOrder.getId());
                    advanceAndSave(recurringOrder);
                    return;
                }

                BigDecimal estimatedCost = listing.getPrice()
                        .multiply(BigDecimal.valueOf(quantity));

                BigDecimal availableBalance = account.availableBalance();
                if (availableBalance == null || availableBalance.compareTo(estimatedCost) < 0) {
                    // Nema dovoljno sredstava — best-effort notifikacija + skip
                    notifyInsufficientFunds(recurringOrder);
                    log.warn("Scheduler: Nedovoljno sredstava za nalog id={}, dostupno: {}, potrebno: {}",
                            recurringOrder.getId(), availableBalance, estimatedCost);
                    advanceAndSave(recurringOrder);
                    return;
                }
            }

            // e. Za aktuare (EMPLOYEE): orderService.createOrder ce odbiti ako se prekorace limiti.
            //    U trading-service-u, dnevni limit / usedLimit je odgovornost OrderService-a;
            //    ne dupliramo proveru ovde — exception ce nas dovesti u catch granu.

            // f. Kreiraj Market Order kroz placement bean.
            //    N1 FIX (broken-feature): scheduler thread NEMA Spring Security context.
            //    OrderServiceImpl.createOrder razresava identitet (resolveCurrent) i
            //    autorizaciju (ensureTradingAccess) iskljucivo iz SecurityContextHolder-a —
            //    bez auth-a bi resolveCurrent bacio IllegalState ("Nema autentifikovanog
            //    korisnika"), exception bi bio progutan ispod, a nextRun tiho napredovao →
            //    DCA NIKAD ne kupuje. placementService.placeMarketOrder postavlja sistemski
            //    kontekst vlasnika (ownerId/ownerType → email + permisije) i radi u SOPSTVENOJ
            //    REQUIRES_NEW tx, pa njegov eventualni rollback NE poisonuje ovu (executeOne)
            //    tx — advanceAndSave nextRun ispod uvek uspeva (no busy-loop).
            placementService.placeMarketOrder(recurringOrder, quantity);

            log.info("Scheduler: Market order kreiran iz trajnog naloga id={}, quantity={}, listing={}",
                    recurringOrder.getId(), quantity, listing.getTicker());

            // g. Azuriraj nextRun
            advanceAndSave(recurringOrder);

        } catch (Exception e) {
            // R1-241 / R3-1582 FIX: razdvoji POSLOVNI-skip od INFRA-fail.
            //  - Poslovna/trajna greska (AccessDenied, validacija, ilegalno stanje):
            //    nalog NIKAD nece uspeti u trenutnom stanju → napreduj nextRun (da
            //    scheduler ne busy-loop-uje) + best-effort notifikacija korisniku.
            //  - Prolazna infra greska (banka-core nedostupan, DB/optimistic-lock,
            //    konekcija): NE napreduj nextRun — nalog ostaje dospeo i pokusava se
            //    ponovo sledeci ciklus; propagiraj da scheduler loop zabelezi gresku.
            if (isTransientInfraFailure(e)) {
                log.error("Scheduler: PROLAZNA infra-greska za nalog id={} — nextRun NE pomeram, retry sledeci ciklus: {}",
                        recurringOrder.getId(), e.getMessage(), e);
                throw new RecurringOrderExecutionException(
                        "Prolazna greska pri izvrsavanju trajnog naloga id=" + recurringOrder.getId(), e);
            }
            log.error("Scheduler: POSLOVNA greska za nalog id={} — napredujem nextRun + notifikacija: {}",
                    recurringOrder.getId(), e.getMessage(), e);
            notifyExecutionFailed(recurringOrder, e);
            advanceAndSave(recurringOrder);
        }
    }

    /**
     * Klasifikuje gresku kao prolaznu infra-gresku (retry, NE pomeraj nextRun) ili
     * trajnu poslovnu gresku (napreduj nextRun). Konzervativno: sve sto je
     * banka-core/konekcija/DB-optimistic tretira se kao prolazno; AccessDenied i
     * validacione greske kao trajno-poslovno.
     */
    private boolean isTransientInfraFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.security.access.AccessDeniedException
                    || t instanceof IllegalArgumentException
                    || t instanceof IllegalStateException) {
                return false;
            }
            if (t instanceof BankaCoreClientException
                    || t instanceof org.springframework.web.client.ResourceAccessException
                    || t instanceof jakarta.persistence.OptimisticLockException
                    || t instanceof org.springframework.dao.DataAccessException
                    || t instanceof org.springframework.dao.OptimisticLockingFailureException) {
                return true;
            }
        }
        // Nepoznata greska: tretiraj kao poslovnu (napreduj) da scheduler ne
        // zaglavi u busy-loop-u na neklasifikovanom uzroku.
        return false;
    }

    private void advanceAndSave(RecurringOrder order) {
        // R2-1383 FIX (catch-up burst): nextRun se racuna tako da PRESKOCI sve
        // propustene cikluse i padne u buducnost. Bez ovoga, posle downtime-a
        // (nextRun vise ciklusa u proslosti) advance po jedan cadence ostaje u
        // proslosti → findDue() vraca nalog svaki ciklus → niz BUY ordera (spam)
        // dok ne sustigne sadasnjost. Jedan run po dospelosti, pa skok na buducnost.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime newNextRun = advanceNextRun(order.getNextRun(), order.getCadence());
        // Ako je i posle jednog koraka jos u proslosti (zaostatak), nastavi da
        // koracas dok ne predjes 'now' — ali izvrsavanje je vec bilo SAMO jednom
        // (jedan placement gore), ovo je iskljucivo pomeranje rasporeda.
        int guard = 0;
        while (!newNextRun.isAfter(now) && guard++ < 100_000) {
            newNextRun = advanceNextRun(newNextRun, order.getCadence());
        }
        order.setNextRun(newNextRun);
        recurringOrderRepo.save(order);
    }

    private LocalDateTime advanceNextRun(LocalDateTime from, RecurringCadence cadence) {
        return switch (cadence) {
            case DAILY -> from.plusDays(1);
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> from.plusMonths(1);
        };
    }

    private void notifyInsufficientFunds(RecurringOrder order) {
        try {
            notificationService.notify(
                    order.getOwnerId(),
                    order.getOwnerType(),
                    NotificationType.RECURRING_ORDER_SKIPPED,
                    "Trajni nalog preskocen - nedovoljna sredstva",
                    "Trajni nalog id=" + order.getId() + " nije izvrsen jer nema dovoljno sredstava na racunu.",
                    "RECURRING_ORDER",
                    order.getId()
            );
        } catch (Exception ex) {
            log.warn("Scheduler: notifikacija o preskocenom trajnom nalogu id={} nije poslata: {}",
                    order.getId(), ex.getMessage());
        }
    }

    /**
     * R1-241: best-effort notifikacija korisniku da trajni nalog nije izvrsen zbog
     * (trajne) poslovne greske — npr. izgubljena TRADE_STOCKS permisija ili odbijen
     * limit. Pre fix-a je sirok {@code catch} tiho gutao sve i napredovao nextRun bez
     * ikakve notifikacije; korisnik nikad nije saznao zasto se nalog "ne izvrsava".
     */
    private void notifyExecutionFailed(RecurringOrder order, Throwable cause) {
        try {
            notificationService.notify(
                    order.getOwnerId(),
                    order.getOwnerType(),
                    NotificationType.RECURRING_ORDER_SKIPPED,
                    "Trajni nalog nije izvrsen",
                    "Trajni nalog id=" + order.getId() + " nije izvrsen u ovom ciklusu ("
                            + (cause != null ? cause.getMessage() : "nepoznat razlog") + ").",
                    "RECURRING_ORDER",
                    order.getId()
            );
        } catch (Exception ex) {
            log.warn("Scheduler: notifikacija o neuspelom trajnom nalogu id={} nije poslata: {}",
                    order.getId(), ex.getMessage());
        }
    }

    private RecurringOrderDto toDto(RecurringOrder order) {
        Listing listing = listingRepository.findById(order.getListingId()).orElse(null);
        return toDto(order, listing);
    }

    /**
     * R1 818: overload koji prima vec-ucitan {@code listing} (iz batch IN upita u
     * {@link #listMy()}) da izbegne per-nalog findById (N+1). {@code listing} moze
     * biti null (obrisana hartija) → ticker "N/A".
     */
    private RecurringOrderDto toDto(RecurringOrder order, Listing listing) {
        String ticker = listing != null ? listing.getTicker() : "N/A";

        RecurringOrderDto dto = new RecurringOrderDto();
        dto.setId(order.getId());
        dto.setOwnerId(order.getOwnerId());
        dto.setOwnerType(order.getOwnerType());
        dto.setListingId(order.getListingId());
        dto.setListingTicker(ticker);
        dto.setDirection(order.getDirection());
        dto.setMode(order.getMode().toString());
        dto.setValue(order.getValue());
        dto.setAccountId(order.getAccountId());
        dto.setCadence(order.getCadence().toString());
        dto.setNextRun(order.getNextRun());
        dto.setActive(order.isActive());
        dto.setCreatedAt(order.getCreatedAt());
        dto.setUpdatedAt(order.getUpdatedAt());

        return dto;
    }
}
