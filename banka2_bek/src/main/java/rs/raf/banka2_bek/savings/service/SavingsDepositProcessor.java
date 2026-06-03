package rs.raf.banka2_bek.savings.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.audit.model.AuditActionType;
import rs.raf.banka2_bek.audit.service.AuditLogService;
import rs.raf.banka2_bek.savings.entity.SavingsDeposit;
import rs.raf.banka2_bek.savings.entity.SavingsDepositStatus;
import rs.raf.banka2_bek.savings.entity.SavingsInterestRate;
import rs.raf.banka2_bek.savings.entity.SavingsTransaction;
import rs.raf.banka2_bek.savings.entity.SavingsTransactionType;
import rs.raf.banka2_bek.savings.repository.SavingsDepositRepository;
import rs.raf.banka2_bek.savings.repository.SavingsTransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Procesor pojedinacnih scheduler operacija. Izdvojen iz {@link SavingsScheduler}
 * tako da {@code @Transactional} prolazi kroz CGLib proxy (intra-class pozivi unutar
 * istog @Service bean-a ne prolaze kroz proxy, pa ce @Transactional biti ignorisan).
 *
 * Tri metode su public i sve sa @Transactional. Scheduler ih poziva preko ovog
 * bean-a (proxy poziv), tako da svaki invocation ima nezavisnu transakciju.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SavingsDepositProcessor {

    private final SavingsDepositRepository depositRepo;
    private final SavingsTransactionRepository txRepo;
    private final SavingsInterestRateService rateService;
    private final AccountRepository accountRepo;
    // P2-audit-coverage-1 (R5 1889): audit auto-obnove depozita (dead enum SAVINGS_AUTO_RENEWED).
    private final AuditLogService auditLogService;

    @Value("${bank.registration-number}")
    private String bankRegistrationNumber;

    @Transactional
    public void payMonthlyInterest(SavingsDeposit d, LocalDate today) {
        // R7: kamata se NE sme isplacivati preko maturityDate. Posle downtime catch-up
        // moze pokupiti deposit-e cija je nextInterestPaymentDate vec prosla maturity —
        // takav payout bi placao kamatu za period koji ne postoji. No-op (guard).
        if (d.getMaturityDate() != null
                && d.getNextInterestPaymentDate().isAfter(d.getMaturityDate())) {
            log.debug("Scheduler: deposit {} nextInterest {} > maturity {} — kamata preskocena (R7)",
                    d.getId(), d.getNextInterestPaymentDate(), d.getMaturityDate());
            return;
        }

        // P1-savings-loans-1 (R1-137 / R4-1734): multi-mesecni catch-up. Posle downtime
        // nextInterestPaymentDate moze biti VISE meseci u proslosti. Ranije se placao samo
        // JEDAN mesec po ciklusu — ako bi maturity grana istog ciklusa vratila glavnicu i
        // postavila MATURED, zaostala kamata bi se izgubila. Sada while-petlja isplacuje
        // SVE dospele mesece (uz R7 ogranicenje <= maturityDate) u jednom pozivu.
        BigDecimal monthlyInterest = SavingsCalculator.monthlyInterest(
                d.getPrincipalAmount(), d.getAnnualInterestRate());

        int monthsCovered = 0;
        BigDecimal totalInterest = BigDecimal.ZERO;
        while (!d.getNextInterestPaymentDate().isAfter(today)
                && (d.getMaturityDate() == null
                    || !d.getNextInterestPaymentDate().isAfter(d.getMaturityDate()))) {
            totalInterest = totalInterest.add(monthlyInterest);
            monthsCovered++;
            d.setNextInterestPaymentDate(
                    SavingsCalculator.nextMonthlyAnniversary(d.getNextInterestPaymentDate()));
        }

        if (monthsCovered == 0) {
            // Nije dospela nijedna isplata (guard — scheduler filtrira, ali defanzivno).
            return;
        }

        Account linked = accountRepo.findForUpdateById(d.getLinkedAccountId())
                .orElseThrow(() -> new IllegalStateException("Povezani racun ne postoji"));
        linked.setBalance(linked.getBalance().add(totalInterest));
        linked.setAvailableBalance(linked.getAvailableBalance().add(totalInterest));
        accountRepo.save(linked);

        // Double-entry: kamata je bankin trosak — debituje se bankin liability racun
        // za UKUPAN iznos svih dospelih meseci, da novac ne nastaje iz vazduha (P0-B1).
        debitBankAccount(d, totalInterest);

        d.setTotalInterestPaid(d.getTotalInterestPaid().add(totalInterest));
        depositRepo.save(d);

        // Audit trag: po jedna INTEREST_PAYMENT transakcija za svaki dospeli mesec.
        for (int i = 0; i < monthsCovered; i++) {
            txRepo.save(SavingsTransaction.builder()
                    .deposit(d).type(SavingsTransactionType.INTEREST_PAYMENT)
                    .amount(monthlyInterest).currency(d.getCurrency()).processedDate(today)
                    .description("Mesecna kamata depozita #" + d.getId())
                    .build());
        }

        log.info("Scheduler: isplacena kamata {} {} ({} mesec/i) za deposit {}",
                totalInterest, d.getCurrency().getCode(), monthsCovered, d.getId());
    }

    @Transactional
    public void returnPrincipal(SavingsDeposit d, LocalDate today) {
        Account linked = accountRepo.findForUpdateById(d.getLinkedAccountId())
                .orElseThrow(() -> new IllegalStateException("Povezani racun ne postoji"));
        linked.setBalance(linked.getBalance().add(d.getPrincipalAmount()));
        linked.setAvailableBalance(linked.getAvailableBalance().add(d.getPrincipalAmount()));
        accountRepo.save(linked);

        // Double-entry: banka oslobadja custody glavnice — debituje se bankin liability
        // racun za glavnicu (kontra-noga uplate na otvaranju, P0-B1 konzervacija).
        debitBankAccount(d, d.getPrincipalAmount());

        d.setStatus(SavingsDepositStatus.MATURED);
        depositRepo.save(d);

        txRepo.save(SavingsTransaction.builder()
                .deposit(d).type(SavingsTransactionType.PRINCIPAL_RETURN)
                .amount(d.getPrincipalAmount()).currency(d.getCurrency()).processedDate(today)
                .description("Glavnica dospelog depozita #" + d.getId())
                .build());

        log.info("Scheduler: vracena glavnica {} {} za deposit {}",
                d.getPrincipalAmount(), d.getCurrency().getCode(), d.getId());
    }

    /**
     * P0-B1 double-entry: debituje bankin liability racun u valuti depozita za
     * {@code amount} (pesimisticki lock). Kontra-noga svake isplate klijentu
     * (kamata/glavnica) — bez nje novac nastaje iz vazduha.
     */
    private void debitBankAccount(SavingsDeposit d, BigDecimal amount) {
        Account bankAccount = accountRepo.findBankAccountForUpdateByCurrency(
                bankRegistrationNumber, d.getCurrency().getCode())
                .orElseThrow(() -> new IllegalStateException(
                        "Banka nema racun u valuti " + d.getCurrency().getCode()));
        bankAccount.setBalance(bankAccount.getBalance().subtract(amount));
        bankAccount.setAvailableBalance(bankAccount.getAvailableBalance().subtract(amount));
        accountRepo.save(bankAccount);
    }

    @Transactional
    public void renewDeposit(SavingsDeposit d, LocalDate today) {
        BigDecimal currentRate = rateService.findActive(d.getCurrency().getId(), d.getTermMonths())
                .map(SavingsInterestRate::getAnnualRate)
                .orElseThrow(() -> new IllegalStateException(
                    "Stopa za auto-obnovu nije dostupna za " + d.getCurrency().getCode()));

        d.setStatus(SavingsDepositStatus.RENEWED);
        depositRepo.save(d);

        SavingsDeposit renewed = SavingsDeposit.builder()
                .clientId(d.getClientId())
                .linkedAccountId(d.getLinkedAccountId())
                .principalAmount(d.getPrincipalAmount())
                .currency(d.getCurrency())
                .termMonths(d.getTermMonths())
                .annualInterestRate(currentRate)
                .startDate(today)
                .maturityDate(today.plusMonths(d.getTermMonths()))
                .nextInterestPaymentDate(today.plusMonths(1))
                .totalInterestPaid(BigDecimal.ZERO)
                .autoRenew(true)
                .status(SavingsDepositStatus.ACTIVE)
                .build();
        renewed = depositRepo.save(renewed);

        txRepo.save(SavingsTransaction.builder()
                .deposit(renewed).type(SavingsTransactionType.RENEWAL_OPEN)
                .amount(d.getPrincipalAmount()).currency(d.getCurrency()).processedDate(today)
                .description("Auto-obnova dospelog depozita #" + d.getId())
                .build());

        // R5 1889: SAVINGS_AUTO_RENEWED je bio definisan ali NIKAD emitovan (dead enum).
        // Auto-obnova menja glavnicu/rok/stopu — money/lifecycle dogadjaj. Aktor = SYSTEM
        // (scheduler thread, nema SecurityContext); target = NOVI depozit. Best-effort.
        try {
            auditLogService.record(
                    0L, "SYSTEM",
                    AuditActionType.SAVINGS_AUTO_RENEWED,
                    "Savings deposit #" + d.getId() + " auto-renewed into #" + renewed.getId()
                            + " (principal=" + d.getPrincipalAmount() + " " + d.getCurrency().getCode()
                            + ", term=" + d.getTermMonths() + "m, rate=" + currentRate + ")",
                    "SAVINGS_DEPOSIT", renewed.getId(),
                    "depositId=" + d.getId(),
                    "depositId=" + renewed.getId() + ",rate=" + currentRate);
        } catch (Exception e) {
            log.warn("Audit log fail (best-effort) action=SAVINGS_AUTO_RENEWED deposit={}: {}",
                    renewed.getId(), e.getMessage());
        }

        log.info("Scheduler: auto-obnova {} -> {} za client {}",
                d.getId(), renewed.getId(), d.getClientId());
    }
}
