package rs.raf.banka2_bek.interbank.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.CommitStockRequest;
import rs.raf.banka2.contracts.internal.ReleaseStockRequest;
import rs.raf.banka2.contracts.internal.ReserveStockRequest;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.interbank.client.TradingServiceClientException;
import rs.raf.banka2_bek.interbank.client.TradingServiceInternalClient;
import rs.raf.banka2_bek.interbank.exception.InterbankExceptions;

import java.math.BigDecimal;

/**
 * Primenjuje rezervacije/oslobadjanja/commit-e na novcane i hartijske noge
 * inter-bank 2PC transakcija.
 *
 * <p><b>Novcane noge</b> ({@code reserveMonas}/{@code releaseMonas}/{@code commitMonas})
 * rade in-process JPA pristup {@code accounts} tabeli — racuni pripadaju banka-core-u
 * i posle 2f cutover-a, pa tu nema seam-a.
 *
 * <p><b>Hartijske noge</b> ({@code reserveStock}/{@code releaseStock}/{@code commitStock})
 * su u fazi 2f prevezane sa in-process {@code Portfolio}/{@code Listing} JPA na HTTP
 * seam — {@code portfolios}/{@code listings} tabele posle cutover-a zive samo u
 * trading_db. Pozivi idu kroz {@link TradingServiceInternalClient}; idempotency
 * kljuc je determinisitcki po inter-bank transakciji + postingu tako da retry
 * (banka-core {@code InterbankRetryScheduler}) ne primeni kretanje hartija dvaput.
 */
@Service
@Transactional
public class InterbankReservationApplier {

    private final AccountRepository accountRepository;
    private final TradingServiceInternalClient tradingServiceClient;
    private final InterbankFxService interbankFxService;
    private final String bankRegistrationNumber;

    public InterbankReservationApplier(AccountRepository accountRepository,
                                       TradingServiceInternalClient tradingServiceClient,
                                       InterbankFxService interbankFxService,
                                       @Value("${bank.registration-number}") String bankRegistrationNumber) {
        this.accountRepository = accountRepository;
        this.tradingServiceClient = tradingServiceClient;
        this.interbankFxService = interbankFxService;
        this.bankRegistrationNumber = bankRegistrationNumber;
    }

    public void reserveMonas(String accountNumber, BigDecimal amount){
        Account acct = accountRepository.findForUpdateByAccountNumber(accountNumber)
                .orElseThrow(
                        () -> new InterbankExceptions.InterbankProtocolException("NO_SUCH_ACCOUNT: " + accountNumber)
                );

        if (acct.getAvailableBalance().compareTo(amount) < 0) {
            throw new InterbankExceptions.InterbankProtocolException("INSUFFICIENT_ASSET on " + accountNumber);
        }

        acct.setAvailableBalance(acct.getAvailableBalance().subtract(amount));
        acct.setReservedAmount(acct.getReservedAmount().add(amount));
        accountRepository.save(acct);

    }

    public void releaseMonas(String accountNumber, BigDecimal amount){
        Account acct = accountRepository.findForUpdateByAccountNumber(accountNumber)
                .orElseThrow(
                        () -> new InterbankExceptions.InterbankProtocolException("NO_SUCH_ACCOUNT: " + accountNumber)
                );

        acct.setAvailableBalance(acct.getAvailableBalance().add(amount));
        acct.setReservedAmount(acct.getReservedAmount().subtract(amount));
        accountRepository.save(acct);

    }

    /**
     * Commit sender (Banka A) novcane noge — trosi rezervaciju iz {@code reserveMonas}:
     * skida {@code amount} sa {@code balance} i {@code reservedAmount}
     * ({@code availableBalance} je vec umanjen pri rezervaciji).
     *
     * <p>R1-681: raniji {@code isDebit} parametar je uklonjen. Recipient (Banka B) credit
     * noga vise NE ide kroz ovaj metod — od §Celina 5 §40-66 ona ide kroz FX-svesni
     * {@link #commitRecipientCredit} (konverzija + provizija). U produkciji je
     * {@code commitMonas} pozivan iskljucivo sa {@code isDebit=false} (sender debit),
     * pa je {@code isDebit=true} grana bila mrtav kod.
     */
    public void commitMonas(String accountNumber, BigDecimal amount){
        Account acct = accountRepository.findForUpdateByAccountNumber(accountNumber)
                .orElseThrow(
                        () -> new InterbankExceptions.InterbankProtocolException("NO_SUCH_ACCOUNT: " + accountNumber)
                );

        acct.setBalance(acct.getBalance().subtract(amount));
        acct.setReservedAmount(acct.getReservedAmount().subtract(amount));
        accountRepository.save(acct);
    }

