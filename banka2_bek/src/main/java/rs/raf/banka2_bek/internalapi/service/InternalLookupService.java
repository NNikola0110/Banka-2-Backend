package rs.raf.banka2_bek.internalapi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lookup operations exposed over the internal API for trading-service:
 * - account metadata (balance, owner, currency)
 * - employee permissions (for authorization decisions in trading-service)
 * - user identity (numeric id + role) resolved from email or id
 */
@Service
public class InternalLookupService {

    private final AccountRepository accountRepository;
    private final EmployeeRepository employeeRepository;
    private final ClientRepository clientRepository;

    public InternalLookupService(AccountRepository accountRepository,
                                 EmployeeRepository employeeRepository,
                                 ClientRepository clientRepository) {
        this.accountRepository = accountRepository;
        this.employeeRepository = employeeRepository;
        this.clientRepository = clientRepository;
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

    /**
     * Razresava identitet korisnika (numericki id + rola) na osnovu email-a.
     * Trazi prvo medju klijentima, pa medju zaposlenima.
     * Baca {@link IllegalArgumentException} (→ 404) ako nijedan ne postoji.
     */
    @Transactional(readOnly = true)
    public InternalUserDto getUserByEmail(String email) {
        Client client = clientRepository.findByEmail(email).orElse(null);
        if (client != null) {
            return new InternalUserDto(
                    client.getId(), "CLIENT", email,
                    client.getFirstName(), client.getLastName(),
                    Boolean.TRUE.equals(client.getActive()));
        }
        Employee employee = employeeRepository.findByEmail(email).orElse(null);
        if (employee != null) {
            return new InternalUserDto(
                    employee.getId(), "EMPLOYEE", email,
                    employee.getFirstName(), employee.getLastName(),
                    Boolean.TRUE.equals(employee.getActive()));
        }
        throw new IllegalArgumentException("User not found: " + email);
    }

    /**
     * Razresava identitet korisnika (numericki id + rola) na osnovu role + id-a.
     * {@code userRole} CLIENT → klijent; EMPLOYEE/ADMIN → zaposleni.
     * Baca {@link IllegalArgumentException} (→ 404) ako korisnik ne postoji.
     */
    @Transactional(readOnly = true)
    public InternalUserDto getUserById(String userRole, Long id) {
        if ("CLIENT".equalsIgnoreCase(userRole)) {
            Client client = clientRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Client not found: " + id));
            return new InternalUserDto(
                    client.getId(), "CLIENT", client.getEmail(),
                    client.getFirstName(), client.getLastName(),
                    Boolean.TRUE.equals(client.getActive()));
        }
        if ("EMPLOYEE".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            Employee employee = employeeRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));
            return new InternalUserDto(
                    employee.getId(), "EMPLOYEE", employee.getEmail(),
                    employee.getFirstName(), employee.getLastName(),
                    Boolean.TRUE.equals(employee.getActive()));
        }
        throw new IllegalArgumentException("Unknown user role: " + userRole);
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
