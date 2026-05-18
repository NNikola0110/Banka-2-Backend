package rs.raf.banka2_bek.loan.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage test targeting remaining missed branches in LoanInstallmentScheduler:
 *  - installment with null principalAmount falls back to amount (L99 false branch + L100)
 *  - installment with null interestAmount logs "N/A" (L110 false branch)
 */
@ExtendWith(MockitoExtension.class)
class LoanInstallmentSchedulerCoverageTest {

    private static final String BANK_REG_NUMBER = "1234567890";

    @Mock private LoanInstallmentRepository installmentRepository;
    @Mock private LoanRepository loanRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private NotificationPublisher notificationPublisher;

    private LoanInstallmentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new LoanInstallmentScheduler(
                installmentRepository, loanRepository, accountRepository,
                notificationPublisher, BANK_REG_NUMBER);
    }

    @Test
    @DisplayName("falls back to amount when principalAmount is null, logs N/A when interestAmount is null")
    void nullPrincipalAndInterestAmounts() {
        Currency currency = new Currency();
        currency.setId(1L);
        currency.setCode("RSD");

        Client client = mock(Client.class);
        lenient().when(client.getEmail()).thenReturn("client@banka.rs");

        Account account = new Account();
        account.setId(1L);
        account.setBalance(BigDecimal.valueOf(50000));
        account.setAvailableBalance(BigDecimal.valueOf(50000));

        Loan loan = new Loan();
        loan.setId(1L);
        loan.setLoanNumber("LOAN-NULL");
        loan.setAccount(account);
        loan.setClient(client);
        loan.setCurrency(currency);
        loan.setRemainingDebt(BigDecimal.valueOf(20000));
        loan.setStatus(LoanStatus.ACTIVE);

        LoanInstallment installment = new LoanInstallment();
        installment.setId(1L);
        installment.setLoan(loan);
        installment.setAmount(BigDecimal.valueOf(5000));
        installment.setPrincipalAmount(null); // triggers fallback to amount
        installment.setInterestAmount(null);  // triggers "N/A" log branch
        installment.setExpectedDueDate(LocalDate.now());
        installment.setPaid(false);

        when(installmentRepository.findByExpectedDueDateLessThanEqualAndPaidFalse(any()))
                .thenReturn(List.of(installment));
        when(accountRepository.findForUpdateById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.findBankAccountForUpdateByCurrency(any(), eq("RSD")))
                .thenReturn(Optional.empty());

        scheduler.processInstallments();

        // Paid, and remainingDebt reduced by amount (fallback) = 20000 - 5000 = 15000
        assertThat(installment.getPaid()).isTrue();
        assertThat(loan.getRemainingDebt()).isEqualByComparingTo(BigDecimal.valueOf(15000));
    }
}