    /**
     * §Celina 5 §40-66: commit recipient (Banka B) monetary credit sa FX konverzijom
     * i Banka-B provizijom.
     *
     * <p>Wire posting nosi {@code amount} u valuti posiljaoca ({@code postingCurrency}).
     * Ako se ta valuta poklapa sa valutom primaocevog racuna — ovo je byte-identicno
     * obicnom kreditu primaoca (rate=1, fee=0). Inace Banka B
     * konvertuje po mid-rate-u, naplacuje inter-bank proviziju, i:
     * <ul>
     *   <li>kreditira primaocu "Krajnju vrednost" = converted − fee, u target valuti;</li>
     *   <li>skida {@code converted} sa bankinog pool racuna u target valuti
     *       (banka isplacuje primaocu);</li>
     *   <li>vraca {@code fee} na isti bankin racun (provizija ostaje banci) —
     *       neto promena bankinog racuna = −(converted − fee) = −recipientCredit.</li>
     * </ul>
     * Tako je novac ocuvan: banka ukupno isplati {@code converted} (primalac +
     * zadrzana provizija), za sta je primila {@code amount} u source valuti od
     * Banke A kroz 2PC settlement — koja sada (N5) i KNJIZI: source-ccy pool Banke B
     * dobija {@code +amount}, cime je inter-bank konzervacija na nasim knjigama 0
     * (target-ccy isplata je pokrivena source-ccy prilivom).
     *
     * @param pinnedFxRate N5 — zakljucan mid-rate iz VOTE faze; ako je non-null,
     *        commit koristi NJEGA umesto live rate-a (FX drift se eliminise). Null
     *        → fallback na live mid-rate (regression-safe za stare pozive).
     */
    public void commitRecipientCredit(String accountNumber, BigDecimal amount,
                                      String postingCurrency, BigDecimal pinnedFxRate) {
        Account recipient = accountRepository.findForUpdateByAccountNumber(accountNumber)
                .orElseThrow(
                        () -> new InterbankExceptions.InterbankProtocolException("NO_SUCH_ACCOUNT: " + accountNumber)
                );

        String recipientCcy = recipient.getCurrency() != null
                ? recipient.getCurrency().getCode() : postingCurrency;

        // Same-currency: zadrzi staro ponasanje (rate=1, fee=0) — regression-safe.
        if (postingCurrency.equalsIgnoreCase(recipientCcy)) {
            recipient.setBalance(recipient.getBalance().add(amount));
            recipient.setAvailableBalance(recipient.getAvailableBalance().add(amount));
            accountRepository.save(recipient);
            return;
        }

        // Cross-currency: Banka B konvertuje + naplacuje proviziju.
        // N5: ako imamo pinned kurs iz vote-a, koristimo NJEGA (FX drift eliminisan);
        // inace fallback na live mid-rate.
        InterbankFxService.InterbankFxQuote quote = (pinnedFxRate != null)
                ? interbankFxService.quoteInboundSettlementWithRate(
                        amount, postingCurrency, recipientCcy, pinnedFxRate)
                : interbankFxService.quoteInboundSettlement(amount, postingCurrency, recipientCcy);
        BigDecimal recipientCredit = quote.targetAmount(); // "Krajnja vrednost"
        BigDecimal fee = quote.commission();
        BigDecimal payout = recipientCredit.add(fee);      // = converted (mid-rate)

        // Bankin pool racun u target valuti — banka isplacuje primaocu, zadrzava proviziju.
        Account bankAccount = accountRepository.findBankAccountForUpdateByCurrency(
                        bankRegistrationNumber, recipientCcy)
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Banka nema racun za " + recipientCcy + " (cross-currency inter-bank settlement)"));

        // N5 — balance check u commit-u (uz pinned kurs): banka MORA imati 'payout'
        // (converted) na target pool racunu. Vote-faza je proverila isto pod pinned
        // kursom; ova provera stiti od stanja koje se promenilo izmedju vote i commit
        // (npr. drugi konkurentni settlement potrosio pool). Overdraft → throw (commit
        // pada, 2PC ostaje konzistentan jer recipient jos nije kreditiran).
        if (bankAccount.getAvailableBalance().compareTo(payout) < 0) {
            throw new InterbankExceptions.InterbankProtocolException(
                    "INSUFFICIENT_ASSET: bankin " + recipientCcy + " pool nema "
                            + payout + " za cross-currency inbound settlement");
        }

        // N5 — source-ccy pool Banke B prima 'amount' (wire asset koji je stigao od
        // Banke A). Bez ovog priliva, target-ccy isplata bi bila nepokrivena → leak.
        // Isti pool racun za same source-ccy je idempotentno pronadjen po valuti.
        Account sourcePool = accountRepository.findBankAccountForUpdateByCurrency(
                        bankRegistrationNumber, postingCurrency.toUpperCase())
                .orElseThrow(() -> new InterbankExceptions.InterbankProtocolException(
                        "Banka nema racun za " + postingCurrency
                                + " (cross-currency inter-bank source settlement)"));
        sourcePool.setBalance(sourcePool.getBalance().add(amount));
        sourcePool.setAvailableBalance(sourcePool.getAvailableBalance().add(amount));
        accountRepository.save(sourcePool);

        // Primalac dobija "Krajnju vrednost".
        recipient.setBalance(recipient.getBalance().add(recipientCredit));
        recipient.setAvailableBalance(recipient.getAvailableBalance().add(recipientCredit));
        accountRepository.save(recipient);

        // Banka (target pool): −converted (isplata) +fee (provizija) = −recipientCredit neto.
        bankAccount.setBalance(bankAccount.getBalance().subtract(payout).add(fee));
        bankAccount.setAvailableBalance(bankAccount.getAvailableBalance().subtract(payout).add(fee));
        accountRepository.save(bankAccount);
    }

