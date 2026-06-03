package rs.raf.trading.margin.service;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import rs.raf.banka2.contracts.internal.DebitFundsRequest;
import rs.raf.banka2.contracts.internal.InternalAccountDto;
import rs.raf.trading.client.BankaCoreClient;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.margin.dto.CreateMarginAccountDto;
import rs.raf.trading.margin.dto.MarginAccountDto;
import rs.raf.trading.margin.dto.MarginTransactionDto;
import rs.raf.trading.margin.model.MarginAccount;
import rs.raf.trading.margin.model.MarginAccountStatus;
import rs.raf.trading.margin.model.MarginTransaction;
import rs.raf.trading.margin.model.MarginTransactionType;
import rs.raf.trading.margin.repository.MarginAccountRepository;
import rs.raf.trading.margin.repository.MarginTransactionRepository;
import rs.raf.trading.security.TradingUserResolver;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Extended testovi {@link MarginAccountService} — pokrivaju preostale grane
 * koje {@link MarginAccountServiceTest} ne dotice.
 *
 * <p>NAPOMENA (copy-first ekstrakcija, faza 2d-D): identitet razresava
 * {@code TradingUserResolver}, bazni racun banka-core. Monolitni test
 * {@code handlesNullBalanceOnAccount} (koji je proveravao
 * {@code if (account.getBalance() != null)} granu pri direktnoj mutaciji
 * {@code Account.balance}) je IZBACEN — ta grana vise ne postoji, jer debit
 * baznog racuna sad radi banka-core preko {@code debitFunds}, a ne servis.
 */
@ExtendWith(MockitoExtension.class)
class MarginAccountServiceExtendedTest {

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

    private MarginAccountService service;

    @BeforeEach
    void setUp() {
        service = new MarginAccountService(
                marginAccountRepository,
                marginTransactionRepository,
                bankaCoreClient,
                userResolver,
                eventPublisher
        );
    }

    // ── createForUser — missing branch coverage ───────────────────────────────

    @Nested
    @DisplayName("createForUser - DTO validation branches")
    class CreateForUserDtoBranches {

