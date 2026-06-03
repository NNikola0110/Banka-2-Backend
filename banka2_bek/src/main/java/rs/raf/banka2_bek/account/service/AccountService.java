package rs.raf.banka2_bek.account.service;

import org.springframework.data.domain.Page;
import rs.raf.banka2_bek.account.dto.AccountResponseDto;
import rs.raf.banka2_bek.account.dto.CreateAccountDto;

import java.math.BigDecimal;
import java.util.List;

public interface AccountService {

    AccountResponseDto createAccount(CreateAccountDto request);

    /**
     * Returns a list of active accounts for the currently authenticated client,
     * sorted by available balance in descending order.
     *
     * @return list of account response DTOs (empty if the authenticated user is not a client)
     */
    List<AccountResponseDto> getMyAccounts();

    /**
     * Returns detailed information about a single account.
     * Only the account owner (client) can access it.
     *
     * @param accountId account ID
     * @return account response DTO with full details
     * @throws IllegalArgumentException if account not found
     * @throws IllegalStateException    if the authenticated user is not the account owner
     */
    AccountResponseDto getAccountById(Long accountId);

    /**
     * updates the name of the acc
     * only owner can change it
     * cannot be the same as the old one
     * @param accountId
     * @param newName
     * @return
     */
    AccountResponseDto updateAccountName(Long accountId, String newName);

    /**
     * Updates the daily/monthly spending limits for an account.
     *
     * <p>ACCEPTED-DEVIATION (user-directed 03.06): the OTP/verification gate that
     * Celina 2 ("Promena limita (zahteva verifikaciju)") prescribed has been removed
     * by explicit product decision — OTP now applies ONLY to payments and transfers
     * (money-out). Limit change applies directly with no verification code.
     *
     * @param accountId   account ID
     * @param dailyLimit  new daily limit (nullable — only applied when present)
     * @param monthlyLimit new monthly limit (nullable — only applied when present)
     * @return updated account
     */
    AccountResponseDto updateAccountLimits(Long accountId, BigDecimal dailyLimit,
                                           BigDecimal monthlyLimit);

    Page<AccountResponseDto> getAllAccounts(int page, int limit, String ownerName);

    List<AccountResponseDto> getAccountsByClient(Long clientId);

    AccountResponseDto changeAccountStatus(Long accountId, String newStatus);

    List<AccountResponseDto> getBankAccounts();
}