    /**
     * Rezervise hartije preko trading-service seam-a (faza 2f). {@code idempotencyKey}
     * je determinisitcki po inter-bank transakciji + postingu — retry je bezbedan.
     */
    public void reserveStock(String idempotencyKey, Long userId, String role,
                             String ticker, int quantity){
        try {
            tradingServiceClient.reserveStock(idempotencyKey,
                    new ReserveStockRequest(userId, role, ticker, quantity));
        } catch (TradingServiceClientException e) {
            throw new InterbankExceptions.InterbankProtocolException(e.getMessage());
        }
    }

    /**
     * Oslobadja rezervisane hartije preko trading-service seam-a (faza 2f).
     */
    public void releaseStock(String idempotencyKey, Long userId, String role,
                             String ticker, int quantity){
        try {
            tradingServiceClient.releaseStock(idempotencyKey,
                    new ReleaseStockRequest(userId, role, ticker, quantity));
        } catch (TradingServiceClientException e) {
            throw new InterbankExceptions.InterbankProtocolException(e.getMessage());
        }
    }

    /**
     * Commit kretanja hartija preko trading-service seam-a (faza 2f). trading-service
     * razresava listing po ticker-u; za {@code isDebit=true} kreira portfolio ako ne
     * postoji (sa {@code averageBuyPrice} = trenutna cena listinga).
     */
    public void commitStock(String idempotencyKey, Long userId, String role,
                            String ticker, int quantity, boolean isDebit){
        try {
            tradingServiceClient.commitStock(idempotencyKey,
                    new CommitStockRequest(userId, role, ticker, quantity, isDebit));
        } catch (TradingServiceClientException e) {
            throw new InterbankExceptions.InterbankProtocolException(e.getMessage());
        }
    }
}