        @Test
        @DisplayName("throws when dto is null")
        void throwsWhenDtoIsNull() {
            when(userResolver.resolveCurrent()).thenReturn(client(10L));

            assertThatThrownBy(() -> service.createForUser(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Account id and initial deposit are required.");
        }

        @Test
        @DisplayName("throws when dto.accountId is null")
        void throwsWhenAccountIdIsNull() {
            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            CreateMarginAccountDto dto = new CreateMarginAccountDto(null, new BigDecimal("100"));

            assertThatThrownBy(() -> service.createForUser(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Account id and initial deposit are required.");
        }

        @Test
        @DisplayName("throws when dto.initialDeposit is null and IM/MM/BP not provided")
        void throwsWhenInitialDepositIsNull() {
            // BE-STK-07: u legacy putanji (kad IM/MM/BP nisu zadati eksplicitno),
            // initialDeposit mora biti > 0; null je tretiran kao "ne moze biti manje od nule".
            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, null);

            assertThatThrownBy(() -> service.createForUser(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Initial deposit must be greater than zero.");
        }

        @Test
        @DisplayName("throws when initial deposit is negative")
        void throwsWhenDepositNegative() {
            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("-500"));

            assertThatThrownBy(() -> service.createForUser(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Initial deposit must be greater than zero.");
        }

        @Test
        @DisplayName("throws when base account has null owner client id")
        void throwsWhenAccountOwnerClientIdIsNull() {
            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            // bankin racun (ownerClientId == null) — klijent ga ne moze koristiti.
            when(bankaCoreClient.getAccount(1L)).thenReturn(
                    new InternalAccountDto(1L, "333000000000000001", "Banka 2025",
                            new BigDecimal("10000"), new BigDecimal("10000"),
                            BigDecimal.ZERO, "RSD", "ACTIVE", null, null, "BANK_TRADING"));

            CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("100"));

            assertThatThrownBy(() -> service.createForUser(dto))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("You are not allowed to create a margin account for this base account.");
        }

        @Test
        @DisplayName("handles null availableBalance as zero — insufficient for any deposit")
        void handlesNullAvailableBalance() {
            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(bankaCoreClient.getAccount(1L)).thenReturn(
                    new InternalAccountDto(1L, "222000112345678911", "Client User",
                            new BigDecimal("10000"), null,
                            BigDecimal.ZERO, "RSD", "ACTIVE", 10L, null, "CHECKING"));
            when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of());

            CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("100"));

            assertThatThrownBy(() -> service.createForUser(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Insufficient available balance for initial margin deposit.");

            verify(bankaCoreClient, never()).debitFunds(any(), any());
        }

        @Test
        @DisplayName("success path debits base account via banka-core and persists margin account")
        void successDebitsViaBankaCore() {
            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(bankaCoreClient.getAccount(1L)).thenReturn(
                    new InternalAccountDto(1L, "222000112345678911", "Client User",
                            new BigDecimal("10000"), new BigDecimal("10000"),
                            BigDecimal.ZERO, "RSD", "ACTIVE", 10L, null, "CHECKING"));
            when(marginAccountRepository.findByAccountId(1L)).thenReturn(List.of());
            when(marginAccountRepository.save(any(MarginAccount.class))).thenAnswer(inv -> {
                MarginAccount ma = inv.getArgument(0);
                ma.setId(1L);
                ma.setCreatedAt(LocalDateTime.now());
                return ma;
            });

            CreateMarginAccountDto dto = new CreateMarginAccountDto(1L, new BigDecimal("5000"));

            MarginAccountDto result = service.createForUser(dto);

            assertThat(result).isNotNull();
            assertThat(result.getAccountId()).isEqualTo(1L);
            assertThat(result.getAccountNumber()).isEqualTo("222000112345678911");
            // monolit je menjao Account.balance/availableBalance; sad to radi banka-core.
            verify(bankaCoreClient).debitFunds(any(), any(DebitFundsRequest.class));
        }
    }

    // ── getMyMarginAccounts — extra branches ──────────────────────────────────

    @Nested
    @DisplayName("getMyMarginAccounts - extra branches")
    class GetMyAccountsBranches {

        @Test
        @DisplayName("returns empty list when client has no margin accounts")
        void returnsEmptyList() {
            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findByUserId(10L)).thenReturn(Collections.emptyList());

            List<MarginAccountDto> result = service.getMyMarginAccounts();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns multiple margin accounts for same client")
        void returnsMultipleAccounts() {
            MarginAccount ma1 = MarginAccount.builder()
                    .id(1L).accountId(1L).accountNumber("111").userId(10L)
                    .initialMargin(new BigDecimal("10000")).loanValue(new BigDecimal("5000"))
                    .maintenanceMargin(new BigDecimal("5000")).bankParticipation(new BigDecimal("0.50"))
                    .status(MarginAccountStatus.ACTIVE).build();

            MarginAccount ma2 = MarginAccount.builder()
                    .id(2L).accountId(2L).accountNumber("222").userId(10L)
                    .initialMargin(new BigDecimal("20000")).loanValue(new BigDecimal("10000"))
                    .maintenanceMargin(new BigDecimal("10000")).bankParticipation(new BigDecimal("0.50"))
                    .status(MarginAccountStatus.BLOCKED).build();

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findByUserId(10L)).thenReturn(List.of(ma1, ma2));

            List<MarginAccountDto> result = service.getMyMarginAccounts();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getAccountNumber()).isEqualTo("111");
            assertThat(result.get(1).getAccountNumber()).isEqualTo("222");
            assertThat(result.get(1).getStatus()).isEqualTo("BLOCKED");
        }
    }

    // ── deposit — additional edge cases ───────────────────────────────────────

    @Nested
    @DisplayName("deposit - edge cases")
    class DepositEdgeCases {

        @Test
        @DisplayName("deposit of exactly 1 (boundary) succeeds")
        void depositExactlyOne() {
            MarginAccount account = marginAccount(10L, "5000", "2500", MarginAccountStatus.ACTIVE);

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            // P2-concurrency-locks-1 (R1-465/R3-1603): deposit zakljucava red preko findByIdForUpdate.
            when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

            service.deposit(1L, new BigDecimal("1"));

            assertThat(account.getInitialMargin()).isEqualByComparingTo("5001");
            verify(marginAccountRepository).save(account);
            verify(marginTransactionRepository).save(any(MarginTransaction.class));
        }

        @Test
        @DisplayName("large deposit on blocked account reactivates it")
        void largeDepositReactivatesBlocked() {
            // BE-STK-07: MM se NE preracunava na deposit (fixed pri kreiranju).
            // Test verifikuje da deposit (a) inkrementira IM, (b) odblokirava racun
            // kad IM predje MM, (c) NE menja MM.
            MarginAccount account = marginAccount(10L, "1000", "2000", MarginAccountStatus.BLOCKED);

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

            service.deposit(1L, new BigDecimal("50000"));

            assertThat(account.getStatus()).isEqualTo(MarginAccountStatus.ACTIVE);
            assertThat(account.getInitialMargin()).isEqualByComparingTo("51000");
            assertThat(account.getMaintenanceMargin()).isEqualByComparingTo("2000"); // BE-STK-07: nepromenjeno
        }

        @Test
        @DisplayName("deposit transaction description contains correct balance info")
        void depositTransactionDescription() {
            MarginAccount account = marginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

            service.deposit(1L, new BigDecimal("3000"));

            var txCaptor = org.mockito.ArgumentCaptor.forClass(MarginTransaction.class);
            verify(marginTransactionRepository).save(txCaptor.capture());

            String desc = txCaptor.getValue().getDescription();
            assertThat(desc).contains("3000");
            assertThat(desc).contains("13000"); // 10000 + 3000
        }
    }

    // ── withdraw — additional edge cases ──────────────────────────────────────

    @Nested
    @DisplayName("withdraw - edge cases")
    class WithdrawEdgeCases {

        @Test
        @DisplayName("withdraw exactly to maintenance margin boundary succeeds")
        void withdrawExactlyToMaintenance() {
            // BE-STK-07: MM se NE preracunava na withdraw (fixed pri kreiranju).
            // initialMargin=10000, maintenanceMargin=5000 => max withdraw = 10000-5000 = 5000
            MarginAccount account = marginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            // P1-8: withdraw sad zakljucava red preko findByIdForUpdate.
            when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

            service.withdraw(1L, new BigDecimal("5000"));

            assertThat(account.getInitialMargin()).isEqualByComparingTo("5000");
            // BE-STK-07: MM ostaje 5000 (nepromenjeno).
            assertThat(account.getMaintenanceMargin()).isEqualByComparingTo("5000");
            verify(marginAccountRepository).save(account);
        }

        @Test
        @DisplayName("withdraw 1 penny over maintenance boundary fails")
        void withdrawJustOverMaintenanceFails() {
            MarginAccount account = marginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

            // 10000 - 5001 = 4999 < 5000 (maintenance) => should fail
            assertThatThrownBy(() -> service.withdraw(1L, new BigDecimal("5001")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Funds in the account cannot be below");

            verify(marginAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("withdraw transaction description contains correct amount and balance")
        void withdrawTransactionDescription() {
            MarginAccount account = marginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

            service.withdraw(1L, new BigDecimal("2000"));

            var txCaptor = org.mockito.ArgumentCaptor.forClass(MarginTransaction.class);
            verify(marginTransactionRepository).save(txCaptor.capture());

            String desc = txCaptor.getValue().getDescription();
            assertThat(desc).contains("2000");
            assertThat(desc).contains("8000"); // 10000 - 2000
            assertThat(txCaptor.getValue().getType()).isEqualTo(MarginTransactionType.WITHDRAWAL);
        }

        @Test
        @DisplayName("withdraw from BLOCKED account throws even with small amount")
        void withdrawFromBlockedAccountFails() {
            MarginAccount account = marginAccount(10L, "10000", "5000", MarginAccountStatus.BLOCKED);

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> service.withdraw(1L, new BigDecimal("1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not active");
        }
    }

    // ── getTransactions — extra branches ──────────────────────────────────────

    @Nested
    @DisplayName("getTransactions - extra branches")
    class GetTransactionsExtended {

        @Test
        @DisplayName("returns transactions with all DTO fields mapped correctly")
        void mapsAllFieldsCorrectly() {
            MarginAccount account = marginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

            LocalDateTime now = LocalDateTime.of(2026, 4, 1, 12, 0);
            MarginTransaction tx = MarginTransaction.builder()
                    .id(42L)
                    .marginAccount(account)
                    .type(MarginTransactionType.DEPOSIT)
                    .amount(new BigDecimal("1500"))
                    .description("Test deposit")
                    .createdAt(now)
                    .build();

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findById(1L)).thenReturn(Optional.of(account));
            when(marginTransactionRepository.findByMarginAccountIdOrderByCreatedAtDesc(1L))
                    .thenReturn(List.of(tx));

            List<MarginTransactionDto> result = service.getTransactions(1L);

            assertThat(result).hasSize(1);
            MarginTransactionDto dto = result.get(0);
            assertThat(dto.getId()).isEqualTo(42L);
            assertThat(dto.getMarginAccountId()).isEqualTo(1L);
            assertThat(dto.getType()).isEqualTo("DEPOSIT");
            assertThat(dto.getAmount()).isEqualByComparingTo("1500");
            assertThat(dto.getDescription()).isEqualTo("Test deposit");
            assertThat(dto.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("maps transaction with null marginAccount gracefully")
        void handlesNullMarginAccountInTransaction() {
            MarginAccount account = marginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

            MarginTransaction tx = MarginTransaction.builder()
                    .id(1L)
                    .marginAccount(null) // edge: null margin account reference
                    .type(MarginTransactionType.WITHDRAWAL)
                    .amount(new BigDecimal("100"))
                    .build();

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findById(1L)).thenReturn(Optional.of(account));
            when(marginTransactionRepository.findByMarginAccountIdOrderByCreatedAtDesc(1L))
                    .thenReturn(List.of(tx));

            List<MarginTransactionDto> result = service.getTransactions(1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getMarginAccountId()).isNull();
        }

        @Test
        @DisplayName("maps transaction with null type gracefully")
        void handlesNullTypeInTransaction() {
            MarginAccount account = marginAccount(10L, "10000", "5000", MarginAccountStatus.ACTIVE);

            MarginTransaction tx = MarginTransaction.builder()
                    .id(1L)
                    .marginAccount(account)
                    .type(null) // edge: null type
                    .amount(new BigDecimal("100"))
                    .build();

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findById(1L)).thenReturn(Optional.of(account));
            when(marginTransactionRepository.findByMarginAccountIdOrderByCreatedAtDesc(1L))
                    .thenReturn(List.of(tx));

            List<MarginTransactionDto> result = service.getTransactions(1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getType()).isNull();
        }
    }

    // ── toDto (MarginAccount) — null branches ─────────────────────────────────

    @Nested
    @DisplayName("toDto - null-safe branches")
    class ToDtoNullBranches {

        @Test
        @DisplayName("toDto handles margin account with null accountId/accountNumber/status")
        void toDtoNullSoftFields() {
            // posle FK→soft-id: accountId/accountNumber su obican Long/String —
            // racun bez njih ima ih kao null; status moze biti null.
            MarginAccount ma = MarginAccount.builder()
                    .id(1L)
                    .accountId(null)
                    .accountNumber(null)
                    .userId(10L)
                    .initialMargin(new BigDecimal("10000"))
                    .loanValue(new BigDecimal("5000"))
                    .maintenanceMargin(new BigDecimal("5000"))
                    .bankParticipation(new BigDecimal("0.50"))
                    .status(null)
                    .build();

            when(userResolver.resolveCurrent()).thenReturn(client(10L));
            when(marginAccountRepository.findByUserId(10L)).thenReturn(List.of(ma));

            List<MarginAccountDto> result = service.getMyMarginAccounts();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getAccountId()).isNull();
            assertThat(result.get(0).getAccountNumber()).isNull();
            assertThat(result.get(0).getStatus()).isNull();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserContext client(Long id) {
        return new UserContext(id, "CLIENT");
    }

    private MarginAccount marginAccount(Long userId, String initialMargin,
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
