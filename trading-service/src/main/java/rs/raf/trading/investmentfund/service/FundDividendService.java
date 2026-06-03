package rs.raf.trading.investmentfund.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.dto.FundDividendHistoryDto;
import rs.raf.trading.investmentfund.model.ClientFundPosition;
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.ClientFundTransactionStatus;
import rs.raf.trading.investmentfund.model.FundDividendDistributionLedger;
import rs.raf.trading.investmentfund.model.InvestmentFund;
import rs.raf.trading.investmentfund.repository.ClientFundPositionRepository;
import rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository;
import rs.raf.trading.investmentfund.repository.FundDividendDistributionLedgerRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.investmentfund.scheduler.FundValueSnapshotScheduler;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.order.service.FundReservationService;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.util.ListingCurrencyResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * B11 — Dividende u investicionim fondovima.
 *
 * <p>Servis vodi tri faze obrade dividendi koje pristignu na racun fonda:
 * <ol>
 *   <li><strong>creditDividendToFund</strong> — DividendScheduler proceni
 *       da je portfolio pozicija FUND vlasnistva i pozove ovaj metod;
 *       sredstva idu na bankin trading racun fonda preko banka-core
 *       {@code /internal/funds/credit} a lokalno se evidentira
 *       {@link ClientFundTransactionStatus#DIVIDEND_INFLOW}.</li>
 *   <li><strong>reinvestDividends</strong> — kreira interni BUY order u
 *       ime fonda za iste hartije (po dnevnoj ASK ceni). Order rezervisanje
 *       sredstava ide kroz {@link FundReservationService}.</li>
 *   <li><strong>distributeDividendsToClients</strong> — preostali cash u
 *       fondu raspodeljuje klijentima srazmerno {@code totalInvested}.</li>
 * </ol>
 *
 * <p>NAPOMENA (mikroservisi, faza 2c): racuni su u banka-core. Sve novcane
 * noge idu preko {@link BankaCoreClient} (credit/transfer) sa
 * deterministickim idempotency kljucevima po transakciji.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FundDividendService {

    private static final String RSD = "RSD";
    private static final int MONEY_SCALE = 4;

    private final InvestmentFundRepository investmentFundRepository;
    private final ClientFundTransactionRepository clientFundTransactionRepository;
    private final ClientFundPositionRepository clientFundPositionRepository;
    private final ListingRepository listingRepository;
    private final OrderRepository orderRepository;
    private final FundReservationService fundReservationService;
    private final CurrencyConversionService currencyConversionService;
    private final FundValueSnapshotScheduler fundValueSnapshotScheduler;
    private final BankaCoreClient bankaCoreClient;
    private final FundDividendDistributionLedgerRepository distributionLedgerRepository;
    private final FundDividendLedgerWriter ledgerWriter;

    @Transactional
    public ClientFundTransaction creditDividendToFund(Long fundId, Long listingId, BigDecimal totalDividendAmount) {
        if (fundId == null) {
            throw new IllegalArgumentException("Fund id je obavezan za B11 dividendni priliv.");
        }
        if (listingId == null) {
            throw new IllegalArgumentException("Listing id je obavezan za B11 dividendni priliv.");
        }
        if (totalDividendAmount == null || totalDividendAmount.signum() <= 0) {
            throw new IllegalArgumentException("Iznos dividende mora biti pozitivan.");
        }

        InvestmentFund fund = getActiveFund(fundId);
        InternalAccountDto fundAccount = getFundAccount(fund);
        BigDecimal amount = scale(totalDividendAmount);

        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setFundId(fund.getId());
        tx.setUserId(fund.getId());
        tx.setUserRole(UserRole.FUND);
        tx.setAmountRsd(amount);
        tx.setSourceAccountId(fundAccount.id());
        tx.setInflow(true);
        tx.setStatus(ClientFundTransactionStatus.DIVIDEND_INFLOW);
        tx.setCreatedAt(LocalDateTime.now());
        tx.setFailureReason("DIVIDEND_INFLOW listingId=" + listingId);

        ClientFundTransaction saved = clientFundTransactionRepository.save(tx);

        // banka-core kredit: fond racun (RSD) prima dividendu. Pozivalac
        // (DividendScheduler / DividendService) je vec konvertovao u RSD
        // ako je listing kotiran u stranoj valuti.
        //
        // P1-funds-1 (1552): idempotency kljuc je STABILAN — izveden iz
        // (fundId, listingId, paymentDate=danas), NE iz IDENTITY id-a
        // ClientFundTransaction-a. IDENTITY id NIJE postojan kroz rollback:
        // ako save(payout) ili neki kasniji korak padne POSLE banka-core
        // kredita → JPA rollback → retry dodeli nov txId → nov kljuc →
        // banka-core ne dedup-uje → fond bi bio DOUBLE-CREDITED. Kvartalna
        // isplata po listingu ide jednom dnevno, pa je (fund,listing,dan)
        // jedinstvena kotva koja prezivi rollback i identicna je pri re-run-u.
        String idempotencyKey = "fund-dividend-inflow-" + fund.getId()
                + "-" + listingId + "-" + LocalDate.now();
        bankaCoreClient.creditFunds(
                idempotencyKey,
                new CreditFundsRequest(fundAccount.id(), amount, BigDecimal.ZERO,
                        RSD, "Dividend inflow listingId=" + listingId + " fundTx#" + saved.getId()));

        fundValueSnapshotScheduler.snapshotFundIfMissing(fund);

        log.info(
                "B11 dividend inflow credited: fund={}, listing={}, amountRsd={}",
                fundId,
                listingId,
                amount
        );

        return saved;
    }

    @Transactional(readOnly = true)
    public List<ClientFundTransaction> listPendingDividends(Long fundId) {
        return clientFundTransactionRepository.findByFundIdAndStatusOrderByCreatedAtAsc(
                fundId,
                ClientFundTransactionStatus.DIVIDEND_INFLOW
        );
    }

    /**
     * Vraca istoriju svih dividendnih transakcija za zadati fond (B11).
     *
     * <p>Filtriramo sve {@link ClientFundTransaction} redove ciji status pripada
     * dividendnom lifecyclu (INFLOW / REINVESTED / DISTRIBUTED). Ne ukljucujemo
     * regularne invest/withdraw redove (status PENDING/COMPLETED/FAILED).</p>
     *
     * <p>Endpoint: {@code GET /funds/{fundId}/dividends}. Sortirano po
     * {@code createdAt DESC} (najnovije prvo).</p>
     */
    @Transactional(readOnly = true)
    public List<FundDividendHistoryDto> getFundDividendHistory(Long fundId) {
        if (fundId == null) {
            throw new IllegalArgumentException("Fund id je obavezan.");
        }
        // Garantujemo da fond postoji — vraca 404 ako ne (resolvuje GlobalExceptionHandler).
        investmentFundRepository.findById(fundId)
                .orElseThrow(() -> new EntityNotFoundException("Fond ne postoji: " + fundId));

        return clientFundTransactionRepository.findByFundIdOrderByCreatedAtDesc(fundId).stream()
                .filter(tx -> tx.getStatus() == ClientFundTransactionStatus.DIVIDEND_INFLOW
                        || tx.getStatus() == ClientFundTransactionStatus.DIVIDEND_REINVESTED
                        || tx.getStatus() == ClientFundTransactionStatus.DIVIDEND_DISTRIBUTED)
                .map(this::toDividendHistoryDto)
                .toList();
    }

    private FundDividendHistoryDto toDividendHistoryDto(ClientFundTransaction tx) {
        Long listingId = extractListingId(tx);
        String ticker = null;
        if (listingId != null) {
            ticker = listingRepository.findById(listingId)
                    .map(Listing::getTicker)
                    .orElse(null);
        }
        return FundDividendHistoryDto.builder()
                .id(tx.getId())
                .fundId(tx.getFundId())
                .listingId(listingId)
                .listingTicker(ticker)
                .paymentDate(tx.getCreatedAt() != null ? tx.getCreatedAt().toLocalDate() : null)
                .createdAt(tx.getCreatedAt())
                .completedAt(tx.getCompletedAt())
                .grossAmount(scale(tx.getAmountRsd()))
                .status(tx.getStatus() != null ? tx.getStatus().name() : null)
                .currency(RSD)
                .note(tx.getFailureReason())
                .build();
    }

    @Transactional
    public List<Order> reinvestDividends(Long fundId) {
        InvestmentFund fund = getActiveFund(fundId);
        InternalAccountDto fundAccount = getFundAccount(fund);
        List<ClientFundTransaction> pendingDividends = listPendingDividends(fundId);

        if (pendingDividends.isEmpty()) {
            log.info("B11 reinvest skipped: fund={} nema pending dividendi.", fundId);
            return List.of();
        }

        List<Order> createdOrders = new java.util.ArrayList<>();

        for (ClientFundTransaction dividendTx : pendingDividends) {
            Long listingId = extractListingId(dividendTx);

            if (listingId == null) {
                markFailed(dividendTx, "Nije moguce reinvestirati dividendu bez listingId reference.");
                continue;
            }

            // P1-funds-1 (1347): trajni ledger guard za reinvest put (mirror
            // distribute P1-2). Bez njega, sinhroni dispatch
            // (DividendService.dispatchFundDividendsByPolicy posle kvartalne
            // isplate) + dnevni scheduler (2:30) mogu oba videti isti
            // DIVIDEND_INFLOW red PRE flip-a na DIVIDEND_REINVESTED i kreirati
            // DVA auto-BUY ordera (dvostruko trosenje fond cash-a). Marker se
            // upisuje write-ahead (REQUIRES_NEW) TEK POSLE uspesno kreiranog
            // ordera; drugi konkurentni dispatch vidi marker → preskace.
            String reinvestKey = buildReinvestIdempotencyKey(fundId, dividendTx.getId());
            if (distributionLedgerRepository.existsByIdempotencyKey(reinvestKey)) {
                log.info("B11 reinvest skip (vec reinvestiran): fund={}, inflowTx={}, key={}",
                        fundId, dividendTx.getId(), reinvestKey);
                continue;
            }

            Listing listing = listingRepository.findById(listingId)
                    .orElseThrow(() -> new EntityNotFoundException("Listing nije pronadjen: " + listingId));

            BigDecimal dividendAmount = scale(dividendTx.getAmountRsd());
            // Re-fetch fund account so we observe latest balance after prior iterations.
            InternalAccountDto refreshedFundAccount = getFundAccount(fund);
            BigDecimal availableCash = scale(refreshedFundAccount.availableBalance());
            BigDecimal amountForReinvestment = dividendAmount.min(availableCash);

            if (amountForReinvestment.signum() <= 0) {
                markFailed(dividendTx, "Fond nema raspoloziv cash za reinvestiranje dividende.");
                continue;
            }

            BigDecimal priceInListingCurrency = resolveBuyPrice(listing);
            BigDecimal priceInRsd = convertToRsd(
                    priceInListingCurrency,
                    ListingCurrencyResolver.resolveSafe(listing, RSD)
            );

            if (priceInRsd.signum() <= 0) {
                markFailed(dividendTx, "Cena listinga nije validna za reinvestiranje.");
                continue;
            }

            int quantity = amountForReinvestment
                    .divide(priceInRsd, 0, RoundingMode.FLOOR)
                    .intValue();

            if (quantity <= 0) {
                // P2-state-machine-1 (R2 1422 / R1 499): rezidual manji od cene
                // jedne hartije. Ranije je bio prazan `continue` BEZ promene statusa
                // → red ostaje DIVIDEND_INFLOW → dnevni scheduler (2:30) i sinhroni
                // dispatch ga VECNO ponovo pokusavaju (eternal retry log spam, nikad
                // ne napreduje). Markiraj TERMINALNO (FAILED, paritet sa sibling
                // signum<=0 granom iznad) sa jasnim rezidual-razlogom — vise se ne
                // pokupi kroz listPendingDividends (samo DIVIDEND_INFLOW).
                log.warn(
                        "B11 reinvest residual (terminalno): fund={}, tx={}, amount={} nije dovoljan za jednu hartiju {} po ceni {} RSD.",
                        fundId,
                        dividendTx.getId(),
                        amountForReinvestment,
                        listing.getTicker(),
                        priceInRsd
                );
                markFailed(dividendTx,
                        "Rezidual dividende (" + amountForReinvestment + " RSD) manji od cene jedne hartije "
                                + listing.getTicker() + " (" + priceInRsd + " RSD) — nije reinvestiran.");
                continue;
            }

            BigDecimal reservedAmount = priceInRsd
                    .multiply(BigDecimal.valueOf(quantity))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            if (scale(refreshedFundAccount.availableBalance()).compareTo(reservedAmount) < 0) {
                throw new InsufficientFundsException(
                        "Fond nema dovoljno raspolozivih sredstava za reinvestiranje dividende."
                );
            }

            Order order = createInternalBuyOrder(
                    fund,
                    refreshedFundAccount,
                    listing,
                    quantity,
                    priceInListingCurrency,
                    reservedAmount
            );

            createdOrders.add(order);

            // P1-funds-1 (1347): write-ahead ledger marker TEK POSLE uspesnog
            // ordera (REQUIRES_NEW preko ledgerWriter), pa prezivi eventualni
            // outer-tx rollback i sledeci dispatch preskace ovaj priliv.
            FundDividendDistributionLedger reinvestLedger = new FundDividendDistributionLedger();
            reinvestLedger.setFundId(fundId);
            reinvestLedger.setClientUserId(fundId); // FUND kao "klijent" reinvest noge
            reinvestLedger.setSourceDividendInflowTxId(dividendTx.getId());
            reinvestLedger.setCycleKey("reinvest");
            reinvestLedger.setIdempotencyKey(reinvestKey);
            reinvestLedger.setAmountRsd(reservedAmount);
            ledgerWriter.recordPaid(reinvestLedger);

            dividendTx.setStatus(ClientFundTransactionStatus.DIVIDEND_REINVESTED);
            dividendTx.setCompletedAt(LocalDateTime.now());
            dividendTx.setFailureReason(
                    "DIVIDEND_REINVESTED orderId=" + order.getId() + ", listingId=" + listing.getId()
            );
            clientFundTransactionRepository.save(dividendTx);
        }

        fundValueSnapshotScheduler.snapshotFundIfMissing(fund);

        return createdOrders;
    }

    /**
     * Raspodeljuje preostali cash u fondu klijentima srazmerno {@code totalInvested}.
     *
     * <p><b>Concurrency / atomicnost (BE-FND-01):</b> Cela metoda je u jednom
     * {@code @Transactional} bloku, ali svaki per-klijent {@code transferFunds}
     * je nezavisna banka-core operacija. Da bi se izbeglo da concurrent
     * withdrawal izmedju pre-check-a i finalnog transfer-a izazove 409 nakon
     * sto su prethodni transferi vec uspeli (parcijalna raspodela →
     * orphan funds), pre svake iteracije re-fetch-ujemo fund account i
     * proveravamo {@code availableBalance}. Ako sredstva nedostaju, NE
     * pokusavamo dalje da prebacujemo (ostala raspodela ostaje kao
     * pending — log + early exit), tako da reconciler / sledeci poziv
     * pokupi preostale ClientFundPosition-e bez double-debit-ovanja.
     *
     * <p><b>P1-2 — idempotentnost kroz run-ove/rollback (no double-pay).</b>
     * Idempotency kljuc per-klijent transfera je <b>stabilan</b> i izvodi se iz
     * kotvi koje prezivljavaju rollback i identicne su pri re-run-u:
     * {@code "fund-dividend-distribution-" + fundId + "-" + clientUserId + "-" +
     * inflowAnchorTxId + "-" + cycleKey}, gde je {@code inflowAnchorTxId} id
     * najmanjeg pending DIVIDEND_INFLOW reda (vec commit-ovan, stabilan), a
     * {@code cycleKey} deterministicki diskriminator runde (hash svih pending
     * inflow id-eva — isti pri re-run-u za isti pending skup). NE izvodimo kljuc
     * iz {@code ClientFundTransaction.id} (IDENTITY id NIJE postojan kroz
     * rollback). Pre transfera proveravamo trajni
     * {@link FundDividendDistributionLedger} guard (zapisan write-ahead u
     * {@code REQUIRES_NEW} preko {@link FundDividendLedgerWriter}) — klijent koji
     * je vec placen za ovaj priliv se PRESKACE, cak i ako je prethodni run
     * parcijalno zavrsio i posle rollback-ovao (banka-core transferi su vec
     * commit-ovani out-of-process). Banka-core dedup po istom stabilnom kljucu je
     * primarna odbrana; ledger je sekundarni guard za cisto preskakanje.
     */
    @Transactional
    public List<ClientFundTransaction> distributeDividendsToClients(Long fundId) {
        InvestmentFund fund = getActiveFund(fundId);
        InternalAccountDto fundAccount = getFundAccount(fund);
        List<ClientFundTransaction> pendingDividends = listPendingDividends(fundId);

        if (pendingDividends.isEmpty()) {
            log.info("B11 distribution skipped: fund={} nema pending dividendi.", fundId);
            return List.of();
        }

        // P1-2: stabilne kotve idempotencije izvedene iz pending DIVIDEND_INFLOW
        // id-eva (vec commit-ovani, prezive rollback, identicni pri re-run-u).
        List<Long> inflowTxIds = pendingDividends.stream()
                .map(ClientFundTransaction::getId)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        Long inflowAnchorTxId = inflowTxIds.isEmpty() ? null : inflowTxIds.get(0);
        String cycleKey = buildCycleKey(inflowTxIds);

        BigDecimal totalDividend = pendingDividends.stream()
                .map(ClientFundTransaction::getAmountRsd)
                .filter(Objects::nonNull)
                .map(this::scale)
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);

        if (totalDividend.signum() <= 0) {
            return List.of();
        }

        if (scale(fundAccount.availableBalance()).compareTo(totalDividend) < 0) {
            throw new InsufficientFundsException(
                    "Fond nema dovoljno likvidnih sredstava za raspodelu dividendi."
            );
        }

        List<ClientFundPosition> positions = clientFundPositionRepository.findByFundId(fundId)
                .stream()
                .filter(position -> position.getTotalInvested() != null)
                .filter(position -> position.getTotalInvested().signum() > 0)
                .sorted(Comparator.comparing(ClientFundPosition::getId))
                .toList();

        BigDecimal totalInvested = positions.stream()
                .map(ClientFundPosition::getTotalInvested)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (positions.isEmpty() || totalInvested.signum() <= 0) {
            log.warn(
                    "B11 distribution skipped: fund={} nema klijentske pozicije za proporcionalnu raspodelu.",
                    fundId
            );
            return List.of();
        }

        List<ClientFundTransaction> distributions = new java.util.ArrayList<>();
        BigDecimal distributedSoFar = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        boolean partialDistribution = false;

        for (int i = 0; i < positions.size(); i++) {
            ClientFundPosition position = positions.get(i);

            BigDecimal clientAmount;

            if (i == positions.size() - 1) {
                clientAmount = totalDividend
                        .subtract(distributedSoFar)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            } else {
                clientAmount = totalDividend
                        .multiply(position.getTotalInvested())
                        .divide(totalInvested, MONEY_SCALE, RoundingMode.HALF_UP);

                distributedSoFar = distributedSoFar
                        .add(clientAmount)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            }

            if (clientAmount.signum() <= 0) {
                // R1 799: ne preskoci tiho — udeo klijenta se zaokruzio na 0 RSD
                // (mali totalInvested / scale). Vidljivo u logu da raspodela nije
                // izgubljena nego ispod min. jedinice obracuna.
                log.info("B11 distribution skip (udeo zaokruzen na 0): fund={}, client={}, "
                                + "totalInvested={}, fundTotalInvested={}, totalDividend={}",
                        fundId, position.getUserId(), position.getTotalInvested(),
                        totalInvested, totalDividend);
                continue;
            }

            // P1-2: stabilan idempotency kljuc (NE iz IDENTITY id-a). Identican
            // pri re-run-u za isti priliv → banka-core dedup + ledger guard.
            String idempotencyKey = buildDistributionIdempotencyKey(
                    fundId, position.getUserId(), inflowAnchorTxId, cycleKey);

            // P1-2: trajni guard — ako je ovaj klijent VEC placen za ovaj priliv
            // (marker zapisan write-ahead u prethodnom run-u i prezivi rollback),
            // preskoci ga. Sprecava double-pay posle parcijalnog run-a + cron re-run-a.
            if (distributionLedgerRepository.existsByIdempotencyKey(idempotencyKey)) {
                log.info("B11 distribution skip (vec placen): fund={}, client={}, key={}",
                        fundId, position.getUserId(), idempotencyKey);
                continue;
            }

            // BE-FND-01: re-fetch fund account pre svake per-iteration provere.
            // Ako concurrent withdrawal smanji availableBalance, ne pokusavamo
            // transfer (izbegava partial distribution + 409 mid-loop).
            InternalAccountDto refreshedFundAccount = getFundAccount(fund);
            if (scale(refreshedFundAccount.availableBalance()).compareTo(clientAmount) < 0) {
                log.warn("B11 distribution prekinut: fund={} availableBalance={} pao ispod "
                                + "potrebnog clientAmount={} (iteration={} of {}). Preostale pozicije "
                                + "ostaju u pendingDividends za naredni run.",
                        fundId,
                        refreshedFundAccount.availableBalance(),
                        clientAmount,
                        i,
                        positions.size());
                partialDistribution = true;
                break;
            }

            // P1-funds-1 (1346/1554): per-klijent guard. Ako jedan klijent nema
            // aktivan RSD racun, NE rusimo CELU raspodelu (raniji kod je pustao
            // EntityNotFoundException iz resolveClientRsdAccount → rollback svih
            // transfera → niko ne dobija + sledeci run opet udari u istog klijenta
            // = stuck-forever). Umesto toga preskacemo SAMO tog klijenta,
            // markiramo run kao parcijalan (pending ostaje INFLOW za naredni run)
            // i nastavljamo sa ostalima. Ledger guard + stabilan idempotency kljuc
            // i dalje sprecavaju double-pay vec placenih klijenata.
            InternalAccountDto destinationAccount;
            try {
                destinationAccount = resolveClientRsdAccount(position.getUserId());
            } catch (EntityNotFoundException ex) {
                log.warn("B11 distribution skip (klijent={} nema aktivan RSD racun): {}. "
                                + "Preskacem ovog klijenta; raspodela ostaje parcijalna.",
                        position.getUserId(), ex.getMessage());
                partialDistribution = true;
                continue;
            }

            ClientFundTransaction distribution = new ClientFundTransaction();
            distribution.setFundId(fundId);
            distribution.setUserId(position.getUserId());
            distribution.setUserRole(position.getUserRole());
            distribution.setAmountRsd(clientAmount);
            distribution.setSourceAccountId(destinationAccount.id());
            distribution.setInflow(false);
            distribution.setStatus(ClientFundTransactionStatus.DIVIDEND_DISTRIBUTED);
            distribution.setCreatedAt(LocalDateTime.now());
            distribution.setCompletedAt(LocalDateTime.now());
            distribution.setFailureReason("DIVIDEND_DISTRIBUTION fromFundAccountId=" + fundAccount.id());

            ClientFundTransaction savedDistribution = clientFundTransactionRepository.save(distribution);

            // banka-core transfer: fond racun (RSD) -> klijentov RSD racun.
            // P1-2: idempotency kljuc je STABILAN (fundId+clientUserId+inflowAnchor+cycleKey),
            // ne izvodi se iz IDENTITY id-a — ponovni poziv NE prebacuje dvaput isti
            // udeo (banka-core dedup po istom kljucu).
            try {
                bankaCoreClient.transferFunds(
                        idempotencyKey,
                        new TransferFundsRequest(
                                fundAccount.id(), clientAmount,
                                destinationAccount.id(), clientAmount,
                                BigDecimal.ZERO, RSD,
                                "B11 dividenda fundId=" + fundId + " clientUserId=" + position.getUserId()
                                        + " cycle=" + cycleKey));
            } catch (BankaCoreClientException ex) {
                if (ex.getHttpStatus() == 409) {
                    // Concurrent withdrawal je uspeo izmedju mid-loop check-a i
                    // ovog transfera. Bacamo InsufficientFundsException pa cela
                    // {@code @Transactional} radi rollback. P1-2: vec uspeli
                    // transferi su zapisani u trajni ledger (write-ahead,
                    // REQUIRES_NEW) PRE ovog izuzetka — ti marker-i prezive
                    // rollback, pa sledeci cron run preskace placene klijente
                    // (no double-pay). Stabilan banka-core idempotency kljuc je
                    // dodatna primarna odbrana pri retry-u.
                    throw new InsufficientFundsException(
                            "Nedovoljno sredstava na racunu fonda za raspodelu dividende."
                    );
                }
                throw ex;
            }

            // P1-2: write-ahead trajni marker — TEK POSLE uspesnog banka-core
            // transfera, u zasebnoj REQUIRES_NEW transakciji, da prezivi eventualni
            // rollback outer @Transactional-a (banka-core novac je vec presao).
            FundDividendDistributionLedger ledger = new FundDividendDistributionLedger();
            ledger.setFundId(fundId);
            ledger.setClientUserId(position.getUserId());
            ledger.setSourceDividendInflowTxId(inflowAnchorTxId);
            ledger.setCycleKey(cycleKey);
            ledger.setIdempotencyKey(idempotencyKey);
            ledger.setAmountRsd(clientAmount);
            ledgerWriter.recordPaid(ledger);

            distributions.add(savedDistribution);
        }

        // Pending dividende oznacavamo DIVIDEND_DISTRIBUTED SAMO ako je raspodela
        // u potpunosti zavrsena. Pri parcijalnoj raspodeli ostavljamo ih kao
        // DIVIDEND_INFLOW da ih sledeci run pokupi (idempotentni kljucevi
        // spreceavaju double-pay onih klijenata koji su vec dobili svoj deo).
        if (!partialDistribution) {
            for (ClientFundTransaction pending : pendingDividends) {
                pending.setStatus(ClientFundTransactionStatus.DIVIDEND_DISTRIBUTED);
                pending.setCompletedAt(LocalDateTime.now());
                pending.setFailureReason("DIVIDEND_DISTRIBUTED totalAmount=" + totalDividend);
                clientFundTransactionRepository.save(pending);
            }
        }

        fundValueSnapshotScheduler.snapshotFundIfMissing(fund);

        log.info(
                "B11 dividends distributed: fund={}, total={}, clients={}, partial={}",
                fundId,
                totalDividend,
                distributions.size(),
                partialDistribution
        );

        return distributions;
    }

    /**
     * Dnevni catch-up scheduler (TODO_final C4 #14 / Sc 70): dispatch-uje
     * preostale DIVIDEND_INFLOW transakcije po per-fund politici. Glavna
     * dispatch logika sad zivi u {@code DividendService.dispatchFundDividendsByPolicy}
     * koja se poziva odmah posle kvartalne isplate; ovaj scheduler je sigurnosna
     * mreza za retry-ove (ako je primarni dispatch pao za neki fond, sledeci
     * dan ovaj scheduler ce ga pokupiti).
     */
    @Scheduled(cron = "0 30 2 * * *")
    public void scheduledDividendProcessing() {
        List<InvestmentFund> activeFunds = investmentFundRepository.findByActiveTrueOrderByNameAsc();

        for (InvestmentFund fund : activeFunds) {
            try {
                dispatchByPolicy(fund);
            } catch (RuntimeException ex) {
                log.error(
                        "B11 scheduled dividend processing failed for fund #{}: {}",
                        fund.getId(),
                        ex.getMessage(),
                        ex
                );
            }
        }
    }

    /**
     * R1 796 — deljeni per-fund reinvest-vs-distribute switch. Ranije verbatim
     * dupliran u {@code DividendService.dispatchFundDividendsByPolicy} i
     * {@link #scheduledDividendProcessing}. Caller je odgovoran za per-fund
     * try/catch izolaciju (jedan fond ne sme blokirati ostale).
     */
    public void dispatchByPolicy(InvestmentFund fund) {
        if (Boolean.TRUE.equals(fund.getReinvestDividends())) {
            reinvestDividends(fund.getId());
        } else {
            distributeDividendsToClients(fund.getId());
        }
    }

    private Order createInternalBuyOrder(
            InvestmentFund fund,
            InternalAccountDto fundAccount,
            Listing listing,
            int quantity,
            BigDecimal priceInListingCurrency,
            BigDecimal reservedAmount
    ) {
        Order order = new Order();

        order.setUserId(fund.getId());
        order.setUserRole(UserRole.FUND);
        order.setFundId(fund.getId());
        order.setListing(listing);
        order.setQuantity(quantity);
        order.setRemainingPortions(quantity);
        order.setContractSize(1);
        order.setPricePerUnit(priceInListingCurrency);
        order.setApproximatePrice(
                priceInListingCurrency
                        .multiply(BigDecimal.valueOf(quantity))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        );
        order.setDirection(OrderDirection.BUY);
        order.setOrderType(OrderType.MARKET);
        order.setStatus(OrderStatus.APPROVED);
        order.setApprovedBy("SYSTEM_FUND_DIVIDEND_REINVESTMENT");
        order.setApprovedAt(LocalDateTime.now());
        order.setCreatedAt(LocalDateTime.now());
        order.setLastModification(LocalDateTime.now());
        order.setDone(false);
        order.setAfterHours(false);
        order.setAllOrNone(false);
        order.setMargin(false);
        order.setReservedAccountId(fundAccount.id());
        order.setAccountId(fundAccount.id());
        order.setReservedAmount(reservedAmount);
        order.setExchangeRate(resolveListingToRsdRate(listing));
        order.setFxCommission(BigDecimal.ZERO);

        Order saved = orderRepository.save(order);

        // FundReservationService rezervise sredstva u banka-core (idempotentno).
        fundReservationService.reserveForBuy(saved);

        log.info(
                "B11 reinvest order created: fund={}, order={}, listing={}, quantity={}, reservedRsd={}",
                fund.getId(),
                saved.getId(),
                listing.getTicker(),
                quantity,
                reservedAmount
        );

        return saved;
    }

    private InvestmentFund getActiveFund(Long fundId) {
        InvestmentFund fund = investmentFundRepository.findById(fundId)
                .orElseThrow(() -> new EntityNotFoundException("Fond ne postoji: " + fundId));

        if (!fund.isActive()) {
            throw new IllegalStateException("Fond " + fund.getName() + " nije aktivan.");
        }

        return fund;
    }

    private InternalAccountDto getFundAccount(InvestmentFund fund) {
        InternalAccountDto account;
        try {
            account = bankaCoreClient.getAccount(fund.getAccountId());
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 404) {
                throw new EntityNotFoundException("Racun fonda ne postoji: " + fund.getAccountId());
            }
            throw ex;
        }

        if (account.status() == null || !"ACTIVE".equalsIgnoreCase(account.status())) {
            throw new IllegalStateException("Racun fonda nije aktivan: " + account.accountNumber());
        }

        return account;
    }

    /**
     * Razresava klijentov preferiran RSD racun za isplatu dividende.
     * Banka-core endpoint vraca aktivan RSD racun po istom obrascu kao
     * monolit (preferowo onaj sa najvecim availableBalance-om).
     */
    private InternalAccountDto resolveClientRsdAccount(Long clientId) {
        try {
            return bankaCoreClient.getPreferredAccount(UserRole.CLIENT, clientId, RSD);
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 404) {
                throw new EntityNotFoundException("Klijent nema aktivan RSD racun: " + clientId);
            }
            throw ex;
        }
    }

    private Long extractListingId(ClientFundTransaction tx) {
        String reason = tx.getFailureReason();

        if (reason == null) {
            return null;
        }

        String marker = "listingId=";
        int start = reason.indexOf(marker);

        if (start < 0) {
            return null;
        }

        start += marker.length();

        int end = start;
        while (end < reason.length() && Character.isDigit(reason.charAt(end))) {
            end++;
        }

        if (end == start) {
            return null;
        }

        return Long.parseLong(reason.substring(start, end));
    }

    private BigDecimal resolveBuyPrice(Listing listing) {
        BigDecimal price = listing.getAsk() != null ? listing.getAsk() : listing.getPrice();

        if (price == null || price.signum() <= 0) {
            throw new IllegalStateException("Listing " + listing.getTicker() + " nema validnu cenu za BUY order.");
        }

        return price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveListingToRsdRate(Listing listing) {
        String listingCurrency = ListingCurrencyResolver
                .resolveSafe(listing, RSD)
                .toUpperCase(Locale.ROOT);

        if (RSD.equals(listingCurrency)) {
            return BigDecimal.ONE;
        }

        return currencyConversionService.getRate(listingCurrency, RSD);
    }

    private BigDecimal convertToRsd(BigDecimal amount, String fromCurrency) {
        String normalized = fromCurrency == null ? RSD : fromCurrency.toUpperCase(Locale.ROOT);

        if (RSD.equals(normalized)) {
            return scale(amount);
        }

        return currencyConversionService.convert(amount, normalized, RSD);
    }

    private void markFailed(ClientFundTransaction tx, String reason) {
        tx.setStatus(ClientFundTransactionStatus.FAILED);
        tx.setCompletedAt(LocalDateTime.now());
        tx.setFailureReason(reason);

        clientFundTransactionRepository.save(tx);
    }

    private BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * P1-2: deterministicki diskriminator distribucione runde. Izveden iskljucivo
     * iz sortiranih pending DIVIDEND_INFLOW id-eva — isti pending skup uvek daje
     * isti {@code cycleKey} (i pri cron re-run-u), pa stabilni idempotency kljuc
     * po klijentu ostaje konstantan. Koristimo zbir (kratak, deterministican, bez
     * preterane duzine) — kolizija razlicitih skupova je benigna jer je par
     * {@code (inflowAnchorTxId, clientUserId)} u unique constraint-u takodje deo guard-a.
     */
    private String buildCycleKey(List<Long> sortedInflowTxIds) {
        if (sortedInflowTxIds.isEmpty()) {
            return "empty";
        }
        long checksum = 0L;
        for (Long id : sortedInflowTxIds) {
            checksum = checksum * 31 + id;
        }
        return sortedInflowTxIds.get(0) + "_" + sortedInflowTxIds.size() + "_" + checksum;
    }

    /**
     * P1-2: stabilan per-klijent idempotency kljuc. NE izvodi se iz nestabilnog
     * IDENTITY id-a {@link ClientFundTransaction}; izveden iz kotvi koje prezive
     * rollback i identicne su pri re-run-u.
     */
    private String buildDistributionIdempotencyKey(
            Long fundId, Long clientUserId, Long inflowAnchorTxId, String cycleKey) {
        return "fund-dividend-distribution-" + fundId + "-" + clientUserId
                + "-" + inflowAnchorTxId + "-" + cycleKey;
    }

    /**
     * P1-funds-1 (1347): stabilan per-priliv idempotency kljuc za reinvest put.
     * Kotva je DIVIDEND_INFLOW id (vec commit-ovan, stabilan) — isti pri
     * re-dispatch-u za isti priliv, pa konkurentni reinvest dispatch-i ne
     * kreiraju dva auto-BUY ordera za istu dividendu.
     */
    private String buildReinvestIdempotencyKey(Long fundId, Long inflowTxId) {
        return "fund-dividend-reinvest-" + fundId + "-" + inflowTxId;
    }
}
