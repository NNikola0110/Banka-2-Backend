package rs.raf.banka2_bek.interbank.service;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@Transactional
public class InterbankReservationApplier {

    private final AccountRepository accountRepository;
    private final TradingServiceInternalClient tradingServiceClient;

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

    public void commitMonas(String accountNumber, BigDecimal amount, boolean isDebit){
        Account acct = accountRepository.findForUpdateByAccountNumber(accountNumber)
                .orElseThrow(
                        () -> new InterbankExceptions.InterbankProtocolException("NO_SUCH_ACCOUNT: " + accountNumber)
                );

        if (isDebit) {
            acct.setBalance(acct.getBalance().add(amount));
            acct.setAvailableBalance(acct.getAvailableBalance().add(amount));
        }
        else {
            acct.setBalance(acct.getBalance().subtract(amount));
            acct.setReservedAmount(acct.getReservedAmount().subtract(amount));
        }
        accountRepository.save(acct);
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
