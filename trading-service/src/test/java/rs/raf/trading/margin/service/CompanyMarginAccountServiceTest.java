package rs.raf.trading.margin.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsResponse;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.margin.dto.CreateCompanyMarginAccountDto;
import rs.raf.trading.margin.dto.MarginAccountDto;
import rs.raf.trading.margin.model.CompanyMarginAccount;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.repository.MarginAccountRepository;
import rs.raf.trading.margin.repository.MarginTransactionRepository;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit testovi {@link MarginAccountService} — COMPANY (BE-STK-06) putanja.
 *
 * <p>Marzni_Racuni.txt §25-27 + §43-61: marzni racun kompanije kreira zaposleni
 * sa eksplicitnim IM/MM/BP. Identitet ide kroz {@code TradingUserResolver}
 * (samo EMPLOYEE sme), bazni racun se debituje preko {@code BankaCoreClient}.
 * Mirror eksplicitne grane {@link MarginAccountService#createForUser}.
 */
@ExtendWith(MockitoExtension.class)
class CompanyMarginAccountServiceTest {

    @Mock
    private MarginAccountRepository marginAccountRepository;
    @Mock
    private MarginTransactionRepository marginTransactionRepository;
    @Mock
    private BankaCoreClient bankaCoreClient;
    @Mock
    private TradingUserResolver userResolver;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MarginAccountService marginAccountService;

    @BeforeEach
    void setUp() {
        marginAccountService = new MarginAccountService(
                marginAccountRepository,
                marginTransactionRepository,
                bankaCoreClient,
                userResolver,
                eventPublisher
        );
        // P2-authz-method-1 (R1 468/467): company-margin endpointi sad zahtevaju
        // SUPERVISOR/ADMIN authority (ne bilo kog EMPLOYEE-a). Postojeci
        // pozitivni testovi predstavljaju supervizora — postavljamo SUPERVISOR
        // authority u SecurityContext (mirror produkcionog JWT filtera).
        setAuthorities("supervisor@banka.rs", "SUPERVISOR");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── createForCompany ─────────────────────────────────────────────────────

    @Test
    void createForCompany_success_persistsCompanyAccountWithExplicitValues() {
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 777L, new BigDecimal("8000.00"), new BigDecimal("4000.00"),
                new BigDecimal("0.60"));

        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccount(1L, "ACTIVE", "222000112345678911", "10000.00", "10000.00"));
        when(marginAccountRepository.findByCompanyId(777L)).thenReturn(Optional.empty());
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(java.util.List.of());
        when(bankaCoreClient.debitFunds(any(), any(DebitFundsRequest.class)))
                .thenReturn(new DebitFundsResponse(1L, new BigDecimal("2000.00"),
                        new BigDecimal("2000.00")));
        when(marginAccountRepository.save(any(MarginAccount.class))).thenAnswer(inv -> {
            MarginAccount saved = inv.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        MarginAccountDto result = marginAccountService.createForCompany(dto);

        // Persistovan tip je CompanyMarginAccount (dtype COMPANY).
        ArgumentCaptor<MarginAccount> accountCaptor = ArgumentCaptor.forClass(MarginAccount.class);
        verify(marginAccountRepository).save(accountCaptor.capture());
        MarginAccount saved = accountCaptor.getValue();
        assertThat(saved).isInstanceOf(CompanyMarginAccount.class);
        assertThat(((CompanyMarginAccount) saved).getCompanyId()).isEqualTo(777L);
        assertThat(saved.getOwnerType()).isEqualTo("COMPANY");
        assertThat(saved.getUserId()).isNull();
        assertThat(saved.getCurrency()).isEqualTo("RSD");
        assertThat(saved.getStatus()).isEqualTo(MarginAccountStatus.ACTIVE);
        // Eksplicitne IM/MM/BP, loanValue 0.
        assertThat(saved.getInitialMargin()).isEqualByComparingTo("8000.0000");
        assertThat(saved.getMaintenanceMargin()).isEqualByComparingTo("4000.0000");
        assertThat(saved.getBankParticipation()).isEqualByComparingTo("0.60");
        assertThat(saved.getLoanValue()).isEqualByComparingTo("0.0000");

        // DTO nosi companyId, ne userId.
        assertThat(result.getCompanyId()).isEqualTo(777L);
        assertThat(result.getUserId()).isNull();
        assertThat(result.getInitialMargin()).isEqualByComparingTo("8000.0000");
        assertThat(result.getStatus()).isEqualTo(MarginAccountStatus.ACTIVE.name());

        // Debit baznog racuna iznosom IM (pocetni depozit = IM).
        verify(bankaCoreClient).debitFunds(
                eq("margin-create-company-1"),
                eq(new DebitFundsRequest(1L, new BigDecimal("8000.0000"), BigDecimal.ZERO,
                        "RSD", "Initial company margin deposit")));
    }

    @Test
    void createForCompany_throwsWhenCallerIsNotEmployee() {
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 777L, new BigDecimal("8000.00"), new BigDecimal("4000.00"),
                new BigDecimal("0.60"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));

        assertThatThrownBy(() -> marginAccountService.createForCompany(dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only employees can manage company margin accounts.");

        verify(marginAccountRepository, never()).save(any());
        verify(bankaCoreClient, never()).debitFunds(any(), any());
    }

    @Test
    void createForCompany_agentEmployeeIsDenied_notSupervisorNorAdmin() {
        // P2-authz-method-1 (R1 468/467) — RED pre fix-a: AGENT je EMPLOYEE i
        // ranija provera {@code UserRole.EMPLOYEE.equals(role)} ga je puštala da
        // kreira marzni racun kompanije. Sad: agent (EMPLOYEE bez SUPERVISOR/ADMIN
        // authority) → AccessDeniedException. Marzni_Racuni.txt §25-27: company
        // margin zadaje supervizor/admin.
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 777L, new BigDecimal("8000.00"), new BigDecimal("4000.00"),
                new BigDecimal("0.60"));
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        // Agent: EMPLOYEE rola ali NEMA SUPERVISOR ni ADMIN authority.
        setAuthorities("agent@banka.rs", "AGENT", "ROLE_EMPLOYEE");

        assertThatThrownBy(() -> marginAccountService.createForCompany(dto))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("supervisors and admins");

        verify(marginAccountRepository, never()).save(any());
        verify(bankaCoreClient, never()).debitFunds(any(), any());
    }

    @Test
    void createForCompany_adminEmployeeIsAllowed() {
        // ADMIN authority (admin je uvek supervizor) — prolazi gate (paritet sa
        // supervizorom). Verifikuje da fix ne over-restriktira admina.
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 777L, new BigDecimal("8000.00"), new BigDecimal("4000.00"),
                new BigDecimal("0.60"));
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        setAuthorities("admin@banka.rs", "ROLE_ADMIN", "ADMIN");
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccount(1L, "ACTIVE", "222000112345678911", "10000.00", "10000.00"));
        when(marginAccountRepository.findByCompanyId(777L)).thenReturn(Optional.empty());
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of());
        when(bankaCoreClient.debitFunds(any(), any(DebitFundsRequest.class)))
                .thenReturn(new DebitFundsResponse(1L, new BigDecimal("2000.00"),
                        new BigDecimal("2000.00")));
        when(marginAccountRepository.save(any(MarginAccount.class))).thenAnswer(inv -> {
            MarginAccount saved = inv.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        MarginAccountDto result = marginAccountService.createForCompany(dto);

        assertThat(result.getCompanyId()).isEqualTo(777L);
        verify(marginAccountRepository).save(any(MarginAccount.class));
    }

    @Test
    void getCompanyMarginAccount_agentEmployeeIsDenied() {
        // P2-authz-method-1 (R1 468/467) — citanje marzni racuna kompanije isto
        // zahteva SUPERVISOR/ADMIN. Agent (EMPLOYEE bez authority) → denied.
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        setAuthorities("agent@banka.rs", "AGENT", "ROLE_EMPLOYEE");

        assertThatThrownBy(() -> marginAccountService.getCompanyMarginAccount(777L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("supervisors and admins");

        verify(marginAccountRepository, never()).findByCompanyId(any());
    }

    @Test
    void createForCompany_throwsWhenDtoIsNull() {
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));

        assertThatThrownBy(() -> marginAccountService.createForCompany(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void createForCompany_throwsWhenCompanyAlreadyHasMarginAccount() {
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 777L, new BigDecimal("8000.00"), new BigDecimal("4000.00"),
                new BigDecimal("0.60"));
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        when(marginAccountRepository.findByCompanyId(777L))
                .thenReturn(Optional.of(new CompanyMarginAccount()));

        assertThatThrownBy(() -> marginAccountService.createForCompany(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Margin account already exists for this company.");

        verify(bankaCoreClient, never()).debitFunds(any(), any());
        verify(marginAccountRepository, never()).save(any());
    }

    @Test
    @DisplayName("OT-1105 (TOCTOU): createForCompany — per-bazni-racun guard (findByAccountId) okine "
            + "→ NEPOVRATNI debitFunds se NIKAD ne poziva (distinktan od company-id guard-a)")
    void createForCompany_duplicateBaseAccountGuard_neverDebits() {
        // Drugi guard u createForCompany: posle company-id provere i getAccount-a,
        // proverava se da bazni racun (account.id()) vec NEMA margin racun
        // (findByAccountId). Ovo je TOCTOU gubitnik na nivou baznog racuna (npr.
        // company-id se razlikuje ali bazni racun se preklapa). Invarijanta:
        // ireverzibilni debit se ne sme izvrsiti kad ovaj guard okine.
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 888L, new BigDecimal("8000.00"), new BigDecimal("4000.00"),
                new BigDecimal("0.60"));
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        when(marginAccountRepository.findByCompanyId(888L)).thenReturn(Optional.empty());
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccount(1L, "ACTIVE", "222000112345678911", "10000.00", "10000.00"));
        // Bazni racun vec ima margin racun (konkurentni create ga je vezao).
        when(marginAccountRepository.findByAccountId(1L))
                .thenReturn(java.util.List.of(new MarginAccount()));

        assertThatThrownBy(() -> marginAccountService.createForCompany(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Margin account already exists for this base account.");

        verify(bankaCoreClient, never()).debitFunds(any(), any());
        verify(marginAccountRepository, never()).save(any());
    }

    @Test
    void createForCompany_throwsWhenInitialMarginNonPositive() {
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 777L, BigDecimal.ZERO, new BigDecimal("0.00"),
                new BigDecimal("0.60"));
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccount(1L, "ACTIVE", "222000112345678911", "10000.00", "10000.00"));
        when(marginAccountRepository.findByCompanyId(777L)).thenReturn(Optional.empty());
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(java.util.List.of());

        assertThatThrownBy(() -> marginAccountService.createForCompany(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("InitialMargin must be greater than zero");
    }

    @Test
    void createForCompany_throwsWhenMaintenanceExceedsInitial() {
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 777L, new BigDecimal("4000.00"), new BigDecimal("5000.00"),
                new BigDecimal("0.60"));
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccount(1L, "ACTIVE", "222000112345678911", "10000.00", "10000.00"));
        when(marginAccountRepository.findByCompanyId(777L)).thenReturn(Optional.empty());
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(java.util.List.of());

        assertThatThrownBy(() -> marginAccountService.createForCompany(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MaintenanceMargin must not exceed InitialMargin");
    }

    @Test
    void createForCompany_throwsWhenBankParticipationOutOfRange() {
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 777L, new BigDecimal("8000.00"), new BigDecimal("4000.00"),
                BigDecimal.ONE);
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccount(1L, "ACTIVE", "222000112345678911", "10000.00", "10000.00"));
        when(marginAccountRepository.findByCompanyId(777L)).thenReturn(Optional.empty());
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(java.util.List.of());

        assertThatThrownBy(() -> marginAccountService.createForCompany(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BankParticipation must be strictly between 0 and 1");
    }

    @Test
    void createForCompany_throwsWhenBaseAccountNotRsd() {
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 777L, new BigDecimal("8000.00"), new BigDecimal("4000.00"),
                new BigDecimal("0.60"));
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                new InternalAccountDto(1L, "222000112345678911", "Company",
                        new BigDecimal("10000.00"), new BigDecimal("10000.00"),
                        BigDecimal.ZERO, "EUR", "ACTIVE", null, null, "COMPANY"));
        when(marginAccountRepository.findByCompanyId(777L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> marginAccountService.createForCompany(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RSD");
    }

    @Test
    void createForCompany_throwsWhenBankaCoreDebitReturns409() {
        CreateCompanyMarginAccountDto dto = new CreateCompanyMarginAccountDto(
                1L, 777L, new BigDecimal("8000.00"), new BigDecimal("4000.00"),
                new BigDecimal("0.60"));
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccount(1L, "ACTIVE", "222000112345678911", "10000.00", "10000.00"));
        when(marginAccountRepository.findByCompanyId(777L)).thenReturn(Optional.empty());
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(java.util.List.of());
        when(bankaCoreClient.debitFunds(any(), any(DebitFundsRequest.class)))
                .thenThrow(new BankaCoreClientException(409, "insufficient funds"));

        assertThatThrownBy(() -> marginAccountService.createForCompany(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient available balance");

        verify(marginAccountRepository, never()).save(any());
    }

    // ── getCompanyMarginAccount ──────────────────────────────────────────────

    @Test
    void getCompanyMarginAccount_success_returnsDto() {
        CompanyMarginAccount account = CompanyMarginAccount.builder()
                .id(99L)
                .accountId(1L)
                .accountNumber("222000112345678911")
                .companyId(777L)
                .initialMargin(new BigDecimal("8000.0000"))
                .loanValue(BigDecimal.ZERO)
                .maintenanceMargin(new BigDecimal("4000.0000"))
                .bankParticipation(new BigDecimal("0.60"))
                .status(MarginAccountStatus.ACTIVE)
                .build();

        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        when(marginAccountRepository.findByCompanyId(777L)).thenReturn(Optional.of(account));

        MarginAccountDto result = marginAccountService.getCompanyMarginAccount(777L);

        assertThat(result.getId()).isEqualTo(99L);
        assertThat(result.getCompanyId()).isEqualTo(777L);
        assertThat(result.getUserId()).isNull();
        assertThat(result.getAccountNumber()).isEqualTo("222000112345678911");
        assertThat(result.getInitialMargin()).isEqualByComparingTo("8000.0000");
    }

    @Test
    void getCompanyMarginAccount_throwsWhenNotFound() {
        when(userResolver.resolveCurrent()).thenReturn(employee(50L));
        when(marginAccountRepository.findByCompanyId(777L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> marginAccountService.getCompanyMarginAccount(777L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("777");
    }

    @Test
    void getCompanyMarginAccount_throwsWhenCallerIsNotEmployee() {
        when(userResolver.resolveCurrent()).thenReturn(client(10L));

        assertThatThrownBy(() -> marginAccountService.getCompanyMarginAccount(777L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only employees can manage company margin accounts.");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Postavlja SecurityContext sa datim email-om i authority string-ovima. */
    private void setAuthorities(String email, String... authorities) {
        var auths = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "N/A", auths));
    }

    private UserContext client(Long id) {
        return new UserContext(id, "CLIENT");
    }

    private UserContext employee(Long id) {
        return new UserContext(id, "EMPLOYEE");
    }

    private InternalAccountDto bankAccount(Long accountId, String status, String accountNumber,
                                           String available, String balance) {
        return new InternalAccountDto(accountId, accountNumber, "Company",
                new BigDecimal(balance), new BigDecimal(available),
                BigDecimal.ZERO, "RSD", status, null, null, "COMPANY");
    }
}
