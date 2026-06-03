package rs.raf.trading.otc.service;

import io.micrometer.core.instrument.Counter;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.otc.dto.CounterOtcOfferDto;
import rs.raf.trading.otc.dto.CreateOtcOfferDto;
import rs.raf.trading.otc.dto.OtcContractDto;
import rs.raf.trading.otc.dto.OtcListingDto;
import rs.raf.trading.otc.dto.OtcOfferDto;
import rs.raf.trading.otc.mapper.OtcMapper;
import rs.raf.trading.otc.model.OtcContract;
import rs.raf.trading.otc.model.OtcContractStatus;
import rs.raf.trading.otc.model.OtcOffer;
import rs.raf.trading.otc.model.OtcOfferStatus;
import rs.raf.trading.otc.repository.OtcContractRepository;
import rs.raf.trading.otc.repository.OtcOfferRepository;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.util.ListingCurrencyResolver;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Servis za OTC (Over-the-Counter) trgovinu unutar iste banke.
 *
 * Spec: Celina 4 - OTC Trgovina. Trguju se samo akcije koje je prodavac
 * prebacio na "javni rezim" (Portfolio.publicQuantity). Kupac i prodavac
 * pregovaraju o kolicini, strike ceni, premiji i settlementDate; kada
 * se dogovor postigne, kupac placa premiju i dobija opcioni ugovor.
 * Do settlementDate-a kupac moze iskoristiti ugovor (kupovina akcija po
 * strike ceni). Inace ugovor istice.
 *
 * Napomena: za generaciju 2024/25 radimo samo intra-bank OTC — nema SAGA
 * pattern-a jer obe strane imaju racune u istoj banci.
 *
 * <p><b>NAPOMENA (copy-first ekstrakcija, faza 2d-B — money-seam rewiring):</b>
 * monolitna verzija je direktno menjala {@code Account.balance} /
 * {@code Account.availableBalance} / {@code Account.reservedAmount} preko
 * {@code AccountRepository}. U trading-service-u racuni zive u banka-core
 * domenu, pa sve novcane noge idu kroz banka-core interni {@code /internal/**}
 * seam ({@link BankaCoreClient}):
 * <ul>
 *   <li>premija buyer→seller — {@code POST /internal/funds/transfer}</li>
 *   <li>rezervacija strike×qty kupcu — {@code POST /internal/funds/reserve}
 *       (handle se cuva na {@link OtcContract#getBankaCoreReservationId()})</li>
 *   <li>exercise — {@code commit} buyer rezervacije + {@code credit} prodavcu</li>
 *   <li>abandon/expire — {@code release} rezervacije</li>
 * </ul>
 * Rezervacija/transfer akcija prodavca diraju samo lokalni {@link Portfolio}
 * i kopirani su verbatim. FX racuna lokalni {@link CurrencyConversionService}
 * (kursevi poticu iz banka-core, pa su konzistentni). Idempotency kljucevi su
 * deterministicki po (operacija, offer/contract id) — retry replay-uje umesto
 * da dvaput naplati.
 */
@Slf4j
@Service
public class OtcService {

    private final OtcOfferRepository offerRepository;
    private final OtcContractRepository contractRepository;
    private final PortfolioRepository portfolioRepository;
    private final ListingRepository listingRepository;
    private final BankaCoreClient bankaCoreClient;
    private final CurrencyConversionService currencyConversionService;
    private final TradingUserResolver userResolver;

    private final NotificationService notificationService;

    // [B10 - Aja Timotic]: pozivamo recordEntry() iz counterOffer/declineOffer/acceptOffer
    private final OtcNegotiationHistoryService negotiationHistoryService;

    /**
     * W2-T1: counter koji broji finalizovane intra-bank OTC kontrakte
     * (inkrement u {@link #acceptOffer(Long, Long)} jer tamo se ugovor sklapa).
     * Inter-bank (SAGA) noga nije u trading-service-u (Tim 1 protokol radi
     * banka-core), pa {@code otcInterTotal} ostaje 0 dok ne dobijemo
     * inter-bank inbound finalize hook.
     */
    private final Counter otcIntraTotal;

    public OtcService(OtcOfferRepository offerRepository,
                      OtcContractRepository contractRepository,
                      PortfolioRepository portfolioRepository,
                      ListingRepository listingRepository,
                      BankaCoreClient bankaCoreClient,
                      CurrencyConversionService currencyConversionService,
                      TradingUserResolver userResolver,
                      NotificationService notificationService,
                      OtcNegotiationHistoryService negotiationHistoryService,
                      @Qualifier("otcIntraTotal") Counter otcIntraTotal) {
        this.offerRepository = offerRepository;
        this.contractRepository = contractRepository;
        this.portfolioRepository = portfolioRepository;
        this.listingRepository = listingRepository;
        this.bankaCoreClient = bankaCoreClient;
        this.currencyConversionService = currencyConversionService;
        this.userResolver = userResolver;
        this.notificationService = notificationService;
        this.negotiationHistoryService = negotiationHistoryService;
        this.otcIntraTotal = otcIntraTotal;
    }

    // ────────────────────────── Discovery ──────────────────────────

    /**
     * Lista akcija koje drugi korisnici trenutno nude na OTC — osnov za
     * "Portal: OTC Trgovina". Prikazuje se kolicina koja je JOS
     * raspolozi­va (publicQuantity - aktivna OTC rezervacija).
     */
    public List<OtcListingDto> listDiscoveryListings() {
        UserContext me = resolveCurrentUser();
        ensureOtcAccess(me);
        // P3 — Klijenti vide samo ponude klijenata, supervizori samo supervizora.
        // Spec Celina 4 (Nova) §822-826 + Celina 5 (Nova) §840-848.
        boolean meIsClient = UserRole.isClient(me.userRole());
        // P2-perf-nplus1-1 (R5 1898): DB-side filter (publicQuantity > 0) umesto
        // findAll() + in-memory filter — izbegava pun-table-scan nad celom
        // portfolios tabelom; materijalizuju se samo javne pozicije.
        List<Portfolio> publicPortfolios = portfolioRepository.findAllWithPublicQuantity().stream()
                .filter(p -> !(p.getUserId().equals(me.userId())
                        && me.userRole().equals(p.getUserRole())))
                .filter(p -> meIsClient ? UserRole.isClient(p.getUserRole())
                                        : UserRole.isEmployee(p.getUserRole()))
                .toList();

        // P2-perf-nplus1-1 (R5 1898): batch-resolve listinga jednim findAllById
        // umesto per-row findById (DB N+1). Distinct listingId set → jedan IN upit.
        List<Long> distinctListingIds = publicPortfolios.stream()
                .map(Portfolio::getListingId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        java.util.Map<Long, Listing> listingsById = listingRepository.findAllById(distinctListingIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(Listing::getId, l -> l));

        return publicPortfolios.stream()
                .map(p -> toListingDto(p, listingsById.get(p.getListingId())))
                .filter(dto -> dto != null && dto.getAvailablePublicQuantity() > 0)
                .sorted(Comparator.comparing(OtcListingDto::getListingTicker))
                .toList();
    }

    /**
     * Moje sopstvene javne akcije — portfolio item-i tekuceg korisnika
     * gde je publicQuantity > 0. Razliciti od {@link #listDiscoveryListings}
     * koji eksplicitno filtrira `me.userId()` (Discovery prikazuje samo tude
     * akcije za pravljenje ponuda). Ovaj endpoint daje user-u vidljivost
     * tome STA JE on objavio za druge — UX bag prijavljen 10.05.2026 vece-7
     * ("ne vidim svoje akcije da su javne").
     */
    public List<OtcListingDto> listMyPublicListings() {
        UserContext me = resolveCurrentUser();
        ensureOtcAccess(me);
        return portfolioRepository.findByUserIdAndUserRole(me.userId(), me.userRole()).stream()
                .filter(p -> p.getPublicQuantity() != null && p.getPublicQuantity() > 0)
                .map(this::toListingDto)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(OtcListingDto::getListingTicker))
                .toList();
    }

    // ────────────────────────── Offers ──────────────────────────

    public List<OtcOfferDto> listMyActiveOffers() {
        UserContext me = resolveCurrentUser();
        ensureOtcAccess(me);
        List<OtcOffer> offers = offerRepository.findActiveForUser(me.userId(), me.userRole());
        return offers.stream().map(o -> mapOffer(o, me.userId())).toList();
    }

    @Transactional
    public OtcOfferDto createOffer(CreateOtcOfferDto dto) {
        UserContext me = resolveCurrentUser();
        ensureOtcAccess(me);
        Listing listing = listingRepository.findById(dto.getListingId())
                .orElseThrow(() -> new EntityNotFoundException("Listing ne postoji: " + dto.getListingId()));
        if (listing.getListingType() != ListingType.STOCK) {
            throw new IllegalArgumentException("OTC je dozvoljen samo za akcije.");
        }
        ensureSettlementInFuture(dto.getSettlementDate());

        String sellerRole = resolveUserRole(dto.getSellerId());
        if (me.userId().equals(dto.getSellerId()) && me.userRole().equals(sellerRole)) {
            throw new IllegalArgumentException("Ne mozete napraviti OTC ponudu sami sebi.");
        }
        ensureSameRoleParticipants(me.userRole(), sellerRole);

        Portfolio sellerPortfolio = portfolioRepository
                .findByUserIdAndUserRole(dto.getSellerId(), sellerRole).stream()
                .filter(p -> p.getListingId().equals(listing.getId()))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException(
                        "Prodavac nema akcije " + listing.getTicker() + " u portfoliju."));

        int available = availablePublicQty(sellerPortfolio);
        if (available < dto.getQuantity()) {
            throw new IllegalArgumentException(
                    "Prodavac javno nudi " + available + " akcija, a ponuda trazi " + dto.getQuantity() + ".");
        }

        OtcOffer offer = new OtcOffer();
        offer.setBuyerId(me.userId());
        offer.setBuyerRole(me.userRole());
        offer.setSellerId(dto.getSellerId());
        offer.setSellerRole(sellerRole);
        offer.setListing(listing);
        offer.setQuantity(dto.getQuantity());
        offer.setPricePerStock(dto.getPricePerStock());
        offer.setPremium(dto.getPremium());
        offer.setSettlementDate(dto.getSettlementDate());
        offer.setLastModifiedById(me.userId());
        offer.setLastModifiedByName(resolveUserName(me.userId(), me.userRole()));
        offer.setWaitingOnUserId(dto.getSellerId());
        offer.setStatus(OtcOfferStatus.ACTIVE);

        return mapOffer(offerRepository.save(offer), me.userId());
    }

    @Transactional
    public OtcOfferDto counterOffer(Long offerId, CounterOtcOfferDto dto) {
        UserContext me = resolveCurrentUser();
        ensureOtcAccess(me);
        OtcOffer offer = loadActiveOfferForParticipant(offerId, me);
        ensureSameRoleParticipants(offer.getBuyerRole(), offer.getSellerRole());

        // R2-1339 (CORRECTNESS) — defense-in-depth pozitivna validacija. Kontroler
        // primenjuje @Valid (DTO ima @Min(1)/@Positive), ali servis se moze pozvati
        // direktno (drugi servis/test) → bez ove provere bi qty=0 / negativna
        // premija ili cena korumpirali ponudu (a kasniji accept bi kreirao
        // ugovor sa 0 akcija / 0 strike). Inter-bank wrapper vec ima @Positive;
        // intra ovde dobija ekvivalentnu service-level garanciju.
        validateCounterTerms(dto);

        boolean isBuyer = offer.getBuyerId().equals(me.userId())
                && offer.getBuyerRole().equals(me.userRole());
        boolean isSeller = offer.getSellerId().equals(me.userId())
                && offer.getSellerRole().equals(me.userRole());
        // R2-1339 — uklonjen `offer.getQuantity() > 0 &&` guard: stara verzija je
        // za korumpiranu ponudu (qty==0) PRESKAKALA participant check → ne-ucesnik
        // je mogao da posalje kontraponudu. Participant check sad vazi bezuslovno.
        // (loadActiveOfferForParticipant vec proverava ucesnistvo; ovo je dodatni
        // eksplicitan sloj jer counterOffer menja sadrzaj ponude.)
        if (!isBuyer && !isSeller) {
            throw new AccessDeniedException("Niste ucesnik u ovoj ponudi.");
        }

        // R2-1338 (CORRECTNESS/TURN-ORDER) — kontraponudu sme da posalje samo
        // ucesnik kome je RED (kome je upucena prethodna ponuda: waitingOnUserId).
        // Bez ove provere je strana koja je upravo poslala ponudu mogla odmah da
        // posalje jos jednu (spam/race) ili da pregazi sopstvenu ponudu. acceptOffer
        // vec ima isti turn-order guard; counterOffer ga je nedostajao.
        if (offer.getWaitingOnUserId() != null
                && !me.userId().equals(offer.getWaitingOnUserId())) {
            throw new IllegalStateException(
                    "Nije na vama red da odgovorite na ovu ponudu.");
        }

        // Prodavac mora i dalje da ima dovoljno javnih akcija za predlozenu kolicinu
        if (offer.getSellerId() != null) {
            Portfolio sp = portfolioRepository
                    .findByUserIdAndUserRole(offer.getSellerId(), offer.getSellerRole()).stream()
                    .filter(p -> p.getListingId().equals(offer.getListing().getId()))
                    .findFirst()
                    .orElseThrow(() -> new EntityNotFoundException("Prodavac vise nema ove akcije."));
            int avail = availablePublicQty(sp);
            if (avail < dto.getQuantity()) {
                throw new IllegalArgumentException(
                        "Prodavac javno nudi samo " + avail + " akcija.");
            }
        }

        ensureSettlementInFuture(dto.getSettlementDate());
        offer.setQuantity(dto.getQuantity());
        offer.setPricePerStock(dto.getPricePerStock());
        offer.setPremium(dto.getPremium());
        offer.setSettlementDate(dto.getSettlementDate());
        offer.setLastModifiedById(me.userId());
        offer.setLastModifiedByName(resolveUserName(me.userId(), me.userRole()));
        offer.setWaitingOnUserId(me.userId().equals(offer.getBuyerId())
                ? offer.getSellerId() : offer.getBuyerId());

        OtcOffer savedOffer = offerRepository.save(offer);

        // B10 — svaka kontraponuda ostavlja snimak u istoriji pregovora
        negotiationHistoryService.recordEntry(
                savedOffer.getId(),
                savedOffer.getQuantity(),
                savedOffer.getPricePerStock(),
                savedOffer.getPremium(),
                savedOffer.getSettlementDate(),
                savedOffer.getStatus().name(),
                me.userId(),
                resolveUserName(me.userId(), me.userRole()));

        try {
            String otherRole = savedOffer.getWaitingOnUserId().equals(savedOffer.getBuyerId())
                    ? savedOffer.getBuyerRole() : savedOffer.getSellerRole();
            notificationService.notify(
                    savedOffer.getWaitingOnUserId(),
                    otherRole,
                    NotificationType.OTC_COUNTER_OFFER,
                    "Nova kontraponuda",
                    "Primili ste novu kontraponudu za " + savedOffer.getListing().getTicker() + ".",
                    "OTC_OFFER",
                    savedOffer.getId()
            );
        } catch (Exception e) {
            log.warn("Failed to send OTC counter offer notification: {}", e.getMessage());
        }

        return mapOffer(savedOffer, me.userId());
    }

    @Transactional
    public OtcOfferDto declineOffer(Long offerId) {
        UserContext me = resolveCurrentUser();
        ensureOtcAccess(me);
        OtcOffer offer = loadActiveOfferForParticipant(offerId, me);
        offer.setStatus(OtcOfferStatus.DECLINED);
        offer.setLastModifiedById(me.userId());
        offer.setLastModifiedByName(resolveUserName(me.userId(), me.userRole()));
        OtcOffer savedOffer = offerRepository.save(offer);

        // B10 — snimi finalni DECLINED zapis kako bi istorija imala kraj
        negotiationHistoryService.recordEntry(
                savedOffer.getId(),
                savedOffer.getQuantity(),
                savedOffer.getPricePerStock(),
                savedOffer.getPremium(),
                savedOffer.getSettlementDate(),
                savedOffer.getStatus().name(),
                me.userId(),
                resolveUserName(me.userId(), me.userRole()));

        try {
            Long otherPartyId = me.userId().equals(savedOffer.getBuyerId())
                    ? savedOffer.getSellerId() : savedOffer.getBuyerId();
            String otherRole = me.userId().equals(savedOffer.getBuyerId())
                    ? savedOffer.getSellerRole() : savedOffer.getBuyerRole();
            notificationService.notify(
                    otherPartyId,
                    otherRole,
                    NotificationType.OTC_DECLINED,
                    "Ponuda odbijena",
                    "Vaša OTC ponuda za " + savedOffer.getListing().getTicker() + " je odbijena.",
                    "OTC_OFFER",
                    savedOffer.getId()
            );
        } catch (Exception e) {
            log.warn("Failed to send OTC declined notification: {}", e.getMessage());
        }

        return mapOffer(savedOffer, me.userId());
    }

    /**
     * Prihvatanje ponude — moguce je samo kad je {@code waitingOnUserId == me}.
     * Kreira opcioni ugovor, placa premiju prodavcu (sa eventualnom menjacnickom
     * konverzijom ako buyerAccount nije u valuti listinga).
     */
    @Transactional
    public OtcOfferDto acceptOffer(Long offerId, Long buyerAccountId) {
        UserContext me = resolveCurrentUser();
        ensureOtcAccess(me);
        OtcOffer offer = loadActiveOfferForParticipant(offerId, me);
        ensureSameRoleParticipants(offer.getBuyerRole(), offer.getSellerRole());

        if (!me.userId().equals(offer.getWaitingOnUserId())) {
            throw new IllegalStateException("Nije na vama red da odgovorite na ovu ponudu.");
        }

        // P2-authz-method-1 (R1 474) — PESSIMISTIC LOCK na seller portfolio red.
        // Ranije se seller portfolio citao plain {@code findByUserIdAndUserRole}
        // (bez locka), pa avail-check ({@code availablePublicQty = publicQuantity −
        // sumActiveReservedByListing}) i kasniji reservedQuantity++ (linija ~470)
        // nisu bili serijalizovani. Dva konkurentna {@code acceptOffer} za ponude
        // na ISTOM (seller, listing) bi oba procitala isti stale rezervisani-broj,
        // oba prosla {@code avail >= quantity}, oba kreirala ACTIVE ugovor →
        // OVER-COMMIT (prodavac obavezao vise akcija nego sto ima javno dostupno).
        // {@code findByUserIdAndUserRoleAndListingIdForUpdate} zakljucava red →
        // druga tx ceka prvu i u {@code sumActiveReservedByListing} vidi vec
        // commit-ovani prvi ugovor → odbija se. Isti lock se koristi i u
        // {@code releaseSellerReservation}.
        Portfolio sp = portfolioRepository
                .findByUserIdAndUserRoleAndListingIdForUpdate(
                        offer.getSellerId(), offer.getSellerRole(), offer.getListing().getId())
                .orElseThrow(() -> new EntityNotFoundException("Prodavac nema akcije za ovu ponudu."));
        int avail = availablePublicQty(sp);
        if (avail < offer.getQuantity()) {
            throw new IllegalArgumentException(
                    "Prodavac vise ne nudi dovoljno javnih akcija (preostalo: " + avail + ").");
        }

        // Iznos premije transferuje buyer -> seller. Buyer racun je izabran pri
        // prihvatanju; seller dobija na racun u valuti listinga (fallback: prvi aktivan).
        String listingCurrency = resolveListingCurrency(offer.getListing());
        InternalAccountDto buyerAccount = resolveBuyerAccount(
                offer.getBuyerId(), offer.getBuyerRole(), buyerAccountId, listingCurrency);
        InternalAccountDto sellerAccount = resolveSellerAccount(
                offer.getSellerId(), offer.getSellerRole(), listingCurrency);

        // Rezervacija sredstava kupcu (strike × qty u njegovoj valuti) + akcija prodavcu
        // — spec: pri sklapanju kupac je solventan, prodavac ne moze prodati istu hartiju
        // nekom drugom dok ugovor traje. Pri abandon-u se oslobadja, pri exercise-u trosi.
        BigDecimal strikeCostInListingCcy = offer.getPricePerStock()
                .multiply(BigDecimal.valueOf(offer.getQuantity()));
        String buyerCcy = buyerAccount.currencyCode();
        BigDecimal reservedInBuyerCcy;
        if (buyerCcy.equals(listingCurrency)) {
            reservedInBuyerCcy = strikeCostInListingCcy;
        } else {
            // Konverzija na buyer-ovu valutu po srednjem kursu (bez FX komisije —
            // komisija ce se naplatiti tek pri exercise-u, ne pri rezervaciji).
            reservedInBuyerCcy = currencyConversionService.convert(
                    strikeCostInListingCcy, listingCurrency, buyerCcy);
        }

        // P0-B6 (Nalaz 2) — ATOMICNOST: rezervacija sredstava MORA da prethodi
        // transferu premije. Ranije je premija isla PRVA, pa ako bi reserveFunds
        // vratio 409 (nedovoljno raspolozivih sredstava), premija bi vec bila
        // TRAJNO premestena kupac->prodavac a ugovor ne bi nastao → money-loss.
        // Sad: prvo rezervisemo (jeftino i bez novcanog kretanja — samo hold), pa
        // tek onda premestamo premiju. Ako premija padne posle uspesne rezervacije,
        // oslobadjamo rezervaciju (compensacija) tako da nijedan korak ne ostane
        // delimicno primenjen (conservation: ili se sve desi, ili nista).
        String reservationId;
        try {
            ReserveFundsResponse reserveResponse = bankaCoreClient.reserveFunds(
                    "otc-accept-" + offer.getId() + "-reserve",
                    new ReserveFundsRequest(buyerAccount.id(), reservedInBuyerCcy, buyerCcy));
            reservationId = reserveResponse.reservationId();
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 409) {
                throw new InsufficientFundsException(
                        "Nedovoljno raspolozivih sredstava za rezervaciju na racunu "
                                + buyerAccount.accountNumber() + " (" + buyerAccount.ownerName()
                                + "): potrebno " + reservedInBuyerCcy + " " + buyerCcy);
            }
            throw ex;
        }

        // Premija buyer -> seller. Ako padne (npr. nedovoljno za premiju POSLE
        // rezervacije, ili banka-core 5xx), oslobadjamo upravo kreiranu rezervaciju
        // pre nego sto propagiramo gresku — bez ovoga bi rezervacija ostala stranded
        // a ugovor nikad ne nastaje (obrnuti leak).
        try {
            transferPremium(offer.getId(), "premium", buyerAccount, sellerAccount,
                    offer.getPremium(), listingCurrency, UserRole.isClient(offer.getBuyerRole()));
        } catch (RuntimeException premiumFail) {
            try {
                bankaCoreClient.releaseFunds(
                        reservationId,
                        "otc-accept-" + offer.getId() + "-reserve-compensate",
                        new ReleaseFundsRequest(
                                "Oslobadjanje OTC rezervacije — transfer premije nije uspeo (offer #"
                                        + offer.getId() + ")"));
            } catch (RuntimeException releaseFail) {
                log.error("OTC accept #{}: transfer premije pao i kompenzacijski releaseFunds "
                                + "(reservationId={}) takodje pao: {}",
                        offer.getId(), reservationId, releaseFail.getMessage());
            }
            throw premiumFail;
        }

        // Rezervacija akcija prodavcu — povecava Portfolio.reservedQuantity
        sp.setReservedQuantity(sp.getReservedQuantity() + offer.getQuantity());
        portfolioRepository.save(sp);

        // Kreiraj ugovor
        OtcContract contract = new OtcContract();
        contract.setSourceOfferId(offer.getId());
        contract.setBuyerId(offer.getBuyerId());
        contract.setBuyerRole(offer.getBuyerRole());
        contract.setSellerId(offer.getSellerId());
        contract.setSellerRole(offer.getSellerRole());
        contract.setListing(offer.getListing());
        contract.setQuantity(offer.getQuantity());
        contract.setStrikePrice(offer.getPricePerStock());
        contract.setPremium(offer.getPremium());
        contract.setSettlementDate(offer.getSettlementDate());
        contract.setStatus(OtcContractStatus.ACTIVE);
        contract.setBuyerReservedAccountId(buyerAccount.id());
        contract.setBuyerReservedAmount(reservedInBuyerCcy);
        contract.setBankaCoreReservationId(reservationId);
        contractRepository.save(contract);

        offer.setStatus(OtcOfferStatus.ACCEPTED);
        offer.setLastModifiedById(me.userId());
        offer.setLastModifiedByName(resolveUserName(me.userId(), me.userRole()));
        offerRepository.save(offer);

        // B10 — snimi finalni ACCEPTED zapis u istoriji pregovora
        negotiationHistoryService.recordEntry(
                offer.getId(),
                offer.getQuantity(),
                offer.getPricePerStock(),
                offer.getPremium(),
                offer.getSettlementDate(),
                offer.getStatus().name(),
                me.userId(),
                resolveUserName(me.userId(), me.userRole()));

        // W2-T1: brojaj svaki uspesno sklopljen intra-bank OTC kontrakt
        // (poziva se posle save kontrakta i pre notification publish-a — Tx je vec commit).
        try {
            otcIntraTotal.increment();
        } catch (RuntimeException metricsEx) {
            log.warn("Failed to increment OTC intra counter for contract #{}: {}",
                    contract.getId(), metricsEx.getMessage());
        }

        log.info("OTC offer #{} accepted by {} — contract #{} created (rezervacija {})",
                offer.getId(), me.userId(), contract.getId(), reservationId);

        try {
            Long otherPartyId = me.userId().equals(contract.getBuyerId())
                    ? contract.getSellerId() : contract.getBuyerId();
            String otherRole = me.userId().equals(contract.getBuyerId())
                    ? contract.getSellerRole() : contract.getBuyerRole();
            notificationService.notify(
                    otherPartyId,
                    otherRole,
                    NotificationType.OTC_ACCEPTED,
                    "Ponuda prihvaćena",
                    "Vaša OTC ponuda za " + contract.getListing().getTicker() + " je prihvaćena i opcioni ugovor je sklopljen.",
                    "OTC_CONTRACT",
                    contract.getId()
            );
        } catch (Exception e) {
            log.warn("Failed to send OTC accepted notification: {}", e.getMessage());
        }

        return mapOffer(offer, me.userId());
    }

    // ────────────────────────── Contracts ──────────────────────────

    public List<OtcContractDto> listMyContracts(String statusFilter) {
        UserContext me = resolveCurrentUser();
        ensureOtcAccess(me);
        List<OtcContract> contracts;
        if (statusFilter == null || statusFilter.isBlank() || "ALL".equalsIgnoreCase(statusFilter)) {
            contracts = contractRepository.findAllForUser(me.userId(), me.userRole());
        } else {
            OtcContractStatus status;
            try {
                status = OtcContractStatus.valueOf(statusFilter.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Nepoznat status: " + statusFilter);
            }
            contracts = contractRepository.findByUserAndStatus(me.userId(), me.userRole(), status);
        }
        return contracts.stream().map(this::toContractDto).toList();
    }

    /**
     * Automatsko markiranje isteklih aktivnih ugovora kao EXPIRED.
     * Cist bookkeeping — publicQuantity prodavca se automatski vraca
     * jer availablePublicQty koristi samo ACTIVE ugovore.
     */
    @Transactional
    public int expireSettledContracts() {
        List<OtcContract> expired = contractRepository.findExpiredActive(LocalDate.now());
        for (OtcContract c : expired) {
            // Pre marking-a kao EXPIRED oslobodi rezervisana sredstva i akcije
            // (po spec-u: premija ostaje kod prodavca, ostalo se vraca)
            releaseBuyerReservation(c);
            releaseSellerReservation(c);
            c.setStatus(OtcContractStatus.EXPIRED);
            contractRepository.save(c);
        }
        return expired.size();
    }

    /**
     * Rucno odustajanje od ugovora od strane kupca. Spec Celina 4: opciono trgovinom
     * kupac stice PRAVO (ne obavezu) da kupi. Premija je vec placena pri accept-u i
     * NE VRACA SE — to je cena opcije. Ovaj endpoint zatvara ugovor pre settlement-a
     * (status=EXPIRED) tako da seller-ovi public available shares budu slobodni odmah.
     *
     * - Samo kupac moze odustati (premiju je on platio)
     * - Samo ACTIVE ugovori
     * - Nikakvi novcani transferi — premija ostaje kod prodavca (vec je placena)
     */
    @Transactional
    public OtcContractDto abandonContract(Long contractId) {
        UserContext me = resolveCurrentUser();
        ensureOtcAccess(me);
        OtcContract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new EntityNotFoundException("OTC ugovor ne postoji: " + contractId));
        if (!contract.getBuyerId().equals(me.userId())
                || !contract.getBuyerRole().equals(me.userRole())) {
            throw new AccessDeniedException("Samo kupac moze odustati od ugovora.");
        }
        if (contract.getStatus() != OtcContractStatus.ACTIVE) {
            throw new IllegalStateException("Ugovor nije aktivan (status=" + contract.getStatus() + ").");
        }

        // Oslobodi rezervisana sredstva kupcu (vraca u available)
        releaseBuyerReservation(contract);
        // Oslobodi rezervisane akcije prodavcu
        releaseSellerReservation(contract);

        contract.setStatus(OtcContractStatus.EXPIRED);
        contractRepository.save(contract);
        log.info("OTC contract #{} abandoned by buyer {} — premium {} {} NIJE vracena, rezervisana sredstva i akcije oslobodjeni.",
                contract.getId(), contract.getBuyerId(),
                contract.getPremium(), resolveListingCurrency(contract.getListing()));
        return toContractDto(contract);
    }

    /**
     * Oslobadja buyer-ovu rezervaciju (banka-core {@code /internal/funds/.../release}).
     * Idempotentno — banka-core release je idempotentan, plus preskace ako ugovor
     * nema {@code bankaCoreReservationId} (legacy).
     */
    private void releaseBuyerReservation(OtcContract contract) {
        String reservationId = contract.getBankaCoreReservationId();
        if (reservationId == null
                || contract.getBuyerReservedAmount() == null
                || contract.getBuyerReservedAmount().signum() <= 0) {
            return;
        }
        bankaCoreClient.releaseFunds(
                reservationId,
                "otc-release-" + contract.getId(),
                new ReleaseFundsRequest("Oslobadjanje OTC rezervacije za ugovor #" + contract.getId()));
    }

    /** Smanjuje seller portfolio.reservedQuantity za qty. Idempotentno. */
    private void releaseSellerReservation(OtcContract contract) {
        Portfolio sp = portfolioRepository
                .findByUserIdAndUserRoleAndListingIdForUpdate(
                        contract.getSellerId(), contract.getSellerRole(), contract.getListing().getId())
                .orElse(null);
        if (sp == null) return;
        int toRelease = Math.min(sp.getReservedQuantity(), contract.getQuantity());
        // R2 1412: Math.min stiti od negativne reservedQuantity, ali tiho maskira
        // drift — ako je rezervisana kolicina manja od kolicine ugovora, nesto je
        // vec oslobodilo deo rezervacije (dupli release / nekonzistentno stanje).
        // Logujemo WARN da drift ne ostane nevidljiv (umesto da se cisto proguta).
        if (sp.getReservedQuantity() < contract.getQuantity()) {
            log.warn("OTC contract #{} releaseSellerReservation drift: seller reservedQuantity={} < contract quantity={} "
                            + "(oslobadja se samo {}). Moguc dupli release ili nekonzistentna rezervacija.",
                    contract.getId(), sp.getReservedQuantity(), contract.getQuantity(), toRelease);
        }
        sp.setReservedQuantity(sp.getReservedQuantity() - toRelease);
        portfolioRepository.save(sp);
    }

    // ────────────────────────── Helpers ──────────────────────────

    /**
     * P1 — Spec Celina 4 (Nova) §145-148: OTC dozvoljen samo SUPERVIZORIMA
     * (od zaposlenih) i KLIJENTIMA. Agenti su eksplicitno iskljuceni.
     * Spring SecurityConfig vec hvata role; ovaj poziv je defense-in-depth
     * za slucaj da neko zaobidje filter (npr. test sa @WithMockUser).
     *
     * <p>R1 783 / R2 1443: logika je izdvojena u {@link OtcAccessPolicy}
     * (deljena sa {@code OtcExerciseSagaOrchestrator}, bez duplikacije).
     */
    private void ensureOtcAccess(UserContext user) {
        OtcAccessPolicy.ensureOtcAccess(user);
    }

    /**
     * P2 — Spec Celina 4 (Nova) §822-826: "Komuniciraju 2 klijenta ili 2 supervizora".
     * Klijent nikad ne sklapa ugovor sa supervizorom — strane moraju biti iste role.
     */
    private void ensureSameRoleParticipants(String roleA, String roleB) {
        boolean bothClients = UserRole.isClient(roleA) && UserRole.isClient(roleB);
        boolean bothEmployees = UserRole.isEmployee(roleA) && UserRole.isEmployee(roleB);
        if (!bothClients && !bothEmployees) {
            throw new IllegalArgumentException(
                    "OTC trgovina je dozvoljena samo izmedju ucesnika iste role "
                            + "(klijent-klijent ili supervizor-supervizor).");
        }
    }

    /**
     * R2-1339 — service-level pozitivna validacija kontraponude (paritet sa
     * {@code @Positive}/{@code @Min(1)} na DTO-u + inter-bank wrapper-om). Stiti
     * od korumpirane ponude (qty&lt;=0 / cena&lt;=0 / premija&lt;0) kad servis
     * pozove neko ko ne prolazi kroz {@code @Valid} kontroler.
     */
    private void validateCounterTerms(CounterOtcOfferDto dto) {
        if (dto.getQuantity() == null || dto.getQuantity() < 1) {
            throw new IllegalArgumentException("Kolicina mora biti najmanje 1.");
        }
        if (dto.getPricePerStock() == null || dto.getPricePerStock().signum() <= 0) {
            throw new IllegalArgumentException("Cena po akciji mora biti pozitivna.");
        }
        if (dto.getPremium() == null || dto.getPremium().signum() <= 0) {
            throw new IllegalArgumentException("Premija mora biti pozitivna.");
        }
    }

    private void ensureSettlementInFuture(LocalDate settlementDate) {
        if (settlementDate == null) {
            throw new IllegalArgumentException("Settlement datum je obavezan.");
        }
        if (!settlementDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException(
                    "Settlement datum mora biti u buducnosti (zadato: " + settlementDate + ").");
        }
    }

    private int availablePublicQty(Portfolio portfolio) {
        Integer publicQtyRaw = portfolio.getPublicQuantity();
        int publicQty = publicQtyRaw != null ? publicQtyRaw : 0;
        int reserved = contractRepository.sumActiveReservedByListing(
                portfolio.getUserId(), portfolio.getUserRole(), portfolio.getListingId());
        return Math.max(0, publicQty - reserved);
    }

    private OtcOffer loadActiveOfferForParticipant(Long offerId, UserContext me) {
        OtcOffer offer = offerRepository.findById(offerId)
                .orElseThrow(() -> new EntityNotFoundException("OTC ponuda ne postoji: " + offerId));
        boolean isBuyer = offer.getBuyerId().equals(me.userId())
                && offer.getBuyerRole().equals(me.userRole());
        boolean isSeller = offer.getSellerId().equals(me.userId())
                && offer.getSellerRole().equals(me.userRole());
        if (!isBuyer && !isSeller) {
            throw new AccessDeniedException("Niste ucesnik u ovoj ponudi.");
        }
        if (offer.getStatus() != OtcOfferStatus.ACTIVE) {
            throw new IllegalStateException("Ponuda vise nije aktivna (status=" + offer.getStatus() + ").");
        }
        return offer;
    }

    /**
     * Single-arg varijanta — per-row {@code findById}. Koristi je
     * {@code listMyPublicListings} (mali, sopstveni portfolio korisnika, bez N+1
     * pritiska). Discovery koristi batch-aware {@link #toListingDto(Portfolio, Listing)}.
     */
    private OtcListingDto toListingDto(Portfolio portfolio) {
        Listing listing = listingRepository.findById(portfolio.getListingId())
                .orElse(null);
        return toListingDto(portfolio, listing);
    }

    /**
     * P2-perf-nplus1-1 (R5 1898): batch-aware varijanta — listing je vec
     * razresen (iz {@code findAllById} mape), bez per-row DB lookup-a.
     */
    private OtcListingDto toListingDto(Portfolio portfolio, Listing listing) {
        if (listing == null) {
            return null;
        }
        String listingCurrency = resolveListingCurrency(listing);
        String sellerRole = resolveUserRole(portfolio.getUserId());
        return new OtcListingDto(
                portfolio.getId(),
                listing.getId(),
                listing.getTicker(),
                listing.getName(),
                listing.getExchangeAcronym(),
                listingCurrency,
                listing.getPrice(),
                portfolio.getPublicQuantity(),
                availablePublicQty(portfolio),
                portfolio.getUserId(),
                sellerRole,
                resolveUserName(portfolio.getUserId(), sellerRole));
    }

    private OtcOfferDto mapOffer(OtcOffer offer, Long viewerUserId) {
        String buyerName = resolveUserName(offer.getBuyerId(), offer.getBuyerRole());
        String sellerName = resolveUserName(offer.getSellerId(), offer.getSellerRole());
        String currency = resolveListingCurrency(offer.getListing());
        return OtcMapper.toDto(offer, buyerName, sellerName, currency, viewerUserId);
    }

    private OtcContractDto toContractDto(OtcContract contract) {
        String buyerName = resolveUserName(contract.getBuyerId(), contract.getBuyerRole());
        String sellerName = resolveUserName(contract.getSellerId(), contract.getSellerRole());
        String currency = resolveListingCurrency(contract.getListing());
        BigDecimal currentPrice = contract.getListing() != null ? contract.getListing().getPrice() : null;
        return OtcMapper.toDto(contract, buyerName, sellerName, currency, currentPrice);
    }

    /**
     * Prenos premije/strike troska {@code from} → {@code to} preko banka-core
     * {@code /internal/funds/transfer}.
     *
     * <p>NAPOMENA (faza 2d-B money-seam): u monolitu su balansi menjani direktno.
     * Ovde se FX matematika radi lokalno ({@link CurrencyConversionService}) —
     * verno monolitovom {@code transferPremium}: debit noga preko
     * {@code convertForPurchase} sa FX komisijom kad je kupac klijent, credit
     * noga preko {@code convert} (srednji kurs) — pa se tacni
     * {@code debitAmount}/{@code creditAmount}/{@code commission} prosledjuju
     * banka-core {@code transfer}-u koji je cross-currency-sposoban.
     */
    private void transferPremium(Long entityId, String op, InternalAccountDto from, InternalAccountDto to,
                                 BigDecimal amountInListingCurrency, String listingCurrency,
                                 boolean chargeFxCommission) {
        // Debit noga: iznos koji se skida sa from racuna (u njegovoj valuti) + FX komisija.
        BigDecimal debitAmount;
        BigDecimal fxCommission = BigDecimal.ZERO;
        String fromCcy = from.currencyCode();
        if (fromCcy.equals(listingCurrency)) {
            debitAmount = amountInListingCurrency;
        } else {
            CurrencyConversionService.ConversionResult conv = currencyConversionService
                    .convertForPurchase(amountInListingCurrency, listingCurrency, fromCcy, chargeFxCommission);
            debitAmount = conv.amount();
            fxCommission = conv.commission();
        }

        // Credit noga: iznos koji prodavac dobija (u svojoj valuti) po srednjem kursu.
        BigDecimal creditAmount;
        String toCcy = to.currencyCode();
        if (toCcy.equals(listingCurrency)) {
            creditAmount = amountInListingCurrency;
        } else {
            creditAmount = currencyConversionService.convert(amountInListingCurrency, listingCurrency, toCcy);
        }

        try {
            bankaCoreClient.transferFunds(
                    "otc-accept-" + entityId + "-" + op,
                    new TransferFundsRequest(from.id(), debitAmount, to.id(), creditAmount,
                            fxCommission.signum() > 0 ? fxCommission : null,
                            fxCommission.signum() > 0 ? fromCcy : null,
                            "OTC " + op + " — prenos sa racuna " + from.accountNumber()));
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 409) {
                // T4A-007: dodati ime vlasnika u poruku radi lakse orijentacije
                throw new InsufficientFundsException(
                        "Nedovoljno sredstava na racunu " + from.accountNumber()
                                + " (" + from.ownerName() + "): potrebno " + debitAmount + " " + fromCcy);
            }
            throw ex;
        }
    }

    /**
     * Razresava buyer-ov racun. Kontroler uvek prosledi {@code requestedAccountId}
     * (kupac je akter koji prihvata/iskoriscava); ako fali, koristi se podrazumevani
     * racun. Racuni zive u banka-core domenu — citaju se preko {@code getAccount}.
     */
    private InternalAccountDto resolveBuyerAccount(Long buyerId, String buyerRole,
                                                   Long requestedAccountId, String listingCurrency) {
        if (requestedAccountId != null) {
            InternalAccountDto account = getAccountOrThrow(requestedAccountId);
            verifyAccountOwnership(account, buyerId, buyerRole);
            return account;
        }
        return findDefaultAccount(buyerId, buyerRole, listingCurrency);
    }

    private InternalAccountDto resolveSellerAccount(Long sellerId, String sellerRole, String listingCurrency) {
        return findDefaultAccount(sellerId, sellerRole, listingCurrency);
    }

    /**
     * Podrazumevani racun korisnika u datoj valuti — verno monolitovom
     * {@code OtcService.findDefaultAccount}: za KLIJENTA klijentov preferiran
     * aktivan racun (racun u {@code preferredCurrency}, inace prvi aktivan sa
     * najvecim raspolozivim balansom), za ZAPOSLENOG bankin trading racun. Sva
     * logika izbora racuna zivi u banka-core domenu — razresava je interni
     * {@code GET /internal/accounts/preferred/{userRole}/{userId}} endpoint.
     */
    private InternalAccountDto findDefaultAccount(Long userId, String role, String preferredCurrency) {
        try {
            return bankaCoreClient.getPreferredAccount(role, userId, preferredCurrency);
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 404) {
                throw new EntityNotFoundException(
                        UserRole.isClient(role)
                                ? "Korisnik #" + userId + " nema aktivan racun."
                                : "Bankin racun u " + preferredCurrency + " ne postoji.");
            }
            throw ex;
        }
    }

    private void verifyAccountOwnership(InternalAccountDto account, Long userId, String role) {
        if (UserRole.isClient(role)) {
            if (account.ownerClientId() == null || !userId.equals(account.ownerClientId())) {
                throw new AccessDeniedException("Racun " + account.accountNumber()
                        + " ne pripada korisniku.");
            }
        }
        // Za EMPLOYEE — pretpostavka je da je racun bankin; ne proveravamo vlasnistvo striktno.
    }

    private InternalAccountDto getAccountOrThrow(Long accountId) {
        try {
            return bankaCoreClient.getAccount(accountId);
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 404) {
                throw new EntityNotFoundException("Racun ne postoji: " + accountId);
            }
            throw ex;
        }
    }

    private String resolveListingCurrency(Listing listing) {
        return ListingCurrencyResolver.resolve(listing);
    }

    private UserContext resolveCurrentUser() {
        return userResolver.resolveCurrent();
    }

    private String resolveUserName(Long userId, String role) {
        return userResolver.resolveName(userId, role);
    }

    private String resolveUserRole(Long userId) {
        return userResolver.resolveRole(userId);
    }
}
