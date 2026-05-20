package rs.raf.banka2_bek.interbank.wrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.interbank.config.InterbankProperties;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContract;
import rs.raf.banka2_bek.interbank.model.InterbankOtcContractStatus;
import rs.raf.banka2_bek.interbank.model.InterbankOtcNegotiation;
import rs.raf.banka2_bek.interbank.model.InterbankOtcNegotiationStatus;
import rs.raf.banka2_bek.interbank.model.InterbankPartyType;
import rs.raf.banka2_bek.interbank.protocol.Asset;
import rs.raf.banka2_bek.interbank.protocol.CurrencyCode;
import rs.raf.banka2_bek.interbank.protocol.ForeignBankId;
import rs.raf.banka2_bek.interbank.protocol.MonetaryAsset;
import rs.raf.banka2_bek.interbank.protocol.MonetaryValue;
import rs.raf.banka2_bek.interbank.protocol.OtcNegotiation;
import rs.raf.banka2_bek.interbank.protocol.OtcOffer;
import rs.raf.banka2_bek.interbank.protocol.Posting;
import rs.raf.banka2_bek.interbank.protocol.PublicStock;
import rs.raf.banka2_bek.interbank.protocol.StockDescription;
import rs.raf.banka2_bek.interbank.protocol.Transaction;
import rs.raf.banka2_bek.interbank.protocol.TxAccount;
import rs.raf.banka2_bek.interbank.protocol.UserInformation;
import rs.raf.banka2_bek.interbank.service.TransactionExecutorService;
import rs.raf.banka2.contracts.internal.InternalListingDto;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcContractRepository;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcNegotiationRepository;
import rs.raf.banka2_bek.interbank.service.OtcNegotiationService;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.CounterOtcInterbankOfferRequest;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.CreateOtcInterbankOfferRequest;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.OtcInterbankContract;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.OtcInterbankListing;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.OtcInterbankOffer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service za FE-facing OTC inter-bank rute. Orkestrira:
 * <ul>
 *   <li>Discovery — agregira public-stock liste sa svih partnera i mapira u DTO.</li>
 *   <li>Outbound pregovori (kad smo MI inicijatori, kao kupac):
 *       persist {@code InterbankOtcNegotiation} sa {@code localPartyType=BUYER},
 *       deleguje na {@link OtcNegotiationService} za HTTP poziv ka prodavcu.</li>
 *   <li>Listanje pregovora i ugovora za prikaz na FE-u.</li>
 *   <li>Exercise SAGA za inter-bank ugovor (sa nase strane kao kupac).</li>
 * </ul>
 *
 * <b>ID format:</b> {@code offerId} u FE-u je serijalizacija {@code "{rn}:{idString}"}
 * (npr. "222:abc-uuid"). Parsiramo ga preko {@link #parseForeignBankId(String)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterbankOtcWrapperService {

    private static final String CLIENT_ID_PREFIX = "C-";
    private static final String EMPLOYEE_ID_PREFIX = "E-";

    private final OtcNegotiationService negotiationService;
    private final InterbankProperties properties;
    private final InterbankOtcNegotiationRepository negotiationRepository;
    private final InterbankOtcContractRepository contractRepository;
    private final ClientRepository clientRepository;
    private final EmployeeRepository employeeRepository;
    private final TradingServiceInternalClient tradingServiceClient;
    private final AccountRepository accountRepository;
    private final TransactionExecutorService transactionExecutor;

    /** Cache imena partner banaka po routing number-u (resolveUserName izlaz). */
    private final Map<String, UserInformation> userInfoCache = new ConcurrentHashMap<>();

    // ─── Discovery ────────────────────────────────────────────────────────────

    // NOTE: NE koristimo @Transactional na ovoj metodi — radi HTTP pozive ka
    // partner bankama (RestClient) koji mogu da bace RuntimeException ako
    // partner nije dostupan. @Transactional + uhvacen RuntimeException ostavlja
    // transakciju u "rollback-only" stanju, pa Spring TransactionManager kasnije
    // baci UnexpectedRollbackException ("Transaction silently rolled back")
    // i vrati klijentu 400. Bez @Transactional, catch radi gracefully — vraca
    // listings od partnera koje SU dostupne, ignorise ostale.
    public List<OtcInterbankListing> listRemoteListings() {
        List<OtcInterbankListing> result = new ArrayList<>();
        for (InterbankProperties.PartnerBank partner : properties.getPartners()) {
            if (partner.getRoutingNumber() == null) continue;
            int rn = partner.getRoutingNumber();
            String bankCode = "RN-" + rn;
            try {
                List<PublicStock> stocks = negotiationService.fetchRemotePublicStocks(rn);
                for (PublicStock ps : stocks) {
                    Optional<InternalListingDto> localListing =
                            tradingServiceClient.findListingByTicker(ps.stock().ticker());
                    String listingName = localListing.map(InternalListingDto::name)
                            .orElse(ps.stock().ticker());
                    String currency = localListing.map(l -> l.quoteCurrency() != null
                            ? l.quoteCurrency()
                            : (l.baseCurrency() != null ? l.baseCurrency() : "USD"))
                            .orElse("USD");
                    BigDecimal currentPrice = localListing.map(InternalListingDto::price)
                            .map(price -> price != null ? price : BigDecimal.ZERO)
                            .orElse(BigDecimal.ZERO);

                    for (PublicStock.Seller seller : ps.sellers()) {
                        String role = inferRole(seller.seller().id());
                        result.add(new OtcInterbankListing(
                                bankCode,
                                seller.seller().id(),
                                resolvePartnerUserName(seller.seller(), partner),
                                ps.stock().ticker(),
                                listingName,
                                currency,
                                currentPrice,
                                seller.amount(),
                                role
                        ));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Ne mogu da dohvatim listings od partnera {} ({}): {}",
                        partner.getDisplayName(), rn, e.getMessage());
            }
        }
        return result;
    }

    // ─── Pregovori (mi smo BUYER — outbound flow) ─────────────────────────────

    @Transactional
    public OtcInterbankOffer createOffer(CreateOtcInterbankOfferRequest request, Long buyerUserId, String buyerUserRole) {
        int myRouting = requireMyRoutingNumber();
        int sellerRouting = parseRoutingFromBankCode(request.sellerBankCode());
        if (sellerRouting == myRouting) {
            throw new IllegalArgumentException("sellerBankCode mora biti partner banka, ne nasa");
        }

        // M-3 fix: §2.7.2 zahteva ceo broj akcija. Validacija pre HTTP poziva
        // ka partneru (inace bi vec poslali nevalidan payload).
        validateIntegerAmount(request.quantity());

        InternalListingDto listing = tradingServiceClient.findListingByTicker(request.listingTicker())
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Ticker " + request.listingTicker() + " ne postoji u nasem listings-u"));
        String currency = listing.quoteCurrency() != null
                ? listing.quoteCurrency()
                : (listing.baseCurrency() != null ? listing.baseCurrency() : "USD");

        ForeignBankId buyerId = new ForeignBankId(myRouting, prefixedId(buyerUserId, buyerUserRole));
        ForeignBankId sellerId = new ForeignBankId(sellerRouting, request.sellerUserId());

        // M-2 fix: cuvamo full OffsetDateTime od ulaza ka partneru i u nasoj
        // bazi. Settlement koji DTO daje (LocalDate) tretiramo kao start-of-day
        // UTC — to je jedini smisao "datum"-only u kontekstu spec §2.4.
        OffsetDateTime settlement = request.settlementDate().atStartOfDay().atOffset(ZoneOffset.UTC);

        OtcOffer outboundOffer = new OtcOffer(
                new StockDescription(request.listingTicker()),
                settlement,
                new MonetaryValue(safeCurrency(currency), request.pricePerStock()),
                new MonetaryValue(safeCurrency(currency), request.premium()),
                buyerId, sellerId, request.quantity(),
                buyerId // buyer je inicijator (lastModifiedBy = buyerId po §3.2)
        );

        // BE -> partner banka (POST /negotiations) — vraca foreignNegotiationId.
        ForeignBankId foreignId = negotiationService.createNegotiation(outboundOffer);

        // Persist lokalno (kao BUYER kopiju).
        InterbankOtcNegotiation entity = new InterbankOtcNegotiation();
        entity.setForeignNegotiationRoutingNumber(foreignId.routingNumber());
        entity.setForeignNegotiationIdString(foreignId.id());
        entity.setLocalPartyType(InterbankPartyType.BUYER);
        entity.setLocalPartyId(buyerUserId);
        entity.setLocalPartyRole(buyerUserRole);
        entity.setForeignPartyRoutingNumber(sellerId.routingNumber());
        entity.setForeignPartyIdString(sellerId.id());
        entity.setTicker(request.listingTicker());
        entity.setAmount(request.quantity());
        entity.setPricePerUnit(request.pricePerStock());
        entity.setPriceCurrency(currency);
        entity.setPremium(request.premium());
        entity.setPremiumCurrency(currency);
        entity.setSettlementDate(settlement);
        entity.setLastModifiedByRoutingNumber(myRouting);
        entity.setLastModifiedByIdString(buyerId.id());
        entity.setOngoing(true);
        entity.setStatus(InterbankOtcNegotiationStatus.ACTIVE);

        negotiationRepository.save(entity);
        return mapNegotiationToDto(entity);
    }

    // NOTE: NE koristimo @Transactional na ovoj metodi — pull-sync radi outbound
    // HTTP GET ka partner bankama (autoritativne kopije). Iste razloge kao u
    // listRemoteListings (vidi komentar gore): RuntimeException pod @Transactional
    // ostavlja Tx u rollback-only stanju, pa Spring vraca 400 cak i ako su lokalni
    // save-ovi prosli. Bez Tx ovde, svaki repository.save() otvara per-call Tx
    // (Spring Data JPA default), a partner-unreachable se gracefully logguje.
    public List<OtcInterbankOffer> listMyOffers(Long userId, String userRole) {
        List<InterbankOtcNegotiation> mine = negotiationRepository
                .findByLocalPartyIdAndLocalPartyRoleAndStatus(userId, userRole, InterbankOtcNegotiationStatus.ACTIVE);

        // T2-I fix (Tim 1 cross-bank Stage C mirror sync, 2026-05-20):
        // pull-sync sa autoritativne banke pre nego sto mapiramo DTO-e. Bez ovog
        // mirror moze biti stale kad partner uradi counter/decline/accept jer
        // Tim 1 (i nas seller-strana) koristi pull model — bez outbound push na
        // autoritativni update. Posledica je da FE pokazuje staru poruku ("ceka
        // na Vas") iako je u stvarnosti partner vec odgovorio. Spec §3.4 jeste
        // namenjena upravo ovome (read current state), pa je GET legitiman pre-list.
        //
        // Pull samo za pregovore gde je partner autoritativni
        // (foreignNegotiationRoutingNumber != myRouting). Pregovori autoritativni
        // kod nas su izvor istine — nema sta da sinhronizujemo. Pull se izvodi
        // best-effort; ako partner ne odgovori, fall-back na stale local mirror
        // sa WARN log-om (graceful degradation, FE i dalje dobija nesto).
        int myRouting = requireMyRoutingNumber();
        for (InterbankOtcNegotiation n : mine) {
            if (n.getForeignNegotiationRoutingNumber() == null
                    || n.getForeignNegotiationRoutingNumber() == myRouting) {
                continue; // autoritativni kod nas — mirror je izvor istine
            }
            ForeignBankId foreignId = new ForeignBankId(
                    n.getForeignNegotiationRoutingNumber(),
                    n.getForeignNegotiationIdString());
            try {
                OtcNegotiation remote = negotiationService.readNegotiation(foreignId);
                applyRemoteSnapshot(n, remote);
            } catch (RuntimeException e) {
                log.warn("T2-I pull-sync pregovora {} nije uspeo (zadrzavamo stale mirror): {}",
                        foreignId, e.getMessage());
            }
        }

        List<OtcInterbankOffer> result = new ArrayList<>(mine.size());
        for (InterbankOtcNegotiation n : mine) {
            result.add(mapNegotiationToDto(n));
        }
        return result;
    }

    /**
     * T2-I helper: primeni svezi snapshot iz autoritativne banke na lokalnu
     * mirror kopiju. Salje update kroz {@code repository.save()} koji otvara
     * per-call Tx (zato pozivajuca metoda ne mora biti @Transactional).
     *
     * <p>Ako daljinski {@code isOngoing == false} a nas mirror je jos ACTIVE,
     * to znaci da je partner zatvorio pregovor (DELETE §3.5) ili je on prihvacen
     * (§3.6). Razliku ne mozemo da znamo iz §3.4 response-a sam (vraca samo
     * {@code isOngoing}), pa najsigurnije markiramo kao CLOSED — accept flow
     * ide kroz {@link OtcNegotiationService#acceptReceivedNegotiation} koji
     * eksplicitno postavlja ACCEPTED, tako da je CLOSED ovde "ne-accept zatvaranje".
     */
    private void applyRemoteSnapshot(InterbankOtcNegotiation entity, OtcNegotiation remote) {
        if (remote == null) return;
        entity.setAmount(remote.amount());
        if (remote.pricePerUnit() != null) {
            entity.setPricePerUnit(remote.pricePerUnit().amount());
            if (remote.pricePerUnit().currency() != null) {
                entity.setPriceCurrency(remote.pricePerUnit().currency().name());
            }
        }
        if (remote.premium() != null) {
            entity.setPremium(remote.premium().amount());
            if (remote.premium().currency() != null) {
                entity.setPremiumCurrency(remote.premium().currency().name());
            }
        }
        if (remote.settlementDate() != null) {
            entity.setSettlementDate(remote.settlementDate());
        }
        if (remote.lastModifiedBy() != null) {
            entity.setLastModifiedByRoutingNumber(remote.lastModifiedBy().routingNumber());
            entity.setLastModifiedByIdString(remote.lastModifiedBy().id());
        }
        if (!remote.isOngoing() && entity.isOngoing()) {
            entity.setOngoing(false);
            if (entity.getStatus() == InterbankOtcNegotiationStatus.ACTIVE) {
                entity.setStatus(InterbankOtcNegotiationStatus.CLOSED);
            }
        }
        negotiationRepository.save(entity);
    }

    @Transactional
    public OtcInterbankOffer counterOffer(String offerId, CounterOtcInterbankOfferRequest request,
                                          Long userId, String userRole) {
        ForeignBankId foreignId = parseForeignBankId(offerId);
        InterbankOtcNegotiation entity = lookupOrThrow(foreignId);
        ensureMyParty(entity, userId, userRole);

        // M-3: counter-offer amount mora takodje biti ceo broj.
        validateIntegerAmount(request.quantity());

        int myRouting = requireMyRoutingNumber();
        ForeignBankId myParty = new ForeignBankId(myRouting, prefixedId(userId, userRole));
        ForeignBankId otherParty = new ForeignBankId(
                entity.getForeignPartyRoutingNumber(), entity.getForeignPartyIdString());

        ForeignBankId buyerId = entity.getLocalPartyType() == InterbankPartyType.BUYER ? myParty : otherParty;
        ForeignBankId sellerId = entity.getLocalPartyType() == InterbankPartyType.SELLER ? myParty : otherParty;

        CurrencyCode ccy = CurrencyCode.valueOf(entity.getPriceCurrency());

        // M-2 fix: full OffsetDateTime sa TZ.
        OffsetDateTime settlement = request.settlementDate().atStartOfDay().atOffset(ZoneOffset.UTC);

        OtcOffer outbound = new OtcOffer(
                new StockDescription(entity.getTicker()),
                settlement,
                new MonetaryValue(ccy, request.pricePerStock()),
                new MonetaryValue(ccy, request.premium()),
                buyerId, sellerId, request.quantity(),
                myParty
        );

        // Lokalni update entiteta (ovo radimo uvek — mi cuvamo mirror ili autoritativnu kopiju).
        entity.setAmount(request.quantity());
        entity.setPricePerUnit(request.pricePerStock());
        entity.setPremium(request.premium());
        entity.setSettlementDate(settlement);
        entity.setLastModifiedByRoutingNumber(myRouting);
        entity.setLastModifiedByIdString(myParty.id());
        negotiationRepository.save(entity);

        // T2-J fix (Tim 1 cross-bank Stage C, 2026-05-20): outbound PUT counter UVEK
        // ide ka partnerovoj banci — bez obzira ko je autoritativni. Razlikujemo
        // SAMO "kome saljemo HTTP" (target partner routing) od "vlasnika {rn,id}"
        // (URL path):
        //
        //   1) Mi NE-autoritativni (foreignId.rn != myRouting): partner = autoritativni
        //      = foreignId.rn. URL: /negotiations/{foreignId.rn}/{foreignId.id}.
        //      Spec §3.3 default — BUYER counter na SELLER-authoritative.
        //
        //   2) Mi autoritativni (foreignId.rn == myRouting): partner = foreignParty
        //      (kontra strana). URL i dalje /negotiations/{myRouting}/{id} jer Tim 1
        //      prepoznaje da je {222, negId} njihov mirror kljuc. T2-H je gresio
        //      sto je SKIPOVAO ovaj slucaj — partner mirror nije dobijao update.
        //      T2-J ga propusta ka pravom partner routing-u.
        int targetPartnerRouting = (foreignId.routingNumber() == myRouting)
                ? entity.getForeignPartyRoutingNumber()
                : foreignId.routingNumber();
        negotiationService.postCounterOffer(foreignId, targetPartnerRouting, outbound);
        return mapNegotiationToDto(entity);
    }

    @Transactional
    public OtcInterbankOffer declineOffer(String offerId, Long userId, String userRole) {
        ForeignBankId foreignId = parseForeignBankId(offerId);
        InterbankOtcNegotiation entity = lookupOrThrow(foreignId);
        ensureMyParty(entity, userId, userRole);

        // Local close + (uslovni) DELETE outbound.
        entity.setOngoing(false);
        entity.setStatus(InterbankOtcNegotiationStatus.DECLINED);
        negotiationRepository.save(entity);

        // T2-J mirror (zamena za T2-H skip): outbound DELETE UVEK ide ka partneru —
        // izbor target routing-a:
        //   1) Mi NE-autoritativni: partner = foreignId.rn (autoritativni vlasnik).
        //   2) Mi autoritativni: partner = foreignParty (kontra strana); URL path
        //      i dalje /negotiations/{myRouting}/{id} (partner mirror kljuc).
        // Bez T2-J, partner nikad ne sazna za nas decline na nase-autoritativni
        // pregovor (T2-H je SKIPOVAO outbound). Close je i dalje idempotentan:
        // RuntimeException se logguje WARN-om i ne ruzimo lokalno azuriranje.
        int myRouting = requireMyRoutingNumber();
        int targetPartnerRouting = (foreignId.routingNumber() == myRouting)
                ? entity.getForeignPartyRoutingNumber()
                : foreignId.routingNumber();
        try {
            negotiationService.closeNegotiation(foreignId, targetPartnerRouting);
        } catch (RuntimeException e) {
            log.warn("Outbound DELETE pregovora {} (target partner {}) nije uspelo: {}",
                    foreignId, targetPartnerRouting, e.getMessage());
        }
        return mapNegotiationToDto(entity);
    }

    @Transactional
    public OtcInterbankOffer acceptOffer(String offerId, Long buyerAccountId, Long userId, String userRole) {
        ForeignBankId foreignId = parseForeignBankId(offerId);
        InterbankOtcNegotiation entity = lookupOrThrow(foreignId);
        ensureMyParty(entity, userId, userRole);

        if (entity.getLocalPartyType() != InterbankPartyType.BUYER) {
            // T2-G (Stage C UX polish, 2026-05-20): user-friendly poruka. Per
            // inter-bank protokol §3.6, accept moze samo kupac — premium debit
            // ide sa kupcevog racuna pa coordinator runs na buyer-strani.
            // Prodavac u inter-bank pregovoru moze samo: kontra-ponudu (PUT)
            // ili odbijanje (DELETE).
            throw new InterbankExceptions.InterbankProtocolException(
                    "Inter-bank ponudu prihvata kupac. Vi ste prodavac u ovom "
                            + "pregovoru — mozete poslati kontra-ponudu ili odbiti ponudu.");
        }

        // Outbound GET .../accept — sinhrono ceka da prodavceva banka commit-uje 2PC.
        // Dok ceka, ovde nemamo nista da uradimo — partnerova banka kreira opciju
        // na svojoj strani i salje COMMIT_TX nama (handleCommitTx).
        negotiationService.acceptOffer(foreignId);

        entity.setStatus(InterbankOtcNegotiationStatus.ACCEPTED);
        entity.setOngoing(false);
        negotiationRepository.save(entity);

        // Posle accept-a contract postoji na obema stranama (kreiraju se kroz 2PC commit
        // u TransactionExecutorService.commitLocal koji handluje Asset.OptionAsset postings).
        return mapNegotiationToDto(entity);
    }

    // ─── Ugovori ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OtcInterbankContract> listMyContracts(Long userId, String userRole, String statusFilter) {
        List<InterbankOtcContract> all = contractRepository.findByLocalPartyIdAndLocalPartyRole(userId, userRole);
        List<OtcInterbankContract> result = new ArrayList<>(all.size());
        for (InterbankOtcContract c : all) {
            if (statusFilter != null && !"ALL".equalsIgnoreCase(statusFilter)) {
                if (!c.getStatus().name().equalsIgnoreCase(statusFilter)) continue;
            }
            result.add(mapContractToDto(c));
        }
        return result;
    }

    /**
     * Exercise inter-bank OTC ugovor sa nase (kupceve) strane (§2.7.2).
     * <p>
     * <b>C-2 fix po Celini 5 audit-u:</b> ranije je metod samo postavljao
     * status=EXERCISED lokalno bez ikakve 2PC interakcije sa prodavcevom
     * bankom — novac i akcije se nikad nisu pravo prebacili. Sad formiramo
     * 4-posting transakciju po spec §2.7.2 i prosledjujemo je
     * {@link TransactionExecutorService#execute}-u (postojeci 2PC seam).
     * <p>
     * 4-posting exercise transakcija (lokalna sign konvencija: pozitivno =
     * debit/povecava, negativno = kredit/smanjuje):
     * <pre>
     *   Option pseudo-account  debit  pi*k MONAS    (option ac receives money)
     *   Buyer (Person)         credit pi*k MONAS    (buyer pays)
     *   Option pseudo-account  credit k STOCK       (option ac gives stocks)
     *   Buyer (Person)         debit  k STOCK       (buyer receives stocks)
     * </pre>
     * Po §2.7.2 option pseudo-account je u prodavcevoj banci, koja je u 2PC
     * recipient (prima NEW_TX/COMMIT_TX/ROLLBACK_TX). 2PC commit ce u
     * prodavcevoj banci da debituje rezervisane hartije + obrise reservaciju.
     * U nasoj banci, commitLocal handluje Stock+Option posting kao "exercise
     * marker" i postavlja contract.status=EXERCISED.
     * <p>
     * Pre-validacija (po §2.7.2 zadnji paragraf + uobicajenoj 2PC predigri):
     * <ul>
     *   <li>status mora biti ACTIVE</li>
     *   <li>settlementDate mora biti u buducnosti (UTC)</li>
     *   <li>pozivac mora biti buyer i mora vlasnistvo nad ugovorom</li>
     *   <li>buyer mora odabrati validan racun u strike valuti sa dovoljnim saldom</li>
     * </ul>
     */
    public OtcInterbankContract exerciseContract(String contractIdStr, Long buyerAccountId,
                                                 Long userId, String userRole) {
        Long contractId;
        try {
            contractId = Long.parseLong(contractIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("contractId mora biti broj");
        }

        InterbankOtcContract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Ugovor " + contractId + " ne postoji"));

        // C-2 — sve preconditije -> 409 Conflict (ne 400). Payload je validan,
        // ali stanje resursa konfliktuje sa exercise zahtevom.
        if (!contract.getLocalPartyId().equals(userId) || !contract.getLocalPartyRole().equalsIgnoreCase(userRole)) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Ugovor ne pripada trenutno autentifikovanom korisniku");
        }
        if (contract.getLocalPartyType() != InterbankPartyType.BUYER) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Exercise inicira kupac; mi smo SELLER u ovom ugovoru");
        }
        if (contract.getStatus() != InterbankOtcContractStatus.ACTIVE) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Ugovor nije ACTIVE (trenutno: " + contract.getStatus() + ")");
        }
        // §2.7.2 last paragraph: "if that option was not used, the resources
        // stuck in an option shall be un-reserved". Posle settlement-a exercise
        // nije izvrsiv. UTC compare da izbegnemo TZ edge case izmedju banki.
        if (!contract.getSettlementDate().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Ugovor je istekao (settlement: " + contract.getSettlementDate() + ")");
        }

        if (buyerAccountId == null) {
            throw new IllegalArgumentException("buyerAccountId je obavezan za exercise");
        }
        Account buyerAccount = accountRepository.findById(buyerAccountId)
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Racun " + buyerAccountId + " ne postoji"));
        if (buyerAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Racun " + buyerAccount.getAccountNumber() + " nije aktivan");
        }
        if (buyerAccount.getClient() == null
                || !buyerAccount.getClient().getId().equals(userId)
                || !"CLIENT".equalsIgnoreCase(userRole)) {
            // Note: u trenutnoj wrapperu samo CLIENT kao buyer ima Account vlasnistvo
            // (EMPLOYEE/ADMIN buyer-i nisu jos podrzani — vidi resolveLocalAccount).
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Racun " + buyerAccount.getAccountNumber() + " ne pripada korisniku");
        }
        if (!buyerAccount.getCurrency().getCode().equalsIgnoreCase(contract.getStrikeCurrency())) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Racun " + buyerAccount.getAccountNumber() + " nije u valuti "
                            + contract.getStrikeCurrency());
        }

        BigDecimal totalCost = contract.getStrikePrice().multiply(contract.getQuantity());
        if (buyerAccount.getAvailableBalance().compareTo(totalCost) < 0) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Nedovoljno sredstava na racunu " + buyerAccount.getAccountNumber()
                            + " za exercise (potrebno: " + totalCost + " " + contract.getStrikeCurrency() + ")");
        }

        // Formiraj exercise transakciju per §2.7.2.
        int myRouting = requireMyRoutingNumber();
        ForeignBankId negotiationId = new ForeignBankId(
                contract.getForeignPartyRoutingNumber() /* prodavceva banka — autoritativni vlasnik */,
                resolveSourceNegotiationIdString(contract));
        ForeignBankId buyerForeign = new ForeignBankId(myRouting, prefixedId(userId, userRole));

        CurrencyCode strikeCcy = CurrencyCode.valueOf(contract.getStrikeCurrency());
        Asset monasAsset = new Asset.Monas(new MonetaryAsset(strikeCcy));
        Asset stockAsset = new Asset.Stock(new StockDescription(contract.getTicker()));

        BigDecimal qty = contract.getQuantity();
        BigDecimal money = totalCost;

        // §2.7.2 spec wording:
        //   "Debit option pseudo-account for pi*k" — option ac increases money → +pi*k
        //   "Credit the buyer for pi*k"            — buyer decreases money    → -pi*k
        //   "Credit option pseudo-account for k stocks" — option ac decreases stocks → -k
        //   "Debit relevant receiving accounts for k assets" — buyer receives stocks → +k
        Posting p1 = new Posting(new TxAccount.Option(negotiationId), money, monasAsset);
        Posting p2 = new Posting(new TxAccount.Account(buyerAccount.getAccountNumber()), money.negate(), monasAsset);
        Posting p3 = new Posting(new TxAccount.Option(negotiationId), qty.negate(), stockAsset);
        Posting p4 = new Posting(new TxAccount.Person(buyerForeign), qty, stockAsset);

        Transaction tx = transactionExecutor.formTransaction(
                List.of(p1, p2, p3, p4),
                "OTC exercise option " + negotiationId,
                null, "OTC-EX", "Iskoriscavanje OTC opcionog ugovora"
        );

        // Pozovi 2PC OUT-OF-TX (execute koordinira svoju per-fazu Tx). Ako padne,
        // exception se propagira — contract ostaje ACTIVE i kupac moze da retry-uje.
        // Na uspesan COMMIT_TX, commitLocal heuristika (vidi TransactionExecutorService)
        // detektuje Stock+Option posting i postavlja contract.status=EXERCISED.
        transactionExecutor.execute(tx);

        // Posle uspesnog 2PC ucitaj contract sveze (commitLocal je vec save-ovao).
        InterbankOtcContract refreshed = contractRepository.findById(contractId).orElse(contract);
        log.info("OTC inter-bank contract {} exercised (buyerAccount={})", contractId, buyerAccountId);
        return mapContractToDto(refreshed);
    }

    /**
     * Resolve {@code foreignNegotiationIdString} pregovora iz kog je ugovor nastao —
     * to je {@code id} dela {@code ForeignBankId} koji ide u option pseudo-account
     * (§2.7.2: option pseudo-account je u prodavcevoj banci sa negotiationId-em).
     */
    private String resolveSourceNegotiationIdString(InterbankOtcContract contract) {
        InterbankOtcNegotiation neg = negotiationRepository.findById(contract.getSourceNegotiationId())
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Pregovor " + contract.getSourceNegotiationId() + " ne postoji za ugovor "
                                + contract.getId()));
        return neg.getForeignNegotiationIdString();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private int requireMyRoutingNumber() {
        Integer my = properties.getMyRoutingNumber();
        if (my == null) {
            throw new InterbankExceptions.InterbankException(
                    "interbank.my-routing-number nije konfigurisan");
        }
        return my;
    }

    private static int parseRoutingFromBankCode(String bankCode) {
        if (bankCode == null) {
            throw new IllegalArgumentException("bankCode je obavezan");
        }
        String s = bankCode.startsWith("RN-") ? bankCode.substring(3) : bankCode;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Nevalidan bankCode: " + bankCode);
        }
    }

    private static String prefixedId(Long userId, String userRole) {
        return ("CLIENT".equalsIgnoreCase(userRole) ? CLIENT_ID_PREFIX : EMPLOYEE_ID_PREFIX) + userId;
    }

    private static String inferRole(String prefixedId) {
        if (prefixedId == null) return null;
        if (prefixedId.startsWith(CLIENT_ID_PREFIX)) return "CLIENT";
        if (prefixedId.startsWith(EMPLOYEE_ID_PREFIX)) return "EMPLOYEE";
        return null;
    }

    private static CurrencyCode safeCurrency(String code) {
        try {
            return CurrencyCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return CurrencyCode.USD;
        }
    }

    private static ForeignBankId parseForeignBankId(String offerId) {
        if (offerId == null || !offerId.contains(":")) {
            throw new IllegalArgumentException("offerId mora biti formata '{routingNumber}:{idString}'");
        }
        String[] parts = offerId.split(":", 2);
        try {
            return new ForeignBankId(Integer.parseInt(parts[0]), parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("offerId routingNumber nije broj: " + parts[0]);
        }
    }

    private InterbankOtcNegotiation lookupOrThrow(ForeignBankId foreignId) {
        return negotiationRepository
                .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                        foreignId.routingNumber(), foreignId.id())
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Pregovor " + foreignId + " ne postoji"));
    }

    private static void ensureMyParty(InterbankOtcNegotiation entity, Long userId, String userRole) {
        if (!entity.getLocalPartyId().equals(userId)
                || !entity.getLocalPartyRole().equalsIgnoreCase(userRole)) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Pregovor ne pripada trenutno autentifikovanom korisniku");
        }
    }

    private OtcInterbankOffer mapNegotiationToDto(InterbankOtcNegotiation n) {
        int myRouting = requireMyRoutingNumber();
        boolean weAreBuyer = n.getLocalPartyType() == InterbankPartyType.BUYER;

        String buyerBankCode, buyerUserId, sellerBankCode, sellerUserId;
        if (weAreBuyer) {
            buyerBankCode = "RN-" + myRouting;
            buyerUserId = prefixedId(n.getLocalPartyId(), n.getLocalPartyRole());
            sellerBankCode = "RN-" + n.getForeignPartyRoutingNumber();
            sellerUserId = n.getForeignPartyIdString();
        } else {
            sellerBankCode = "RN-" + myRouting;
            sellerUserId = prefixedId(n.getLocalPartyId(), n.getLocalPartyRole());
            buyerBankCode = "RN-" + n.getForeignPartyRoutingNumber();
            buyerUserId = n.getForeignPartyIdString();
        }

        boolean myTurn = n.getLastModifiedByRoutingNumber() != myRouting;
        String waitingOnBankCode = "RN-" + (myTurn ? myRouting : n.getForeignPartyRoutingNumber());
        String waitingOnUserId = myTurn
                ? prefixedId(n.getLocalPartyId(), n.getLocalPartyRole())
                : n.getForeignPartyIdString();

        Optional<InternalListingDto> listing = tradingServiceClient.findListingByTicker(n.getTicker());
        String listingName = listing.map(InternalListingDto::name).orElse(n.getTicker());
        BigDecimal currentPrice = listing.map(InternalListingDto::price)
                .map(price -> price != null ? price : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);

        return new OtcInterbankOffer(
                n.getForeignNegotiationRoutingNumber() + ":" + n.getForeignNegotiationIdString(),
                n.getTicker(),
                listingName,
                n.getPriceCurrency(),
                currentPrice,
                buyerBankCode, buyerUserId, resolveLocalOrForeignName(buyerBankCode, buyerUserId),
                sellerBankCode, sellerUserId, resolveLocalOrForeignName(sellerBankCode, sellerUserId),
                n.getAmount(),
                n.getPricePerUnit(),
                n.getPremium(),
                n.getSettlementDate() != null ? n.getSettlementDate().toLocalDate() : null,
                waitingOnBankCode,
                waitingOnUserId,
                myTurn,
                n.getStatus().name(),
                n.getLastModifiedAt() != null ? n.getLastModifiedAt() : n.getCreatedAt(),
                resolveLocalOrForeignName(
                        "RN-" + n.getLastModifiedByRoutingNumber(),
                        n.getLastModifiedByIdString())
        );
    }

    private OtcInterbankContract mapContractToDto(InterbankOtcContract c) {
        int myRouting = requireMyRoutingNumber();
        boolean weAreBuyer = c.getLocalPartyType() == InterbankPartyType.BUYER;

        String buyerBankCode, buyerUserId, sellerBankCode, sellerUserId;
        if (weAreBuyer) {
            buyerBankCode = "RN-" + myRouting;
            buyerUserId = prefixedId(c.getLocalPartyId(), c.getLocalPartyRole());
            sellerBankCode = "RN-" + c.getForeignPartyRoutingNumber();
            sellerUserId = c.getForeignPartyIdString();
        } else {
            sellerBankCode = "RN-" + myRouting;
            sellerUserId = prefixedId(c.getLocalPartyId(), c.getLocalPartyRole());
            buyerBankCode = "RN-" + c.getForeignPartyRoutingNumber();
            buyerUserId = c.getForeignPartyIdString();
        }

        Optional<InternalListingDto> listing = tradingServiceClient.findListingByTicker(c.getTicker());

        return new OtcInterbankContract(
                String.valueOf(c.getId()),
                listing.map(InternalListingDto::id).orElse(0L),
                c.getTicker(),
                listing.map(InternalListingDto::name).orElse(c.getTicker()),
                c.getStrikeCurrency(),
                buyerUserId, buyerBankCode, resolveLocalOrForeignName(buyerBankCode, buyerUserId),
                sellerUserId, sellerBankCode, resolveLocalOrForeignName(sellerBankCode, sellerUserId),
                c.getQuantity(),
                c.getStrikePrice(),
                c.getPremium(),
                listing.map(InternalListingDto::price)
                        .map(price -> price != null ? price : BigDecimal.ZERO)
                        .orElse(BigDecimal.ZERO),
                c.getSettlementDate() != null ? c.getSettlementDate().toLocalDate() : null,
                c.getStatus().name(),
                c.getCreatedAt(),
                c.getExercisedAt()
        );
    }

    private String resolveLocalOrForeignName(String bankCode, String userId) {
        int myRouting = requireMyRoutingNumber();
        int rn = parseRoutingFromBankCode(bankCode);
        if (rn == myRouting) {
            return resolveLocalUserName(userId);
        }
        // Strana banka — pokusaj keshirano resolveUserName; fallback na opaque id.
        String key = bankCode + "|" + userId;
        UserInformation cached = userInfoCache.get(key);
        if (cached != null) return cached.displayName();
        try {
            UserInformation info = negotiationService.resolveUserName(new ForeignBankId(rn, userId));
            if (info != null) {
                userInfoCache.put(key, info);
                return info.displayName();
            }
        } catch (RuntimeException e) {
            log.debug("resolveUserName fail za {}: {}", key, e.getMessage());
        }
        return userId;
    }

    private String resolveLocalUserName(String prefixedId) {
        if (prefixedId == null) return "";
        if (prefixedId.startsWith(CLIENT_ID_PREFIX)) {
            try {
                Long id = Long.parseLong(prefixedId.substring(2));
                return clientRepository.findById(id)
                        .map(c -> nullSafeJoin(c.getFirstName(), c.getLastName()))
                        .orElse(prefixedId);
            } catch (NumberFormatException e) {
                return prefixedId;
            }
        }
        if (prefixedId.startsWith(EMPLOYEE_ID_PREFIX)) {
            try {
                Long id = Long.parseLong(prefixedId.substring(2));
                return employeeRepository.findById(id)
                        .map(e -> nullSafeJoin(e.getFirstName(), e.getLastName()))
                        .orElse(prefixedId);
            } catch (NumberFormatException e) {
                return prefixedId;
            }
        }
        return prefixedId;
    }

    private String resolvePartnerUserName(ForeignBankId sellerId, InterbankProperties.PartnerBank partner) {
        String key = "RN-" + sellerId.routingNumber() + "|" + sellerId.id();
        UserInformation cached = userInfoCache.get(key);
        if (cached != null) return cached.displayName();
        try {
            UserInformation info = negotiationService.resolveUserName(sellerId);
            if (info != null) {
                userInfoCache.put(key, info);
                return info.displayName();
            }
        } catch (RuntimeException e) {
            log.debug("resolveUserName fail za seller {}: {}", sellerId, e.getMessage());
        }
        return partner.getDisplayName() != null ? partner.getDisplayName() + " — " + sellerId.id() : sellerId.id();
    }

    private static String nullSafeJoin(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        if (f.isEmpty()) return l;
        if (l.isEmpty()) return f;
        return f + " " + l;
    }

    /**
     * M-3 fix: §2.7.2 zahteva ceo broj akcija u OTC opcijama. Frakcioni iznosi
     * se odbacuju sa 400 (protocol exception).
     */
    private static void validateIntegerAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount mora biti > 0 (zadato: " + amount + ")");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "amount mora biti ceo broj (§2.7.2) — zadato: " + amount);
        }
    }
}
