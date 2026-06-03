package rs.raf.banka2_bek.interbank.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import rs.raf.banka2_bek.interbank.protocol.OptionDescription;
import rs.raf.banka2_bek.interbank.protocol.OtcNegotiation;
import rs.raf.banka2_bek.interbank.protocol.OtcOffer;
import rs.raf.banka2_bek.interbank.protocol.Posting;
import rs.raf.banka2_bek.interbank.protocol.PublicStock;
import rs.raf.banka2_bek.interbank.protocol.StockDescription;
import rs.raf.banka2_bek.interbank.protocol.Transaction;
import rs.raf.banka2_bek.interbank.protocol.TxAccount;
import rs.raf.banka2_bek.interbank.protocol.UserInformation;
import rs.raf.banka2.contracts.internal.InternalPublicStockSellerDto;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcContractRepository;
import rs.raf.banka2_bek.interbank.repository.InterbankOtcNegotiationRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inter-bank OTC pregovor servis (protokol §3, Celina 5 (Nova) — OTC trgovina).
 * <p>
 * Implementira oba smera komunikacije:
 * <ul>
 *   <li><b>Outbound</b> — kad je nas korisnik inicijator (kupac salje ponudu prodavcu
 *       u partner banci, ili prodavac salje counter-offer kupcu u partner banci).
 *       Outbound metode (T2/T5) deleguju na {@link InterbankClient}.</li>
 *   <li><b>Inbound</b> (T3) — kad partner banka kontaktira nas. Mi smo prodavac
 *       (autoritativni vlasnik pregovora) ili kupac kojem se nudi ponuda. Inbound
 *       metode persistiraju u {@link InterbankOtcNegotiation} entitetu i pri
 *       prihvatanju kreiraju {@link InterbankOtcContract} kroz 2PC sa
 *       {@link TransactionExecutorService}-om.</li>
 * </ul>
 * <p>
 * <b>Konvencija za seller/buyer ID:</b> sopstveni id-evi koji idu u
 * {@link ForeignBankId#id()} koriste prefiks {@code C-} za klijente i
 * {@code E-} za zaposlene. Partner banka tretira string kao opaque (po
 * §2.3) ali konvencija nam dozvoljava da kasnije razresimo strane preko
 * {@link #serveUserInfo(String)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtcNegotiationService {

    /** TTL za §3.1 outbound cache. */
    static final Duration PUBLIC_STOCK_TTL = Duration.ofMinutes(5);

    private static final String CLIENT_ID_PREFIX = "C-";
    private static final String EMPLOYEE_ID_PREFIX = "E-";

    private final InterbankClient client;
    private final InterbankProperties properties;
    private final InterbankOtcNegotiationRepository negotiationRepository;
    private final InterbankOtcContractRepository contractRepository;
    private final TradingServiceInternalClient tradingServiceClient;
    private final ClientRepository clientRepository;
    private final EmployeeRepository employeeRepository;
    private final TransactionExecutorService transactionExecutor;
    private final InterbankReservationApplier reservationApplier;

    /**
     * Self-proxy: I-3 fix po Celini 5 audit-u — {@code acceptReceivedNegotiation}
     * vise nije jedan veliki @Transactional metod sa 2PC HTTP I/O u sebi. Posao
     * je razdvojen na 3 male @Transactional faze (persist contract, izvrsi 2PC
     * van Tx-a, compensate). Self-pozivi MORAJU ici kroz proxy da bi Spring AOP
     * uhvatio @Transactional na svakoj fazi posebno (self-invocation kroz `this`
     * preskace interceptor).
     */
    @Lazy
    @Autowired
    OtcNegotiationService self;

    /**
     * R7 observability: inkrementira {@code banka2_otc_contracts_created_total} kad se
     * inter-bank OTC ugovor sklopi (accept → ACTIVE). Field injection sa
     * {@code required=false} da se ne dira {@code @RequiredArgsConstructor} ni
     * {@code @InjectMocks} unit-testovi; null-guard pri inkrementu.
     */
    @Autowired(required = false)
    private rs.raf.banka2_bek.monitoring.BusinessMetrics businessMetrics;

    /** Cache po routing number-u partnerske banke. ConcurrentHashMap (low contention). */
    private final Map<Integer, CachedPublicStocks> publicStockCache = new ConcurrentHashMap<>();

    /**
     * R3 1539 — per-(seller,role,ticker) monitori za serijalizaciju seller-side
     * kvote pri inbound kreiranju pregovora. Bez ovoga, dva partnera koja
     * istovremeno kreiraju pregovor za istog prodavca/ticker citaju isti
     * {@code available} i oba persist-uju → prodavac obecao vise akcija nego
     * sto ima (over-allocation). Lock se uzima oko check+persist bloka.
     *
     * <p><b>Domet:</b> in-process (JVM) serijalizacija. Inter-bank inbound
     * (/negotiations) je single-instance ulaz; cross-replica serijalizacija bi
     * trazila DB advisory-lock (pg_advisory_xact_lock) — namerno odlozeno (PG-only,
     * van H2 test-portabilnosti, bez DDL-a u ovom batch-u).
     */
    private final Map<String, Object> sellerQuotaLocks = new ConcurrentHashMap<>();

    // ────────────────────────── outbound (T2 / T5) ────────────────────────────

    /**
     * BE-INT-05: namerno BEZ @Transactional jer radi HTTP I/O van banka-core
     * DB-a (samo cita ConcurrentHashMap + fetcha sa partner banke). Ranija
     * @Transactional anotacija je drzala Hikari konekciju otvorenu kroz HTTP
     * call ka partner banci (latencija + tail rizik) — pool exhaustion ako se
     * pozove iz vise threadova istovremeno. Ako kasnije zatreba Tx (npr.
     * persist u DB cache), koristiti propagation=SUPPORTS.
     */
    public List<PublicStock> fetchRemotePublicStocks(int routingNumber) {
        Instant now = Instant.now();
        CachedPublicStocks cached = publicStockCache.get(routingNumber);
        if (cached != null && cached.isFresh(now)) {
            return cached.stocks();
        }
        List<PublicStock> stocks = client.fetchPublicStocks(routingNumber);
        publicStockCache.put(routingNumber, new CachedPublicStocks(stocks, now));
        return stocks;
    }

    @Transactional
    public ForeignBankId createNegotiation(OtcOffer offer) {
        validateOutboundOffer(offer);
        if (!offer.buyerId().equals(offer.lastModifiedBy())) {
            throw new IllegalArgumentException(
                    "Pri kreiranju pregovora lastModifiedBy mora biti buyerId.");
        }
        int sellerRouting = offer.sellerId().routingNumber();
        ForeignBankId negotiationId = client.postNegotiation(sellerRouting, offer);
        publicStockCache.remove(sellerRouting);
        log.info("OTC outbound: created negotiation {} at bank {}", negotiationId, sellerRouting);
        return negotiationId;
    }

    @Transactional
    public void postCounterOffer(ForeignBankId negotiationId, OtcOffer updated) {
        postCounterOffer(negotiationId, negotiationId.routingNumber(), updated);
    }

    /**
     * T2-J overload (Tim 1 cross-bank Stage C, 2026-05-20): counter sa eksplicitnim
     * {@code targetPartnerRouting}-om. Wrapper koristi ovo kad smo MI autoritativni
     * (negotiationId.routingNumber == myRouting) — partner banka je {@code foreignPartyRouting},
     * URL path zadrzava {rn, id} jer Tim 1 to prepoznaje kao njihov mirror kljuc.
     */
    @Transactional
    public void postCounterOffer(ForeignBankId negotiationId, int targetPartnerRouting, OtcOffer updated) {
        if (negotiationId == null) throw new IllegalArgumentException("negotiationId ne sme biti null");
        validateOutboundOffer(updated);
        ensureLocalParty(updated.lastModifiedBy(),
                "Counter-offer mora biti potpisan od strane korisnika nase banke");
        client.putCounterOffer(negotiationId, targetPartnerRouting, updated);
        log.info("OTC outbound: counter-offered negotiation {} via partner routing {} (lastModifiedBy={})",
                negotiationId, targetPartnerRouting, updated.lastModifiedBy());
    }

    @Transactional
    public OtcNegotiation readNegotiation(ForeignBankId negotiationId) {
        if (negotiationId == null) throw new IllegalArgumentException("negotiationId ne sme biti null");
        return client.getNegotiation(negotiationId);
    }

    @Transactional
    public void closeNegotiation(ForeignBankId negotiationId) {
        closeNegotiation(negotiationId, negotiationId.routingNumber());
    }

    /** T2-J overload: DELETE sa eksplicitnim targetPartnerRouting-om. */
    @Transactional
    public void closeNegotiation(ForeignBankId negotiationId, int targetPartnerRouting) {
        if (negotiationId == null) throw new IllegalArgumentException("negotiationId ne sme biti null");
        client.deleteNegotiation(negotiationId, targetPartnerRouting);
        log.info("OTC outbound: closed negotiation {} via partner routing {}",
                negotiationId, targetPartnerRouting);
    }

    @Transactional
    public void acceptOffer(ForeignBankId negotiationId) {
        if (negotiationId == null) throw new IllegalArgumentException("negotiationId ne sme biti null");
        client.acceptNegotiation(negotiationId);
        publicStockCache.remove(negotiationId.routingNumber());
        log.info("OTC outbound: accepted negotiation {}", negotiationId);
    }

    /**
     * Outbound §3.7 — graceful fallback ako partner banka vrati 404.
     * UI moze da prikaze opaque id umesto imena ako nema friendly mapiranja.
     */
    @Transactional
    public UserInformation resolveUserName(ForeignBankId userId) {
        if (userId == null) throw new IllegalArgumentException("userId ne sme biti null");
        try {
            return client.getUserInfo(userId);
        } catch (InterbankExceptions.InterbankUserNotFoundException notFound) {
            log.debug("Partner bank {} doesn't have user {}: {}",
                    userId.routingNumber(), userId.id(), notFound.getMessage());
            return new UserInformation("Banka " + userId.routingNumber(), userId.id());
        }
    }

    // ────────────────────────── inbound (T3) ──────────────────────────────────

    /**
     * §3.1 — vrati javne akcije za sve nase korisnike (klijenti + zaposleni).
     * Group-by ticker + lista (seller, amount). Filtriranje po roli (klijent
     * vidi klijente, aktuar vidi aktuare) radi kupceva banka — mi vracamo sve
     * jer ne znamo ko nas je query-jevao (X-Api-Key autentifikuje BANKU, ne user-a).
     * Zato kodiramo role u id-u (`C-{id}` ili `E-{id}`).
     */
    @Transactional(readOnly = true)
    public List<PublicStock> serveLocalPublicStocks() {
        int myRouting = requireMyRoutingNumber();

        // Citamo sve javno-vidljive pozicije (publicQuantity > 0) preko trading-service
        // seam-a (faza 2f — portfolios tabela zivi u trading_db) i grupisemo po ticker-u.
        // Quantity koja se nudi je publicQuantity umanjen za one sto su vec rezervisani
        // u ACTIVE pregovorima (gde smo MI seller) — negotiation tabela ostaje u banka-core.
        Map<String, List<PublicStock.Seller>> byTicker = new HashMap<>();
        Map<String, StockDescription> stockByTicker = new HashMap<>();

        for (InternalPublicStockSellerDto p : tradingServiceClient.findAllPublicStock()) {
            int publicQty = p.publicQuantity();
            if (publicQty <= 0) continue;

            BigDecimal reservedInActiveNegotiations = nullToZero(
                    negotiationRepository.sumActiveAmountForSellerAndTicker(
                            p.userId(), p.userRole(), p.ticker()));
            BigDecimal available = BigDecimal.valueOf(publicQty).subtract(reservedInActiveNegotiations);
            if (available.signum() <= 0) continue;

            String prefixed = ("CLIENT".equalsIgnoreCase(p.userRole())
                    ? CLIENT_ID_PREFIX : EMPLOYEE_ID_PREFIX) + p.userId();
            ForeignBankId sellerId = new ForeignBankId(myRouting, prefixed);

            byTicker
                    .computeIfAbsent(p.ticker(), k -> new ArrayList<>())
                    .add(new PublicStock.Seller(sellerId, available));
            stockByTicker.putIfAbsent(p.ticker(), new StockDescription(p.ticker()));
        }

        List<PublicStock> result = new ArrayList<>(byTicker.size());
        for (Map.Entry<String, List<PublicStock.Seller>> e : byTicker.entrySet()) {
            result.add(new PublicStock(stockByTicker.get(e.getKey()), e.getValue()));
        }
        return result;
    }

    /**
     * §3.2 — partner banka inicira pregovor (kupac u partner banci, prodavac
     * mi). Validacija sellerId, kreiranje lokalnog entiteta, vracanje
     * ForeignBankId{nasRouting, generisaniId} kupcu.
     *
     * <p>R3 1539 — NAMERNO BEZ @Transactional. Akvizicija per-(seller,role,ticker)
     * monitora se radi OKO transakcionog tela ({@code self.acceptCreatedNegotiationLocked}),
     * tako da prvi inbound pregovor COMMIT-uje (i postaje vidljiv
     * {@code sumActiveAmountForSellerAndTicker}-u) PRE nego sto drugi konkurentni
     * inbound za istog prodavca/ticker procita kvotu. Da je lock unutar
     * @Transactional metode, oslobodio bi se pre commit-a → drugi thread bi
     * citao stale kvotu i over-allocation bi opstao.
     */
    public ForeignBankId acceptCreatedNegotiation(OtcOffer offer) {
        if (offer == null) throw new IllegalArgumentException("offer ne sme biti null");
        int myRouting = requireMyRoutingNumber();
        if (offer.sellerId() == null) {
            throw new IllegalArgumentException(
                    "Mi nismo autoritativna banka za sellerId — sellerId.routingNumber mora biti nas (" + myRouting + ")");
        }
        // Lock kljuc po (seller-id-string, ticker) — pre validacije sadrzaja jer
        // self.acceptCreatedNegotiationLocked ponavlja sve provere unutar Tx-a.
        String lockKey = quotaLockKey(offer);
        Object monitor = sellerQuotaLocks.computeIfAbsent(lockKey, k -> new Object());
        synchronized (monitor) {
            return self.acceptCreatedNegotiationLocked(offer);
        }
    }

    /**
     * §3.2 transakciono telo — kvota check + persist pod per-seller monitorom
     * (vidi {@link #acceptCreatedNegotiation}). Self-pozvano kroz proxy da Spring
     * AOP otvori @Transactional.
     */
    @Transactional
    public ForeignBankId acceptCreatedNegotiationLocked(OtcOffer offer) {
        if (offer == null) throw new IllegalArgumentException("offer ne sme biti null");
        int myRouting = requireMyRoutingNumber();

        if (offer.sellerId() == null || offer.sellerId().routingNumber() != myRouting) {
            throw new IllegalArgumentException(
                    "Mi nismo autoritativna banka za sellerId — sellerId.routingNumber mora biti nas (" + myRouting + ")");
        }
        if (offer.buyerId() == null || offer.buyerId().routingNumber() == myRouting) {
            throw new IllegalArgumentException(
                    "buyerId mora biti iz partner banke (ne nase " + myRouting + ")");
        }
        if (offer.lastModifiedBy() == null || !offer.lastModifiedBy().equals(offer.buyerId())) {
            throw new IllegalArgumentException(
                    "Pri kreiranju pregovora lastModifiedBy mora biti buyerId.");
        }
        if (offer.stock() == null || offer.stock().ticker() == null) {
            throw new IllegalArgumentException("offer.stock.ticker je obavezan");
        }
        validateIntegerAmount(offer.amount());
        if (offer.pricePerUnit() == null || offer.pricePerUnit().amount() == null
                || offer.pricePerUnit().amount().signum() <= 0) {
            throw new IllegalArgumentException("pricePerUnit mora biti > 0");
        }
        if (offer.premium() == null || offer.premium().amount() == null
                || offer.premium().amount().signum() < 0) {
            throw new IllegalArgumentException("premium ne moze biti negativan");
        }
        if (offer.settlementDate() == null
                || !offer.settlementDate().isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException("settlementDate mora biti u buducnosti");
        }

        // Resolve lokalnog seller-a (po prefiks konvenciji).
        LocalParty seller = parseLocalPartyId(offer.sellerId().id());

        // Kvota provera: seller mora imati dovoljno publicQuantity-a
        // (umanjeno za ACTIVE pregovore + ACTIVE ugovore). Public-stock pozicije
        // citamo preko trading-service seam-a (faza 2f — portfolios tabela u trading_db).
        int sellerPublic = tradingServiceClient
                .findPublicStockForSeller(seller.userId(), seller.role(), offer.stock().ticker())
                .stream()
                .mapToInt(InternalPublicStockSellerDto::publicQuantity)
                .sum();
        BigDecimal alreadyReserved = nullToZero(
                negotiationRepository.sumActiveAmountForSellerAndTicker(
                        seller.userId(), seller.role(), offer.stock().ticker()));
        BigDecimal available = BigDecimal.valueOf(sellerPublic).subtract(alreadyReserved);
        if (available.compareTo(offer.amount()) < 0) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Seller nema dovoljno javnih akcija ("
                            + offer.stock().ticker() + " trazeno=" + offer.amount()
                            + ", raspolozivo=" + available + ")");
        }

        // Kreiraj entitet.
        InterbankOtcNegotiation entity = new InterbankOtcNegotiation();
        String generatedId = UUID.randomUUID().toString();
        entity.setForeignNegotiationRoutingNumber(myRouting);
        entity.setForeignNegotiationIdString(generatedId);
        entity.setLocalPartyType(InterbankPartyType.SELLER);
        entity.setLocalPartyId(seller.userId());
        entity.setLocalPartyRole(seller.role());
        entity.setForeignPartyRoutingNumber(offer.buyerId().routingNumber());
        entity.setForeignPartyIdString(offer.buyerId().id());
        entity.setTicker(offer.stock().ticker());
        entity.setAmount(offer.amount());
        entity.setPricePerUnit(offer.pricePerUnit().amount());
        entity.setPriceCurrency(offer.pricePerUnit().currency().name());
        entity.setPremium(offer.premium().amount());
        entity.setPremiumCurrency(offer.premium().currency().name());
        // M-2 fix: cuvamo full ISO 8601 sa TZ (§2.4), ne .toLocalDate().
        entity.setSettlementDate(offer.settlementDate());
        entity.setLastModifiedByRoutingNumber(offer.lastModifiedBy().routingNumber());
        entity.setLastModifiedByIdString(offer.lastModifiedBy().id());
        entity.setOngoing(true);
        entity.setStatus(InterbankOtcNegotiationStatus.ACTIVE);

        negotiationRepository.save(entity);

        ForeignBankId result = new ForeignBankId(myRouting, generatedId);
        log.info("OTC inbound: created negotiation {} (seller={}, buyer={})",
                result, offer.sellerId(), offer.buyerId());
        return result;
    }

    /**
     * §3.3 — counter-offer od partner banke. Provera turn-a + update polja.
     */
    @Transactional
    public void receiveCounterOffer(ForeignBankId negotiationId, OtcOffer updated) {
        if (negotiationId == null) throw new IllegalArgumentException("negotiationId ne sme biti null");
        if (updated == null) throw new IllegalArgumentException("updated ne sme biti null");

        InterbankOtcNegotiation entity = lookupByNegotiationId(negotiationId);

        // §3.3 — zatvoreni pregovor: 409 Conflict (ne 400, nije malformed).
        if (!entity.isOngoing() || entity.getStatus() != InterbankOtcNegotiationStatus.ACTIVE) {
            throw new InterbankExceptions.InterbankNegotiationConflictException(
                    "Pregovor " + negotiationId + " nije aktivan (status=" + entity.getStatus() + ")");
        }

        // Pravilo turne (§3.3): turn je strana cije lastModifiedBy != lastModifiedBy entiteta.
        // Caller mora biti suprotna strana od one koja je poslednja izmenila.
        if (updated.lastModifiedBy() == null) {
            throw new IllegalArgumentException("updated.lastModifiedBy mora biti postavljen");
        }
        if (updated.lastModifiedBy().routingNumber() == entity.getLastModifiedByRoutingNumber()
                && updated.lastModifiedBy().id().equals(entity.getLastModifiedByIdString())) {
            // §3.3 — turn violation: 409 Conflict.
            throw new InterbankExceptions.InterbankNegotiationConflictException(
                    "Nije turn pozivaoca — pregovor je poslednje izmenjen od strane "
                            + entity.getLastModifiedByRoutingNumber() + ":"
                            + entity.getLastModifiedByIdString());
        }

        // Caller mora biti jedna od strana (buyer ili seller).
        ForeignBankId localPartyAsForeign = new ForeignBankId(
                requireMyRoutingNumber(),
                (entity.getLocalPartyType() == InterbankPartyType.SELLER
                        ? toForeignIdString(entity.getLocalPartyId(), entity.getLocalPartyRole()) : null));
        ForeignBankId foreignPartyAsForeign = new ForeignBankId(
                entity.getForeignPartyRoutingNumber(), entity.getForeignPartyIdString());

        boolean callerIsLocal = entity.getLocalPartyType() == InterbankPartyType.SELLER
                && updated.lastModifiedBy().equals(localPartyAsForeign);
        boolean callerIsForeign = updated.lastModifiedBy().equals(foreignPartyAsForeign);

        if (!callerIsLocal && !callerIsForeign) {
            throw new IllegalArgumentException(
                    "lastModifiedBy mora biti buyer ili seller iz pregovora");
        }

        // Update polja koja mogu da se menjaju (po §3.3: amount, pricePerUnit, premium, settlementDate).
        if (updated.amount() != null) {
            // M-3: counter-offer amount mora takodje biti ceo broj.
            validateIntegerAmount(updated.amount());
            entity.setAmount(updated.amount());
        }
        if (updated.pricePerUnit() != null && updated.pricePerUnit().amount() != null) {
            entity.setPricePerUnit(updated.pricePerUnit().amount());
            entity.setPriceCurrency(updated.pricePerUnit().currency().name());
        }
        if (updated.premium() != null && updated.premium().amount() != null) {
            entity.setPremium(updated.premium().amount());
            entity.setPremiumCurrency(updated.premium().currency().name());
        }
        if (updated.settlementDate() != null) {
            // M-2 fix: cuvamo full ISO 8601 sa TZ.
            entity.setSettlementDate(updated.settlementDate());
        }
        entity.setLastModifiedByRoutingNumber(updated.lastModifiedBy().routingNumber());
        entity.setLastModifiedByIdString(updated.lastModifiedBy().id());

        negotiationRepository.save(entity);
        log.info("OTC inbound: counter-offer applied on negotiation {}", negotiationId);
    }

    /**
     * §3.4 — vrati trenutno stanje pregovora kao OtcNegotiation (offer + isOngoing).
     */
    @Transactional(readOnly = true)
    public OtcNegotiation getNegotiation(ForeignBankId negotiationId) {
        if (negotiationId == null) throw new IllegalArgumentException("negotiationId ne sme biti null");
        InterbankOtcNegotiation entity = lookupByNegotiationId(negotiationId);
        return mapToProtocol(entity);
    }

    /**
     * §3.5 — bilo koja strana zatvara pregovor. isOngoing=false, status=CLOSED.
     * Idempotentno: ponovo DELETE je no-op.
     */
    @Transactional
    public void closeReceivedNegotiation(ForeignBankId negotiationId) {
        if (negotiationId == null) throw new IllegalArgumentException("negotiationId ne sme biti null");

        Optional<InterbankOtcNegotiation> opt = negotiationRepository
                .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                        negotiationId.routingNumber(), negotiationId.id());
        if (opt.isEmpty()) return; // idempotent — nema entiteta, nema sta da zatvorimo

        InterbankOtcNegotiation entity = opt.get();
        if (!entity.isOngoing()) return; // vec zatvoren

        entity.setOngoing(false);
        entity.setStatus(InterbankOtcNegotiationStatus.CLOSED);
        negotiationRepository.save(entity);
        log.info("OTC inbound: closed negotiation {}", negotiationId);
    }

    /**
     * §3.6 — kupac (foreign) prihvata ponudu. Mi smo seller (autoritativni).
     * <p>
     * Po §3.6 transakcija ima 4 postinga (sve protiv PERSON racuna; lokalna sign
     * konvencija: pozitivno = debit/povecava, negativno = kredit/smanjuje):
     * <pre>
     *   buyer  credit O.premium             (foreign Person, premija ide od kupca)
     *   seller debit  O.premium             (seller account, premija stize prodavcu)
     *   buyer  debit  optionContract(1)     (foreign Person dobija 1 ugovor)
     *   seller credit optionContract(1)     (local Person, prodavac daje 1 ugovor)
     * </pre>
     * <p>
     * Spec §3.6 zadnji paragraf: "Note that the debit of the Seller above should
     * reserve their stocks as part of the contract." — pa MORAMO rezervisati
     * sellerove hartije pri accept-u (C-3 fix). Rezervacija ide kroz
     * {@link InterbankReservationApplier#reserveStock} sa idempotentnim kljucem
     * po {@code negotiationId} — retry je bezbedan.
     * <p>
     * I-3 fix po Celini 5 audit-u: ranije je sav posao bio u jednom velikom
     * {@code @Transactional} metodu, ukljucujuci 2PC HTTP I/O. To je pinned
     * Hikari konekciju kroz mrezne pozive (60s read timeout × broj partnera).
     * Sad je posao razdvojen:
     * <ol>
     *   <li>{@link #persistAcceptArtifacts(InterbankOtcNegotiation)} — kratak Tx:
     *       kreira contract + flip negotiation na ACCEPTED.</li>
     *   <li>Rezervacija hartija + 2PC izvrsenje OUT-OF-TX (mrezni I/O).</li>
     *   <li>{@link #compensateAccept(Long, Long, Long, String, String, int)} —
     *       kratak Tx za compensacijski rollback ako 2PC pukne.</li>
     * </ol>
     */
    public void acceptReceivedNegotiation(ForeignBankId negotiationId) {
        acceptReceivedNegotiation(negotiationId, null);
    }

    /**
     * T2-F overload (Tim 1 cross-bank Stage C, 2026-05-20): accept sa sender routing-om
     * za turn check. Bez sender param-a (legacy poziv iz internih putova) turn check
     * se preskace — partner banke uvek pozivaju kroz overload sa sender-om.
     */
    public void acceptReceivedNegotiation(ForeignBankId negotiationId, Integer senderRoutingNumber) {
        if (negotiationId == null) throw new IllegalArgumentException("negotiationId ne sme biti null");

        AcceptPrep prep = self.persistAcceptArtifacts(negotiationId, senderRoutingNumber);

        // OUT-OF-TX: rezervacija hartija (§3.6 zadnji paragraf, C-3 fix).
        // Idempotency kljuc deterministicki po negotiationId-u — partnerov retry,
        // duplicate accept, ili nas mid-flight crash sve gadjaju isti kljuc i
        // trading-service vraca kesiran odgovor (vidi ReservationApplier).
        String reserveKey = stockReservationIdempotencyKey(negotiationId);
        try {
            reservationApplier.reserveStock(reserveKey,
                    prep.sellerUserId(), prep.sellerRole(),
                    prep.ticker(), prep.quantity());
        } catch (RuntimeException reservationFail) {
            // Compensate: vrati pregovor u ACTIVE + obrisi contract.
            self.compensateAccept(prep.negotiationEntityId(), prep.contractEntityId(),
                    prep.sellerUserId(), prep.sellerRole(), prep.ticker(), prep.quantity());
            throw new InterbankExceptions.InterbankProtocolException(
                    "Rezervacija hartija prodavca nije uspela: " + reservationFail.getMessage());
        }

        // OUT-OF-TX: izvrsi 2PC. Ako 2PC pukne (NO glas, mrezni fail), kompenzuj
        // pregovor + contract + rezervaciju.
        try {
            transactionExecutor.execute(prep.tx());
        } catch (RuntimeException twoPcFail) {
            self.compensateAccept(prep.negotiationEntityId(), prep.contractEntityId(),
                    prep.sellerUserId(), prep.sellerRole(), prep.ticker(), prep.quantity());
            throw twoPcFail;
        }

        log.info("OTC inbound: accepted negotiation {} -> contract {}",
                negotiationId, prep.contractEntityId());
    }

    /**
     * §3.6 prvi korak — perzistira contract entitet + flip pregovora na ACCEPTED.
     * Kratak {@code @Transactional} blok bez mreznih poziva — Hikari konekcija
     * se odmah oslobadja (I-3 fix).
     */
    @Transactional
    public AcceptPrep persistAcceptArtifacts(ForeignBankId negotiationId) {
        return persistAcceptArtifacts(negotiationId, null);
    }

    /**
     * T2-F overload — sa sender routing-om za turn-check (sender != lastModifier).
     * Spec semantika §3.6: BUYER prihvata SELLER-ovu poslednju ponudu (ili kontra-ponudu).
     * Ako sender (X-Api-Key) pripada istoj banci kao lastModifier, accept je
     * "ja accept-ujem svoj sopstveni offer" — strange semantically, blokiramo 409.
     */
    @Transactional
    public AcceptPrep persistAcceptArtifacts(ForeignBankId negotiationId, Integer senderRoutingNumber) {
        InterbankOtcNegotiation entity = lookupByNegotiationId(negotiationId);
        if (!entity.isOngoing() || entity.getStatus() != InterbankOtcNegotiationStatus.ACTIVE) {
            // T2-E fix (Tim 1 cross-bank Stage C, 2026-05-20): zatvoreni pregovor je
            // 409 Conflict, ne 400 (mirror Tim 1 ponasanju + Tim 2 §6.6 spec).
            throw new InterbankExceptions.InterbankNegotiationConflictException(
                    "Pregovor " + negotiationId + " nije aktivan (status=" + entity.getStatus() + ")");
        }
        // T2-F turn check (Tim 1 cross-bank Stage C, 2026-05-20): sender ne sme
        // biti lastModifier — strana koja je upravo modifikovala ne moze odmah
        // accept-ovati sopstveni offer. Mirror Tim 1 "Not your turn to accept".
        if (senderRoutingNumber != null
                && entity.getLastModifiedByRoutingNumber() == senderRoutingNumber) {
            throw new InterbankExceptions.InterbankNegotiationConflictException(
                    "Nije turn za accept — vasa banka je poslednja modifikovala pregovor");
        }
        if (entity.getLocalPartyType() != InterbankPartyType.SELLER) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "Mi nismo seller u ovom pregovoru — accept moze samo na sellerovoj banci");
        }
        // §3.6 — settlementDate validacija. Spec koristi OffsetDateTime; sad i mi
        // (M-2 fix). Poredimo UTC trenutni trenutak da izbegnemo TZ edge case.
        if (!entity.getSettlementDate().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new InterbankExceptions.InterbankNegotiationConflictException(
                    "settlementDate je prosao — pregovor je istekao");
        }

        ForeignBankId myParty = new ForeignBankId(requireMyRoutingNumber(),
                toForeignIdString(entity.getLocalPartyId(), entity.getLocalPartyRole()));
        ForeignBankId buyerParty = new ForeignBankId(
                entity.getForeignPartyRoutingNumber(), entity.getForeignPartyIdString());

        // Forma 4-posting transakciju (§3.6). C-1 fix: optionContract postings
        // su PERSON↔PERSON, qty=1 (ne `k` — spec eksplicitno kaze "Buyer — Debit
        // ONE optionContract(O)"; k je broj akcija u opciji, ne broj ugovora).
        CurrencyCode premiumCcy = CurrencyCode.valueOf(entity.getPremiumCurrency());
        Asset premiumAsset = new Asset.Monas(new MonetaryAsset(premiumCcy));

        String sellerAccountNumber = resolveLocalAccount(entity.getLocalPartyId(),
                entity.getLocalPartyRole(), premiumCcy.name())
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Seller nema racun u valuti " + premiumCcy.name()));

        OptionDescription optDesc = new OptionDescription(
                negotiationId,
                new StockDescription(entity.getTicker()),
                new MonetaryValue(CurrencyCode.valueOf(entity.getPriceCurrency()), entity.getPricePerUnit()),
                entity.getSettlementDate(),
                entity.getAmount()
        );
        Asset optionAsset = new Asset.OptionAsset(optDesc);

        BigDecimal premium = entity.getPremium();

        // §3.6:
        //   p1: buyer credit premium       (Person buyer, -premium, Monas)
        //   p2: seller debit premium       (Account seller, +premium, Monas)
        //   p3: buyer debit 1 optionContract (Person buyer, +1, OptionAsset)
        //   p4: seller credit 1 optionContract (Person seller, -1, OptionAsset)  ← C-1 fix
        Posting p1 = new Posting(new TxAccount.Person(buyerParty), premium.negate(), premiumAsset);
        Posting p2 = new Posting(new TxAccount.Account(sellerAccountNumber), premium, premiumAsset);
        Posting p3 = new Posting(new TxAccount.Person(buyerParty), BigDecimal.ONE, optionAsset);
        Posting p4 = new Posting(new TxAccount.Person(myParty), BigDecimal.ONE.negate(), optionAsset);

        Transaction tx = transactionExecutor.formTransaction(
                List.of(p1, p2, p3, p4),
                "OTC accept negotiation " + negotiationId,
                null, "OTC", "Premium za opcioni ugovor"
        );

        // Kreiraj contract pre 2PC tako da exercise (mozda u istom milisekundu)
        // moze da ga nadje preko sourceNegotiationId.
        InterbankOtcContract contract = new InterbankOtcContract();
        contract.setSourceNegotiationId(entity.getId());
        contract.setLocalPartyType(InterbankPartyType.SELLER);
        contract.setLocalPartyId(entity.getLocalPartyId());
        contract.setLocalPartyRole(entity.getLocalPartyRole());
        contract.setForeignPartyRoutingNumber(entity.getForeignPartyRoutingNumber());
        contract.setForeignPartyIdString(entity.getForeignPartyIdString());
        contract.setTicker(entity.getTicker());
        contract.setQuantity(entity.getAmount());
        contract.setStrikePrice(entity.getPricePerUnit());
        contract.setStrikeCurrency(entity.getPriceCurrency());
        contract.setPremium(entity.getPremium());
        contract.setPremiumCurrency(entity.getPremiumCurrency());
        contract.setSettlementDate(entity.getSettlementDate());
        contract.setStatus(InterbankOtcContractStatus.ACTIVE);
        contract.setCreatedAt(LocalDateTime.now());
        contractRepository.save(contract);

        // R7 observability: sklopljen inter-bank OTC ugovor (banka2_otc_contracts_created_total).
        if (businessMetrics != null) {
            businessMetrics.recordOtcContractCreated();
        }

        entity.setStatus(InterbankOtcNegotiationStatus.ACCEPTED);
        entity.setOngoing(false);
        entity.setLastModifiedByRoutingNumber(myParty.routingNumber());
        entity.setLastModifiedByIdString(myParty.id());
        negotiationRepository.save(entity);

        return new AcceptPrep(
                entity.getId(), contract.getId(), tx,
                entity.getLocalPartyId(), entity.getLocalPartyRole(),
                entity.getTicker(), entity.getAmount().intValueExact()
        );
    }

    /**
     * Compensacijski rollback kad rezervacija hartija ili 2PC pukne posle
     * {@link #persistAcceptArtifacts}: vraca pregovor u ACTIVE, brise contract,
     * pokusava da oslobodi hartije (best-effort). Kratak {@code @Transactional}.
     */
    @Transactional
    public void compensateAccept(Long negotiationEntityId, Long contractEntityId,
                                  Long sellerUserId, String sellerRole,
                                  String ticker, int quantity) {
        negotiationRepository.findById(negotiationEntityId).ifPresent(e -> {
            e.setStatus(InterbankOtcNegotiationStatus.ACTIVE);
            e.setOngoing(true);
            negotiationRepository.save(e);
        });
        contractRepository.findById(contractEntityId)
                .ifPresent(contractRepository::delete);

        // Best-effort release — rezervacija mozda nije ni izvrsena (npr. kad
        // 2PC pukne pre nego sto reserveStock uspe), ali idempotency je takav
        // da bezopasan poziv ne pravi stetu (trading-service vraca kesiran
        // odgovor ili 404). Logujemo gresku, ne propagiramo.
        try {
            reservationApplier.releaseStock(
                    stockReservationReleaseKey(negotiationEntityId),
                    sellerUserId, sellerRole, ticker, quantity);
        } catch (RuntimeException releaseFail) {
            log.warn("OTC accept compensate: releaseStock failed for neg={}, ticker={}, qty={}: {}",
                    negotiationEntityId, ticker, quantity, releaseFail.getMessage());
        }
    }

    /**
     * Deterministicki idempotency kljuc za hartijsku rezervaciju pri accept-u.
     * Format: {@code "otc-accept-{rn}-{idString}:stock-reserve"}. Sluzi za to
     * da partnerov retry, duplikat-accept, ili nas mid-flight crash pogadjaju
     * isti kljuc → trading-service vraca kesiran odgovor.
     */
    private static String stockReservationIdempotencyKey(ForeignBankId negotiationId) {
        return "otc-accept-" + negotiationId.routingNumber() + "-" + negotiationId.id()
                + ":stock-reserve";
    }

    /**
     * Release kljuc je drugaciji od reserve-kljuca (kombinacija fazu + neg ID):
     * ako bi bio isti, trading-service idempotency kes bi tretirao oba kao isti
     * poziv. Zato koristimo lokalni entitetski id (sigurno != od inicijalnog
     * routing+idString reserve-kljuca).
     */
    private static String stockReservationReleaseKey(Long negotiationEntityId) {
        return "otc-accept-compensate-" + negotiationEntityId + ":stock-release";
    }

    /**
     * P0-B6 (Nalaz 1) — auto-expiry isteklih inter-bank OTC opcionih ugovora.
     *
     * <p>Bug koji resava: inter-bank ugovor se kreira ACTIVE pri §3.6 accept-u, sa
     * {@code settlementDate}-om i rezervisanim sellerovim hartijama (§3.6 zadnji
     * paragraf). Jedini prelaz iz ACTIVE je →EXERCISED (kupac iskoristi opciju).
     * {@code EXPIRED} se NIGDE nije upisivao, pa kad settlementDate prodje bez
     * exercise-a ugovor je ostajao TRAJNO ACTIVE, a sellerova rezervacija hartija
     * je bila stranded zauvek (§2.7.2: "if that option was not used, the resources
     * stuck in an option shall be un-reserved" — sad postovano).
     *
     * <p>Cist lokalni bookkeeping — BEZ wire-promene. Za svaki ACTIVE ugovor sa
     * {@code settlementDate < now}:
     * <ul>
     *   <li>ako smo MI SELLER ({@code localPartyType == SELLER}) — oslobadjamo lokalnu
     *       rezervaciju hartija preko {@link InterbankReservationApplier#releaseStock}
     *       (idempotentno; trading-service razresava listing po ticker-u). Ako smo
     *       BUYER, sellerova rezervacija zivi u partner banci — nemamo sta lokalno da
     *       oslobodimo, samo markiramo status.</li>
     *   <li>postavljamo status EXPIRED.</li>
     * </ul>
     *
     * <p>Po-ugovor {@code REQUIRES_NEW} izolacija ({@link #expireOneContract}) tako da
     * jedan release-fail (mrezni, trading-service down) ne rollback-uje vec uspesno
     * isteknute ugovore iz iste runde.
     *
     * @return broj ugovora markiranih EXPIRED u ovoj rundi
     */
    public int expireSettledContracts() {
        List<InterbankOtcContract> expired = contractRepository.findByStatusAndSettlementDateBefore(
                InterbankOtcContractStatus.ACTIVE, OffsetDateTime.now(ZoneOffset.UTC));
        int count = 0;
        for (InterbankOtcContract c : expired) {
            try {
                self.expireOneContract(c.getId());
                count++;
            } catch (RuntimeException e) {
                log.warn("OTC inter-bank expiry: ugovor #{} (ticker={}) nije uspeo da istekne: {}",
                        c.getId(), c.getTicker(), e.getMessage());
            }
        }
        if (count > 0) {
            log.info("OTC inter-bank: {} ugovora isteklo i markirano EXPIRED", count);
        }
        return count;
    }

    /**
     * Istice jedan inter-bank OTC ugovor u sopstvenoj transakciji ({@code REQUIRES_NEW}).
     * Status-guard: samo ACTIVE → EXPIRED (race sa exercise-om koji je u medjuvremenu
     * markirao EXERCISED ne sme biti pregazen). Sellerova rezervacija hartija se
     * oslobadja PRE flip-a statusa; idempotentan release ne pravi stetu pri retry-u.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void expireOneContract(Long contractId) {
        InterbankOtcContract contract = contractRepository.findById(contractId).orElse(null);
        if (contract == null || contract.getStatus() != InterbankOtcContractStatus.ACTIVE) {
            // Vec EXERCISED/EXPIRED u medjuvremenu (race) — ne diramo.
            return;
        }

        // Oslobodi sellerovu rezervaciju hartija SAMO ako smo MI seller. Ako smo
        // buyer, sellerova rezervacija je u partner banci (njihov lokalni bookkeeping).
        if (contract.getLocalPartyType() == InterbankPartyType.SELLER) {
            int qty = contract.getQuantity() != null ? contract.getQuantity().intValueExact() : 0;
            if (qty > 0) {
                reservationApplier.releaseStock(
                        otcExpiryReleaseKey(contract.getId()),
                        contract.getLocalPartyId(), contract.getLocalPartyRole(),
                        contract.getTicker(), qty);
            }
        }

        contract.setStatus(InterbankOtcContractStatus.EXPIRED);
        contractRepository.save(contract);
        log.info("OTC inter-bank ugovor #{} istekao (settlementDate={}) — status EXPIRED{}",
                contract.getId(), contract.getSettlementDate(),
                contract.getLocalPartyType() == InterbankPartyType.SELLER
                        ? ", sellerova rezervacija hartija oslobodjena" : "");
    }

    /**
     * Idempotency kljuc za oslobadjanje hartija pri auto-expiry-ju. Razlicit od
     * reserve/compensate kljuceva (po contract id + faza "expire-release") da
     * trading-service idempotency kes ne pomesa pozive.
     */
    private static String otcExpiryReleaseKey(Long contractEntityId) {
        return "otc-expire-" + contractEntityId + ":stock-release";
    }

    /**
     * Vrednost koja se prosledjuje iz prvog Tx u out-of-Tx 2PC + compensaciju.
     * Samo immutable record da nema racing/aliasing.
     */
    public record AcceptPrep(
            Long negotiationEntityId,
            Long contractEntityId,
            Transaction tx,
            Long sellerUserId,
            String sellerRole,
            String ticker,
            int quantity
    ) {}

    /**
     * §3.7 — vrati friendly ime za nas lokalni id (prefix-encoded "C-" / "E-").
     * Caller je partner banka koja prikazuje user info u svom UI-u.
     */
    @Transactional(readOnly = true)
    public UserInformation serveUserInfo(String localUserId) {
        if (localUserId == null || localUserId.isBlank()) {
            throw new InterbankExceptions.InterbankUserNotFoundException("localUserId je prazan");
        }

        LocalParty p;
        try {
            p = parseLocalPartyId(localUserId);
        } catch (IllegalArgumentException malformed) {
            // §3.7 — opaque ID koji ne razumemo se tretira kao "not found", ne kao 400.
            throw new InterbankExceptions.InterbankUserNotFoundException(
                    "Nepoznat id format: " + localUserId);
        }
        String myDisplay = properties.getMyBankDisplayName();
        if (myDisplay == null || myDisplay.isBlank()) myDisplay = "Banka 2";

        if ("CLIENT".equals(p.role())) {
            Optional<Client> cOpt = clientRepository.findById(p.userId());
            if (cOpt.isEmpty()) {
                throw new InterbankExceptions.InterbankUserNotFoundException(
                        "Klijent " + p.userId() + " ne postoji");
            }
            Client c = cOpt.get();
            return new UserInformation(myDisplay,
                    nullSafeJoin(c.getFirstName(), c.getLastName()));
        } else {
            Optional<Employee> eOpt = employeeRepository.findById(p.userId());
            if (eOpt.isEmpty()) {
                throw new InterbankExceptions.InterbankUserNotFoundException(
                        "Zaposleni " + p.userId() + " ne postoji");
            }
            Employee e = eOpt.get();
            return new UserInformation(myDisplay,
                    nullSafeJoin(e.getFirstName(), e.getLastName()));
        }
    }

    // ────────────────────────── helpers ──────────────────────────────────────

    private void validateOutboundOffer(OtcOffer offer) {
        if (offer == null) throw new IllegalArgumentException("offer ne sme biti null");
        if (offer.buyerId() == null) throw new IllegalArgumentException("offer.buyerId ne sme biti null");
        if (offer.sellerId() == null) throw new IllegalArgumentException("offer.sellerId ne sme biti null");
        if (offer.lastModifiedBy() == null) throw new IllegalArgumentException("offer.lastModifiedBy ne sme biti null");
        if (offer.stock() == null) throw new IllegalArgumentException("offer.stock ne sme biti null");
        if (offer.pricePerUnit() == null) throw new IllegalArgumentException("offer.pricePerUnit ne sme biti null");
        if (offer.premium() == null) throw new IllegalArgumentException("offer.premium ne sme biti null");
        if (offer.amount() == null) throw new IllegalArgumentException("offer.amount ne sme biti null");
        validateIntegerAmount(offer.amount());
        if (offer.pricePerUnit().amount() == null || offer.pricePerUnit().amount().signum() <= 0) {
            throw new IllegalArgumentException("pricePerUnit mora biti > 0");
        }
        if (offer.premium().amount() == null || offer.premium().amount().signum() < 0) {
            throw new IllegalArgumentException("premium ne moze biti negativan");
        }
        OffsetDateTime settlement = offer.settlementDate();
        if (settlement == null || !settlement.isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException(
                    "settlementDate mora biti u buducnosti (zadato: " + settlement + ")");
        }
        if (offer.buyerId().equals(offer.sellerId())) {
            throw new IllegalArgumentException("buyer i seller ne mogu biti isto lice");
        }
    }

    /**
     * M-3 fix po Celini 5 audit-u: §2.7.2 zahteva "amount of stocks exchanged
     * must be an integer greater than zero". Sirovi {@code signum() > 0} provera
     * nije dovoljna — frakcioni iznosi (npr. 1.5) bi prosli. Sad odbacujemo
     * sve sto nije celobrojno.
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

    private void ensureLocalParty(ForeignBankId who, String message) {
        int myRouting = requireMyRoutingNumber();
        if (who.routingNumber() != myRouting) {
            throw new IllegalArgumentException(
                    message + " (rn=" + who.routingNumber() + ", nasa=" + myRouting + ")");
        }
    }

    private int requireMyRoutingNumber() {
        Integer myRouting = properties.getMyRoutingNumber();
        if (myRouting == null) {
            throw new InterbankExceptions.InterbankException(
                    "interbank.my-routing-number nije konfigurisan u properties.");
        }
        return myRouting;
    }

    /** Public accessor — kontroleri koriste za §3.7 validaciju routingNumber-a. */
    public int requireMyRouting() {
        return requireMyRoutingNumber();
    }

    private InterbankOtcNegotiation lookupByNegotiationId(ForeignBankId negotiationId) {
        return negotiationRepository
                .findByForeignNegotiationRoutingNumberAndForeignNegotiationIdString(
                        negotiationId.routingNumber(), negotiationId.id())
                .orElseThrow(() -> new InterbankExceptions.InterbankNegotiationNotFoundException(
                        "Pregovor " + negotiationId + " ne postoji"));
    }

    /** Parsira "C-{id}" ili "E-{id}" u (userId, role). */
    private LocalParty parseLocalPartyId(String prefixed) {
        if (prefixed == null || prefixed.length() < 3) {
            throw new IllegalArgumentException("Lokalni party id mora biti formata C-{id} ili E-{id}");
        }
        String role;
        if (prefixed.startsWith(CLIENT_ID_PREFIX)) {
            role = "CLIENT";
        } else if (prefixed.startsWith(EMPLOYEE_ID_PREFIX)) {
            role = "EMPLOYEE";
        } else {
            throw new IllegalArgumentException(
                    "Lokalni party id mora pocinjati sa 'C-' ili 'E-' (zadato: " + prefixed + ")");
        }
        long id;
        try {
            id = Long.parseLong(prefixed.substring(2));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Lokalni party id ima nevalidnu numericku ID komponentu: " + prefixed);
        }
        return new LocalParty(id, role);
    }

    /** Suprotno od parseLocalPartyId — formira "C-{id}" / "E-{id}". */
    private static String toForeignIdString(Long userId, String role) {
        return ("CLIENT".equalsIgnoreCase(role) ? CLIENT_ID_PREFIX : EMPLOYEE_ID_PREFIX) + userId;
    }

    /**
     * Pronalazi seller-ov racun u zadatoj valuti — koristi se u §3.6 da se
     * premium credit-uje na pravi racun. Vraca prvi racun matchovan po
     * vlasniku/roli i valuti.
     */
    private Optional<String> resolveLocalAccount(Long userId, String role, String currencyCode) {
        if (!"CLIENT".equalsIgnoreCase(role)) {
            // Zaposleni nemaju klijentske racune — zauzimamo bankin trading
            // racun (kao kod intra-bank exec) ali za pojednostavljen P1
            // demo prijavljujemo da ne podrzavamo employee-as-seller na
            // inter-bank OTC-u sve dok se T11 fund-flow ne prosiri.
            return Optional.empty();
        }
        Optional<Client> cOpt = clientRepository.findById(userId);
        if (cOpt.isEmpty()) return Optional.empty();
        return cOpt.get().getAccounts().stream()
                .filter(a -> a.getCurrency() != null
                        && currencyCode.equalsIgnoreCase(a.getCurrency().getCode()))
                .map(a -> a.getAccountNumber())
                .findFirst();
    }

    private OtcNegotiation mapToProtocol(InterbankOtcNegotiation e) {
        ForeignBankId localAsForeign = new ForeignBankId(
                requireMyRoutingNumber(),
                toForeignIdString(e.getLocalPartyId(), e.getLocalPartyRole()));
        ForeignBankId foreignAsForeign = new ForeignBankId(
                e.getForeignPartyRoutingNumber(), e.getForeignPartyIdString());

        ForeignBankId buyerId, sellerId;
        if (e.getLocalPartyType() == InterbankPartyType.SELLER) {
            sellerId = localAsForeign;
            buyerId = foreignAsForeign;
        } else {
            buyerId = localAsForeign;
            sellerId = foreignAsForeign;
        }

        ForeignBankId lastModifiedBy = new ForeignBankId(
                e.getLastModifiedByRoutingNumber(), e.getLastModifiedByIdString());

        return new OtcNegotiation(
                new StockDescription(e.getTicker()),
                e.getSettlementDate(),
                new MonetaryValue(CurrencyCode.valueOf(e.getPriceCurrency()), e.getPricePerUnit()),
                new MonetaryValue(CurrencyCode.valueOf(e.getPremiumCurrency()), e.getPremium()),
                buyerId,
                sellerId,
                e.getAmount(),
                lastModifiedBy,
                e.isOngoing()
        );
    }

    private static String nullSafeJoin(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        if (f.isEmpty()) return l;
        if (l.isEmpty()) return f;
        return f + " " + l;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * R3 1539 — monitor kljuc za seller-kvota serijalizaciju. Bazira se na
     * sirovom {@code sellerId.id()} (prefiksiran C-/E- string) + ticker, da bi
     * dva inbound pregovora za istog prodavca i ticker delila isti monitor.
     * Defanzivan na null (fallback na "?" — i dalje serijalizuje, samo grublje).
     */
    private static String quotaLockKey(OtcOffer offer) {
        String sellerId = (offer.sellerId() != null && offer.sellerId().id() != null)
                ? offer.sellerId().id() : "?";
        String ticker = (offer.stock() != null && offer.stock().ticker() != null)
                ? offer.stock().ticker() : "?";
        return sellerId + "|" + ticker;
    }

    /** Test hook — programmatic cache invalidation (paket-private). */
    void invalidatePublicStockCache(int routingNumber) {
        publicStockCache.remove(routingNumber);
    }

    /** TTL-tracked cache entry za §3.1 outbound rezultate. Package-private za testove. */
    record CachedPublicStocks(List<PublicStock> stocks, Instant fetchedAt) {
        boolean isFresh(Instant now) {
            return Duration.between(fetchedAt, now).compareTo(PUBLIC_STOCK_TTL) < 0;
        }
    }

    private record LocalParty(Long userId, String role) {}
}
