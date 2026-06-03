package rs.raf.banka2_bek.loan.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.loan.model.Loan;
import rs.raf.banka2_bek.loan.model.LoanInstallment;
import rs.raf.banka2_bek.loan.model.LoanStatus;
import rs.raf.banka2_bek.loan.repository.LoanInstallmentRepository;
import rs.raf.banka2_bek.loan.repository.LoanRepository;
import rs.raf.banka2_bek.notification.NotificationPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BE-PAY-02: Unit testovi za per-installment processor.
 *
 * <p>Pokriva svu pravu logiku koja je ranije bila u
 * {@link LoanInstallmentScheduler#processInstallments()}: payment success,
 * insufficient funds reschedule, null principal fallback, status promene,
 * account/bank account lookup, email failure isolation.</p>
 */
@ExtendWith(MockitoExtension.class)
class InstallmentProcessorTest {

    private static final String BANK_REG_NUMBER = "1234567890";

    @Mock private LoanInstallmentRepository installmentRepository;
    @Mock private LoanRepository loanRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private NotificationPublisher notificationPublisher;
    @Mock private rs.raf.banka2_bek.audit.service.AuditLogService auditLogService;

    private InstallmentProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new InstallmentProcessor(
                installmentRepository, loanRepository, accountRepository,
                notificationPublisher, auditLogService, BANK_REG_NUMBER);
    }

    private Currency buildCurrency() {
        Currency currency = new Currency();
        currency.setId(1L);
        currency.setCode("RSD");
        return currency;
    }

    private Client buildClient() {
        Client client = mock(Client.class);
        lenient().when(client.getEmail()).thenReturn("client@banka.rs");
        return client;
    }

    private Account buildAccount(Long id, BigDecimal balance) {
        Account account = new Account();
        account.setId(id);
        account.setBalance(balance);
        account.setAvailableBalance(balance);
        return account;
    }

    private Loan buildLoan(Long id, Account account, Client client, Currency currency,
                           BigDecimal remainingDebt) {
        Loan loan = new Loan();
        loan.setId(id);
        loan.setLoanNumber("LOAN-" + id);
        loan.setAccount(account);
        loan.setClient(client);
        loan.setCurrency(currency);
        loan.setRemainingDebt(remainingDebt);
        loan.setStatus(LoanStatus.ACTIVE);
        return loan;
    }

    private LoanInstallment buildInstallment(Long id, Loan loan, BigDecimal amount, LocalDate dueDate) {
        LoanInstallment inst = new LoanInstallment();
        inst.setId(id);
        inst.setLoan(loan);
        inst.setAmount(amount);
        inst.setPrincipalAmount(amount.multiply(new BigDecimal("0.80")));
        inst.setInterestAmount(amount.multiply(new BigDecimal("0.20")));
        inst.setExpectedDueDate(dueDate);
        inst.setPaid(false);
        return inst;
    }

    @Nested
    @DisplayName("processOne — sufficient funds path")
    class SufficientFunds {

        @Test
        @DisplayName("pays installment when account has sufficient funds")
        void paysInstallment() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));

            processor.processOne(installment, LocalDate.now());

            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(45000));
            assertThat(account.getAvailableBalance()).isEqualByComparingTo(BigDecimal.valueOf(45000));
            assertThat(installment.getPaid()).isTrue();
            assertThat(installment.getActualDueDate()).isEqualTo(LocalDate.now());
            verify(installmentRepository).save(installment);
            verify(loanRepository).save(loan);
            // R5 1887: naplata rate je sad audit-ovana (LOAN_INSTALLMENT_PAID, target LOAN).
            verify(auditLogService).record(
                    eq(0L), eq("SYSTEM"),
                    eq(rs.raf.banka2_bek.audit.model.AuditActionType.LOAN_INSTALLMENT_PAID),
                    anyString(), eq("LOAN"), eq(1L));
        }

        @Test
        @DisplayName("credits bank account when found")
        void creditsBankAccount() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account clientAccount = buildAccount(1L, BigDecimal.valueOf(50000));
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));
            Loan loan = buildLoan(1L, clientAccount, client, currency, BigDecimal.valueOf(100000));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(clientAccount));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));

            processor.processOne(installment, LocalDate.now());

            assertThat(bankAccount.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1005000));
            assertThat(bankAccount.getAvailableBalance()).isEqualByComparingTo(BigDecimal.valueOf(1005000));
            verify(accountRepository).save(bankAccount);
        }

        @Test
        @DisplayName("sets loan status to PAID when remaining debt reaches zero")
        void setsStatusToPaidWhenDebtCleared() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            BigDecimal installmentAmount = BigDecimal.valueOf(5000);
            BigDecimal principalAmount = installmentAmount.multiply(new BigDecimal("0.80"));
            Loan loan = buildLoan(1L, account, client, currency, principalAmount);
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));
            LoanInstallment installment = buildInstallment(1L, loan, installmentAmount, LocalDate.now());

            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));

            processor.processOne(installment, LocalDate.now());

            assertThat(loan.getStatus()).isEqualTo(LoanStatus.PAID);
            assertThat(loan.getRemainingDebt()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("email failure does not rollback installment processing on successful payment")
        void emailFailureDoesNotRollbackPayment() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));
            doThrow(new RuntimeException("SMTP error"))
                    .when(notificationPublisher).sendInstallmentPaidMail(
                            anyString(), anyString(), any(), anyString(), any());

            processor.processOne(installment, LocalDate.now());

            assertThat(installment.getPaid()).isTrue();
            verify(installmentRepository).save(installment);
        }

        @Test
        @DisplayName("falls back to amount when principalAmount is null")
        void nullPrincipalFallsBackToAmount() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(20000));
            LoanInstallment installment = new LoanInstallment();
            installment.setId(1L);
            installment.setLoan(loan);
            installment.setAmount(BigDecimal.valueOf(5000));
            installment.setPrincipalAmount(null); // triggers fallback
            installment.setInterestAmount(null);  // triggers "N/A" log branch
            installment.setExpectedDueDate(LocalDate.now());
            installment.setPaid(false);
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));

            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));

            processor.processOne(installment, LocalDate.now());

            assertThat(installment.getPaid()).isTrue();
            // remainingDebt reduced by amount (fallback) = 20000 - 5000 = 15000
            assertThat(loan.getRemainingDebt()).isEqualByComparingTo(BigDecimal.valueOf(15000));
        }
    }

    @Nested
    @DisplayName("processOne — insufficient funds path")
    class InsufficientFunds {

        @Test
        @DisplayName("reschedules installment for 72h later when insufficient funds")
        void reschedulesWhenInsufficientFunds() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(1000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

            processor.processOne(installment, LocalDate.now());

            assertThat(installment.getPaid()).isFalse();
            assertThat(installment.getExpectedDueDate()).isEqualTo(LocalDate.now().plusDays(3));
            assertThat(loan.getStatus()).isEqualTo(LoanStatus.LATE);
            verify(installmentRepository).save(installment);
            verify(loanRepository).save(loan);
            // R5 1887: neuspela naplata (nedovoljno sredstava) je sad audit-ovana.
            verify(auditLogService).record(
                    eq(0L), eq("SYSTEM"),
                    eq(rs.raf.banka2_bek.audit.model.AuditActionType.LOAN_INSTALLMENT_FAILED),
                    anyString(), eq("LOAN"), eq(1L));
        }

        @Test
        @DisplayName("does not change loan status to LATE if already LATE")
        void doesNotChangeLateStatusAgain() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(100));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(50000));
            loan.setStatus(LoanStatus.LATE);
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

            processor.processOne(installment, LocalDate.now());

            assertThat(loan.getStatus()).isEqualTo(LoanStatus.LATE);
            verify(loanRepository, never()).save(loan);
        }

        @Test
        @DisplayName("email failure does not rollback installment rescheduling")
        void emailFailureDoesNotRollbackRescheduling() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(100));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            doThrow(new RuntimeException("SMTP error"))
                    .when(notificationPublisher).sendInstallmentFailedMail(
                            anyString(), anyString(), any(), anyString(), any());

            processor.processOne(installment, LocalDate.now());

            assertThat(installment.getExpectedDueDate()).isEqualTo(LocalDate.now().plusDays(3));
            verify(installmentRepository).save(installment);
        }

        @Test
        @DisplayName("R1 346: prvi neuspeh inkrementira failedAttempts ali NE primenjuje penal")
        void firstFailure_incrementsAttempts_noPenaltyYet() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(100));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            loan.setNominalRate(new BigDecimal("10.00"));
            loan.setEffectiveRate(new BigDecimal("11.75"));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());
            // failedAttempts pocinje na 0 (default).

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

            processor.processOne(installment, LocalDate.now());

            assertThat(installment.getFailedAttempts()).isEqualTo(1);
            assertThat(installment.getPenaltyApplied()).isFalse();
            // Rate-ovi nepromenjeni posle prvog neuspeha.
            assertThat(loan.getNominalRate()).isEqualByComparingTo("10.00");
            assertThat(loan.getEffectiveRate()).isEqualByComparingTo("11.75");
        }

        @Test
        @DisplayName("R1 346 (§417): drugi neuspeh primenjuje penal +0.05% na nominalRate i effectiveRate")
        void secondFailure_appliesLatePenalty() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(100));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            loan.setStatus(LoanStatus.LATE); // vec LATE od prvog neuspeha
            loan.setNominalRate(new BigDecimal("10.00"));
            loan.setEffectiveRate(new BigDecimal("11.75"));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());
            installment.setFailedAttempts(1); // jedan neuspeh vec zabelezen

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

            processor.processOne(installment, LocalDate.now());

            assertThat(installment.getFailedAttempts()).isEqualTo(2);
            assertThat(installment.getPenaltyApplied()).isTrue();
            // +0.05% na obe stope.
            assertThat(loan.getNominalRate()).isEqualByComparingTo("10.05");
            assertThat(loan.getEffectiveRate()).isEqualByComparingTo("11.80");
            // Loan je perzistovan (penal primenjen) iako status nije menjan (vec LATE).
            verify(loanRepository).save(loan);
        }

        @Test
        @DisplayName("R1 346: penal je idempotentan po rati — treci neuspeh ne dodaje jos jednom")
        void thirdFailure_doesNotReapplyPenalty() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(100));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            loan.setStatus(LoanStatus.LATE);
            loan.setNominalRate(new BigDecimal("10.05"));
            loan.setEffectiveRate(new BigDecimal("11.80"));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());
            installment.setFailedAttempts(2);
            installment.setPenaltyApplied(true); // penal vec primenjen ranije

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

            processor.processOne(installment, LocalDate.now());

            assertThat(installment.getFailedAttempts()).isEqualTo(3);
            // Penal se NE dodaje ponovo.
            assertThat(loan.getNominalRate()).isEqualByComparingTo("10.05");
            assertThat(loan.getEffectiveRate()).isEqualByComparingTo("11.80");
            // Loan nije ponovo cuvan zbog penala (status vec LATE, penal nije primenjen).
            verify(loanRepository, never()).save(loan);
        }
    }

    @Nested
    @DisplayName("processOne — error paths")
    class ErrorPaths {

        @Test
        @DisplayName("skips installment when account not found")
        void skipsWhenAccountNotFound() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(99L, BigDecimal.valueOf(50000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(accountRepository.findForUpdateById(99L)).thenReturn(Optional.empty());

            processor.processOne(installment, LocalDate.now());

            verify(installmentRepository, never()).save(any());
            verify(loanRepository, never()).save(any());
        }
    }

    // === P0-B2: 3 verifikovana nalaza ===

    @Nested
    @DisplayName("P0-B2 #1: bank-credit noga (one-legged debit / money-destroy)")
    class BankCreditConservation {

        /**
         * Nalaz #1: klijent debitovan za ratu, ali bank-credit noga TIHO preskocena
         * (orElse(null)) kad bankin racun fali -> novac unisten. Posle fix-a baca.
         */
        @Test
        @DisplayName("baca kad bankin racun fali (umesto tihog preskoka jedne noge)")
        void throwsWhenBankAccountMissing() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.empty());

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> processor.processOne(installment, LocalDate.now()))
                    .isInstanceOf(IllegalStateException.class);
        }

        /**
         * Nalaz #1 (conservation): kad bankin racun postoji, suma promena para = 0
         * (klijent -rata, banka +rata). Rata = double-entry.
         */
        @Test
        @DisplayName("conservation: suma promena = 0 kad bankin racun postoji")
        void conservationWhenBankAccountPresent() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            BigDecimal clientStart = BigDecimal.valueOf(50000);
            BigDecimal bankStart = BigDecimal.valueOf(1000000);
            Account clientAccount = buildAccount(1L, clientStart);
            Account bankAccount = buildAccount(2L, bankStart);
            Loan loan = buildLoan(1L, clientAccount, client, currency, BigDecimal.valueOf(100000));
            BigDecimal rata = BigDecimal.valueOf(5000);
            LoanInstallment installment = buildInstallment(1L, loan, rata, LocalDate.now());

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(clientAccount));
            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));

            processor.processOne(installment, LocalDate.now());

            BigDecimal clientDelta = clientAccount.getBalance().subtract(clientStart);
            BigDecimal bankDelta = bankAccount.getBalance().subtract(bankStart);
            assertThat(clientDelta.add(bankDelta)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(clientDelta).isEqualByComparingTo(rata.negate());
            assertThat(bankDelta).isEqualByComparingTo(rata);
        }
    }

    @Nested
    @DisplayName("P1: debt reduced only by charged amount (R3-1596 money-loss)")
    class PrincipalCappedToChargedAmount {

        /**
         * R3-1596: VariableRate je za poslednju ratu mogao postaviti principalAmount = ceo
         * preostali dug dok amount ostaje mesecna rata. InstallmentProcessor je naplacivao
         * amount a umanjivao dug za principalAmount → ako principal > amount, dug pada vise
         * nego sto je naplaceno (banka gubi glavnicu). Guard kapira principalPaid na amount.
         */
        @Test
        @DisplayName("dug se umanjuje za amount, ne za prenaduvani principalAmount")
        void debtReducedByChargedAmountNotInflatedPrincipal() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            account.setCurrency(currency);
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            // Drifted installment: amount=5000 ali principalAmount=80000 (>> amount).
            LoanInstallment installment = new LoanInstallment();
            installment.setId(1L);
            installment.setLoan(loan);
            installment.setAmount(BigDecimal.valueOf(5000));
            installment.setPrincipalAmount(BigDecimal.valueOf(80000)); // prenaduvan
            installment.setInterestAmount(BigDecimal.valueOf(0));
            installment.setExpectedDueDate(LocalDate.now());
            installment.setPaid(false);

            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));

            processor.processOne(installment, LocalDate.now());

            // klijent naplacen 5000 → dug 100000 - 5000 = 95000 (NE 100000 - 80000 = 20000)
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(45000));
            assertThat(loan.getRemainingDebt()).isEqualByComparingTo(BigDecimal.valueOf(95000));
            assertThat(loan.getStatus()).isNotEqualTo(LoanStatus.PAID);
        }
    }

    @Nested
    @DisplayName("P1: cross-currency guard (R4-1736 conservation)")
    class CrossCurrencyGuard {

        /**
         * R4-1736: ako se valuta racuna razlikuje od valute kredita, debit (klijent) i
         * credit (banka) bi bili u razlicitim valutama bez FX → konzervacija pukla.
         * createLoanRequest garantuje jednakost, ali guard fail-loud brani drift.
         */
        @Test
        @DisplayName("baca kad se valuta racuna razlikuje od valute kredita")
        void throwsWhenAccountCurrencyDiffersFromLoanCurrency() {
            Currency loanCurrency = buildCurrency(); // RSD
            Currency accountCurrency = new Currency();
            accountCurrency.setId(2L);
            accountCurrency.setCode("EUR");

            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            account.setCurrency(accountCurrency); // EUR racun
            Loan loan = buildLoan(1L, account, client, loanCurrency, BigDecimal.valueOf(100000)); // RSD kredit
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> processor.processOne(installment, LocalDate.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("se razlikuju");
            // konzervacija: nista nije naplaceno
            assertThat(installment.getPaid()).isFalse();
            verify(accountRepository, never()).findBankAccountForUpdateByCurrency(any(), any());
        }

        /** Ista valuta (najcesci slucaj) prolazi normalno. */
        @Test
        @DisplayName("ista valuta racuna i kredita prolazi normalno")
        void sameCurrencyProceeds() {
            Currency currency = buildCurrency(); // RSD
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            account.setCurrency(currency);
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));

            processor.processOne(installment, LocalDate.now());

            assertThat(installment.getPaid()).isTrue();
        }
    }

    @Nested
    @DisplayName("P0-B2 #2: LATE -> ACTIVE reset (Sc38)")
    class LateReset {

        @Test
        @DisplayName("LATE kredit -> naplata poslednje dospele rate -> ACTIVE")
        void resetsLateToActiveWhenNoMoreDueUnpaid() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            loan.setStatus(LoanStatus.LATE);
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));
            // nema vise dospelih neplacenih rata posle ove
            when(installmentRepository.countByLoanIdAndPaidFalseAndExpectedDueDateLessThanEqual(
                    eq(1L), any())).thenReturn(0L);

            processor.processOne(installment, LocalDate.now());

            assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        }

        @Test
        @DisplayName("LATE kredit ostaje LATE ako jos ima dospelih neplacenih rata")
        void staysLateWhenMoreDueUnpaidRemain() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            loan.setStatus(LoanStatus.LATE);
            LoanInstallment installment = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));
            // jos jedna dospela neplacena rata ostaje
            when(installmentRepository.countByLoanIdAndPaidFalseAndExpectedDueDateLessThanEqual(
                    eq(1L), any())).thenReturn(1L);

            processor.processOne(installment, LocalDate.now());

            assertThat(loan.getStatus()).isEqualTo(LoanStatus.LATE);
        }

        @Test
        @DisplayName("PAID kredit ostaje PAID (LATE reset ne pregazi terminal status)")
        void paidLoanStaysPaid() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));
            BigDecimal installmentAmount = BigDecimal.valueOf(5000);
            BigDecimal principalAmount = installmentAmount.multiply(new BigDecimal("0.80"));
            // remainingDebt = principal portion -> dovodi do PAID
            Loan loan = buildLoan(1L, account, client, currency, principalAmount);
            loan.setStatus(LoanStatus.LATE);
            LoanInstallment installment = buildInstallment(1L, loan, installmentAmount, LocalDate.now());

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(installment));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));

            processor.processOne(installment, LocalDate.now());

            assertThat(loan.getStatus()).isEqualTo(LoanStatus.PAID);
        }
    }

    @Nested
    @DisplayName("P2-concurrency-locks-1 (R3-1580): @Version na LoanInstallment (defense-in-depth)")
    class OptimisticLockVersion {

        /**
         * R3-1580: glavna double-debit zastita je P0-B2 pessimistic re-read + paid-recheck.
         * @Version je dodatni sloj (paritet sa SavingsScheduler-om) — ako bi dva pisanja
         * nekako prosla lock, drugi commit baca OptimisticLockException umesto tihog
         * lost-update-a paid/actualDueDate. Cementiramo prisustvo @Version polja.
         */
        @Test
        @DisplayName("LoanInstallment ima @Version polje (optimisticko zakljucavanje)")
        void loanInstallmentHasVersionField() throws NoSuchFieldException {
            java.lang.reflect.Field versionField = LoanInstallment.class.getDeclaredField("version");
            assertThat(versionField.isAnnotationPresent(jakarta.persistence.Version.class))
                    .as("LoanInstallment.version mora nositi @jakarta.persistence.Version")
                    .isTrue();
            assertThat(versionField.getType()).isEqualTo(Long.class);
        }
    }

    @Nested
    @DisplayName("P0-B2 #3: double-charge guard (paid re-check pod lockom)")
    class DoubleChargeGuard {

        @Test
        @DisplayName("rata vec paid=true -> processOne je no-op (ne naplacuje 2x)")
        void noOpWhenAlreadyPaid() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            BigDecimal clientStart = BigDecimal.valueOf(50000);
            Account account = buildAccount(1L, clientStart);
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            // stale installment iz scheduler liste: paid=false
            LoanInstallment stale = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());
            // re-read pod lockom vidi da je druga replika vec naplatila: paid=true
            LoanInstallment locked = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());
            locked.setPaid(true);

            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(locked));

            processor.processOne(stale, LocalDate.now());

            // klijentski racun netaknut, nikakva naplata
            assertThat(account.getBalance()).isEqualByComparingTo(clientStart);
            verify(accountRepository, never()).findForUpdateById(any());
            verify(accountRepository, never()).save(any());
            verify(loanRepository, never()).save(any());
        }

        @Test
        @DisplayName("re-read pod lockom: naplata koristi sveze stanje rate")
        void usesFreshlyLockedInstallment() {
            Currency currency = buildCurrency();
            Client client = buildClient();
            Account account = buildAccount(1L, BigDecimal.valueOf(50000));
            Account bankAccount = buildAccount(2L, BigDecimal.valueOf(1000000));
            Loan loan = buildLoan(1L, account, client, currency, BigDecimal.valueOf(100000));
            LoanInstallment stale = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());
            LoanInstallment locked = buildInstallment(1L, loan, BigDecimal.valueOf(5000),
                    LocalDate.now());

            when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
            when(installmentRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(locked));
            when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                    .thenReturn(Optional.of(bankAccount));

            processor.processOne(stale, LocalDate.now());

            // locked je naplacen, ne stale
            assertThat(locked.getPaid()).isTrue();
            verify(installmentRepository).save(locked);
            verify(installmentRepository).findByIdForUpdate(1L);
        }
    }
}
