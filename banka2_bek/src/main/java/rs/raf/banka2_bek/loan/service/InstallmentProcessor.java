package rs.raf.banka2_bek.loan.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.service.AuditLogService;
import rs.raf.banka2_bek.loan.model.Loan;
import rs.raf.banka2_bek.loan.model.LoanInstallment;
import rs.raf.banka2_bek.loan.model.LoanStatus;
import rs.raf.banka2_bek.loan.repository.LoanInstallmentRepository;
import rs.raf.banka2_bek.loan.repository.LoanRepository;
import rs.raf.banka2_bek.notification.NotificationPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * BE-PAY-02: Per-installment transaction processor.
 *
 * <p>Razlog izdvajanja: {@link LoanInstallmentScheduler} ranije je imao outer
 * {@code @Transactional} na {@code processInstallments()} — jedna lose
 * installment (npr. DB constraint violation, neocekivani SQL error) je
 * povlacila rollback svih prethodno uspesno obradjenih installments u istom
 * batch-u. To je suprotno SavingsScheduler obrascu i intent-u "process all
 * due installments, isolate failures".</p>
 *
 * <p>Resenje: ovaj bean ima {@link Propagation#REQUIRES_NEW} na
 * {@link #processOne(LoanInstallment, LocalDate)} — svaki installment dobija
 * svoju nezavisnu transakciju. Scheduler iterira + delegira, hvata
 * exception per-installment i nastavlja.</p>
 */
@Slf4j
@Component
public class InstallmentProcessor {

    private final LoanInstallmentRepository installmentRepository;
    private final LoanRepository loanRepository;
    private final AccountRepository accountRepository;
    private final NotificationPublisher notificationPublisher;
    // P2-audit-coverage-1 (R5 1887): audit naplate/neuspeha rate (money-moving lifecycle).
    private final AuditLogService auditLogService;
    private final String bankRegistrationNumber;

    /**
     * R1-660: broj dana za odlaganje rate kod nedovoljno sredstava (spec korak 5:
     * "pokusaj ponovo za 72h"). Eksternalizovano u config
     * ({@code loan.installment.retry-delay-days}); in-code default 3 pa radi i bez
     * Spring konteksta (unit testovi koji koriste eksplicitni konstruktor).
     */
    @Value("${loan.installment.retry-delay-days:3}")
    private int retryDelayDays = 3;

    public InstallmentProcessor(LoanInstallmentRepository installmentRepository,
                                LoanRepository loanRepository,
                                AccountRepository accountRepository,
                                NotificationPublisher notificationPublisher,
                                AuditLogService auditLogService,
                                @Value("${bank.registration-number}") String bankRegistrationNumber) {
        this.installmentRepository = installmentRepository;
        this.loanRepository = loanRepository;
        this.accountRepository = accountRepository;
        this.notificationPublisher = notificationPublisher;
        this.auditLogService = auditLogService;
        this.bankRegistrationNumber = bankRegistrationNumber;
    }

    /**
     * P2-audit-coverage-1 (R5 1887): best-effort audit hook (scheduler thread —
     * nema SecurityContext, aktor = SYSTEM/0). Audit ne sme da obori naplatu rate;
     * pisemo TEK posle uspesnih money-noga (poziva se na kraju paid/failed grane),
     * tako da REQUIRES_NEW audit upis odgovara stvarnom commit-ovanom stanju rate.
     */
    private void recordInstallmentAudit(AuditActionType action, String description, Long loanId) {
        try {
            auditLogService.record(0L, "SYSTEM", action, description, "LOAN", loanId);
        } catch (Exception e) {
            log.warn("Audit log fail (best-effort) action={} target=LOAN/{}: {}",
                    action, loanId, e.getMessage());
        }
    }

    /**
     * BE-PAY-02: Procesira jednu installment u nezavisnoj transakciji.
     * Greska/rollback ne utice na druge installments u batch-u.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOne(LoanInstallment staleInstallment, LocalDate today) {
        // P0-B2 #3 (double-charge guard pod replicas:2): scheduler ucita listu dospelih
        // rata van transakcije; obe replike vide istu (stale) ratu. Re-citamo ratu pod
        // PESSIMISTIC_WRITE i ponovo proveravamo paid — druga replika blokira na lock-u,
        // vidi paid=true i radi no-op (idempotentno). Bez ovoga ista rata se naplati 2x.
        LoanInstallment installment = installmentRepository.findByIdForUpdate(staleInstallment.getId())
                .orElse(staleInstallment);

        if (Boolean.TRUE.equals(installment.getPaid())) {
            log.debug("Installment {} vec naplacena (paid=true) — no-op (idempotentno)",
                    installment.getId());
            return;
        }

        Loan loan = installment.getLoan();
        Account account = accountRepository.findForUpdateById(loan.getAccount().getId())
                .orElse(null);

        if (account == null) {
            log.error("Account not found for loan {}", loan.getLoanNumber());
            return;
        }

        // P1-savings-loans-1 (R4-1736): klijent se debituje na racunu loan.getAccount(),
        // a bankin liability racun se razresava po loan.getCurrency(). createLoanRequest
        // garantuje da su jednaki (valuta kredita == valuta racuna), ALI ako bi se ikad
        // razlikovale, debit i credit bi bili u RAZLICITIM valutama bez FX → konzervacija
        // pukla (banka kreira/gubi novac). Fail-loud (kao earlyRepayment guard) umesto
        // tihog money-break-a. Null-safe: dira samo kad su obe valute prisutne i razlicite.
        if (account.getCurrency() != null && loan.getCurrency() != null
                && account.getCurrency().getCode() != null
                && !account.getCurrency().getCode().equals(loan.getCurrency().getCode())) {
            throw new IllegalStateException(
                    "Valuta racuna (" + account.getCurrency().getCode() + ") i kredita ("
                    + loan.getCurrency().getCode() + ") se razlikuju — naplata rate prekinuta "
                    + "(konzervacija, FX nije podrzan za rate)");
        }

        String currencyCode = loan.getCurrency().getCode();

        if (account.getAvailableBalance().compareTo(installment.getAmount()) >= 0) {
            // Deduct from client account
            account.setBalance(account.getBalance().subtract(installment.getAmount()));
            account.setAvailableBalance(account.getAvailableBalance().subtract(installment.getAmount()));
            accountRepository.save(account);

            // P0-B2 #1 (one-legged debit / money-destroy): kreditiraj bankin liability
            // racun ZA ISTI iznos. orElseThrow (fail-loud, kao P0-B1) umesto tihog
            // orElse(null) — bez ove druge noge klijent je debitovan a novac unisten,
            // a REQUIRES_NEW Tx ne rollback-uje klijentski debit. Rata je double-entry.
            Account bankAccount = accountRepository.findBankAccountForUpdateByCurrency(bankRegistrationNumber, currencyCode)
                    .orElseThrow(() -> new IllegalStateException(
                            "Banka nema racun u valuti " + currencyCode + " — naplata rate prekinuta (konzervacija)"));
            bankAccount.setBalance(bankAccount.getBalance().add(installment.getAmount()));
            bankAccount.setAvailableBalance(bankAccount.getAvailableBalance().add(installment.getAmount()));
            accountRepository.save(bankAccount);

            installment.setPaid(true);
            installment.setActualDueDate(today);
            installmentRepository.save(installment);

            // Update remaining debt (only principal portion reduces debt).
            // P1-savings-loans-1 (R3-1596 money-loss): dug se NE sme umanjiti za vise nego sto
            // je STVARNO naplaceno (installment.getAmount()). Ako je principalAmount > amount
            // (drift: VariableRate poslednja rata ranije = ceo preostali dug dok amount ostaje
            // mesecna rata), klijent plati amount a dug padne za ceo principal → banka gubi
            // glavnicu. Kapiranje na amount garantuje konzervaciju (naplaceno == umanjen dug).
            BigDecimal principalPaid = installment.getPrincipalAmount() != null
                    ? installment.getPrincipalAmount() : installment.getAmount();
            if (principalPaid.compareTo(installment.getAmount()) > 0) {
                log.warn("Installment {} principalAmount {} > naplaceno {} — kapiram na naplaceno "
                        + "(konzervacija, R3-1596)", installment.getId(),
                        principalPaid, installment.getAmount());
                principalPaid = installment.getAmount();
            }
            loan.setRemainingDebt(loan.getRemainingDebt().subtract(principalPaid));
            if (loan.getRemainingDebt().compareTo(BigDecimal.ZERO) <= 0) {
                loan.setRemainingDebt(BigDecimal.ZERO);
                loan.setStatus(LoanStatus.PAID);
            } else if (loan.getStatus() == LoanStatus.LATE) {
                // P0-B2 #2 (Sc38): kredit koji je jednom zakasnio ostajao je LATE zauvek.
                // Posle uspesne naplate, ako vise nema dospelih neplacenih rata, vrati ACTIVE.
                long stillDueUnpaid = installmentRepository
                        .countByLoanIdAndPaidFalseAndExpectedDueDateLessThanEqual(loan.getId(), today);
                if (stillDueUnpaid == 0) {
                    loan.setStatus(LoanStatus.ACTIVE);
                    log.info("Loan {} LATE -> ACTIVE (sve dospele rate naplacene)", loan.getLoanNumber());
                }
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

            // R5 1887: audit naplate rate (money-moving debit) — ranije bez traga.
            recordInstallmentAudit(
                    AuditActionType.LOAN_INSTALLMENT_PAID,
                    "Installment " + installment.getId() + " paid for loan " + loan.getLoanNumber()
                            + " (amount=" + installment.getAmount() + " " + currencyCode
                            + ", remainingDebt=" + loan.getRemainingDebt() + ")",
                    loan.getId());
        } else {
            // Insufficient funds - reschedule for retryDelayDays later (R1-660: default 72h).
            LocalDate nextRetryDate = today.plusDays(retryDelayDays);
            installment.setExpectedDueDate(nextRetryDate);

            // R1 346 (§409-417): prati neuspele pokusaje. Ranije se due-date klizao
            // +3 dana UNEDOGLED bez ikakve eskalacije (rata bi mogla beskonacno da
            // pluta). Spec: "Ako ni tada nema dovoljno sredstava, moze doci do
            // povecanja osnovice kamatne stope (npr. +0.05% za kasnjenje)."
            int attempts = (installment.getFailedAttempts() != null ? installment.getFailedAttempts() : 0) + 1;
            installment.setFailedAttempts(attempts);

            boolean penaltyAppliedNow = false;
            if (attempts >= PENALTY_AFTER_FAILED_ATTEMPTS
                    && !Boolean.TRUE.equals(installment.getPenaltyApplied())) {
                applyLatePenalty(loan);
                installment.setPenaltyApplied(true);
                penaltyAppliedNow = true;
            }

            installmentRepository.save(installment);

            if (loan.getStatus() == LoanStatus.ACTIVE) {
                loan.setStatus(LoanStatus.LATE);
                loanRepository.save(loan);
            } else if (penaltyAppliedNow) {
                // applyLatePenalty je vec promenio rate-ove na loan-u — perzistuj i kad
                // status nije menjan (vec LATE).
                loanRepository.save(loan);
            }

            log.warn("Insufficient funds for installment {} on loan {} (attempt {}). Rescheduled to {}{}",
                    installment.getId(), loan.getLoanNumber(), attempts, nextRetryDate,
                    penaltyAppliedNow ? " [penal +" + LATE_PENALTY_RATE + "% primenjen]" : "");

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

            // R5 1887: audit neuspele naplate (nedovoljno sredstava → reschedule + LATE).
            recordInstallmentAudit(
                    AuditActionType.LOAN_INSTALLMENT_FAILED,
                    "Installment " + installment.getId() + " failed for loan " + loan.getLoanNumber()
                            + " (insufficient funds, amount=" + installment.getAmount() + " " + currencyCode
                            + ", attempt=" + attempts + ", rescheduled to " + nextRetryDate
                            + (penaltyAppliedNow ? ", late penalty +" + LATE_PENALTY_RATE + "% applied" : "") + ")",
                    loan.getId());
        }
    }

    /**
     * R1 346 (§417): broj uzastopnih neuspelih pokusaja naplate posle kojeg se
     * primenjuje penal kamate. 1. neuspeh = prvo klizanje +72h (spec korak 5);
     * od {@code >= 2} neuspeha primenjujemo penal (spec korak "Ako ni tada nema
     * dovoljno sredstava").
     */
    private static final int PENALTY_AFTER_FAILED_ATTEMPTS = 2;

    /** R1 346 (§417): povecanje osnovice kamatne stope za kasnjenje (+0.05%). */
    private static final BigDecimal LATE_PENALTY_RATE = new BigDecimal("0.05");

    /**
     * R1 346 (§417): podize {@code nominalRate} i {@code effectiveRate} kredita za
     * {@link #LATE_PENALTY_RATE} (0.05%) kao penal za kasnjenje. Primena je
     * idempotentna po rati ({@code installment.penaltyApplied} guard) — penal se
     * naplacuje jednom po zakasneloj rati, ne pri svakom +72h klizanju.
     */
    private void applyLatePenalty(Loan loan) {
        if (loan.getNominalRate() != null) {
            loan.setNominalRate(loan.getNominalRate().add(LATE_PENALTY_RATE));
        }
        if (loan.getEffectiveRate() != null) {
            loan.setEffectiveRate(loan.getEffectiveRate().add(LATE_PENALTY_RATE));
        }
        log.info("Loan {} late penalty +{}% applied (new nominalRate={}, effectiveRate={})",
                loan.getLoanNumber(), LATE_PENALTY_RATE, loan.getNominalRate(), loan.getEffectiveRate());
    }
}
