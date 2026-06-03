package rs.raf.trading.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CommitFundsRequest;
import rs.raf.banka2.contracts.internal.ReleaseFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsRequest;
import rs.raf.banka2.contracts.internal.ReserveFundsResponse;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.order.exception.InsufficientFundsException;
import rs.raf.trading.order.exception.InsufficientHoldingsException;
import rs.raf.trading.order.model.Order;
import rs.raf.trading.portfolio.model.Portfolio;
import rs.raf.trading.portfolio.repository.PortfolioRepository;

import java.math.BigDecimal;

/**
 * Jedina klasa odgovorna za novcanu nogu naloga (BUY) i za rezervaciju
 * hartija (SELL — {@link Portfolio#getReservedQuantity()}).
 *
 * NAPOMENA (copy-first ekstrakcija, faza 2c — money-seam rewiring):
 * monolitna verzija je direktno menjala {@code Account.availableBalance} i
 * {@code Account.reservedAmount}. U trading-service-u racuni zive u
 * banka-core domenu, pa BUY operacije idu kroz banka-core interni settlement
 * SAGA seam:
 * <ul>
 *   <li>{@code reserveForBuy}  -&gt; {@code POST /internal/funds/reserve}</li>
 *   <li>{@code releaseForBuy}  -&gt; {@code POST /internal/funds/.../release}</li>
 *   <li>{@code consumeForBuyFill} -&gt; {@code POST /internal/funds/.../commit}</li>
 * </ul>
 * Pro-rata rezervacijska matematika je sada banka-core posao ({@code commit}
 * smanjuje rezervaciju). SELL operacije diraju samo lokalni {@link Portfolio}
 * i kopirane su verbatim. Idempotency kljucevi su deterministicki po
 * (order id, operacija, fill-redni-broj) — retry replay-uje umesto da
 * dvaput naplati.
 */
@Service
@RequiredArgsConstructor
public class FundReservationService {

    private final BankaCoreClient bankaCoreClient;
    private final PortfolioRepository portfolioRepository;

    // ── BUY ──────────────────────────────────────────────────────────────────

    /**
     * Rezervise {@code order.reservedAmount} na racunu (banka-core
     * {@code /internal/funds/reserve}) i cuva {@code reservationId} koji
     * banka-core vrati u {@code order.bankaCoreReservationId} (potreban za
     * kasniji commit/release).
     */
    @Transactional
    public void reserveForBuy(Order order) {
        if (order.isReservationReleased()) {
            throw new IllegalStateException(
                    "Rezervacija je vec oslobodjena za order " + order.getId());
        }
        // BE-STK-05: margin BUY orderi imaju rezervaciju u MarginAccount.reservedMargin,
        // ne u banka-core funds reserve. Skip ovde.
        if (order.isMargin()) {
            return;
        }
        BigDecimal amount = order.getReservedAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Iznos rezervacije mora biti pozitivan");
        }

