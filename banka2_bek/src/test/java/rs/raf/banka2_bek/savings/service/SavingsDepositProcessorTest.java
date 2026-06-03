package rs.raf.banka2_bek.savings.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.savings.entity.SavingsDeposit;
import rs.raf.banka2_bek.savings.entity.SavingsDepositStatus;
import rs.raf.banka2_bek.savings.entity.SavingsInterestRate;
import rs.raf.banka2_bek.savings.entity.SavingsTransaction;
import rs.raf.banka2_bek.savings.repository.SavingsDepositRepository;
import rs.raf.banka2_bek.savings.repository.SavingsTransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavingsDepositProcessorTest {

    @Mock SavingsDepositRepository depositRepo;
    @Mock SavingsTransactionRepository txRepo;
    @Mock SavingsInterestRateService rateService;
    @Mock AccountRepository accountRepo;
    @Mock rs.raf.banka2_bek.audit.service.AuditLogService auditLogService;
    @InjectMocks SavingsDepositProcessor processor;

    private Currency rsd;
    private SavingsDeposit deposit;
    private Account linked;
    private Account bankAccount;

    @BeforeEach
    void setUp() {
        rsd = new Currency();
        rsd.setId(1L);
        rsd.setCode("RSD");

        deposit = SavingsDeposit.builder()
                .id(1L).clientId(1L).linkedAccountId(10L)
                .principalAmount(new BigDecimal("100000"))
                .currency(rsd).termMonths(12)
                .annualInterestRate(new BigDecimal("4.00"))
                .startDate(LocalDate.of(2026, 5, 12))
                .maturityDate(LocalDate.of(2027, 5, 12))
                .nextInterestPaymentDate(LocalDate.of(2026, 6, 12))
                .totalInterestPaid(BigDecimal.ZERO)
                .autoRenew(false)
                .status(SavingsDepositStatus.ACTIVE).build();

        linked = new Account();
        linked.setId(10L);
        linked.setBalance(new BigDecimal("1000"));
        linked.setAvailableBalance(new BigDecimal("1000"));
        linked.setCurrency(rsd);

        bankAccount = new Account();
        bankAccount.setId(100L);
        bankAccount.setBalance(new BigDecimal("1000000"));
        bankAccount.setAvailableBalance(new BigDecimal("1000000"));
        bankAccount.setCurrency(rsd);

        ReflectionTestUtils.setField(processor, "bankRegistrationNumber", "22200022");
    }

    @Test
    void payMonthlyInterest_creditsLinkedAndUpdatesNextDate() {
        when(accountRepo.findForUpdateById(10L)).thenReturn(Optional.of(linked));
        when(accountRepo.save(linked)).thenReturn(linked);
        when(accountRepo.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankAccount));
        when(depositRepo.save(deposit)).thenReturn(deposit);

        processor.payMonthlyInterest(deposit, LocalDate.of(2026, 6, 12));

        // 100000 * 4.00 / 1200 = 333.3333
        assertThat(linked.getBalance()).isEqualByComparingTo("1333.3333");
        assertThat(linked.getAvailableBalance()).isEqualByComparingTo("1333.3333");
        // Double-entry: kamata je bankin trosak → bankin racun debitovan za isti iznos.
        assertThat(bankAccount.getBalance()).isEqualByComparingTo("999666.6667");
        assertThat(bankAccount.getAvailableBalance()).isEqualByComparingTo("999666.6667");
        assertThat(deposit.getNextInterestPaymentDate()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(deposit.getTotalInterestPaid()).isEqualByComparingTo("333.3333");
        verify(txRepo).save(any(SavingsTransaction.class));
        verify(accountRepo).save(bankAccount);
    }

    /** P0-B1 konzervacija: kamata net-zero (klijent +i, banka -i). */
    @Test
    void payMonthlyInterest_conservation_systemMoneyUnchanged() {
        when(accountRepo.findForUpdateById(10L)).thenReturn(Optional.of(linked));
        when(accountRepo.save(linked)).thenReturn(linked);
        when(accountRepo.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankAccount));
        when(depositRepo.save(deposit)).thenReturn(deposit);

        BigDecimal before = linked.getBalance().add(bankAccount.getBalance());
        processor.payMonthlyInterest(deposit, LocalDate.of(2026, 6, 12));
        BigDecimal after = linked.getBalance().add(bankAccount.getBalance());

        assertThat(after).isEqualByComparingTo(before);
    }

    /**
     * R7: kamata se NE sme isplacivati preko maturityDate. Ako je nextInterestPaymentDate
     * vec posle maturity (catch-up posle downtime), payMonthlyInterest je no-op.
     */
    @Test
    void payMonthlyInterest_pastMaturity_skipsNoMoneyMoved() {
        deposit.setNextInterestPaymentDate(LocalDate.of(2027, 6, 12)); // posle maturity 2027-05-12

        processor.payMonthlyInterest(deposit, LocalDate.of(2027, 6, 12));

        assertThat(linked.getBalance()).isEqualByComparingTo("1000");
        assertThat(deposit.getTotalInterestPaid()).isEqualByComparingTo("0");
        verify(accountRepo, never()).findForUpdateById(any());
        verify(txRepo, never()).save(any(SavingsTransaction.class));
    }

    /**
     * P1-savings-loans-1 (R1-137 / R4-1734): multi-mesecni catch-up. Posle downtime
     * nextInterestPaymentDate moze biti vise meseci u proslosti; jedan ciklus mora
     * isplatiti SVE dospele mesece (while-petlja), ne samo jedan. Inace bi maturity
     * grana istog ciklusa vratila glavnicu + MATURED i zaostala kamata bi se izgubila.
     */
    @Test
    void payMonthlyInterest_multiMonthCatchUp_paysAllDueMonthsInOneCycle() {
        // start 2026-05-12, nextInterest 2026-06-12; today 2026-08-12 → dospela 3 meseca
        // (06-12, 07-12, 08-12), sve <= maturity 2027-05-12
        when(accountRepo.findForUpdateById(10L)).thenReturn(Optional.of(linked));
        when(accountRepo.save(linked)).thenReturn(linked);
        when(accountRepo.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankAccount));
        when(depositRepo.save(deposit)).thenReturn(deposit);

        processor.payMonthlyInterest(deposit, LocalDate.of(2026, 8, 12));

        // 3 * (100000 * 4.00 / 1200) = 3 * 333.3333 = 999.9999
        assertThat(linked.getBalance()).isEqualByComparingTo("1999.9999");
        assertThat(deposit.getTotalInterestPaid()).isEqualByComparingTo("999.9999");
        // nextInterestPaymentDate napreduje 3 meseca: 06-12 -> 09-12
        assertThat(deposit.getNextInterestPaymentDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        // 3 INTEREST_PAYMENT transakcije (jedna po mesecu — audit trag)
        verify(txRepo, times(3)).save(any(SavingsTransaction.class));
    }

    /** Multi-month catch-up konzervacija: net-zero (klijent +Σi, banka -Σi). */
    @Test
    void payMonthlyInterest_multiMonthCatchUp_conservation() {
        when(accountRepo.findForUpdateById(10L)).thenReturn(Optional.of(linked));
        when(accountRepo.save(linked)).thenReturn(linked);
        when(accountRepo.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankAccount));
        when(depositRepo.save(deposit)).thenReturn(deposit);

        BigDecimal before = linked.getBalance().add(bankAccount.getBalance());
        processor.payMonthlyInterest(deposit, LocalDate.of(2026, 8, 12));
        BigDecimal after = linked.getBalance().add(bankAccount.getBalance());

        assertThat(after).isEqualByComparingTo(before);
    }

    /**
     * Catch-up se zaustavlja na maturityDate — ne placa kamatu preko roka (R7 saradnja).
     * nextInterest 2027-03-12, maturity 2027-05-12, today 2027-12-12 → samo 03/04/05 (3 meseca).
     */
    @Test
    void payMonthlyInterest_catchUpStopsAtMaturity() {
        deposit.setNextInterestPaymentDate(LocalDate.of(2027, 3, 12)); // maturity 2027-05-12
        when(accountRepo.findForUpdateById(10L)).thenReturn(Optional.of(linked));
        when(accountRepo.save(linked)).thenReturn(linked);
        when(accountRepo.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankAccount));
        when(depositRepo.save(deposit)).thenReturn(deposit);

        processor.payMonthlyInterest(deposit, LocalDate.of(2027, 12, 12));

        // 3 meseca dospela koji su <= maturity (03-12, 04-12, 05-12); 06-12 > maturity → stop
        assertThat(deposit.getTotalInterestPaid()).isEqualByComparingTo("999.9999");
        assertThat(deposit.getNextInterestPaymentDate()).isEqualTo(LocalDate.of(2027, 6, 12));
        verify(txRepo, times(3)).save(any(SavingsTransaction.class));
    }

    /** R7 granica: nextInterestPaymentDate == maturityDate je dozvoljena (poslednja isplata). */
    @Test
    void payMonthlyInterest_onMaturityDate_paysInterest() {
        deposit.setNextInterestPaymentDate(LocalDate.of(2027, 5, 12)); // == maturity
        when(accountRepo.findForUpdateById(10L)).thenReturn(Optional.of(linked));
        when(accountRepo.save(linked)).thenReturn(linked);
        when(accountRepo.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankAccount));
        when(depositRepo.save(deposit)).thenReturn(deposit);

        processor.payMonthlyInterest(deposit, LocalDate.of(2027, 5, 12));

        assertThat(linked.getBalance()).isEqualByComparingTo("1333.3333");
        verify(txRepo).save(any(SavingsTransaction.class));
    }

    @Test
    void returnPrincipal_setsStatusMatured() {
        when(accountRepo.findForUpdateById(10L)).thenReturn(Optional.of(linked));
        when(accountRepo.save(linked)).thenReturn(linked);
        when(accountRepo.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankAccount));
        when(depositRepo.save(deposit)).thenReturn(deposit);

        processor.returnPrincipal(deposit, LocalDate.of(2027, 5, 12));

        assertThat(deposit.getStatus()).isEqualTo(SavingsDepositStatus.MATURED);
        assertThat(linked.getBalance()).isEqualByComparingTo("101000");
        // Double-entry: banka oslobadja custody glavnice (-100000).
        assertThat(bankAccount.getBalance()).isEqualByComparingTo("900000");
        assertThat(bankAccount.getAvailableBalance()).isEqualByComparingTo("900000");
        verify(txRepo).save(any(SavingsTransaction.class));
        verify(accountRepo).save(bankAccount);
    }

    /** P0-B1 konzervacija: maturity payout net-zero (klijent +principal, banka -principal). */
    @Test
    void returnPrincipal_conservation_systemMoneyUnchanged() {
        when(accountRepo.findForUpdateById(10L)).thenReturn(Optional.of(linked));
        when(accountRepo.save(linked)).thenReturn(linked);
        when(accountRepo.findBankAccountForUpdateByCurrency("22200022", "RSD")).thenReturn(Optional.of(bankAccount));
        when(depositRepo.save(deposit)).thenReturn(deposit);

        BigDecimal before = linked.getBalance().add(bankAccount.getBalance());
        processor.returnPrincipal(deposit, LocalDate.of(2027, 5, 12));
        BigDecimal after = linked.getBalance().add(bankAccount.getBalance());

        assertThat(after).isEqualByComparingTo(before);
    }

    @Test
    void renewDeposit_createsNewWithCurrentRate() {
        SavingsInterestRate currentRate = SavingsInterestRate.builder()
                .id(2L).currency(rsd).termMonths(12).annualRate(new BigDecimal("4.50"))
                .active(true).effectiveFrom(LocalDate.now()).build();
        when(rateService.findActive(1L, 12)).thenReturn(Optional.of(currentRate));
        when(depositRepo.save(any(SavingsDeposit.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.renewDeposit(deposit, LocalDate.of(2027, 5, 12));

        assertThat(deposit.getStatus()).isEqualTo(SavingsDepositStatus.RENEWED);
        verify(depositRepo, times(2)).save(any(SavingsDeposit.class));
        verify(txRepo).save(any(SavingsTransaction.class));
        // R5 1889: SAVINGS_AUTO_RENEWED je sad emitovan (vise nije dead enum).
        verify(auditLogService).record(
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq("SYSTEM"),
                org.mockito.ArgumentMatchers.eq(rs.raf.banka2_bek.audit.model.AuditActionType.SAVINGS_AUTO_RENEWED),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("SAVINGS_DEPOSIT"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * TEST-savings-6 (SavingsDepositProcessor:150-153): auto-obnova zahteva da stopa
     * za (valuta, rok) JOS POSTOJI kao aktivna. Kad je admin u medjuvremenu povukao
     * stopu (findActive -> empty), renewDeposit baca IllegalStateException i NE pravi
     * obnovljeni depozit (ne smemo obnoviti po nepostojecoj stopi). @Transactional
     * rollback-uje status izmenu — ovde (unit, bez tx) pinujemo da exception propagira
     * pre kreiranja novog depozita i da nema audit-emita.
     */
    @Test
    void renewDeposit_rateNoLongerExists_throwsAndDoesNotCreateRenewal() {
        when(rateService.findActive(1L, 12)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> processor.renewDeposit(deposit, LocalDate.of(2027, 5, 12)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stopa za auto-obnovu nije dostupna");

        // Ni jedan novi depozit nije perzistiran (orElseThrow je PRE setStatus/save).
        verify(depositRepo, never()).save(any(SavingsDeposit.class));
        verify(txRepo, never()).save(any(SavingsTransaction.class));
        // Audit za auto-obnovu se NE emituje na neuspeloj obnovi.
        verify(auditLogService, never()).record(
                any(), org.mockito.ArgumentMatchers.anyString(), any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}
