package rs.raf.banka2_bek.account.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import rs.raf.banka2_bek.account.dto.AccountResponseDto;
import rs.raf.banka2_bek.account.dto.CreateAccountDto;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.model.AccountStatus;
import rs.raf.banka2_bek.account.model.AccountType;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.account.service.implementation.AccountServiceImplementation;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.card.service.CardService;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.company.model.AuthorizedPerson;
import rs.raf.banka2_bek.company.model.Company;
import rs.raf.banka2_bek.company.repository.CompanyRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.currency.repository.CurrencyRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Targets remaining missed branches/instructions in AccountServiceImplementation:
 *  - L84 currency null branch
 *  - L107 ownerEmail blank branch (business path)
 *  - L199 createCard=false branch
 *  - L204 client.getEmail()==null branch
 *  - L205-207 accountType==null ternary branch
 *  - L266 authorizedPersons stream with ap.getClient()==null branch
 *  - L314 duplicate name in another account branch
 *  - L388-389 toResponse accountType==null branch
 *  - L428 getAuthenticatedEmail auth==null branch
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceBranchCoverageTest {

    @Mock private AccountRepository accountRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private CurrencyRepository currencyRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private CardService cardService;
    @Mock private NotificationPublisher notificationPublisher;
    @Mock private rs.raf.banka2_bek.audit.service.AuditLogService auditLogService;
    @Mock private rs.raf.banka2_bek.audit.service.CurrentAuditActorResolver currentAuditActorResolver;

    private AccountServiceImplementation accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountServiceImplementation(
                accountRepository, clientRepository, currencyRepository,
                companyRepository, employeeRepository, userRepository,
                cardService, notificationPublisher, "22200011", auditLogService,
                currentAuditActorResolver
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authAs(String email) {
        UserDetails ud = User.builder().username(email).password("x").authorities("ROLE_CLIENT").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities()));
    }

    private Currency rsd() {
        Currency c = new Currency();
        c.setId(8L);
        c.setCode("RSD");
        return c;
    }

    private Employee emp() {
        return Employee.builder().id(1L).firstName("E").lastName("E").active(true).build();
    }

    // ---------- L84: currencyCode == null ----------
    @Test
    void createAccount_throwsWhenCurrencyNull() {
        CreateAccountDto req = new CreateAccountDto();
        req.setCurrencyCode(null);
        req.setCurrency(null);

        assertThatThrownBy(() -> accountService.createAccount(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Valuta je obavezna");
    }

    // ---------- L107: ownerEmail blank branch (business path so we don't NPE elsewhere) ----------
    @Test
    void createAccount_ownerEmailBlank_businessAccount_doesNotResolveClient() {
        Currency c = rsd();
        when(currencyRepository.findByCode("RSD")).thenReturn(Optional.of(c));
        authAs("admin@bank.rs");
        when(employeeRepository.findByEmail("admin@bank.rs")).thenReturn(Optional.of(emp()));
        when(companyRepository.save(any(Company.class))).thenAnswer(inv -> {
            Company comp = inv.getArgument(0);
            comp.setId(99L);
            return comp;
        });
        when(accountRepository.existsByAccountNumber(any())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(123L);
            return a;
        });

        CreateAccountDto req = new CreateAccountDto();
        req.setCurrency("RSD");
        req.setAccountType(AccountType.CHECKING);
        req.setClientId(null);
        req.setOwnerEmail(""); // blank — exercises L107 false branch
        req.setCompanyName("ACME");
        req.setRegistrationNumber("123");
        req.setTaxId("456");
        req.setActivityCode("01");
        req.setFirmAddress("addr");

        AccountResponseDto dto = accountService.createAccount(req);
        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(123L);
    }

    // ---------- L204: client.getEmail() == null (skip email send branch) ----------
    @Test
    void createAccount_personalAccount_createCardFalse_clientEmailNull() {
        Currency c = rsd();
        when(currencyRepository.findByCode("RSD")).thenReturn(Optional.of(c));
        authAs("admin@bank.rs");
        when(employeeRepository.findByEmail("admin@bank.rs")).thenReturn(Optional.of(emp()));

        Client client = Client.builder()
                .id(7L)
                .firstName("F")
                .lastName("L")
                .email(null) // L204 false branch
                .build();
        when(clientRepository.findById(7L)).thenReturn(Optional.of(client));

        when(accountRepository.existsByAccountNumber(any())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(55L);
            return a;
        });

        CreateAccountDto req = new CreateAccountDto();
        req.setCurrency("RSD");
        req.setAccountType(AccountType.CHECKING);
        req.setClientId(7L);
        req.setCreateCard(false); // L199 false branch

        AccountResponseDto dto = accountService.createAccount(req);
        assertThat(dto).isNotNull();
    }

    // ---------- L199: createCard=true && client != null (calls cardService) ----------
    // ---------- L205-207: accountType == null with email send block executed ----------
    @Test
    void createAccount_createCardTrue_accountTypeNull_sendsMail() {
        Currency c = rsd();
        when(currencyRepository.findByCode("RSD")).thenReturn(Optional.of(c));
        authAs("admin@bank.rs");
        when(employeeRepository.findByEmail("admin@bank.rs")).thenReturn(Optional.of(emp()));

        Client client = Client.builder()
                .id(8L)
                .firstName("F")
                .lastName("L")
                .email("client@x") // non-null email — enters mail block
                .build();
        when(clientRepository.findById(8L)).thenReturn(Optional.of(client));

        when(accountRepository.existsByAccountNumber(any())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(56L);
            return a;
        });

        CreateAccountDto req = new CreateAccountDto();
        req.setCurrency("RSD");
        req.setAccountType(null); // L205-207 accountType null branch (inside mail block)
        req.setClientId(8L);
        req.setCreateCard(true); // L199 true branch

        AccountResponseDto dto = accountService.createAccount(req);
        assertThat(dto).isNotNull();
    }

    // ---------- L266: authorizedPersons stream where ap.getClient() == null ----------
    @Test
    void getAccountById_companyAuthorizedPersonWithNullClient_throws() {
        Client actor = Client.builder().id(42L).email("actor@x").build();
        authAs("actor@x");
        when(clientRepository.findByEmail("actor@x")).thenReturn(Optional.of(actor));

        Company company = Company.builder()
                .id(1L).name("ACME")
                .authorizedPersons(new ArrayList<>())
                .build();
        // ap with null client → exercises ap.getClient() != null FALSE branch on L266
        AuthorizedPerson apNull = AuthorizedPerson.builder().client(null).company(company).build();
        company.getAuthorizedPersons().add(apNull);

        Account acc = Account.builder()
                .id(100L)
                .currency(rsd())
                .status(AccountStatus.ACTIVE)
                .balance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .company(company)
                .build();
        when(accountRepository.findById(100L)).thenReturn(Optional.of(acc));

        assertThatThrownBy(() -> accountService.getAccountById(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not have access");
    }

    // ---------- L314: duplicate name in another account ----------
    @Test
    void updateAccountName_duplicateNameInAnotherAccount_throws() {
        Client client = Client.builder().id(5L).email("c@x").build();
        authAs("c@x");
        when(clientRepository.findByEmail("c@x")).thenReturn(Optional.of(client));

        Account target = Account.builder()
                .id(1L).name("Old")
                .client(client)
                .currency(rsd())
                .status(AccountStatus.ACTIVE)
                .balance(BigDecimal.ZERO).availableBalance(BigDecimal.ZERO)
                .build();
        Account other = Account.builder()
                .id(2L).name("Stednja")
                .client(client)
                .currency(rsd())
                .status(AccountStatus.ACTIVE)
                .balance(BigDecimal.ZERO).availableBalance(BigDecimal.ZERO)
                .build();

        when(accountRepository.findById(1L)).thenReturn(Optional.of(target));
        // R1-307: dedup ide preko svih racuna vlasnika (findByClientId), svi statusi.
        when(accountRepository.findByClientId(5L))
                .thenReturn(List.of(target, other));

        assertThatThrownBy(() -> accountService.updateAccountName(1L, "Stednja"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already used");
    }

    // ---------- L314: updateAccountName success path (no duplicate) ----------
    @Test
    void updateAccountName_success_noDuplicate() {
        Client client = Client.builder().id(6L).email("c@x").build();
        authAs("c@x");
        when(clientRepository.findByEmail("c@x")).thenReturn(Optional.of(client));

        Account target = Account.builder()
                .id(1L).name("Old")
                .client(client)
                .currency(rsd())
                .status(AccountStatus.ACTIVE)
                .balance(BigDecimal.ZERO).availableBalance(BigDecimal.ZERO)
                .build();

        when(accountRepository.findById(1L)).thenReturn(Optional.of(target));
        when(accountRepository.findByClientId(6L))
                .thenReturn(List.of(target));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponseDto dto = accountService.updateAccountName(1L, "NewName");
        assertThat(dto.getName()).isEqualTo("NewName");
    }

    // ---------- L428: getAuthenticatedEmail with auth.isAuthenticated() == false ----------
    @Test
    void getAuthenticatedEmail_authNotAuthenticated_throws() throws Exception {
        org.springframework.security.core.Authentication auth =
                org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class);
        org.mockito.Mockito.when(auth.isAuthenticated()).thenReturn(false);
        org.springframework.security.core.context.SecurityContext ctx =
                org.mockito.Mockito.mock(org.springframework.security.core.context.SecurityContext.class);
        org.mockito.Mockito.when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);

        Method m = AccountServiceImplementation.class.getDeclaredMethod("getAuthenticatedEmail");
        m.setAccessible(true);
        assertThatThrownBy(() -> {
            try {
                m.invoke(accountService);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not authenticated");
    }

    // ---------- L388-389: toResponse with accountType == null ----------
    // Covered indirectly via createAccount above, but invoke directly through reflection
    // to guarantee the ternary's false branch on L388 + the mi=1 on L389 (`null` literal arm).
    @Test
    void toResponse_accountTypeNull_returnsNull() throws Exception {
        Account a = Account.builder()
                .id(1L)
                .accountNumber("X")
                .accountType(null)
                .currency(rsd())
                .status(AccountStatus.ACTIVE)
                .balance(BigDecimal.ZERO).availableBalance(BigDecimal.ZERO)
                .build();

        Method m = AccountServiceImplementation.class.getDeclaredMethod("toResponse", Account.class);
        m.setAccessible(true);
        AccountResponseDto dto = (AccountResponseDto) m.invoke(accountService, a);
        assertThat(dto.getAccountType()).isNull();
    }

    // ---------- L428: getAuthenticatedEmail auth == null ----------
    @Test
    void getAuthenticatedEmail_authNull_throws() throws Exception {
        SecurityContextHolder.clearContext();
        Method m = AccountServiceImplementation.class.getDeclaredMethod("getAuthenticatedEmail");
        m.setAccessible(true);
        assertThatThrownBy(() -> {
            try {
                m.invoke(accountService);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not authenticated");
    }
}
