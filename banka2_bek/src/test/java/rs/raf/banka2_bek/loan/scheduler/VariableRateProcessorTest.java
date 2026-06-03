package rs.raf.banka2_bek.loan.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import rs.raf.banka2_bek.loan.model.InterestType;
import rs.raf.banka2_bek.loan.model.Loan;
import rs.raf.banka2_bek.loan.model.LoanInstallment;
import rs.raf.banka2_bek.loan.model.LoanStatus;
import rs.raf.banka2_bek.loan.model.LoanType;
import rs.raf.banka2_bek.loan.repository.LoanInstallmentRepository;
import rs.raf.banka2_bek.loan.repository.LoanRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BE-PAY-03: Unit tests za {@link VariableRateProcessor}. Pokriva svu pravu
 * matematiku koja je ranije bila u {@link VariableRateScheduler}: rate floor,
 * monthly payment annuity formulu, installment principal/interest breakdown,
 * margin per loan type, skip-unpaid-installments grana.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VariableRateProcessorTest {

    @Mock private LoanRepository loanRepository;
    @Mock private LoanInstallmentRepository installmentRepository;

    @InjectMocks
    private VariableRateProcessor processor;

    private Loan buildVariableLoan(Long id, String loanNumber, LoanType loanType,
                                   BigDecimal nominalRate, BigDecimal effectiveRate,
                                   BigDecimal monthlyPayment, BigDecimal remainingDebt) {
        Loan loan = new Loan();
        loan.setId(id);
        loan.setLoanNumber(loanNumber);
        loan.setLoanType(loanType);
        loan.setInterestType(InterestType.VARIABLE);
        loan.setNominalRate(nominalRate);
        loan.setEffectiveRate(effectiveRate);
        loan.setMonthlyPayment(monthlyPayment);
        loan.setRemainingDebt(remainingDebt);
        loan.setStatus(LoanStatus.ACTIVE);
        loan.setAmount(BigDecimal.valueOf(100000));
        loan.setRepaymentPeriod(60);
        loan.setStartDate(LocalDate.now().minusMonths(6));
        loan.setEndDate(LocalDate.now().plusMonths(54));
        return loan;
    }

    private LoanInstallment buildInstallment(Long id, Loan loan, boolean paid, BigDecimal amount) {
        LoanInstallment inst = new LoanInstallment();
        inst.setId(id);
        inst.setLoan(loan);
        inst.setPaid(paid);
        inst.setAmount(amount);
        inst.setInterestRate(loan.getEffectiveRate());
        inst.setExpectedDueDate(LocalDate.now().plusMonths(id.intValue()));
        return inst;
    }

    @Test
    @DisplayName("processes active variable-rate loan and updates effective rate + monthly payment")
    void processesActiveVariableLoan() {
        Loan loan = buildVariableLoan(1L, "VAR-001", LoanType.CASH,
                new BigDecimal("4.00"), new BigDecimal("5.75"),
                new BigDecimal("2000.0000"), BigDecimal.valueOf(80000));

        LoanInstallment unpaid1 = buildInstallment(1L, loan, false, new BigDecimal("2000.0000"));
        LoanInstallment unpaid2 = buildInstallment(2L, loan, false, new BigDecimal("2000.0000"));
        LoanInstallment paid = buildInstallment(3L, loan, true, new BigDecimal("2000.0000"));

        when(installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(1L))
                .thenReturn(List.of(paid, unpaid1, unpaid2));

        // Fixed offset za determinisitican test
        processor.adjustOne(loan, new BigDecimal("0.00"));

        ArgumentCaptor<Loan> loanCaptor = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(loanCaptor.capture());
        Loan savedLoan = loanCaptor.getValue();

        // effectiveRate = nominal(4.00) + offset(0.00) + margin(CASH=1.75) = 5.75
        assertThat(savedLoan.getEffectiveRate()).isEqualByComparingTo(new BigDecimal("5.75"));
        assertThat(savedLoan.getMonthlyPayment()).isPositive();

        // Only unpaid installments should be updated (2 saves)
        verify(installmentRepository, times(2)).save(any(LoanInstallment.class));
    }

    @Test
    @DisplayName("processes LATE variable-rate loan")
    void processesLateVariableLoan() {
        Loan loan = buildVariableLoan(1L, "VAR-LATE", LoanType.MORTGAGE,
                new BigDecimal("3.50"), new BigDecimal("5.00"),
                new BigDecimal("1500.0000"), BigDecimal.valueOf(50000));
        loan.setStatus(LoanStatus.LATE);

        LoanInstallment unpaid = buildInstallment(1L, loan, false, new BigDecimal("1500.0000"));

        when(installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(1L)).thenReturn(List.of(unpaid));

        processor.adjustOne(loan, new BigDecimal("0.50"));

        verify(loanRepository).save(loan);
        verify(installmentRepository).save(unpaid);
    }

    @Test
    @DisplayName("skips loan with no unpaid installments")
    void skipsLoanWithNoUnpaidInstallments() {
        Loan loan = buildVariableLoan(1L, "VAR-DONE", LoanType.CASH,
                new BigDecimal("4.00"), new BigDecimal("5.75"),
                new BigDecimal("2000.0000"), BigDecimal.valueOf(80000));

        LoanInstallment paid = buildInstallment(1L, loan, true, new BigDecimal("2000.0000"));

        when(installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(1L)).thenReturn(List.of(paid));

        processor.adjustOne(loan, new BigDecimal("0.00"));

        verify(loanRepository, never()).save(any());
        verify(installmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("effective rate has floor of 0.50%")
    void effectiveRateHasFloor() {
        Loan loan = buildVariableLoan(1L, "VAR-LOW", LoanType.STUDENT,
                new BigDecimal("0.00"), new BigDecimal("0.75"),
                new BigDecimal("1000.0000"), BigDecimal.valueOf(10000));

        LoanInstallment unpaid = buildInstallment(1L, loan, false, new BigDecimal("1000.0000"));

        when(installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(1L)).thenReturn(List.of(unpaid));

        // Very negative offset to trigger floor: nominal(0.00) + offset(-1.50) + margin(0.75) = -0.75 → clamped to 0.50
        processor.adjustOne(loan, new BigDecimal("-1.50"));

        ArgumentCaptor<Loan> captor = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(captor.capture());
        assertThat(captor.getValue().getEffectiveRate()).isEqualByComparingTo(new BigDecimal("0.50"));
    }

    @Test
    @DisplayName("uses correct margin for REFINANCING loan type (1.00)")
    void usesRefinancingMargin() {
        Loan loan = buildVariableLoan(1L, "VAR-REF", LoanType.REFINANCING,
                new BigDecimal("5.00"), new BigDecimal("6.00"),
                new BigDecimal("2500.0000"), BigDecimal.valueOf(60000));

        LoanInstallment unpaid = buildInstallment(1L, loan, false, new BigDecimal("2500.0000"));

        when(installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(1L)).thenReturn(List.of(unpaid));

        // offset=0 → effective = 5.00 + 0 + 1.00 = 6.00
        processor.adjustOne(loan, new BigDecimal("0.00"));

        ArgumentCaptor<Loan> captor = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(captor.capture());
        assertThat(captor.getValue().getEffectiveRate()).isEqualByComparingTo(new BigDecimal("6.00"));
    }

    @Test
    @DisplayName("uses correct margin for MORTGAGE (1.50)")
    void usesMortgageMargin() {
        Loan loan = buildVariableLoan(1L, "VAR-MORT", LoanType.MORTGAGE,
                new BigDecimal("4.00"), new BigDecimal("5.50"),
                new BigDecimal("3000.0000"), BigDecimal.valueOf(200000));
        LoanInstallment unpaid = buildInstallment(1L, loan, false, new BigDecimal("3000.0000"));

        when(installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(1L)).thenReturn(List.of(unpaid));

        processor.adjustOne(loan, new BigDecimal("0.00"));

        ArgumentCaptor<Loan> captor = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(captor.capture());
        assertThat(captor.getValue().getEffectiveRate()).isEqualByComparingTo(new BigDecimal("5.50"));
    }

    @Test
    @DisplayName("uses correct margin for AUTO (1.25)")
    void usesAutoMargin() {
        Loan loan = buildVariableLoan(1L, "VAR-AUTO", LoanType.AUTO,
                new BigDecimal("3.00"), new BigDecimal("4.25"),
                new BigDecimal("2000.0000"), BigDecimal.valueOf(60000));
        LoanInstallment unpaid = buildInstallment(1L, loan, false, new BigDecimal("2000.0000"));

        when(installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(1L)).thenReturn(List.of(unpaid));

        processor.adjustOne(loan, new BigDecimal("0.00"));

        ArgumentCaptor<Loan> captor = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(captor.capture());
        assertThat(captor.getValue().getEffectiveRate()).isEqualByComparingTo(new BigDecimal("4.25"));
    }

    @Test
    @DisplayName("uses correct margin for STUDENT (0.75)")
    void usesStudentMargin() {
        Loan loan = buildVariableLoan(1L, "VAR-STUD", LoanType.STUDENT,
                new BigDecimal("5.00"), new BigDecimal("5.75"),
                new BigDecimal("1000.0000"), BigDecimal.valueOf(20000));
        LoanInstallment unpaid = buildInstallment(1L, loan, false, new BigDecimal("1000.0000"));

        when(installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(1L)).thenReturn(List.of(unpaid));

        processor.adjustOne(loan, new BigDecimal("0.00"));

        ArgumentCaptor<Loan> captor = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(captor.capture());
        assertThat(captor.getValue().getEffectiveRate()).isEqualByComparingTo(new BigDecimal("5.75"));
    }

    @Test
    @DisplayName("P1: last installment principalPortion clamped to amount-interest (R3-1596 consistency)")
    void lastInstallmentPrincipalConsistentWithChargedAmount() {
        // R3-1596 (consistency): ranije je poslednja rata forsirala principalPortion = CEO
        // remainingPrincipal, raskidajuci vezu sa onim sto se naplacuje (amount). Sada se kapira
        // na (amount - interest) → principalAmount <= amount za SVAKU ratu, pa InstallmentProcessor
        // ne moze umanjiti dug za vise nego sto je naplaceno. Pojas-i-tregeri sa guard-om tamo.
        Loan loan = buildVariableLoan(1L, "VAR-LAST", LoanType.CASH,
                new BigDecimal("30.00"), new BigDecimal("31.75"),
                new BigDecimal("2000.0000"), BigDecimal.valueOf(100000));
        LoanInstallment u1 = buildInstallment(1L, loan, false, new BigDecimal("2000.0000"));
        LoanInstallment u2 = buildInstallment(2L, loan, false, new BigDecimal("2000.0000"));
        LoanInstallment u3 = buildInstallment(3L, loan, false, new BigDecimal("2000.0000"));

        when(installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(1L))
                .thenReturn(List.of(u1, u2, u3));

        processor.adjustOne(loan, new BigDecimal("0.00"));

        ArgumentCaptor<LoanInstallment> captor = ArgumentCaptor.forClass(LoanInstallment.class);
        verify(installmentRepository, times(3)).save(captor.capture());

        // Svaka rata: principal koji umanjuje dug NE sme premasiti amount koji se naplacuje.
        for (LoanInstallment saved : captor.getAllValues()) {
            assertThat(saved.getPrincipalAmount())
                    .as("principalAmount (%s) must not exceed charged amount (%s)",
                            saved.getPrincipalAmount(), saved.getAmount())
                    .isLessThanOrEqualTo(saved.getAmount());
        }
    }

    @Test
    @DisplayName("updates all unpaid installments with new monthly payment + principal/interest breakdown")
    void updatesInstallments() {
        Loan loan = buildVariableLoan(1L, "VAR-INST", LoanType.CASH,
                new BigDecimal("4.00"), new BigDecimal("5.75"),
                new BigDecimal("2000.0000"), BigDecimal.valueOf(50000));
        LoanInstallment unpaid1 = buildInstallment(1L, loan, false, new BigDecimal("2000.0000"));
        LoanInstallment unpaid2 = buildInstallment(2L, loan, false, new BigDecimal("2000.0000"));

        when(installmentRepository.findByLoanIdOrderByExpectedDueDateAsc(1L)).thenReturn(List.of(unpaid1, unpaid2));

        processor.adjustOne(loan, new BigDecimal("0.00"));

        ArgumentCaptor<LoanInstallment> captor = ArgumentCaptor.forClass(LoanInstallment.class);
        verify(installmentRepository, times(2)).save(captor.capture());

        for (LoanInstallment saved : captor.getAllValues()) {
            assertThat(saved.getAmount()).isPositive();
            assertThat(saved.getInterestRate()).isNotNull();
            assertThat(saved.getInterestAmount()).isNotNull();
            assertThat(saved.getPrincipalAmount()).isNotNull();
        }
    }
}
