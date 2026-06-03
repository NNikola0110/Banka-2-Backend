package rs.raf.banka2_bek.internalapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountCategory;
import rs.raf.banka2_bek.account.model.AccountStatus;
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

    /**
     * Maticni broj banke — bankini trading racuni su Firma (banka) sa racunima
     * po valuti. Isti property koji koristi {@code OtcService.findDefaultAccount}
     * pri razresavanju podrazumevanog racuna zaposlenog; replicira se ovde da bi
     * interni {@code /internal/accounts/preferred} endpoint razresavao racun na
     * identican nacin kao monolit.
     */
    @Value("${bank.registration-number}")
    private String bankRegistrationNumber;

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

        return toDto(account);
    }

    /**
     * Vraca metadata bankinog trading racuna za datu valutu.
     * Baca {@link IllegalArgumentException} (→ 404) ako racun ne postoji.
     */
    @Transactional(readOnly = true)
    public InternalAccountDto getBankTradingAccount(String currencyCode) {
        Account account = accountRepository
                .findFirstByAccountCategoryAndCurrency_Code(AccountCategory.BANK_TRADING, currencyCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Bank trading account not found for currency: " + currencyCode));

        return toDto(account);
    }

    /**
     * Vraca podrazumevani racun ucesnika OTC dogovora u datoj valuti — verno
     * monolitovom {@code OtcService.findDefaultAccount}:
     * <ul>
     *   <li>{@code userRole} CLIENT → klijentov racun: prvo racun u
     *       {@code currencyCode} medju aktivnim racunima sortiranim po
     *       raspolozivom balansu opadajuce; ako takvog nema, prvi aktivan
     *       racun (najveci raspolozivi balans).</li>
     *   <li>{@code userRole} EMPLOYEE/ADMIN → bankin racun u {@code currencyCode};
     *       ako takav ne postoji, bankin USD racun (fallback, kao u monolitu).</li>
     * </ul>
     * Baca {@link IllegalArgumentException} (→ 404) ako racun ne postoji.
     *
     * <p>Napomena: monolit koristi pesimisticki-zakljucane upite
     * ({@code findForUpdate*}); zakljucavanje je relevantno samo unutar te
     * jedne ACID transakcije i ne prelazi servisnu granicu, pa se ovde koriste
     * ne-zakljucavajuci upiti sa identicnim WHERE/ORDER BY — logika izbora
     * racuna je ista. Novcane mutacije i njihovo zakljucavanje rade
     * {@code /internal/funds/**} endpoint-i.
     */
    @Transactional(readOnly = true)
    public InternalAccountDto getPreferredAccount(String userRole, Long userId, String currencyCode) {
        if ("CLIENT".equalsIgnoreCase(userRole)) {
            List<Account> accounts = accountRepository
                    .findByClientIdAndStatusOrderByAvailableBalanceDesc(userId, AccountStatus.ACTIVE);
            Account account = accounts.stream()
                    .filter(a -> currencyCode.equals(a.getCurrency().getCode()))
                    .findFirst()
                    .or(() -> accounts.stream().findFirst())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Korisnik #" + userId + " nema aktivan racun."));
            return toDto(account);
        }
        if ("EMPLOYEE".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            Account account = accountRepository
                    .findBankAccountByCurrency(bankRegistrationNumber, currencyCode)
                    .or(() -> accountRepository.findBankAccountByCurrency(bankRegistrationNumber, "USD"))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Bankin racun u " + currencyCode + " ne postoji."));
            return toDto(account);
        }
        throw new UnknownUserRoleException(userRole);
    }

    /**
     * Vraca zaposlene filtrirane po opcionim atributima (case-insensitive
     * {@code contains}). Bez parametara → svi zaposleni. Podrzava actuary domen
     * koji posle ekstrakcije filtrira zaposlene po imenu/prezimenu/email/poziciji.
     */
    @Transactional(readOnly = true)
    public List<InternalUserDto> findEmployees(String firstName, String lastName,
                                               String email, String position) {
        return employeeRepository.findByFilters(
                        blankToNull(email), blankToNull(firstName),
                        blankToNull(lastName), blankToNull(position))
                .stream()
                .map(e -> new InternalUserDto(
                        e.getId(), "EMPLOYEE", e.getEmail(),
                        e.getFirstName(), e.getLastName(),
                        Boolean.TRUE.equals(e.getActive()), e.getPosition()))
                .toList();
    }

    /**
     * Vraca permisije korisnika identifikovanog preko {@code email}.
     *
     * <p>Prvo trazi zaposlenog — ako postoji, vraca njegove eksplicitne permisije
     * ({@code SUPERVISOR}, {@code AGENT}, {@code TRADE_STOCKS} ...).
     *
     * <p>Ako zaposlenog nema (npr. email je klijentov), pada na klijenta: klijent
     * sa {@code canTradeStocks=true} razresava jedinu permisiju {@code TRADE_STOCKS}
     * (FE AuthContext mapira isti flag → autoritet). Bez ovog fallback-a svaki
     * klijent dobija 403 na {@code POST /orders} (P0-2), jer trading-service
     * razresava per-permisija autoritete iskljucivo preko ovog endpoint-a.
     *
     * <p>Klijent sa {@code canTradeStocks=false}, kao i nepoznat email, vracaju
     * praznu listu.
     *
     * <p>P2-config-2 (R1 403): lookup je case-INsensitive. Email se cuva u bazi
     * onako kako je unet pri registraciji (bez normalizacije), a JWT subject moze
     * doci sa drugacijim case-om → exact {@code findByEmail} bi vratio prazno →
     * klijent gubi {@code TRADE_STOCKS} → 403 na trgovinu. Mirror login-putanje
     * ({@code findByEmailIgnoreCase}).
     */
    @Transactional(readOnly = true)
    public List<String> getUserPermissions(String email) {
        Employee employee = employeeRepository.findByEmailIgnoreCase(email).orElse(null);
        if (employee != null) {
            return new ArrayList<>(employee.getPermissions());
        }
        return clientRepository.findByEmailIgnoreCase(email)
                .filter(client -> Boolean.TRUE.equals(client.getCanTradeStocks()))
                .map(client -> List.of("TRADE_STOCKS"))
                .orElse(Collections.emptyList());
    }

    /**
     * OT-1061: vraca id-eve svih AKTIVNIH supervizora (zaposleni sa
     * {@code SUPERVISOR} permisijom). Koristi ga interni
     * {@code GET /internal/users/supervisors} endpoint da trading-service razresi
     * primaoce tax-FX-failure notifikacije — trading nema listu supervizora pa je
     * rezolvuje preko ovog banka-core seam-a (mirror {@code getUserPermissions}
     * obrasca: trading donosi authz/notify odluke iz banka-core izvora istine).
     */
    @Transactional(readOnly = true)
    public List<Long> getSupervisorIds() {
        return employeeRepository.findActiveEmployeeIdsByPermission(
                rs.raf.banka2_bek.auth.util.UserRole.SUPERVISOR);
    }

    /**
     * Razresava identitet korisnika (numericki id + rola) na osnovu email-a.
     * Trazi prvo medju klijentima, pa medju zaposlenima.
     * Baca {@link IllegalArgumentException} (→ 404) ako nijedan ne postoji.
     *
     * <p>P2-config-2 (R1 403): case-INsensitive (isto kao {@link #getUserPermissions})
     * da identity resolve ne razilazi sa permission resolve na razlicit case email-a.
     */
    @Transactional(readOnly = true)
    public InternalUserDto getUserByEmail(String email) {
        Client client = clientRepository.findByEmailIgnoreCase(email).orElse(null);
        if (client != null) {
            // Klijent nema radno mesto — position je null.
            return new InternalUserDto(
                    client.getId(), "CLIENT", email,
                    client.getFirstName(), client.getLastName(),
                    Boolean.TRUE.equals(client.getActive()), null);
        }
        Employee employee = employeeRepository.findByEmailIgnoreCase(email).orElse(null);
        if (employee != null) {
            return new InternalUserDto(
                    employee.getId(), "EMPLOYEE", email,
                    employee.getFirstName(), employee.getLastName(),
                    Boolean.TRUE.equals(employee.getActive()), employee.getPosition());
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
            // Klijent nema radno mesto — position je null.
            return new InternalUserDto(
                    client.getId(), "CLIENT", client.getEmail(),
                    client.getFirstName(), client.getLastName(),
                    Boolean.TRUE.equals(client.getActive()), null);
        }
        if ("EMPLOYEE".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            Employee employee = employeeRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + id));
            return new InternalUserDto(
                    employee.getId(), "EMPLOYEE", employee.getEmail(),
                    employee.getFirstName(), employee.getLastName(),
                    Boolean.TRUE.equals(employee.getActive()), employee.getPosition());
        }
        throw new UnknownUserRoleException(userRole);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * R1-713: za sistemske racune (FUND / BANK_TRADING / MARGIN) ne postoji
     * klijent/kompanija vlasnik, pa umesto generickog "Unknown" vracamo eksplicitan
     * sistemski label izveden iz {@link AccountCategory}. Tako trading-service prikaz
     * jasno oznacava bankine/fond pool racune umesto da ih predstavi kao nepoznatog
     * vlasnika (zavaravajuce u audit/UI prikazu).
     */
    private String resolveOwnerName(Account account) {
        if (account.getClient() != null) {
            return account.getClient().getFirstName() + " " + account.getClient().getLastName();
        }
        if (account.getCompany() != null) {
            return account.getCompany().getName();
        }
        AccountCategory category = account.getAccountCategory();
        if (category != null) {
            return switch (category) {
                case FUND -> "Investicioni fond (sistemski)";
                case BANK_TRADING -> "Banka 2 (sistemski)";
                case MARGIN -> "Margin racun (sistemski)";
                case CLIENT -> "Nepoznat vlasnik";
            };
        }
        return "Nepoznat vlasnik";
    }

    /**
     * Mapira {@link Account} u {@link InternalAccountDto}, ukljucujuci polja
     * vlasnistva ({@code ownerClientId}/{@code ownerEmployeeId}/
     * {@code accountCategory}) koja trading-service koristi za reprodukciju
     * monolitove provere vlasnistva racuna.
     */
    private InternalAccountDto toDto(Account account) {
        return new InternalAccountDto(
                account.getId(),
                account.getAccountNumber(),
                resolveOwnerName(account),
                account.getBalance(),
                account.getAvailableBalance(),
                account.getReservedAmount(),
                account.getCurrency().getCode(),
                account.getStatus().name(),
                account.getClient() != null ? account.getClient().getId() : null,
                account.getEmployee() != null ? account.getEmployee().getId() : null,
                account.getAccountCategory() != null ? account.getAccountCategory().name() : null
        );
    }
}
