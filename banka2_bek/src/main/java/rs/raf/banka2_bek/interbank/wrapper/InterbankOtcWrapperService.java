package rs.raf.banka2_bek.interbank.wrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
import rs.raf.banka2_bek.interbank.model.InterbankTransaction;
import rs.raf.banka2_bek.interbank.model.InterbankTransactionStatus;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcContractRepository;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcNegotiationRepository;
import rs.raf.banka2_bek.interbank.repository.InterbankTransactionRepository;
import rs.raf.banka2_bek.interbank.service.InterbankReservationApplier;
import rs.raf.banka2_bek.interbank.service.OtcNegotiationService;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.CounterOtcInterbankOfferRequest;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.CreateOtcInterbankOfferRequest;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.InterbankTransactionDto;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.OtcInterbankContract;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.OtcInterbankListing;
import rs.raf.banka2_bek.interbank.wrapper.InterbankOtcWrapperDtos.OtcInterbankOffer;
import rs.raf.banka2_bek.payment.model.Payment;
import rs.raf.banka2_bek.payment.repository.PaymentRepository;
import org.springframework.security.access.AccessDeniedException;

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
    private final InterbankTransactionRepository interbankTransactionRepository;
    private final PaymentRepository paymentRepository;
    private final InterbankReservationApplier reservationApplier;

    /**
     * P1-interbank-otc-2 (1336/1337) — self-proxy za exercise claim/revert.
     * Claim (lock contract + ACTIVE→EXERCISING + reserveMonas) i revert se izvrsavaju
     * u zasebnim {@code REQUIRES_NEW} transakcijama PRE/POSLE out-of-tx 2PC
     * {@code execute()}. Self-pozivi MORAJU ici kroz proxy da Spring AOP uhvati
     * @Transactional na svakoj fazi (self-invocation kroz {@code this} preskace
     * interceptor). Isti obrazac kao {@code OtcNegotiationService.self}.
     */
    @Lazy
    @Autowired
    InterbankOtcWrapperService self;

    /**
     * bagovi-fix-2: registracioni broj NASE banke — za razresavanje BANKINOG racuna
     * kad supervizor/zaposleni trguje OTC-om (nema licne racune, koristi bankin —
     * isto kao intra-bank OtcService.getPreferredAccount).
     */
    @org.springframework.beans.factory.annotation.Value("${bank.registration-number}")
    private String bankRegistrationNumber;

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
        // R1 209 — inter-bank OTC je za supervizore i klijente sa TRADE_STOCKS;
        // agenti iskljuceni (mirror trading-service ensureOtcAccess).
        ensureInterbankOtcAccess(buyerUserId, buyerUserRole);
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
        ensureInterbankOtcAccess(userId, userRole); // R1 209
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
        ensureInterbankOtcAccess(userId, userRole); // R1 209
        ForeignBankId foreignId = parseForeignBankId(offerId);
        InterbankOtcNegotiation entity = lookupOrThrow(foreignId);
        ensureMyParty(entity, userId, userRole);

        // R6 1976 — state-machine guard: decline je legalan SAMO iz ACTIVE.
        // Pre fix-a declineOffer/acceptOffer su bezuslovno flip-ovali status
        // (proveravali samo ensureMyParty), pa je ilegalan prelaz ACCEPTED→DECLINED
        // (ili DECLINED→DECLINED) prolazio. Intra (loadActiveOfferForParticipant) i
        // inbound (OtcNegotiationService) RADE ovaj guard.
        ensureNegotiationActive(entity);

        // T2-J mirror (zamena za T2-H skip): outbound DELETE UVEK ide ka partneru —
        // izbor target routing-a:
        //   1) Mi NE-autoritativni: partner = foreignId.rn (autoritativni vlasnik).
        //   2) Mi autoritativni: partner = foreignParty (kontra strana); URL path
        //      i dalje /negotiations/{myRouting}/{id} (partner mirror kljuc).
        // Bez T2-J, partner nikad ne sazna za nas decline na nase-autoritativni
        // pregovor (T2-H je SKIPOVAO outbound).
        int myRouting = requireMyRoutingNumber();
        int targetPartnerRouting = (foreignId.routingNumber() == myRouting)
                ? entity.getForeignPartyRoutingNumber()
                : foreignId.routingNumber();

        // FIX (reject-propagacija): outbound DELETE failure NE sme tiho da prodje.
        // Ranije smo lokalno postavljali DECLINED+save PRE outbound-a i gutali svaku
        // RuntimeException WARN-om — pa B1 nikad ne sazna za reject, a FE prikaze lazan
        // uspeh. Sad:
        //   - cist partner 404 (InterbankNegotiationNotFoundException) = benigno: pregovor
        //     vec ne postoji kod partnera (idempotentno zatvaranje) → log.info, nastavi i
        //     lokalno markiraj DECLINED.
        //   - bilo koja DRUGA greska (auth/network/5xx → InterbankCommunicationException ili
        //     ostalo) → PROPAGIRAJ: lokalni status OSTAJE ACTIVE (NE markiramo DECLINED), pa
        //     korisnik vidi da reject NIJE propagiran (handler mapira Communication→502)
        //     umesto laznog uspeha. setStatus(DECLINED)+save je premesten POSLE outbound-a.
        // Idempotentno: ensureNegotiationActive iznad vec sprecava ponovni decline.
        try {
            negotiationService.closeNegotiation(foreignId, targetPartnerRouting);
        } catch (InterbankExceptions.InterbankNegotiationNotFoundException notFound) {
            log.info("Outbound DELETE pregovora {} (target partner {}): partner vec nema pregovor "
                            + "(404) — tretiramo kao benigno zatvaranje: {}",
                    foreignId, targetPartnerRouting, notFound.getMessage());
        }

        // Lokalno zatvaranje TEK posle uspesnog (ili benigno-404) outbound DELETE-a.
        entity.setOngoing(false);
        entity.setStatus(InterbankOtcNegotiationStatus.DECLINED);
        negotiationRepository.save(entity);
        return mapNegotiationToDto(entity);
    }

    @Transactional
    public OtcInterbankOffer acceptOffer(String offerId, Long buyerAccountId, Long userId, String userRole) {
        ensureInterbankOtcAccess(userId, userRole); // R1 209
        ForeignBankId foreignId = parseForeignBankId(offerId);
        InterbankOtcNegotiation entity = lookupOrThrow(foreignId);
        ensureMyParty(entity, userId, userRole);

        // R6 1976 — state-machine guard PRE outbound 2PC accept (premium debit kod
        // partnera). Bez ovog, dva accept-a istog pregovora (ili accept posle
        // decline) bi pokrenula DVA 2PC accept-a → dupli premium debit. Guard mora
        // biti PRE negotiationService.acceptOffer(...) jer to pomera novac.
        ensureNegotiationActive(entity);

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

        // T1/T2 — zabelezi kupcev izabrani settlement racun NA pregovor PRE outbound
        // accept-a. Premium charge (§3.6) i kasniji settlement koriste bas taj racun,
        // pa prepare (rezervacija) i commit (commitMonas) u TransactionExecutorService-u
        // biraju isti deterministicki racun (vidi buyerSettlementAccountNumber javadoc).
        // Samo kad smo BUYER (vec provereno gore) i kad je racun prosledjen sa FE-a.
        Account settlementAccount = null;
        if ("EMPLOYEE".equalsIgnoreCase(userRole)) {
            // bagovi-fix-2: supervizor/zaposleni kupac trguje na BANKINOM racunu (nema
            // licne racune). Server razresava bankin ACTIVE racun u valuti premije i
            // IGNORISE FE buyerAccountId — sprecava IDOR (zaposleni ne moze izabrati tudji
            // racun). Inbound premium debit gadja BAS ovaj racun preko uskladistenog
            // buyerSettlementAccountNumber (resolveBuyerSettlementAccount), pa je novac
            // ocuvan. Mirror intra-bank OtcService (zaposleni → getPreferredAccount = bankin).
            settlementAccount = accountRepository
                    .findBankAccountByCurrency(bankRegistrationNumber, entity.getPremiumCurrency())
                    .orElseThrow(() -> new InterbankExceptions.InterbankExerciseConflictException(
                            "Banka nema ACTIVE racun u valuti premije (" + entity.getPremiumCurrency()
                                    + ") za supervizorsko prihvatanje ponude."));
            entity.setBuyerSettlementAccountNumber(settlementAccount.getAccountNumber());
            negotiationRepository.save(entity);
        } else if (buyerAccountId != null) {
            settlementAccount = accountRepository.findById(buyerAccountId)
                    .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                            "Racun " + buyerAccountId + " ne postoji"));
            // F1 (IDOR) — settlement racun se ovde tereti za premiju (§3.6), pa MORA biti
            // verifikovan PRE upisa: bez ovog kupac moze proslediti tudji ACTIVE racun u
            // valuti premije i naplatiti premiju s njega. Iste provere kao exerciseContract:
            // (a) ACTIVE, (b) vlasnistvo (Client == caller, role CLIENT), (c) valuta == premija.
            if (settlementAccount.getStatus() != AccountStatus.ACTIVE) {
                throw new InterbankExceptions.InterbankExerciseConflictException(
                        "Racun " + settlementAccount.getAccountNumber() + " nije aktivan");
            }
            if (settlementAccount.getClient() == null
                    || !settlementAccount.getClient().getId().equals(userId)
                    || !"CLIENT".equalsIgnoreCase(userRole)) {
                // Samo CLIENT kao buyer ima Account vlasnistvo. EMPLOYEE/ADMIN buyer (koji
                // nema klijentske racune) ovde NE prolazi — sto je tacno: bez resolvabilnog
                // racuna kupca premija se ne moze naplatiti, pa accept MORA biti odbijen
                // (vidi P0 [MONEY CREATED] gate ispod) umesto da se outbound 2PC svejedno
                // posalje i premium debit tiho no-op-uje (stvaranje novca).
                throw new InterbankExceptions.InterbankExerciseConflictException(
                        "Racun " + settlementAccount.getAccountNumber() + " ne pripada korisniku");
            }
            if (settlementAccount.getCurrency() == null
                    || !settlementAccount.getCurrency().getCode().equalsIgnoreCase(entity.getPremiumCurrency())) {
                throw new InterbankExceptions.InterbankExerciseConflictException(
                        "Racun " + settlementAccount.getAccountNumber() + " nije u valuti "
                                + entity.getPremiumCurrency());
            }
            entity.setBuyerSettlementAccountNumber(settlementAccount.getAccountNumber());
            negotiationRepository.save(entity);
        }

        // ─── P0 [MONEY CREATED] — settlement racun u valuti premije je OBAVEZAN ──────
        // za SVE buyer-role PRE bilo kakvog outbound 2PC-a. Bez ovog gate-a:
        //   - EMPLOYEE/ADMIN kupac (koji nema klijentske racune) ili kupac koji NE
        //     prosledi buyerAccountId ostane sa settlementAccount == null →
        //   - ceo funds-guard blok ispod (gated na settlementAccount != null) se PRESKOCI →
        //   - outbound §3.6 accept se SVEJEDNO posalje → partner kreditira prodavca +premium →
        //   - inbound premium-debit kod nas resolve-uje kupcev racun preko
        //     resolveLocalAccountDeterministic → findByClientId(...) vraca prazno za
        //     zaposlenog → premium debit TIHO NE-OP-uje → novac se STVARA (prodavac +100,
        //     kupac nezaduzen). Zato: ako kupac nema resolvabilan ACTIVE settlement racun u
        //     valuti premije, REJECT-ujemo accept PRE outbound-a (cist 4xx) — nikad novac
        //     nije ni krenuo. (NE smemo tiho no-op-ovati premium debit.)
        if (settlementAccount == null) {
            // Pokusaj deterministicke resolucije (isti red kao TransactionExecutorService:
            // ACTIVE racun vlasnika u valuti premije, najmanji broj racuna). Za zaposlene
            // bez klijentskih racuna ova lista je prazna → reject.
            settlementAccount = accountRepository
                    .findByClientIdAndStatusOrderByAvailableBalanceDesc(userId, AccountStatus.ACTIVE)
                    .stream()
                    .filter(a -> a.getCurrency() != null
                            && a.getCurrency().getCode().equalsIgnoreCase(entity.getPremiumCurrency()))
                    .min(java.util.Comparator.comparing(Account::getAccountNumber))
                    .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                            "Nije moguce naplatiti premiju: nedostaje nalog kupca u valuti premije ("
                                    + entity.getPremiumCurrency() + "). Izaberite settlement racun pre prihvatanja."));
            entity.setBuyerSettlementAccountNumber(settlementAccount.getAccountNumber());
            negotiationRepository.save(entity);
        }

        // ─── Bug 4 (PDF) — pri ACCEPT-u kupac placa SAMO PREMIJU ───────────────────
        // Opcioni ugovor: premija se naplacuje pri SKLAPANJU (accept), a strike (pi*k)
        // za akcije TEK pri ISKORISCENJU (exercise, §2.7.2). RANIJE smo @accept rezervisali
        // i strike (premium+strike funds-guard, stari T3/T4) — pa je kupac koji ima dovoljno
        // za premiju ali ne i za ceo strike dobijao "Nedovoljno sredstava (premium + strike)"
        // i NIJE mogao da sklopi ugovor (PDF screenshot). Sad proveravamo SAMO da kupac
        // pokriva premiju (koja se kroz 2PC commit kreditira prodavcu u drugoj banci);
        // strike se rezervise i naplacuje TEK u exerciseContract
        // (claimForExercise → reserveMonas → commitMonas posle uspesnog 2PC-a).
        if (settlementAccount != null
                && settlementAccount.getAvailableBalance().compareTo(entity.getPremium()) < 0) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Nedovoljno sredstava na racunu " + settlementAccount.getAccountNumber()
                            + ". Potrebno (premija): " + entity.getPremium() + " " + entity.getPremiumCurrency());
        }

        // Outbound GET .../accept — sinhrono ceka da prodavceva banka commit-uje 2PC.
        // Dok ceka, ovde nemamo nista da uradimo — partnerova banka kreira opciju
        // na svojoj strani i salje COMMIT_TX nama (handleCommitTx).
        negotiationService.acceptOffer(foreignId);

        entity.setStatus(InterbankOtcNegotiationStatus.ACCEPTED);
        entity.setOngoing(false);
        negotiationRepository.save(entity);

        // Posle accept-a contract postoji na obema stranama (kreiran kroz 2PC commit u
        // TransactionExecutorService.commitLocal — Asset.OptionAsset postings). Strike se
        // NE rezervise ovde (Bug 4) — exerciseContract ga rezervise/naplacuje pri iskoriscenju.
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

    // ─── P1-9: GET /interbank/payments/{id} status view ────────────────────────

    /**
     * P1-9 — vraca FE-facing view inter-bank 2PC / OTC SAGA transakcije za polling
     * progresa ({@code OtcInterBankContractsTab} poll-uje {@code GET /interbank/payments/{id}}).
     *
     * <p>Lookup id moze biti:
     * <ol>
     *   <li><b>OTC contract id</b> (numeric) — FE-ov {@code getTransactionLookupId}
     *       padne na {@code String(transaction.id)} = contract id (OtcInterbankContract
     *       DTO nema poseban transactionId field). Vlasnistvo: {@code localPartyId == userId}
     *       i {@code localPartyRole} se poklapa. Status se mapira iz
     *       {@code InterbankOtcContractStatus}.</li>
     *   <li><b>2PC transaction id string</b> (protocol id) — inter-bank placanje.
     *       Vlasnistvo se razresava preko vezanog {@code Payment} (placanje pripada
     *       {@code fromAccount.client}). Status se mapira iz
     *       {@code InterbankTransactionStatus} sa {@code ROLLED_BACK → ABORTED}.</li>
     * </ol>
     *
     * <p>404 ako nista ne matchuje; {@link AccessDeniedException} (→ 403) ako resurs
     * ne pripada pozivacu (anti-IDOR).
     */
    @Transactional(readOnly = true)
    public InterbankTransactionDto getInterbankTransactionView(String lookupId, Long userId, String userRole) {
        if (lookupId == null || lookupId.isBlank()) {
            throw new java.util.NoSuchElementException("Transakcija nije pronadjena.");
        }

        // (1) Probaj kao OTC contract id (numeric) — FE-ov primarni slucaj.
        Long contractId = null;
        try {
            contractId = Long.parseLong(lookupId.trim());
        } catch (NumberFormatException ignored) {
            // nije numeric → padni na transaction-id-string pretragu (placanja)
        }
        if (contractId != null) {
            Optional<InterbankOtcContract> contractOpt = contractRepository.findById(contractId);
            if (contractOpt.isPresent()) {
                InterbankOtcContract contract = contractOpt.get();
                // Anti-IDOR: samo vlasnik (lokalna strana ugovora) sme da vidi.
                if (!contract.getLocalPartyId().equals(userId)
                        || !contract.getLocalPartyRole().equalsIgnoreCase(userRole)) {
                    throw new AccessDeniedException("Ugovor ne pripada trenutnom korisniku.");
                }
                return mapContractToTransactionView(contract);
            }
            // numeric id koji nije contract — moze i dalje biti InterbankTransaction
            // id string koji slucajno izgleda numericki; nastavi na (2).
        }

        // (2) Probaj kao 2PC transaction id string (medjubankarsko placanje).
        Optional<Payment> paymentOpt = paymentRepository
                .findByInterbankTxRoutingNumberAndInterbankTxIdString(requireMyRoutingNumber(), lookupId);
        if (paymentOpt.isPresent()) {
            Payment payment = paymentOpt.get();
            // Anti-IDOR: placanje mora pripadati pozivacu (vlasniku fromAccount-a).
            boolean owns = payment.getFromAccount() != null
                    && payment.getFromAccount().getClient() != null
                    && payment.getFromAccount().getClient().getId().equals(userId)
                    && "CLIENT".equalsIgnoreCase(userRole);
            if (!owns) {
                throw new AccessDeniedException("Placanje ne pripada trenutnom korisniku.");
            }
            InterbankTransaction ibTx = interbankTransactionRepository
                    .findByTransactionRoutingNumberAndTransactionIdString(
                            payment.getInterbankTxRoutingNumber(), payment.getInterbankTxIdString())
                    .orElseThrow(() -> new java.util.NoSuchElementException(
                            "Inter-bank transakcija nije pronadjena za placanje."));
            return mapTransactionToView(ibTx, payment);
        }

        throw new java.util.NoSuchElementException("Transakcija nije pronadjena: " + lookupId);
    }

    /**
     * Mapira {@code InterbankOtcContract} u FE view. Status mapiranje:
     * ACTIVE → PREPARING (SAGA jos nije finalizovana / retryable),
     * EXERCISED → COMMITTED, EXPIRED → ABORTED.
     */
    private InterbankTransactionDto mapContractToTransactionView(InterbankOtcContract c) {
        String status;
        String currentPhase;
        switch (c.getStatus()) {
            case EXERCISED -> { status = "COMMITTED"; currentPhase = "Finalizacija"; }
            case EXPIRED   -> { status = "ABORTED";   currentPhase = null; }
            default        -> { status = "PREPARING"; currentPhase = "Rezervacija sredstava"; }
        }
        BigDecimal money = c.getStrikePrice() != null && c.getQuantity() != null
                ? c.getStrikePrice().multiply(c.getQuantity())
                : null;
        return new InterbankTransactionDto(
                c.getId(),
                String.valueOf(c.getId()),
                "OTC",
                status,
                currentPhase,
                "RN-" + c.getForeignPartyRoutingNumber(),   // sellerBankCode (foreign)
                "RN-" + requireMyRoutingNumber(),           // our bank
                money,
                c.getStrikeCurrency(),
                c.getTicker(),
                c.getQuantity(),
                c.getStrikePrice(),
                c.getCreatedAt(),
                c.getStatus() == InterbankOtcContractStatus.EXERCISED ? c.getExercisedAt() : null,
                c.getStatus() == InterbankOtcContractStatus.EXPIRED ? c.getCreatedAt() : null,
                0,
                c.getStatus() == InterbankOtcContractStatus.EXPIRED
                        ? "Opcioni ugovor je istekao bez iskoriscenja." : null
        );
    }

    /**
     * Mapira {@code InterbankTransaction} (2PC placanje) u FE view. Kljucno
     * mapiranje po spec-u: {@code ROLLED_BACK → ABORTED} (FE nema ROLLED_BACK
     * status). PREPARING/PREPARED/COMMITTED/STUCK ostaju 1:1.
     */
    private InterbankTransactionDto mapTransactionToView(InterbankTransaction ibTx, Payment payment) {
        String status = mapTransactionStatus(ibTx.getStatus());
        return new InterbankTransactionDto(
                ibTx.getId(),
                ibTx.getTransactionIdString(),
                "PAYMENT",
                status,
                null,
                "RN-" + ibTx.getTransactionRoutingNumber(),
                // R1-683: receiverBankCode mora biti BANK KOD (kao u OTC view-u: "RN-"+routing),
                // ne sirov broj racuna. Routing primaoceve banke je prvih 3 cifre dest. racuna.
                receiverBankCodeFor(payment.getToAccountNumber()),
                payment.getAmount(),
                payment.getCurrency() != null ? payment.getCurrency().getCode() : null,
                null,
                null,
                null,
                ibTx.getCreatedAt(),
                ibTx.getCommittedAt(),
                ibTx.getRolledBackAt(),
                ibTx.getRetryCount() != null ? ibTx.getRetryCount() : 0,
                ibTx.getFailureReason()
        );
    }

    /** §P1-9: ROLLED_BACK → ABORTED (FE shape); ostali statusi 1:1. */
    private static String mapTransactionStatus(InterbankTransactionStatus status) {
        return switch (status) {
            case ROLLED_BACK -> "ABORTED";
            case PREPARING -> "PREPARING";
            case PREPARED -> "PREPARED";
            case COMMITTED -> "COMMITTED";
            case STUCK -> "STUCK";
        };
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
        // R1 209 — exercise je OTC trgovinska akcija; isti gate kao create/accept.
        ensureInterbankOtcAccess(userId, userRole);

        Long contractId;
        try {
            contractId = Long.parseLong(contractIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("contractId mora biti broj");
        }

        // FIX (error-semantika): nepostojeci ugovor je NOT FOUND, ne malformed input.
        // Ranije InterbankProtocolException → wrapper handler 400; sad
        // InterbankNegotiationNotFoundException → 404 (resurs ne postoji).
        InterbankOtcContract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new InterbankExceptions.InterbankNegotiationNotFoundException(
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

        Account buyerAccount;
        if ("EMPLOYEE".equalsIgnoreCase(userRole)) {
            // bagovi-fix-2: supervizor/zaposleni kupac koristi BANKIN racun za strike
            // (§2.7.2). Server razresava bankin ACTIVE racun u strike valuti (query garantuje
            // ACTIVE + valutu), ignorise buyerAccountId. claimForExercise + commitMonas
            // gadjaju bas ovaj racun po broju → strike se naplacuje s bankinog racuna (novac ocuvan).
            buyerAccount = accountRepository
                    .findBankAccountByCurrency(bankRegistrationNumber, contract.getStrikeCurrency())
                    .orElseThrow(() -> new InterbankExceptions.InterbankExerciseConflictException(
                            "Banka nema ACTIVE racun u valuti " + contract.getStrikeCurrency()
                                    + " za supervizorski exercise."));
        } else {
            if (buyerAccountId == null) {
                throw new IllegalArgumentException("buyerAccountId je obavezan za exercise");
            }
            buyerAccount = accountRepository.findById(buyerAccountId)
                    .orElseThrow(() -> new InterbankExceptions.InterbankNegotiationNotFoundException(
                            "Racun " + buyerAccountId + " ne postoji"));
            if (buyerAccount.getStatus() != AccountStatus.ACTIVE) {
                throw new InterbankExceptions.InterbankExerciseConflictException(
                        "Racun " + buyerAccount.getAccountNumber() + " nije aktivan");
            }
            if (buyerAccount.getClient() == null
                    || !buyerAccount.getClient().getId().equals(userId)
                    || !"CLIENT".equalsIgnoreCase(userRole)) {
                throw new InterbankExceptions.InterbankExerciseConflictException(
                        "Racun " + buyerAccount.getAccountNumber() + " ne pripada korisniku");
            }
            if (!buyerAccount.getCurrency().getCode().equalsIgnoreCase(contract.getStrikeCurrency())) {
                throw new InterbankExceptions.InterbankExerciseConflictException(
                        "Racun " + buyerAccount.getAccountNumber() + " nije u valuti "
                                + contract.getStrikeCurrency());
            }
        }

        BigDecimal totalCost = contract.getStrikePrice().multiply(contract.getQuantity());

        // T5(B) — accept→exercise strike double-reserve pomirenje. T3 je strike (pi*k)
        // VEC rezervisao pri accept-u (na contract.reservedStrikeAccountNumber), pa bi
        // svez reserveMonas u claim-u rezervisao isti novac DRUGI put. Ako ugovor nosi
        // pre-rezervaciju, NE rezervisemo ponovo — KONZUMIRAMO postojecu: settlement leg
        // (p2) gadja BAS pre-rezervisani racun, pa commitLocal-ov commitMonas trosi
        // upravo tu rezervaciju (tacno jedna reserve + jedan commit-consume kroz
        // accept→exercise). Validujemo da je rezervisan iznos == strike×qty (inace
        // mismatch — cisto 409, ne tihi knjizni rascep). Ako reservedStrikeAmount==null
        // (legacy ugovor / accept bez izabranog racuna), zadrzavamo reserve-at-exercise.
        boolean strikePreReserved = contract.getReservedStrikeAccountNumber() != null
                && contract.getReservedStrikeAmount() != null;
        String settlementAccountNumber;
        if (strikePreReserved) {
            if (contract.getReservedStrikeAmount().compareTo(totalCost) != 0) {
                throw new InterbankExceptions.InterbankExerciseConflictException(
                        "Rezervisan strike iznos (" + contract.getReservedStrikeAmount()
                                + ") se ne poklapa sa strike×qty (" + totalCost + ") za ugovor "
                                + contractId);
            }
            // Settlement ide sa pre-rezervisanog racuna (tamo je novac vec na hold-u).
            settlementAccountNumber = contract.getReservedStrikeAccountNumber();
        } else {
            // Legacy put: rezervisemo pri exercise-u na kupcevom izabranom racunu.
            // Cheap fail-fast (ne-locking): konacna provera + hold se rade u claimForExercise.
            if (buyerAccount.getAvailableBalance().compareTo(totalCost) < 0) {
                throw new InterbankExceptions.InterbankExerciseConflictException(
                        "Nedovoljno sredstava na racunu " + buyerAccount.getAccountNumber()
                                + " za exercise (potrebno: " + totalCost + " " + contract.getStrikeCurrency() + ")");
            }
            settlementAccountNumber = buyerAccount.getAccountNumber();
        }

        // R2 1336/1337 — exercise CLAIM (REQUIRES_NEW, lock contract + reserveMonas):
        //  (1336) Lock-uje contract red i flip-uje ACTIVE→EXERCISING POD lock-om →
        //         drugi konkurentni exercise cekajuci na lock-u vidi !ACTIVE i 409
        //         (vise nema dvostrukog 2PC/debit-a).
        //  (1337) reserveMonas pomera availableBalance→reservedAmount pod pessimistic
        //         account lock-om (atomicna balance provera) → konkurentno placanje vise
        //         ne moze isprazniti racun izmedju provere i commit-a; commitLocal-ov
        //         commitMonas(isDebit=false) konzumira upravo ovu rezervaciju.
        // Claim je commit-ovan PRE out-of-tx 2PC. Ako 2PC padne — revert (EXERCISING→
        // ACTIVE + releaseMonas) u zasebnoj REQUIRES_NEW transakciji.
        // T5(B): kad je strike vec rezervisan (T3), claim SAMO flip-uje status (bez
        // druge reserveMonas) — rezervacija postoji od accept-a.
        self.claimForExercise(contractId, settlementAccountNumber, totalCost, strikePreReserved);

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

        // §2.7.2 / §S7 — protokol-konforman EXERCISE tx (mirror Banka-1 ExerciseOutbound).
        // Iznos placa kupac (k*pi), a prodavac ga PRIMA — protokolarno preko EKSPLICITNOG
        // "credit seller real cash pi*k" leg-a:
        //   p1 "Debit option pseudo-account for pi*k"          → OPTION prima novac → +pi*k
        //   p2 "Credit seller real cash pi*k"                  → prodavac PRIMA     → -pi*k (Person@seller)
        //   p3 "Credit option pseudo-account for k stocks"     → OPTION predaje     → -k
        //   p4 "Debit relevant receiving accounts for k assets"→ kupac prima        → +k
        //
        // BUG-1 [MONEY DESTROYED] fix: RANIJE je p2 bio (Account, -pi*k) kupcev racun,
        // a prodavac NIJE imao eksplicitan credit leg → partnerska banka (koja kreditira
        // svog prodavca SAMO sa eksplicitnog leg-a, ne sweep-uje OPTION interno) nikad
        // nije isplatila prodavca → k*pi je nestajao (zaglavljen u OPTION pseudo-racunu).
        // Sad saljemo eksplicitan seller-cash credit leg (Person@prodavceva-banka, -pi*k)
        // pa OPTION money (+pi*k) i seller credit (-pi*k) balansiraju MONAS grupu, a
        // partner kreditira prodavca. Kupcev strike NE ide vise kao posting nego se
        // konzumira iz claim-rezervacije eksplicitnim commitMonas-om POSLE uspesnog 2PC
        // (ispod) — kao sto Banka-1 radi (ReserveMonas/CommitMonas van postings liste).
        ForeignBankId sellerForeign = new ForeignBankId(
                contract.getForeignPartyRoutingNumber(), contract.getForeignPartyIdString());
        Posting p1 = new Posting(new TxAccount.Option(negotiationId), money, monasAsset);
        Posting p2 = new Posting(new TxAccount.Person(sellerForeign), money.negate(), monasAsset);
        Posting p3 = new Posting(new TxAccount.Option(negotiationId), qty.negate(), stockAsset);
        Posting p4 = new Posting(new TxAccount.Person(buyerForeign), qty, stockAsset);

        Transaction tx = transactionExecutor.formTransaction(
                List.of(p1, p2, p3, p4),
                "OTC exercise option " + negotiationId,
                null, "OTC-EX", "Iskoriscavanje OTC opcionog ugovora"
        );

        // Pozovi 2PC OUT-OF-TX (execute koordinira svoju per-fazu Tx). Na uspesan
        // COMMIT_TX, commitLocal heuristika (vidi TransactionExecutorService) detektuje
        // Stock+Option posting i postavlja contract.status EXERCISING→EXERCISED. Ako 2PC
        // padne — revert claim (oslobodi rezervaciju + EXERCISING→ACTIVE) pa propagiraj
        // da kupac moze retry.
        try {
            transactionExecutor.execute(tx);
        } catch (RuntimeException ex) {
            // T5(B): revert oslobadja rezervaciju SAMO ako ju je ovaj exercise i napravio
            // (legacy put). Kad je strike pre-rezervisan (T3), rezervacija pripada
            // accept-u i ostaje (expiry/decline ce je osloboditi) — ne diramo je ovde.
            self.revertExerciseClaim(contractId, settlementAccountNumber, totalCost, strikePreReserved);
            throw ex;
        }

        // BUG-1 — exactly-once kupcev strike debit: posto kupcev (Account, -pi*k) leg vise
        // NIJE u postings listi (zamenjen seller-cash leg-om), commitLocal ga vise ne
        // konzumira. Kupcev strike je rezervisan jednom (claimForExercise legacy reserve
        // ili accept-time T3 pre-rezervacija) i ovde ga konzumiramo TACNO jednom posle
        // uspesnog 2PC-a — kupac plati pi*k, prodavac (kod partnera) primi pi*k, novac
        // konzervisan. Na 2PC pad ova linija se ne dosegne (revert je vec oslobodio hold).
        reservationApplier.commitMonas(settlementAccountNumber, totalCost);

        // Posle uspesnog 2PC ucitaj contract sveze (commitLocal je vec save-ovao).
        InterbankOtcContract refreshed = contractRepository.findById(contractId).orElse(contract);
        log.info("OTC inter-bank contract {} exercised (buyerAccount={})", contractId, buyerAccountId);
        return mapContractToDto(refreshed);
    }

    /**
     * R2 1336/1337 — exercise claim (REQUIRES_NEW). Lock-uje contract red, re-citanje
     * statusa POD lock-om, flip ACTIVE→EXERCISING, i rezervise buyer-ova sredstva
     * ({@code reserveMonas} — atomicna availableBalance provera + hold pod account
     * lock-om). Drugi konkurentni exercise koji cekanje na contract lock-u vidi
     * status != ACTIVE → 409. Commit-uje se PRE out-of-tx 2PC (write-ahead claim).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claimForExercise(Long contractId, String buyerAccountNumber, BigDecimal totalCost,
                                 boolean strikePreReserved) {
        InterbankOtcContract locked = contractRepository.findByIdForUpdate(contractId)
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Ugovor " + contractId + " ne postoji"));
        if (locked.getStatus() != InterbankOtcContractStatus.ACTIVE) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Ugovor nije ACTIVE (trenutno: " + locked.getStatus()
                            + ") — exercise je vec u toku ili zavrsen");
        }
        // T5(B): kad je strike vec rezervisan (T3 accept), NE rezervisemo ponovo —
        // rezervacija (pi*k) vec stoji na buyerAccountNumber od accept-a; commitLocal
        // ce je konzumirati. Inace (legacy) hold sredstava sad (atomicno pod account
        // lock-om; baca ProtocolException ako se availableBalance u medjuvremenu smanjio
        // ispod totalCost — 1337 race-window).
        if (!strikePreReserved) {
            reservationApplier.reserveMonas(buyerAccountNumber, totalCost);
        }
        locked.setStatus(InterbankOtcContractStatus.EXERCISING);
        contractRepository.save(locked);
    }

    /**
     * R2 1336/1337 — revert exercise claim (REQUIRES_NEW) na 2PC pad. Oslobodi
     * rezervaciju i vrati EXERCISING→ACTIVE da kupac moze da retry-uje. Idempotentno:
     * ako je status vec EXERCISED (2PC ipak commit-ovao) ili nije EXERCISING, ne
     * dira novac (release se izvrsava samo dok je contract jos EXERCISING).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revertExerciseClaim(Long contractId, String buyerAccountNumber, BigDecimal totalCost,
                                    boolean strikePreReserved) {
        InterbankOtcContract locked = contractRepository.findByIdForUpdate(contractId)
                .orElse(null);
        if (locked == null || locked.getStatus() != InterbankOtcContractStatus.EXERCISING) {
            // Vec finalizovan (EXERCISED) ili ne postoji — rezervaciju je vec
            // konzumirao commitMonas; ne dupliraj release.
            return;
        }
        // T5(B): release SAMO ako je ovaj exercise i napravio rezervaciju (legacy put).
        // Kad je strike pre-rezervisan (T3 accept), rezervacija pripada accept-u i mora
        // ostati posle neuspelog exercise-a (expiry/decline put je oslobadja); puštanje
        // ovde bi razvezalo strike pre exercise-a i ostavilo accept bez garancije.
        if (!strikePreReserved) {
            reservationApplier.releaseMonas(buyerAccountNumber, totalCost);
        }
        locked.setStatus(InterbankOtcContractStatus.ACTIVE);
        contractRepository.save(locked);
    }

    /**
     * T9 / S10b — "Odbi" sklopljeni inter-bank OTC ugovor sa nase (kupceve) strane,
     * pre exercise-a. Lokalno zatvaranje: oslobadjamo buyer-ovu accept-time strike
     * rezervaciju ("pare za kupovinu se vracaju"), markiramo status DECLINED, hartije
     * se NE prenose, premija OSTAJE prodavcu (vec placena pri accept-u, ne refundira se).
     *
     * <p>BEZ cross-bank poruke — sellerova rezervacija hartija (u partner banci) se
     * oslobadja na NJIHOVOM expiry-ju (§2.7.2). Mi samo zatvaramo nasu stranu.
     *
     * <p>Pre-validacija (mirror exercise-a, sve → 409 {@link
     * InterbankExceptions.InterbankExerciseConflictException}):
     * <ul>
     *   <li>pozivac mora biti BUYER i vlasnik ugovora</li>
     *   <li>status mora biti ACTIVE (vec EXERCISED/EXPIRED/DECLINED → 409, idempotent guard)</li>
     *   <li>settlementDate mora biti u buducnosti (posle dospeca ugovor istice auto-expiry-jem)</li>
     * </ul>
     *
     * <p>Ucitavanje ide kroz {@code findByIdForUpdate} (PESSIMISTIC_WRITE) jer mutiramo
     * status — serijalizacija sa konkurentnim exercise-claim-om (1336): drugi pod lock-om
     * vidi DECLINED i odbija.
     */
    @Transactional
    public OtcInterbankContract declineContract(String contractIdStr, Long userId, String userRole) {
        // R1 209 — decline je OTC trgovinska akcija; isti gate kao exercise.
        ensureInterbankOtcAccess(userId, userRole);

        Long contractId;
        try {
            contractId = Long.parseLong(contractIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("contractId mora biti broj");
        }

        InterbankOtcContract contract = contractRepository.findByIdForUpdate(contractId)
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Ugovor " + contractId + " ne postoji"));

        if (!contract.getLocalPartyId().equals(userId) || !contract.getLocalPartyRole().equalsIgnoreCase(userRole)) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Ugovor ne pripada trenutno autentifikovanom korisniku");
        }
        if (contract.getLocalPartyType() != InterbankPartyType.BUYER) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Odbijanje ugovora inicira kupac; mi smo SELLER u ovom ugovoru");
        }
        if (contract.getStatus() != InterbankOtcContractStatus.ACTIVE) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Ugovor nije ACTIVE (trenutno: " + contract.getStatus()
                            + ") — odbijanje nije dozvoljeno nad zatvorenim/iskoriscenim ugovorom");
        }
        // Decline je legalan SAMO pre dospeca; posle settlement-a auto-expiry sweep
        // istice ugovor (releaseMonas je tamo). Strict-before (american-style), kao exercise.
        if (!contract.getSettlementDate().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new InterbankExceptions.InterbankExerciseConflictException(
                    "Ugovor je istekao (settlement: " + contract.getSettlementDate()
                            + ") — odbijanje vise nije moguce");
        }

        // Oslobodi buyer-ovu accept-time strike rezervaciju ("pare za kupovinu se
        // vracaju"). Ako strike nije rezervisan (legacy/accept bez izabranog racuna),
        // nema sta da se oslobodi — samo zatvaramo stranu.
        if (contract.getReservedStrikeAccountNumber() != null
                && contract.getReservedStrikeAmount() != null) {
            reservationApplier.releaseMonas(
                    contract.getReservedStrikeAccountNumber(),
                    contract.getReservedStrikeAmount());
        }

        contract.setStatus(InterbankOtcContractStatus.DECLINED);
        contractRepository.save(contract);
        log.info("OTC inter-bank ugovor {} odbijen (DECLINED) — strike rezervacija oslobodjena, "
                + "premija ostaje prodavcu, hartije nisu prenete", contractId);
        return mapContractToDto(contract);
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

    /**
     * R1-683: izvedi {@code receiverBankCode} ("RN-"+routing) iz broja racuna primaoca,
     * da PAYMENT view bude konzistentan sa OTC view-om (koji koristi isti "RN-" format).
     * Routing je prvih 3 cifre racuna (vidi {@code BankRoutingService.parseRoutingNumber}).
     * Ako racun nije parsabilan/null, vrati sirov racun kao fallback (ne pucaj na display putu).
     */
    private static String receiverBankCodeFor(String toAccountNumber) {
        if (toAccountNumber != null && toAccountNumber.length() >= 3) {
            String first3 = toAccountNumber.substring(0, 3);
            try {
                Integer.parseInt(first3);
                return "RN-" + first3;
            } catch (NumberFormatException ignored) {
                // nije numericki prefiks — fallback na sirov racun
            }
        }
        return toAccountNumber;
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

    /**
     * R6 1976 — state-machine guard. Mutirajuce OTC akcije (accept/decline) su
     * legalne SAMO iz ACTIVE stanja. Pre fix-a su flip-ovale status bezuslovno →
     * ilegalan prelaz (ACCEPTED→DECLINED, DECLINED→ACCEPTED) + dvostruki 2PC accept
     * (dupli premium debit kod partnera).
     */
    private static void ensureNegotiationActive(InterbankOtcNegotiation entity) {
        if (entity.getStatus() != InterbankOtcNegotiationStatus.ACTIVE) {
            throw new InterbankExceptions.InterbankNegotiationConflictException(
                    "Pregovor nije ACTIVE (trenutno: " + entity.getStatus()
                            + ") — akcija nije dozvoljena nad zatvorenim/prihvacenim pregovorom");
        }
    }

    /**
     * R1 209 — inter-bank OTC pristup. Po Celini 4 (Nova) §145-148: OTC trgovina je
     * dozvoljena SUPERVIZORIMA (od zaposlenih) i KLIJENTIMA sa permisijom za trgovinu;
     * AGENTI su eksplicitno iskljuceni. Mirror {@code trading-service OtcService.ensureOtcAccess},
     * ali razresava preko repozitorijuma (u banka-core klijentski JWT NE nosi
     * {@code TRADE_STOCKS} autoritet — vidi {@code User.getAuthorities()} koji daje
     * samo {@code ROLE_<role>}; zaposleni preko {@code EmployeeUserDetails} nose
     * permisije kao autoritete, ali repository-resolved provera je deterministicna za
     * obe role i ne zavisi od populacije SecurityContext-a).
     *
     * @throws AccessDeniedException (→403) ako klijent nema {@code canTradeStocks},
     *         agent (zaposleni bez SUPERVISOR/ADMIN), ili nepoznata rola/korisnik.
     */
    private void ensureInterbankOtcAccess(Long userId, String userRole) {
        if (userId == null || userRole == null) {
            throw new AccessDeniedException("Korisnicki identitet nije razresen za OTC pristup.");
        }
        if ("CLIENT".equalsIgnoreCase(userRole)) {
            Client client = clientRepository.findById(userId)
                    .orElseThrow(() -> new AccessDeniedException("Klijent ne postoji."));
            if (!Boolean.TRUE.equals(client.getCanTradeStocks())) {
                throw new AccessDeniedException(
                        "Nemate dozvolu za OTC trgovinu (permisija za trgovanje nije dodeljena).");
            }
            return;
        }
        if ("EMPLOYEE".equalsIgnoreCase(userRole)) {
            Employee employee = employeeRepository.findById(userId)
                    .orElseThrow(() -> new AccessDeniedException("Zaposleni ne postoji."));
            java.util.Set<String> perms = employee.getPermissions();
            boolean isSupervisor = perms != null
                    && (perms.contains("SUPERVISOR") || perms.contains("ADMIN"));
            if (!isSupervisor) {
                throw new AccessDeniedException(
                        "OTC je dozvoljen samo supervizorima i klijentima (po Celini 4 Nova) — "
                                + "agenti su iskljuceni.");
            }
            return;
        }
        throw new AccessDeniedException("Nepoznata uloga ne moze pristupiti inter-bank OTC-u.");
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
