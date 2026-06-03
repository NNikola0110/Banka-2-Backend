package rs.raf.trading.margin.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import rs.raf.banka2.contracts.internal.CreditFundsRequest;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.banka2.contracts.internal.InternalUserDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.client.BankaCoreClientException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.margin.dto.CreateMarginAccountDto;
import rs.raf.trading.margin.dto.MarginAccountDto;
import rs.raf.trading.margin.event.MarginAccountBlockedEvent;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.model.MarginTransaction;
import rs.raf.trading.margin.model.MarginTransactionType;
import rs.raf.trading.margin.repository.MarginAccountRepository;
import rs.raf.trading.margin.repository.MarginTransactionRepository;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit testovi {@link MarginAccountService} — copy-first ekstrakcija + FK→soft-id
 * + money-seam rewiring (faza 2d-D).
 *
 * <p>NAPOMENA: monolitna verzija je radila sa pravim {@code Account} entitetom,
 * direktno mutirala {@code Account.balance}/{@code availableBalance} preko
 * {@code AccountRepository} i razresavala klijenta preko {@code ClientRepository}.
 * Ovi testovi verifikuju da je money-seam korektno prevezan:
 * <ul>
 *   <li>{@code createForUser} cita bazni racun preko {@code BankaCoreClient.getAccount}
 *       i debituje ga preko {@code debitFunds} (banka-core 409 → faithful
 *       {@code IllegalArgumentException});</li>
 *   <li>identitet ide kroz {@code TradingUserResolver.resolveCurrent()};</li>
 *   <li>{@code MarginAccount} nosi soft {@code accountId} + denormalizovan
 *       {@code accountNumber}.</li>
 * </ul>
 * {@code deposit}/{@code withdraw}/{@code checkMaintenanceMargin} mutiraju samo
 * {@code margin}-owned tabele — testirani kao u monolitu (sa soft identitetom).
 */
