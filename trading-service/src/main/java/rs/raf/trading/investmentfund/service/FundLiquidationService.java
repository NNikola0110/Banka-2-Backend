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
import rs.raf.trading.investmentfund.model.ClientFundTransaction;
import rs.raf.trading.investmentfund.model.ClientFundTransactionStatus;
import rs.raf.trading.investmentfund.repository.ClientFundTransactionRepository;
import rs.raf.trading.investmentfund.repository.InvestmentFundRepository;
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
    private static final BigDecimal FX_FEE_RATE = new BigDecimal("0.01");
    private static final int MONEY_SCALE = 4;

    private final OrderRepository orderRepository;
    private final PortfolioRepository portfolioRepository;
    private final ListingRepository listingRepository;
    private final ClientFundTransactionRepository transactionRepository;
    private final BankaCoreClient bankaCoreClient;
    private final InvestmentFundRepository investmentFundRepository;
    private final CurrencyConversionService currencyConversionService;

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

        List<Portfolio> fundHoldings = new ArrayList<>(portfolioRepository.findByUserIdAndUserRole(fundId, UserRole.FUND));
        fundHoldings.sort(Comparator.comparing(this::calculateValueInRsd).reversed());

        BigDecimal remaining = amountRsd.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

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
                remaining = remaining.subtract(bufferPrice.multiply(BigDecimal.valueOf(quantityToSell)))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            sendPushNotification(999L, "ALARM: Fond #" + fundId
                    + " nema dovoljno hartija za likvidaciju. Nedostaje jos " + remaining + " RSD.");
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
        // Originalno: FX provizija samo za licni klijentski racun. U trading-service-u
        // InternalAccountDto ne nosi tip vlasnika; provizija se obracunava kad je
        // racun za isplatu u stranoj valuti i isplata je za klijenta (CLIENT pozicija).
        boolean clientForeignDestination = UserRole.CLIENT.equals(tx.getUserRole())
                && !RSD.equals(destinationCurrency);
        BigDecimal fxFee = clientForeignDestination
                ? grossCredit.multiply(FX_FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // banka-core transfer: fond racun (RSD) -> racun za isplatu; FX provizija
        // (ako postoji) ide bankinom racunu (banka-core ga sam resolve-uje).
        // banka-core pise audit Transaction. Idempotency kljuc je deterministicki
        // po ClientFundTransaction id-u.
        bankaCoreClient.transferFunds(
                "fund-payout-" + tx.getId(),
                new TransferFundsRequest(fundAccount.id(), destinationAccount.id(),
                        amountRsd, RSD, fxFee,
                        "Isplata iz fonda — transakcija #" + tx.getId()));

        tx.setStatus(ClientFundTransactionStatus.COMPLETED);
        tx.setCompletedAt(LocalDateTime.now());
        tx.setFailureReason(null);
        sendPushNotification(tx.getUserId(), "Isplata iz fonda u iznosu od " + tx.getAmountRsd()
                + " RSD je uspesno procesuirana.");
        transactionRepository.save(tx);
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

    private void sendPushNotification(Long userId, String message) {
        log.info("[PUSH NOTIFICATION] Za korisnika #{}: {}", userId, message);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
