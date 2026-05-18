package rs.raf.banka2_bek.loan.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.loan.model.Loan;
import rs.raf.banka2_bek.loan.model.LoanInstallment;
import rs.raf.banka2_bek.loan.model.LoanStatus;
import rs.raf.banka2_bek.loan.repository.LoanInstallmentRepository;
import rs.raf.banka2_bek.loan.repository.LoanRepository;
import rs.raf.banka2_bek.notification.NotificationPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
public class LoanInstallmentScheduler {

    private final LoanInstallmentRepository installmentRepository;
    private final LoanRepository loanRepository;
    private final AccountRepository accountRepository;
    private final NotificationPublisher notificationPublisher;
    private final String bankRegistrationNumber;

    public LoanInstallmentScheduler(LoanInstallmentRepository installmentRepository,
                                     LoanRepository loanRepository,
                                     AccountRepository accountRepository,
                                     NotificationPublisher notificationPublisher,
                                     @Value("${bank.registration-number}") String bankRegistrationNumber) {
        this.installmentRepository = installmentRepository;
        this.loanRepository = loanRepository;
        this.accountRepository = accountRepository;
        this.notificationPublisher = notificationPublisher;
        this.bankRegistrationNumber = bankRegistrationNumber;
    }

    /**
     * Runs daily at 2:00 AM - processes all unpaid installments due today.
     * Per spec (Celina 2 - Automatsko skidanje rata):
     * 1. Checks all loans with installments due today
     * 2. Attempts to deduct from client's account
     * 3. If insufficient funds, marks as late and retries after 72h
     * 4. On success, marks installment as paid and updates remaining debt
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void processInstallments() {
        LocalDate today = LocalDate.now();
        log.info("Processing loan installments for date: {}", today);

        List<LoanInstallment> dueInstallments = installmentRepository
                .findByExpectedDueDateLessThanEqualAndPaidFalse(today);

        for (LoanInstallment installment : dueInstallments) {
            processInstallment(installment, today);
        }

        log.info("Processed {} installments", dueInstallments.size());
    }

    private void processInstallment(LoanInstallment installment, LocalDate today) {
        Loan loan = installment.getLoan();
        Account account = accountRepository.findForUpdateById(loan.getAccount().getId())
                .orElse(null);

        if (account == null) {
            log.error("Account not found for loan {}", loan.getLoanNumber());
            return;
        }

        String currencyCode = loan.getCurrency().getCode();

        if (account.getAvailableBalance().compareTo(installment.getAmount()) >= 0) {
            // Deduct from client account
            account.setBalance(account.getBalance().subtract(installment.getAmount()));
            account.setAvailableBalance(account.getAvailableBalance().subtract(installment.getAmount()));
            accountRepository.save(account);

            // Credit to bank account (full installment = principal + interest as profit)
            Account bankAccount = accountRepository.findBankAccountForUpdateByCurrency(bankRegistrationNumber, currencyCode)
                    .orElse(null);
            if (bankAccount != null) {
                bankAccount.setBalance(bankAccount.getBalance().add(installment.getAmount()));
                bankAccount.setAvailableBalance(bankAccount.getAvailableBalance().add(installment.getAmount()));
                accountRepository.save(bankAccount);
            }

            installment.setPaid(true);
            installment.setActualDueDate(today);
            installmentRepository.save(installment);

            // Update remaining debt (only principal portion reduces debt)
            BigDecimal principalPaid = installment.getPrincipalAmount() != null
                    ? installment.getPrincipalAmount() : installment.getAmount();
            loan.setRemainingDebt(loan.getRemainingDebt().subtract(principalPaid));
            if (loan.getRemainingDebt().compareTo(BigDecimal.ZERO) <= 0) {
                loan.setRemainingDebt(BigDecimal.ZERO);
                loan.setStatus(LoanStatus.PAID);
            }
            loanRepository.save(loan);

            log.info("Installment {} paid for loan {} (interest profit: {})",
                    installment.getId(), loan.getLoanNumber(),
                    installment.getInterestAmount() != null ? installment.getInterestAmount() : "N/A");

            try {
                notificationPublisher.sendInstallmentPaidMail(
                        loan.getClient().getEmail(),
                        loan.getLoanNumber(),
                        installment.getAmount(),
                        currencyCode,
                        loan.getRemainingDebt());
            } catch (Exception e) {
                log.warn("Failed to send installment paid notification email", e);
            }
        } else {
            // Insufficient funds - reschedule for 72h later
            LocalDate nextRetryDate = today.plusDays(3);
            installment.setExpectedDueDate(nextRetryDate);
            installmentRepository.save(installment);

            if (loan.getStatus() == LoanStatus.ACTIVE) {
                loan.setStatus(LoanStatus.LATE);
                loanRepository.save(loan);
            }

            log.warn("Insufficient funds for installment {} on loan {}. Rescheduled to {}",
                    installment.getId(), loan.getLoanNumber(), nextRetryDate);

            try {
                notificationPublisher.sendInstallmentFailedMail(
                        loan.getClient().getEmail(),
                        loan.getLoanNumber(),
                        installment.getAmount(),
                        currencyCode,
                        nextRetryDate);
            } catch (Exception e) {
                log.warn("Failed to send installment failed notification email", e);
            }
        }
    }
}