@ExtendWith(MockitoExtension.class)
class MarginAccountServiceTest {

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
    }

    @Test
    void getMyMarginAccounts_returnsOnlyAuthenticatedClientAccounts() {
        MarginAccount marginAccount = MarginAccount.builder()
                .id(77L)
                .accountId(5L)
                .accountNumber("222000112345678911")
                .userId(10L)
                .initialMargin(new BigDecimal("10000.0000"))
                .loanValue(new BigDecimal("5000.0000"))
                .maintenanceMargin(new BigDecimal("5000.0000"))
                .bankParticipation(new BigDecimal("0.50"))
                .status(MarginAccountStatus.ACTIVE)
                .build();

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByUserId(10L)).thenReturn(List.of(marginAccount));

        List<MarginAccountDto> result = marginAccountService.getMyMarginAccounts();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(77L);
        assertThat(result.get(0).getUserId()).isEqualTo(10L);
        assertThat(result.get(0).getAccountId()).isEqualTo(5L);
        assertThat(result.get(0).getAccountNumber()).isEqualTo("222000112345678911");
    }

    @Test
    void getMyMarginAccounts_throwsWhenCallerIsNotClient() {
        when(userResolver.resolveCurrent()).thenReturn(employee(99L));

        assertThatThrownBy(() -> marginAccountService.getMyMarginAccounts())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only clients can manage margin accounts.");
    }

    @Test
    void createForUser_success_calculatesAndPersistsAllValues() {
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("5000.00"));

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccountOwnedBy(1L, 10L, "ACTIVE", "222000112345678911",
                        "10000.0000", "10000.0000"));
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of());
        when(marginAccountRepository.save(any(MarginAccount.class))).thenAnswer(invocation -> {
            MarginAccount marginAccount = invocation.getArgument(0);
            marginAccount.setId(55L);
            marginAccount.setCreatedAt(LocalDateTime.now());
            return marginAccount;
        });

        MarginAccountDto result = marginAccountService.createForUser(dto);

        assertThat(result.getId()).isEqualTo(55L);
        assertThat(result.getUserId()).isEqualTo(10L);
        assertThat(result.getStatus()).isEqualTo(MarginAccountStatus.ACTIVE.name());
        assertThat(result.getBankParticipation()).isEqualByComparingTo("0.50");
        assertThat(result.getInitialMargin()).isEqualByComparingTo("10000.0000");
        assertThat(result.getLoanValue()).isEqualByComparingTo("5000.0000");
        assertThat(result.getMaintenanceMargin()).isEqualByComparingTo("5000.0000");
        assertThat(result.getAccountId()).isEqualTo(1L);
        assertThat(result.getAccountNumber()).isEqualTo("222000112345678911");

        // monolit je menjao bazni Account.balance/availableBalance; sad to radi
        // banka-core preko debitFunds — verifikujemo da je debit pozvan tacno.
        verify(bankaCoreClient).debitFunds(
                eq("margin-create-1"),
                eq(new DebitFundsRequest(1L, new BigDecimal("5000.00"), BigDecimal.ZERO,
                        "RSD", "Initial margin deposit")));

        ArgumentCaptor<MarginTransaction> txCaptor = ArgumentCaptor.forClass(MarginTransaction.class);
        verify(marginTransactionRepository).save(txCaptor.capture());

        MarginTransaction tx = txCaptor.getValue();
        assertThat(tx.getType()).isEqualTo(MarginTransactionType.DEPOSIT);
        assertThat(tx.getAmount()).isEqualByComparingTo("5000.0000");
        assertThat(tx.getDescription()).isEqualTo("Initial margin deposit");
    }

    @Test
    void createForUser_throwsWhenDtoIsNull() {
        when(userResolver.resolveCurrent()).thenReturn(client(10L));

        assertThatThrownBy(() -> marginAccountService.createForUser(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account id and initial deposit are required.");
    }

    @Test
    void createForUser_throwsWhenDepositIsNonPositive() {
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, BigDecimal.ZERO);
        when(userResolver.resolveCurrent()).thenReturn(client(10L));

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Initial deposit must be greater than zero.");
    }

    @Test
    void createForUser_throwsWhenAccountMissing() {
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("100.00"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L))
                .thenThrow(new BankaCoreClientException(404, "account not found"));

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account not found.");
    }

    @Test
    void createForUser_throwsWhenAccountOwnedByAnotherClient() {
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("100.00"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccountOwnedBy(1L, 999L, "ACTIVE", "222000112345678911",
                        "1000.00", "1000.00"));

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("You are not allowed to create a margin account for this base account.");
    }

    @Test
    void createForUser_throwsWhenBaseAccountIsInactive() {
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("100.00"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccountOwnedBy(1L, 10L, "BLOCKED", "222000112345678911",
                        "1000.00", "1000.00"));

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Base account must be active.");
    }

    @Test
    void createForUser_throwsWhenMarginAccountAlreadyExists() {
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("100.00"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccountOwnedBy(1L, 10L, "ACTIVE", "222000112345678911",
                        "1000.00", "1000.00"));
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of(new MarginAccount()));

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Margin account already exists for this base account.");
    }

    @Test
    @DisplayName("OT-1105 (TOCTOU): createForUser — kad guard za vec-postojeci racun okine, "
            + "NEPOVRATNI debitFunds se NIKAD ne poziva (gubitnik trke ne dvostruko-debituje)")
    void createForUser_duplicateAccountGuard_neverDebits() {
        // §57 jedan-racun-po-baznom-racunu guard je check-then-act bez DB unique
        // constrainta — racy. Kriticna invarijanta TOCTOU gubitnika: kad guard nadje
        // vec-postojeci margin racun, ireverzibilni out-of-process debitFunds se NE
        // sme izvrsiti (inace bi paralelni create dvaput skinuo pocetni depozit sa
        // baznog racuna). Ovaj test pinuje da guard puca PRE debita.
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("5000.00"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccountOwnedBy(1L, 10L, "ACTIVE", "222000112345678911",
                        "10000.00", "10000.00"));
        // Konkurentni create je vec napravio margin racun za ovaj bazni racun.
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of(new MarginAccount()));

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Margin account already exists for this base account.");

        // NEPOVRATNA novcana noga se nikad ne pokrece + nista se ne perzistuje.
        verify(bankaCoreClient, never()).debitFunds(any(), any());
        verify(marginAccountRepository, never()).save(any());
    }

    @Test
    void createForUser_throwsWhenInsufficientAvailableBalance_preCheck() {
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("100.00"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccountOwnedBy(1L, 10L, "ACTIVE", "222000112345678911",
                        "50.00", "50.00"));
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Insufficient available balance for initial margin deposit.");

        // pre-check je odbio pre nego sto je doslo do banka-core debita.
        verify(bankaCoreClient, never()).debitFunds(any(), any());
    }

    @Test
    void createForUser_throwsWhenBankaCoreDebitReturns409() {
        // Pre-check prodje (availableBalance >= deposit), ali banka-core odbije
        // debit sa 409 (npr. zbog race condition-a) — faithful poruka.
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("5000.00"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccountOwnedBy(1L, 10L, "ACTIVE", "222000112345678911",
                        "10000.00", "10000.00"));
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of());
        when(bankaCoreClient.debitFunds(any(), any(DebitFundsRequest.class)))
                .thenThrow(new BankaCoreClientException(409, "insufficient funds"));

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Insufficient available balance for initial margin deposit.");

        verify(marginAccountRepository, never()).save(any());
    }

    @Test
    void createForUser_propagatesNon409BankaCoreError() {
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("5000.00"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccountOwnedBy(1L, 10L, "ACTIVE", "222000112345678911",
                        "10000.00", "10000.00"));
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of());
        when(bankaCoreClient.debitFunds(any(), any(DebitFundsRequest.class)))
                .thenThrow(new BankaCoreClientException(500, "banka-core down"));

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(BankaCoreClientException.class);

        verify(marginAccountRepository, never()).save(any());
    }

    @Test
    void createForUser_localSaveFailure_compensatesInitialDebit() {
        // P2-money-tx-1 (R3 1577): debitFunds je vec uspeo (out-of-process), pa
        // lokalni save (DataIntegrityViolation — npr. uniqueness race) MORA da
        // okine kompenzacioni creditFunds da se debit ne izgubi.
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("5000.00"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccountOwnedBy(1L, 10L, "ACTIVE", "222000112345678911",
                        "10000.0000", "10000.0000"));
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of());
        // debit prolazi (default mock vraca null je ok — nema 409), ali save baci.
        when(marginAccountRepository.save(any(MarginAccount.class)))
                .thenThrow(new DataIntegrityViolationException("uk_margin_account_account_id"));

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Debit je izvrsen.
        verify(bankaCoreClient).debitFunds(
                eq("margin-create-1"),
                eq(new DebitFundsRequest(1L, new BigDecimal("5000.00"), BigDecimal.ZERO,
                        "RSD", "Initial margin deposit")));
        // Kompenzacioni credit vraca tacno debitovani iznos, sa razlicitim idempotency kljucem.
        verify(bankaCoreClient).creditFunds(
                eq("margin-create-1-compensate"),
                eq(new CreditFundsRequest(1L, new BigDecimal("5000.00"), BigDecimal.ZERO,
                        "RSD", "Compensation: reverse initial margin deposit")));
    }

    @Test
    void createForUser_compensationItselfFailing_stillRethrowsOriginal() {
        // Ako i kompenzacioni credit padne, originalna greska se NE prikriva
        // (rezidual ide u manual reconciliation).
        CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("5000.00"));
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(bankaCoreClient.getAccount(1L)).thenReturn(
                bankAccountOwnedBy(1L, 10L, "ACTIVE", "222000112345678911",
                        "10000.0000", "10000.0000"));
        when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of());
        when(marginAccountRepository.save(any(MarginAccount.class)))
                .thenThrow(new DataIntegrityViolationException("uk_margin_account_account_id"));
        when(bankaCoreClient.creditFunds(any(), any(CreditFundsRequest.class)))
                .thenThrow(new BankaCoreClientException(500, "banka-core down"));

        assertThatThrownBy(() -> marginAccountService.createForUser(dto))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(bankaCoreClient).creditFunds(eq("margin-create-1-compensate"),
                any(CreditFundsRequest.class));
    }

    // ── deposit() tests ────────────────────────────────────────────────────────

    @Test
    void deposit_success_increasesInitialMarginAndPreservesMaintenanceMargin() {
        // BE-STK-07 (25.05.2026): MM je fiksiran pri kreiranju; deposit NE preracunava MM.
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        // P2-concurrency-locks-1 (R1-465/R3-1603): deposit sad zakljucava red preko findByIdForUpdate.
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        marginAccountService.deposit(1L, new BigDecimal("2000"));

        assertThat(account.getInitialMargin()).isEqualByComparingTo("12000");
        // BE-STK-07: MM stays at 5000 (fixed pri kreiranju), nije preracunato u 6000.
        assertThat(account.getMaintenanceMargin()).isEqualByComparingTo("5000");

        verify(marginAccountRepository).save(account);

        ArgumentCaptor<MarginTransaction> txCaptor = ArgumentCaptor.forClass(MarginTransaction.class);
        verify(marginTransactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(MarginTransactionType.DEPOSIT);
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("2000");
    }

    @Test
    void deposit_success_unblocksBLOCKEDAccount() {
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.BLOCKED);

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        marginAccountService.deposit(1L, new BigDecimal("2000"));

        assertThat(account.getStatus()).isEqualTo(MarginAccountStatus.ACTIVE);
    }

    @Test
    void deposit_doesNotUnblock_whenAvailableMarginStillBelowMM_R1_769() {
        // R1 769: odblok prag je RASPOLOZIVA marza (IM − reservedMargin), ne sirov IM.
        // IM=4000, MM=5000, reserved=3000 → available=1000. Deposit(2000) → IM=6000,
        // available=6000-3000=3000 < MM=5000 → racun OSTAJE BLOCKED, iako sirovi
        // IM (6000) sad prelazi MM (raniji raw-IM check bi ga pogresno odblokirao).
        MarginAccount account = activeMarginAccount(10L, "4000", "5000", MarginAccountStatus.BLOCKED);
        account.setReservedMargin(new BigDecimal("3000"));

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        marginAccountService.deposit(1L, new BigDecimal("2000"));

        assertThat(account.getStatus()).isEqualTo(MarginAccountStatus.BLOCKED);
    }

    @Test
    void deposit_unblocks_whenAvailableMarginReachesMM_R1_769() {
        // R1 769: kad raspoloziva marza dostigne MM, odblokira se.
        // IM=4000, MM=5000, reserved=3000 → available=1000. Deposit(7000) → IM=11000,
        // available=11000-3000=8000 >= MM=5000 → odblokira se.
        MarginAccount account = activeMarginAccount(10L, "4000", "5000", MarginAccountStatus.BLOCKED);
        account.setReservedMargin(new BigDecimal("3000"));

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        marginAccountService.deposit(1L, new BigDecimal("7000"));

        assertThat(account.getStatus()).isEqualTo(MarginAccountStatus.ACTIVE);
    }

    @Test
    void deposit_doesNotChangeStatus_whenAccountIsAlreadyActive() {
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        marginAccountService.deposit(1L, new BigDecimal("2000"));

        assertThat(account.getStatus()).isEqualTo(MarginAccountStatus.ACTIVE);
    }

    @Test
    void deposit_throwsWhenAmountIsNull() {
        assertThatThrownBy(() -> marginAccountService.deposit(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be a positive number.");
    }

    @Test
    void deposit_throwsWhenAmountIsZero() {
        assertThatThrownBy(() -> marginAccountService.deposit(1L, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be a positive number.");
    }

    @Test
    void deposit_throwsWhenAmountIsNegative() {
        assertThatThrownBy(() -> marginAccountService.deposit(1L, new BigDecimal("-100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be a positive number.");
    }

    @Test
    void deposit_throwsWhenCallerIsNotClient() {
        when(userResolver.resolveCurrent()).thenReturn(employee(20L));

        assertThatThrownBy(() -> marginAccountService.deposit(1L, new BigDecimal("100")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only clients can manage margin accounts.");
    }

    @Test
    void deposit_throwsWhenMarginAccountNotFound() {
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> marginAccountService.deposit(99L, new BigDecimal("100")))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void deposit_throwsWhenCallerIsNotOwner() {
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

        when(userResolver.resolveCurrent()).thenReturn(client(20L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> marginAccountService.deposit(1L, new BigDecimal("100")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("can deposit funds");
    }

    @Test
    void deposit_usesPessimisticLock_notPlainFindById() {
        // P2-concurrency-locks-1 (R1-465/R3-1603): deposit MORA da koristi findByIdForUpdate
        // (paritet sa withdraw P1-8). Bez pessimistic lock-a, @Version bi pukao 500 na
        // konkurentnom deposit/withdraw/fill umesto da serijalizuje. Ovaj test cementira
        // da deposit zakljucava red (i NE koristi plain findById).
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        marginAccountService.deposit(1L, new BigDecimal("2000"));

        verify(marginAccountRepository).findByIdForUpdate(1L);
        verify(marginAccountRepository, never()).findById(1L);
    }

    // ── withdraw() tests ───────────────────────────────────────────────────────

    @Test
    void withdraw_success_decreasesInitialMarginAndPreservesMaintenanceMargin() {
        // BE-STK-07 (25.05.2026): MM je fiksiran pri kreiranju; withdraw NE preracunava MM.
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        // P1-8: withdraw sad zakljucava red preko findByIdForUpdate.
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        marginAccountService.withdraw(1L, new BigDecimal("2000"));

        assertThat(account.getInitialMargin()).isEqualByComparingTo("8000");
        // BE-STK-07: MM stays at 5000 (fixed pri kreiranju), nije preracunato u 4000.
        assertThat(account.getMaintenanceMargin()).isEqualByComparingTo("5000");

        verify(marginAccountRepository).save(account);

        ArgumentCaptor<MarginTransaction> txCaptor = ArgumentCaptor.forClass(MarginTransaction.class);
        verify(marginTransactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(MarginTransactionType.WITHDRAWAL);
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("2000");
    }

    @Test
    void withdraw_throwsWhenAmountIsNull() {
        assertThatThrownBy(() -> marginAccountService.withdraw(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be a positive number.");
    }

    @Test
    void withdraw_throwsWhenAmountIsZero() {
        assertThatThrownBy(() -> marginAccountService.withdraw(1L, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be a positive number.");
    }

    @Test
    void withdraw_throwsWhenAmountIsNegative() {
        assertThatThrownBy(() -> marginAccountService.withdraw(1L, new BigDecimal("-100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be a positive number.");
    }

    @Test
    void withdraw_throwsWhenCallerIsNotClient() {
        when(userResolver.resolveCurrent()).thenReturn(employee(20L));

        assertThatThrownBy(() -> marginAccountService.withdraw(1L, new BigDecimal("100")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only clients can manage margin accounts.");
    }

    @Test
    void withdraw_throwsWhenMarginAccountNotFound() {
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> marginAccountService.withdraw(99L, new BigDecimal("100")))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void withdraw_throwsWhenCallerIsNotOwner() {
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

        when(userResolver.resolveCurrent()).thenReturn(client(20L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> marginAccountService.withdraw(1L, new BigDecimal("100")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("can withdraw funds");
    }

    @Test
    void withdraw_throwsWhenAccountIsNotActive() {
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.BLOCKED);

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> marginAccountService.withdraw(1L, new BigDecimal("100")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not active");
    }

    @Test
    void withdraw_throwsWhenWithdrawalWouldDropBelowMaintenanceMargin() {
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        // 10000 - 6000 = 4000 < 5000 (maintenanceMargin)
        assertThatThrownBy(() -> marginAccountService.withdraw(1L, new BigDecimal("6000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Funds in the account cannot be below");
    }

    // ── P1-8: withdraw mora postovati reservedMargin (in-flight margin BUY hold) ──

    @Test
    void withdraw_throwsWhenReservedMarginWouldPushAvailableBelowMaintenance() {
        // P1-8: IM=10000, reserved=8000, MM=1000.
        // availableInitialMargin = 10000 - 8000 = 2000.
        // withdraw(9000): sirova IM bi prosla (10000-9000=1000 >= 1000), ali
        // raspoloziva (2000-9000=-7000) je daleko ispod MM → mora da padne.
        MarginAccount account = activeMarginAccount(10L, "10000", "1000", MarginAccountStatus.ACTIVE);
        account.setReservedMargin(new BigDecimal("8000"));

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> marginAccountService.withdraw(1L, new BigDecimal("9000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Funds in the account cannot be below");

        // Sredstva ostaju netaknuta — withdraw je odbijen.
        assertThat(account.getInitialMargin()).isEqualByComparingTo("10000");
        verify(marginAccountRepository, never()).save(account);
    }

    @Test
    void withdraw_succeedsWhenNothingReserved_regressionControlCase() {
        // P1-8 regresija: kad reserved=0, ponasanje je identicno staroj logici.
        // IM=10000, reserved=0, MM=1000. withdraw(9000): available=10000,
        // 10000-9000=1000 >= 1000 → prolazi (granicni slucaj).
        MarginAccount account = activeMarginAccount(10L, "10000", "1000", MarginAccountStatus.ACTIVE);
        account.setReservedMargin(BigDecimal.ZERO);

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

        marginAccountService.withdraw(1L, new BigDecimal("9000"));

        assertThat(account.getInitialMargin()).isEqualByComparingTo("1000");
        verify(marginAccountRepository).save(account);
    }

    // ── checkMaintenanceMargin() tests ─────────────────────────────────────────

    @Test
    void checkMaintenanceMargin_doesNothingWhenNoAccountsNeedBlocking() {
        // BE-STK-04 (25.05): 2-step JPA pattern (H2 test compat). Step 1 vraca prazan
        // list → service eksplicitno NE poziva bulkUpdateStatus i NE objavljuje events.
        when(marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE))
                .thenReturn(List.of());

        marginAccountService.checkMaintenanceMargin();

        verify(marginAccountRepository).findEligibleForBlock(MarginAccountStatus.ACTIVE);
        verify(marginAccountRepository, never()).bulkUpdateStatus(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void checkMaintenanceMargin_blocksAccountsAndPublishesEvent_withEmailResolvedViaBankaCore() {
        // BE-STK-04: Step 1 vraca id-eve eligible-za-blokadu. Step 2 ih flipa na BLOCKED.
        // Posle: service `findAllById(ids)` da popuni detalje + publishuje event.
        MarginAccount blockedAccount = MarginAccount.builder()
                .id(1L)
                .userId(100L)
                .maintenanceMargin(new BigDecimal("5000"))
                .initialMargin(new BigDecimal("4000"))
                .build();

        when(marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE))
                .thenReturn(List.of(1L));
        when(marginAccountRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(blockedAccount));
        when(bankaCoreClient.getUserById("CLIENT", 100L)).thenReturn(
                new InternalUserDto(100L, "CLIENT", "owner@test.com", "Owner", "Test", true, null));

        marginAccountService.checkMaintenanceMargin();

        verify(marginAccountRepository).bulkUpdateStatus(List.of(1L), MarginAccountStatus.BLOCKED);

        ArgumentCaptor<MarginAccountBlockedEvent> eventCaptor =
                ArgumentCaptor.forClass(MarginAccountBlockedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        MarginAccountBlockedEvent event = eventCaptor.getValue();
        assertThat(event.getEmail()).isEqualTo("owner@test.com");
        assertThat(event.getMaintenanceMargin()).isEqualTo("5000");
        assertThat(event.getInitialMargin()).isEqualTo("4000");
        assertThat(event.getDeficit()).isEqualTo("1000"); // 5000 - 4000
    }

    @Test
    void checkMaintenanceMargin_publishesEventWithNullEmail_whenBankaCoreLookupFails() {
        MarginAccount blockedAccount = MarginAccount.builder()
                .id(1L)
                .userId(100L)
                .maintenanceMargin(new BigDecimal("5000"))
                .initialMargin(new BigDecimal("4000"))
                .build();

        when(marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE))
                .thenReturn(List.of(1L));
        when(marginAccountRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(blockedAccount));
        when(bankaCoreClient.getUserById("CLIENT", 100L))
                .thenThrow(new BankaCoreClientException(404, "client not found"));

        marginAccountService.checkMaintenanceMargin();

        ArgumentCaptor<MarginAccountBlockedEvent> eventCaptor =
                ArgumentCaptor.forClass(MarginAccountBlockedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEmail()).isNull();
    }

    @Test
    void checkMaintenanceMargin_publishesOneEventPerBlockedAccount() {
        List<Long> blockedIds = List.of(1L, 2L, 3L);
        List<MarginAccount> blockedAccounts = List.of(
                MarginAccount.builder().id(1L).userId(100L)
                        .maintenanceMargin(new BigDecimal("5000"))
                        .initialMargin(new BigDecimal("4000")).build(),
                MarginAccount.builder().id(2L).userId(200L)
                        .maintenanceMargin(new BigDecimal("6000"))
                        .initialMargin(new BigDecimal("3000")).build(),
                MarginAccount.builder().id(3L).userId(300L)
                        .maintenanceMargin(new BigDecimal("7000"))
                        .initialMargin(new BigDecimal("2000")).build()
        );

        when(marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE))
                .thenReturn(blockedIds);
        when(marginAccountRepository.findAllById(blockedIds)).thenReturn(blockedAccounts);
        when(bankaCoreClient.getUserById(eq("CLIENT"), any())).thenReturn(
                new InternalUserDto(100L, "CLIENT", "x@test.com", "X", "Y", true, null));

        marginAccountService.checkMaintenanceMargin();

        verify(marginAccountRepository).bulkUpdateStatus(blockedIds, MarginAccountStatus.BLOCKED);
        verify(eventPublisher, times(3)).publishEvent(any(MarginAccountBlockedEvent.class));
    }

    @Test
    void checkMaintenanceMargin_batchResolvesEmailPerDistinctUser_R5_1894() {
        // P2-perf-nplus1-1 (R5 1894): dva blokirana racuna ISTOG vlasnika (userId=100)
        // → getUserById se zove TACNO JEDNOM (batch email resolve po distinct userId),
        // ne dvaput. Stiti od N+1 mreznog lookup-a u margin-call cron-u.
        List<Long> blockedIds = List.of(1L, 2L);
        List<MarginAccount> blockedAccounts = List.of(
                MarginAccount.builder().id(1L).userId(100L)
                        .maintenanceMargin(new BigDecimal("5000"))
                        .initialMargin(new BigDecimal("4000")).build(),
                MarginAccount.builder().id(2L).userId(100L)
                        .maintenanceMargin(new BigDecimal("6000"))
                        .initialMargin(new BigDecimal("3000")).build()
        );

        when(marginAccountRepository.findEligibleForBlock(MarginAccountStatus.ACTIVE))
                .thenReturn(blockedIds);
        when(marginAccountRepository.findAllById(blockedIds)).thenReturn(blockedAccounts);
        when(bankaCoreClient.getUserById("CLIENT", 100L)).thenReturn(
                new InternalUserDto(100L, "CLIENT", "shared@test.com", "X", "Y", true, null));

        marginAccountService.checkMaintenanceMargin();

        // Email lookup deduplikovan na 1 poziv (oba racuna isti vlasnik).
        verify(bankaCoreClient, times(1)).getUserById("CLIENT", 100L);
        // Ali se i dalje publikuje po jedan event po racunu.
        verify(eventPublisher, times(2)).publishEvent(any(MarginAccountBlockedEvent.class));
    }

    // ── getTransactions() tests ────────────────────────────────────────────────

    @Test
    void getTransactions_success_returnsTransactionsSortedNewestFirst() {
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

        MarginTransaction tx1 = MarginTransaction.builder()
                .id(1L)
                .marginAccount(account)
                .type(MarginTransactionType.DEPOSIT)
                .amount(new BigDecimal("1000"))
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();
        MarginTransaction tx2 = MarginTransaction.builder()
                .id(2L)
                .marginAccount(account)
                .type(MarginTransactionType.WITHDRAWAL)
                .amount(new BigDecimal("500"))
                .createdAt(LocalDateTime.now())
                .build();

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(marginTransactionRepository.findByMarginAccountIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(tx2, tx1));

        var result = marginAccountService.getTransactions(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getType()).isEqualTo("WITHDRAWAL");
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("500");
    }

    @Test
    void getTransactions_success_returnsEmptyListWhenNoTransactions() {
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(marginTransactionRepository.findByMarginAccountIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of());

        var result = marginAccountService.getTransactions(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void getTransactions_throwsWhenCallerIsNotClient() {
        when(userResolver.resolveCurrent()).thenReturn(employee(20L));

        assertThatThrownBy(() -> marginAccountService.getTransactions(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only clients can manage margin accounts.");
    }

    @Test
    void getTransactions_throwsWhenMarginAccountNotFound() {
        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> marginAccountService.getTransactions(99L))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getTransactions_throwsWhenCallerIsNotOwner() {
        MarginAccount account = activeMarginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

        when(userResolver.resolveCurrent()).thenReturn(client(20L));
        when(marginAccountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> marginAccountService.getTransactions(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("can access margin account transactions");
    }

    @Test
    void getTransactions_companyMarginAccount_throwsForbiddenNotNpe() {
        // P1-margin-1 (R1 203): CompanyMarginAccount ima userId==null. Ranije je
        // {@code marginAccount.getUserId().equals(clientId)} bacao NPE (→ 500).
        // Sada null-safe {@code clientId.equals(userId==null)} → false →
        // IllegalStateException (403), ne NPE.
        rs.raf.trading.margin.model.CompanyMarginAccount company =
                rs.raf.trading.margin.model.CompanyMarginAccount.builder()
                        .id(7L)
                        .accountId(50L)
                        .accountNumber("222000112345678911")
                        .companyId(900L)
                        .currency("RSD")
                        .initialMargin(new BigDecimal("10000"))
                        .loanValue(BigDecimal.ZERO)
                        .maintenanceMargin(new BigDecimal("5000"))
                        .bankParticipation(new BigDecimal("0.50"))
                        .status(MarginAccountStatus.ACTIVE)
                        .build();

        when(userResolver.resolveCurrent()).thenReturn(client(10L));
        when(marginAccountRepository.findById(7L)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> marginAccountService.getTransactions(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("can access margin account transactions");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private UserContext client(Long id) {
        return new UserContext(id, "CLIENT");
    }

    private UserContext employee(Long id) {
        return new UserContext(id, "EMPLOYEE");
    }

    private InternalAccountDto bankAccountOwnedBy(Long accountId, Long ownerClientId, String status,
                                                  String accountNumber, String available, String balance) {
        return new InternalAccountDto(accountId, accountNumber, "Client User",
                new BigDecimal(balance), new BigDecimal(available),
                BigDecimal.ZERO, "RSD", status, ownerClientId, null, "CHECKING");
    }

    private MarginAccount activeMarginAccount(Long userId, String initialMargin,
                                              String maintenanceMargin, MarginAccountStatus status) {
        return MarginAccount.builder()
                .id(1L)
                .accountId(50L)
                .accountNumber("222000112345678911")
                .userId(userId)
                .initialMargin(new BigDecimal(initialMargin))
                .maintenanceMargin(new BigDecimal(maintenanceMargin))
                .bankParticipation(new BigDecimal("0.50"))
                .status(status)
                .build();
    }
}
