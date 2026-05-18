package rs.raf.banka2_bek.internalapi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lookup operations exposed over the internal API for trading-service:
 * - account metadata (balance, owner, currency)
 * - employee permissions (for authorization decisions in trading-service)
 */
@Service
public class InternalLookupService {

    private final AccountRepository accountRepository;
    private final EmployeeRepository employeeRepository;

    public InternalLookupService(AccountRepository accountRepository,
                                 EmployeeRepository employeeRepository) {
        this.accountRepository = accountRepository;
        this.employeeRepository = employeeRepository;
    }

    /**
     * Returns account metadata for the given account ID.
     * Throws {@link IllegalArgumentException} if the account is not found.
     */
    @Transactional(readOnly = true)
    public InternalAccountDto getAccount(Long id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));

        String ownerName = resolveOwnerName(account);

        return new InternalAccountDto(
                account.getId(),
                account.getAccountNumber(),
                ownerName,
                account.getBalance(),
                account.getAvailableBalance(),
                account.getReservedAmount(),
                account.getCurrency().getCode(),
                account.getStatus().name()
        );
    }

    /**
     * Returns the permission strings for the employee identified by {@code email}.
     * Returns an empty list if no employee with that email exists.
     */
    @Transactional(readOnly = true)
    public List<String> getUserPermissions(String email) {
        return employeeRepository.findByEmail(email)
                .map(Employee::getPermissions)
                .map(perms -> (List<String>) new ArrayList<>(perms))
                .orElse(Collections.emptyList());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String resolveOwnerName(Account account) {
        if (account.getClient() != null) {
            return account.getClient().getFirstName() + " " + account.getClient().getLastName();
        }
        if (account.getCompany() != null) {
            return account.getCompany().getName();
        }
        return "Unknown";
    }
}