        String currencyCode = resolveAccountCurrency(order.getReservedAccountId());
        try {
            ReserveFundsResponse response = bankaCoreClient.reserveFunds(
                    "order-" + order.getId() + "-reserve",
                    new ReserveFundsRequest(order.getReservedAccountId(), amount, currencyCode));
            order.setBankaCoreReservationId(response.reservationId());
        } catch (BankaCoreClientException ex) {
            if (ex.getHttpStatus() == 409) {
                // banka-core odbio rezervaciju zbog nedovoljnih sredstava.
                throw new InsufficientFundsException(
                        "Nedovoljno raspolozivih sredstava na racunu "
                                + order.getReservedAccountId());
            }
            throw ex;
        }
    }

    /**
     * Oslobadja preostalu rezervaciju na racunu (banka-core
     * {@code /internal/funds/.../release}). Idempotentno — banka-core release
     * je idempotentan, plus lokalni {@code reservationReleased} flag.
     */
    @Transactional
    public void releaseForBuy(Order order) {
        if (order.isReservationReleased()) {
            return;
        }
        // BE-STK-05: margin orderi imaju rezervaciju u MarginAccount.reservedMargin.
        // OrderExecutionService.releaseReservationSafe rukuje margin direktno.
        if (order.isMargin()) {
            order.setReservationReleased(true);
            return;
        }
        if (order.getReservedAccountId() == null || order.getReservedAmount() == null
                || order.getBankaCoreReservationId() == null) {
            order.setReservationReleased(true);
            return;
        }

        bankaCoreClient.releaseFunds(
                order.getBankaCoreReservationId(),
                "order-" + order.getId() + "-release",
                new ReleaseFundsRequest("Oslobadjanje rezervacije za order " + order.getId()));

        order.setReservationReleased(true);
    }

    /**
     * Knjizi jedan partial fill (banka-core {@code /internal/funds/.../commit}).
     * Cena fill-a se zaduzuje sa rezervisanog racuna (verno monolitu: cena ide
     * na trziste, bez counterparty kreditiranja), provizija ide bankinom racunu
     * (banka-core ga sam resolve-uje). banka-core proporcionalno smanji
     * rezervaciju.
     *
     * @param order       nalog
     * @param qty         kolicina ovog fill-a
     * @param fillPrice   cena fill-a u valuti racuna (bez provizije)
     * @param commission  order provizija + FX za ovaj fill, u valuti racuna
     */
    @Transactional
    public void consumeForBuyFill(Order order, int qty, BigDecimal fillPrice, BigDecimal commission) {
        if (order.isReservationReleased()) {
            throw new IllegalStateException(
                    "Rezervacija je vec oslobodjena za order " + order.getId());
        }
        if (qty <= 0) {
            throw new IllegalArgumentException("Kolicina mora biti pozitivna");
        }
        if (order.getBankaCoreReservationId() == null) {
            throw new IllegalStateException(
                    "Order " + order.getId() + " nema rezervaciju za commit");
        }

        int fillSeq = order.getQuantity()
                - (order.getRemainingPortions() != null ? order.getRemainingPortions() : order.getQuantity());

        bankaCoreClient.commitFunds(
                order.getBankaCoreReservationId(),
                "order-" + order.getId() + "-fill-" + fillSeq,
                new CommitFundsRequest(fillPrice,
                        commission != null ? commission : BigDecimal.ZERO, null,
                        "Order #" + order.getId() + " fill " + fillSeq + " (" + qty + " kom)"));
    }

    // ── SELL ─────────────────────────────────────────────────────────────────

    /**
     * Rezervise kolicinu hartija za SELL order.
     * Povecava {@code portfolio.reservedQuantity} za {@code order.quantity}.
     */
    @Transactional
    public void reserveForSell(Order order, Portfolio portfolio) {
        if (order.isReservationReleased()) {
            throw new IllegalStateException(
                    "Rezervacija je vec oslobodjena za order " + order.getId());
        }
        int need = order.getQuantity();
        if (need <= 0) {
            throw new IllegalArgumentException("Kolicina mora biti pozitivna");
        }
        if (portfolio.getAvailableQuantity() < need) {
            throw new InsufficientHoldingsException(
                    "Nedovoljno hartija: dostupno " + portfolio.getAvailableQuantity()
                            + ", traženo " + need);
        }
        portfolio.setReservedQuantity(portfolio.getReservedQuantity() + need);
        portfolioRepository.save(portfolio);
    }

    /**
     * Oslobadja rezervisanu kolicinu hartija. Idempotentno.
     */
    @Transactional
    public void releaseForSell(Order order, Portfolio portfolio) {
        if (order.isReservationReleased()) {
            return;
        }
        int toRelease = order.getRemainingPortions() != null
                ? order.getRemainingPortions()
                : order.getQuantity();
        int newReserved = Math.max(0, portfolio.getReservedQuantity() - toRelease);
        portfolio.setReservedQuantity(newReserved);
        portfolioRepository.save(portfolio);
        order.setReservationReleased(true);
    }

    /**
     * Knjizi partial fill za SELL order. Smanjuje i ukupnu kolicinu u portfoliu
     * i rezervisanu kolicinu za {@code qty}.
     */
    @Transactional
    public void consumeForSellFill(Order order, Portfolio portfolio, int qty) {
        if (order.isReservationReleased()) {
            throw new IllegalStateException(
                    "Rezervacija je vec oslobodjena za order " + order.getId());
        }
        if (qty <= 0) {
            throw new IllegalArgumentException("Kolicina mora biti pozitivna");
        }
        portfolio.setQuantity(portfolio.getQuantity() - qty);
        portfolio.setReservedQuantity(Math.max(0, portfolio.getReservedQuantity() - qty));
        portfolioRepository.save(portfolio);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Razresava ISO kod valute racuna preko banka-core internog seam-a.
     */
    private String resolveAccountCurrency(Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Racun za rezervaciju nije zadat");
        }
        return bankaCoreClient.getAccount(accountId).currencyCode();
    }
}
