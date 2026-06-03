package rs.raf.trading.investmentfund.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.TransferFundsRequest;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.investmentfund.model.ClientFundPosition;
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.ClientFundTransactionStatus;
import rs.raf.trading.investmentfund.repository.ClientFundPositionRepository;
import rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.order.model.OrderDirection;
import rs.raf.trading.order.model.OrderStatus;
import rs.raf.trading.order.model.OrderType;
import rs.raf.trading.order.repository.OrderRepository;
import rs.raf.trading.order.service.CurrencyConversionService;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.repository.ListingRepository;
import rs.raf.trading.stock.util.ListingCurrencyResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * NAPOMENA (copy-first ekstrakcija, faza 2c — money-seam rewiring):
 * monolitna verzija je direktno menjala {@code Account.balance}/
 * {@code availableBalance} (privatni {@code debit}/{@code credit}/
 * {@code creditBankFxCommission} helperi). U trading-service-u racuni zive
 * u banka-core domenu, pa isplata iz fonda ide kroz banka-core interni
 * settlement seam: {@code executeTransactionPayout} radi jedan
 * {@code POST /internal/funds/transfer} (fond racun -&gt; racun za isplatu;
 * opciona FX provizija ide bankinom racunu — banka-core ga sam resolve-uje).
 * {@code getFundAccount} cita {@link InternalAccountDto} preko
 * {@link BankaCoreClient#getAccount}. {@code createInternalFundOrder} pravi
 * lokalni {@link Order} (trading entitet) i ostaje verbatim; {@code onFillCompleted}
 * je lokalni hook koji {@code OrderExecutionService} okida posle fill-a.
 *
 * Idempotency kljucevi su deterministicki po {@code ClientFundTransaction} id-u
 * ({@code "fund-payout-<txId>"}) — retry replay-uje umesto da dvaput isplati.
 */
@Service
@RequiredArgsConstructor
public class FundLiquidationService {

    private static final Logger log = LoggerFactory.getLogger(FundLiquidationService.class);
    private static final String RSD = "RSD";
    // P2-profit-fx-fee-1 (R5 1877): jedinstven izvor istine za FX fee (1%, nepromenjeno).
    private static final BigDecimal FX_FEE_RATE = rs.raf.trading.common.FxFeePolicy.FX_FEE_RATE;
    private static final int MONEY_SCALE = 4;
    /**
     * R1 491: sentinel "ops/admin" primalac za interne ALARM notifikacije (nelikvidan
     * fond). Nije pravi userId — {@link #sendPushNotification} je trenutno log-only stub
     * (R1 490), pa ovaj kanal samo ide u log. Kad se push wire-uje na notification-service,
     * zameniti pravim ops-routing-om (npr. po roli ADMIN).
     */
    private static final long OPS_ALARM_RECIPIENT_ID = 999L;

    private final OrderRepository orderRepository;
    private final PortfolioRepository portfolioRepository;
    private final ListingRepository listingRepository;
    private final ClientFundTransactionRepository transactionRepository;
    private final ClientFundPositionRepository clientFundPositionRepository;
    private final BankaCoreClient bankaCoreClient;
    private final InvestmentFundRepository investmentFundRepository;
    private final CurrencyConversionService currencyConversionService;
    private final NotificationService notificationService;

    /**
     * T9 — automatska likvidacija kada T8 withdraw nema dovoljno cash-a.
     *
     * T8 prosledjuje shortfall u RSD. Ovaj servis pravi interne FUND SELL
     * naloge, najveci holding prvo. Po svakom fill-u OrderExecutionService
     * poziva onFillCompleted, koji FIFO razresava PENDING isplate.
     */
    @Transactional
    public void liquidateFor(Long fundId, BigDecimal amountRsd) {
        if (amountRsd == null || amountRsd.signum() <= 0) {
            return;
        }

        sendPushNotification(fundId, "Isplata je pokrenuta. Zbog nedovoljno likvidnih sredstava, "
                + "vrsi se automatska prodaja hartija fonda.");

        // P1-funds-1 (1345): pozivalac (withdraw) prosledjuje shortfall SAMO svoje
        // trenutne isplate. Ako fond ima vise PENDING withdrawal-a istovremeno,
        // pojedinacni shortfall potcenjuje stvarnu potrebu → likvidacija prodaje
        // premalo hartija → neki withdrawal ostaje zauvek PENDING. Zato ovde
        // sumiramo SVE PENDING outflow fonda i likvidiramo za agregatni nedostatak
        // (umanjen za vec raspolozivi cash), uzimajuci max(prosledjeni, agregatni).
        BigDecimal availableCash = nullToZero(getFundAccount(fundId).availableBalance())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal totalPendingOutflow = transactionRepository
                .findByStatus(ClientFundTransactionStatus.PENDING)
                .stream()
                .filter(tx -> Objects.equals(tx.getFundId(), fundId))
                .filter(tx -> !tx.isInflow())
                .map(ClientFundTransaction::getAmountRsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal aggregateShortfall = totalPendingOutflow.subtract(availableCash)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal target = amountRsd.max(aggregateShortfall).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        List<Portfolio> fundHoldings = new ArrayList<>(portfolioRepository.findByUserIdAndUserRole(fundId, UserRole.FUND));
        fundHoldings.sort(Comparator.comparing(this::calculateValueInRsd).reversed());

        BigDecimal remaining = target.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        int ordersCreated = 0;

        for (Portfolio holding : fundHoldings) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            if (holding.getQuantity() == null || holding.getQuantity() <= 0) continue;

            Listing listing = listingRepository.findByTicker(holding.getListingTicker())
                    .or(() -> holding.getListingId() == null ? java.util.Optional.empty() : listingRepository.findById(holding.getListingId()))
                    .orElseThrow(() -> new RuntimeException("Listing nije pronadjen za holding: " + holding.getListingTicker()));

            BigDecimal bid = listing.getBid() != null ? listing.getBid() : listing.getPrice();
            if (bid == null || bid.signum() <= 0) continue;

            String listingCurrency = ListingCurrencyResolver.resolveSafe(listing, RSD);
            BigDecimal priceInRsd = convertToRsd(bid, listingCurrency);
            BigDecimal bufferPrice = priceInRsd.multiply(new BigDecimal("0.99")).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if (bufferPrice.compareTo(BigDecimal.ZERO) <= 0) continue;

            int quantityToSell = remaining.divide(bufferPrice, 0, RoundingMode.CEILING).intValue();
            quantityToSell = Math.min(quantityToSell, holding.getQuantity());

            if (quantityToSell > 0) {
                createInternalFundOrder(fundId, holding, listing, quantityToSell, bid);
                ordersCreated++;
                remaining = remaining.subtract(bufferPrice.multiply(BigDecimal.valueOf(quantityToSell)))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            sendPushNotification(OPS_ALARM_RECIPIENT_ID, "ALARM: Fond #" + fundId
                    + " nema dovoljno hartija za likvidaciju. Nedostaje jos " + remaining + " RSD.");

            // P1-funds-1 (1979): ako fond nema NIJEDNU hartiju za prodaju (nijedan
            // SELL order nije kreiran), withdrawal je trajno neispunjiv — ostao bi
            // PENDING zauvek (beskonacni stuck). Markiramo nepokrivene PENDING
            // withdrawal-e kao FAILED. Posto pozicija vise NIJE umanjena unapred
            // (P1-funds-1 1343), klijent zadrzava udeo — withdrawal se samo odbija
            // (bez gubitka novca), klijent moze ponovo pokusati kasnije.
            if (ordersCreated == 0) {
                failUncoverablePendingWithdrawals(fundId, availableCash);
            }
        }
    }

    /**
     * P1-funds-1 (1979): markira PENDING withdrawal-e fonda koji se trajno ne mogu
     * isplatiti (fond bez likvidnog cash-a i bez hartija za prodaju) kao FAILED.
     * Isplaca se FIFO koliko cash dozvoljava; ostatak ide u FAILED. Pozicija nije
     * dirana (umanjuje se tek pri stvarnoj isplati), pa nema refund-a novca.
     */
    private void failUncoverablePendingWithdrawals(Long fundId, BigDecimal availableCash) {
        List<ClientFundTransaction> pending = transactionRepository
                .findByStatus(ClientFundTransactionStatus.PENDING)
                .stream()
                .filter(tx -> Objects.equals(tx.getFundId(), fundId))
                .filter(tx -> !tx.isInflow())
                .sorted(Comparator.comparing(ClientFundTransaction::getCreatedAt))
                .collect(Collectors.toList());

        BigDecimal cash = nullToZero(availableCash);
        for (ClientFundTransaction tx : pending) {
            BigDecimal amount = nullToZero(tx.getAmountRsd());
            if (cash.compareTo(amount) >= 0) {
                // jos uvek pokriveno cash-om — ostavljamo PENDING (onFillCompleted
                // / sledeci ciklus ce ga isplatiti)
                cash = cash.subtract(amount);
                continue;
            }
            tx.setStatus(ClientFundTransactionStatus.FAILED);
            tx.setCompletedAt(LocalDateTime.now());
            tx.setFailureReason("Isplata neuspesna: fond nema dovoljno likvidnih sredstava "
                    + "ni hartija za likvidaciju. Vasa pozicija je sacuvana — pokusajte kasnije.");
            transactionRepository.save(tx);
            notifyClient(tx, "Isplata iz fonda nije izvršena",
                    "Isplata iz fonda nije mogla biti izvršena (nedovoljno sredstava fonda). "
                            + "Vaša pozicija je sačuvana.");
        }
    }

    @Transactional
    public void onFillCompleted(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order nije pronadjen"));

        if (!UserRole.FUND.equals(order.getUserRole())) return;

        Long fundId = order.getFundId() != null ? order.getFundId() : order.getUserId();
        log.info("Hook: FUND order #{} fillovan. Pokusaj FIFO resolve-a PENDING isplata za fond #{}.", orderId, fundId);

        List<ClientFundTransaction> fundPendingWithdrawals = transactionRepository
                .findByStatus(ClientFundTransactionStatus.PENDING)
                .stream()
                .filter(tx -> Objects.equals(tx.getFundId(), fundId))
                .filter(tx -> !tx.isInflow())
                .sorted(Comparator.comparing(ClientFundTransaction::getCreatedAt))
                .collect(Collectors.toList());

        InternalAccountDto fundAccount = getFundAccount(fundId);

        for (ClientFundTransaction tx : fundPendingWithdrawals) {
            if (nullToZero(fundAccount.availableBalance()).compareTo(tx.getAmountRsd()) >= 0) {
                executeTransactionPayout(tx, fundAccount);
                log.info("Pending fund transaction #{} COMPLETED.", tx.getId());
                // Posle isplate ponovo procitaj stanje fond racuna iz banka-core
                // da naredna PENDING isplata vidi azurirani availableBalance.
                fundAccount = getFundAccount(fundId);
            } else {
                break;
            }
        }
    }

    private void createInternalFundOrder(Long fundId, Portfolio holding, Listing listing, int quantity, BigDecimal bid) {
        InternalAccountDto fundAccount = getFundAccount(fundId);

        Order fundOrder = new Order();
        fundOrder.setUserId(fundId);
        fundOrder.setUserRole(UserRole.FUND);
        fundOrder.setFundId(fundId);
        fundOrder.setListing(listing);
        fundOrder.setQuantity(quantity);
        fundOrder.setRemainingPortions(quantity);
        fundOrder.setContractSize(1);
        fundOrder.setPricePerUnit(bid);
        fundOrder.setDirection(OrderDirection.SELL);
        fundOrder.setOrderType(OrderType.MARKET);
        fundOrder.setStatus(OrderStatus.APPROVED);
        fundOrder.setApprovedBy("SYSTEM_FUND_LIQUIDATION");
        fundOrder.setApprovedAt(LocalDateTime.now());
        fundOrder.setCreatedAt(LocalDateTime.now());
        fundOrder.setLastModification(LocalDateTime.now());
        fundOrder.setDone(false);
        fundOrder.setAfterHours(false);
        fundOrder.setAllOrNone(false);
        fundOrder.setMargin(false);
        fundOrder.setReservedAccountId(fundAccount.id());
        fundOrder.setAccountId(fundAccount.id());
        fundOrder.setExchangeRate(resolveListingToRsdRate(listing));

        orderRepository.save(fundOrder);
    }

    private void executeTransactionPayout(ClientFundTransaction tx, InternalAccountDto fundAccount) {
        InternalAccountDto destinationAccount = bankaCoreClient.getAccount(tx.getSourceAccountId());

        BigDecimal amountRsd = tx.getAmountRsd().setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        String destinationCurrency = destinationAccount.currencyCode();
        BigDecimal grossCredit = RSD.equals(destinationCurrency)
                ? amountRsd
                : convertFromRsd(amountRsd, destinationCurrency);
        // Verno monolitu (FundLiquidationService.executeTransactionPayout): FX
        // provizija samo za licni klijentski racun. Sada InternalAccountDto nosi
        // ownerClientId — racun je licni klijentski kad ownerClientId != null,
        // a transakcija je za klijenta (CLIENT pozicija).
        boolean isPersonalClientAccount = destinationAccount.ownerClientId() != null
                && UserRole.CLIENT.equals(tx.getUserRole());
        BigDecimal fxFee = (!RSD.equals(destinationCurrency) && isPersonalClientAccount)
                ? grossCredit.multiply(FX_FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal netCredit = grossCredit.subtract(fxFee).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // banka-core transfer: fond racun (RSD) -> racun za isplatu. Verno monolitu:
        // fond gubi amountRsd u RSD, racun za isplatu dobija netCredit (= grossCredit
        // - fxFee) u SVOJOJ valuti, banka dobija fxFee u valuti racuna za isplatu.
        // banka-core pise audit Transaction. Idempotency kljuc je deterministicki
        // po ClientFundTransaction id-u.
        bankaCoreClient.transferFunds(
                "fund-payout-" + tx.getId(),
                new TransferFundsRequest(
                        fundAccount.id(), amountRsd,
                        destinationAccount.id(), netCredit,
                        fxFee, destinationCurrency,
                        "Isplata iz fonda — transakcija #" + tx.getId()));

        tx.setStatus(ClientFundTransactionStatus.COMPLETED);
        tx.setCompletedAt(LocalDateTime.now());
        tx.setFailureReason(null);

        // P1-funds-1 (1343): pozicija klijenta se umanjuje TEK SADA (pri stvarnoj
        // isplati), ne pri kreiranju PENDING reda. Tako klijent ne gubi udeo ako
        // likvidacija nikad ne uspe. Smanjujemo za cost-basis udeo (investedDelta)
        // izracunat u InvestmentFundService.withdraw.
        decreaseClientPosition(tx);

        // Sc35/36/49/50 (TestoviCelina4): obavestenje o uspesnoj isplati iz fonda.
        notifyClient(tx, "Isplata iz fonda uspešna",
                "Isplata iz fonda u iznosu od " + tx.getAmountRsd()
                        + " RSD je uspešno procesuirana.");
        transactionRepository.save(tx);
    }

    /**
     * P1-funds-1 (1343): umanjuje klijentovu {@link ClientFundPosition} za
     * cost-basis udeo ({@code investedDelta}) povezan sa OVOM withdrawal
     * transakcijom. Poziva se TEK pri stvarnoj isplati (immediate payout u
     * {@code InvestmentFundService} ili FIFO {@code onFillCompleted}). Ako udeo
     * padne na nulu, pozicija se brise.
     */
    private void decreaseClientPosition(ClientFundTransaction tx) {
        if (tx.isInflow()) {
            return;
        }
        BigDecimal delta = nullToZero(tx.getInvestedDelta());
        if (delta.signum() <= 0) {
            return;
        }
        clientFundPositionRepository
                .findByFundIdAndUserIdAndUserRole(tx.getFundId(), tx.getUserId(), tx.getUserRole())
                .ifPresent(position -> {
                    BigDecimal remaining = nullToZero(position.getTotalInvested())
                            .subtract(delta)
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                    if (remaining.signum() <= 0) {
                        clientFundPositionRepository.delete(position);
                    } else {
                        position.setTotalInvested(remaining);
                        position.setLastModifiedAt(LocalDateTime.now());
                        clientFundPositionRepository.save(position);
                    }
                });
    }

    private InternalAccountDto getFundAccount(Long fundId) {
        return investmentFundRepository.findById(fundId)
                .map(fund -> bankaCoreClient.getAccount(fund.getAccountId()))
                .orElseThrow(() -> new RuntimeException("Racun fonda nije pronadjen za fundId=" + fundId));
    }

    private BigDecimal calculateValueInRsd(Portfolio p) {
        if (p == null || p.getQuantity() == null || p.getQuantity() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal unitPrice = p.getAverageBuyPrice();
        String currency = RSD;
        if (p.getListingId() != null) {
            Listing listing = listingRepository.findById(p.getListingId()).orElse(null);
            if (listing != null) {
                unitPrice = listing.getBid() != null ? listing.getBid() : listing.getPrice();
                currency = ListingCurrencyResolver.resolveSafe(listing, RSD);
            }
        }
        if (unitPrice == null) {
            return BigDecimal.ZERO;
        }
        return convertToRsd(unitPrice, currency).multiply(BigDecimal.valueOf(p.getQuantity()))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveListingToRsdRate(Listing listing) {
        String listingCurrency = ListingCurrencyResolver.resolveSafe(listing, RSD);
        if (RSD.equals(listingCurrency)) {
            return BigDecimal.ONE;
        }
        return currencyConversionService.getRate(listingCurrency, RSD);
    }

    private BigDecimal convertToRsd(BigDecimal amount, String fromCurrency) {
        if (amount == null) return BigDecimal.ZERO;
        if (RSD.equals(fromCurrency)) return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return currencyConversionService.convert(amount, fromCurrency, RSD);
    }

    private BigDecimal convertFromRsd(BigDecimal amount, String toCurrency) {
        if (RSD.equals(toCurrency)) return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return currencyConversionService.convert(amount, RSD, toCurrency);
    }

    /**
     * C-notif-email (02.06): salje pravu notifikaciju klijentu/supervizoru o
     * isplati iz fonda (email + in-app) preko {@link NotificationService}, tip
     * {@code FUND_PAYOUT}. Zamenjuje raniji log-only stub (R1 490) za
     * KLIJENT-okrenute evente (Sc35/36/49/50). Best-effort: greska se loguje i NE
     * obara isplatu (notificationService.notify je sam swallow-best-effort, ali
     * dodatno wrap-ujemo radi sigurnosti).
     *
     * <p><b>C-notif-email blocker #3 (03.06): kljuc po {@code ClientFundTransaction.id}.</b>
     * Referenca je {@code ("FUND_TRANSACTION", tx.getId())} — NE {@code ("FUND", fundId)}.
     * banka-core in-app idempotency kljuc ignorise title/body kad je referenca non-null,
     * pa bi vise distinktnih payout eventa istog fonda istom klijentu (pokrenuta vs
     * uspesna, ili dve odvojene isplate) imalo IDENTICAN kljuc → druga in-app
     * notifikacija bi se suprimovala. Kljuc po txId daje svakom logickom eventu
     * jedinstven kljuc, dok i dalje dedupuje pravi retry istog eventa.
     *
     * @param tx transakcija fonda — nosi recipient ({@code userId}/{@code userRole})
     *           i jedinstvenu referencu ({@code id})
     */
    private void notifyClient(ClientFundTransaction tx, String title, String message) {
        try {
            notificationService.notify(
                    tx.getUserId(),
                    tx.getUserRole(),
                    NotificationType.FUND_PAYOUT,
                    title,
                    message,
                    "FUND_TRANSACTION",
                    tx.getId());
        } catch (Exception ex) {
            log.warn("Failed to send fund payout notification for tx #{}: {}",
                    tx.getId(), ex.getMessage());
        }
    }

    /**
     * R1 490: interni log-only kanal za NE-korisnicke evente (fond-recipient
     * "likvidacija pokrenuta" + ops ALARM sentinel). Ovi nemaju realnog primaoca
     * sa email-om (fundId / sentinel 999L), pa ostaju log-only — wire na pravi
     * ops-routing je zaseban feature. KLIJENT-okrenuti eventi idu kroz
     * {@link #notifyClient}.
     */
    private void sendPushNotification(Long userId, String message) {
        log.info("[PUSH NOTIFICATION] Za korisnika #{}: {}", userId, message);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
