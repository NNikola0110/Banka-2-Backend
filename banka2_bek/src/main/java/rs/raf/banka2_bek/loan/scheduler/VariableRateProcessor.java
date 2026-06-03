package rs.raf.banka2_bek.loan.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.loan.model.Loan;
import rs.raf.banka2_bek.loan.model.LoanInstallment;
import rs.raf.banka2_bek.loan.model.LoanType;
import rs.raf.banka2_bek.loan.repository.LoanInstallmentRepository;
import rs.raf.banka2_bek.loan.repository.LoanRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * BE-PAY-03: Per-loan transaction processor za simulaciju promenljive
 * kamatne stope.
 *
 * <p>Razlog: {@link VariableRateScheduler#adjustVariableRates()} ranije je
 * imao outer {@code @Transactional} pa je jedna loona obrada (npr.
 * unexpected SQL error mid-loop) povlacila rollback svih ostalih loans-a u
 * batch-u. Sa per-loan {@code REQUIRES_NEW} izolacijom, scheduler iterira i
 * delegira; svaka greska se lokalizuje.</p>
 */
@Slf4j
@Component
public class VariableRateProcessor {

    private final LoanRepository loanRepository;
    private final LoanInstallmentRepository installmentRepository;

    /**
     * R1-660: minimalna efektivna kamatna stopa (safety floor) — kad
     * nominalRate + offset + margin padne ispod ovoga, kapira se na ovu vrednost.
     * Eksternalizovano u config ({@code loan.variable-rate.floor-percent}); ima
     * in-code default 0.50% pa radi i bez Spring konteksta (unit testovi /
     * {@code @InjectMocks} koji ne setuju {@code @Value} polja).
     */
    @org.springframework.beans.factory.annotation.Value("${loan.variable-rate.floor-percent:0.50}")
    private BigDecimal rateFloor = new BigDecimal("0.50");

    public VariableRateProcessor(LoanRepository loanRepository,
                                 LoanInstallmentRepository installmentRepository) {
        this.loanRepository = loanRepository;
        this.installmentRepository = installmentRepository;
    }

    /**
     * BE-PAY-03: Adjust-uje jedan loan u nezavisnoj transakciji.
     * Recalculate-uje effective rate + monthly payment + sve neplacene rate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void adjustOne(Loan loan, BigDecimal offset) {
        BigDecimal oldEffectiveRate = loan.getEffectiveRate();
        BigDecimal oldMonthlyPayment = loan.getMonthlyPayment();

        // effectiveRate = nominalRate + offset + margin(loanType)
        BigDecimal margin = getMargin(loan.getLoanType());
        BigDecimal newEffectiveRate = loan.getNominalRate().add(offset).add(margin);

        // R1-660: efektivna stopa ne sme ispod safety floor-a (konfigurabilno, default 0.50%).
        if (newEffectiveRate.compareTo(rateFloor) < 0) {
            newEffectiveRate = rateFloor;
        }

        // Count remaining (unpaid) installments
        List<LoanInstallment> unpaidInstallments = installmentRepository
                .findByLoanIdOrderByExpectedDueDateAsc(loan.getId())
                .stream()
                .filter(i -> !Boolean.TRUE.equals(i.getPaid()))
                .toList();

        int remainingMonths = unpaidInstallments.size();
        if (remainingMonths == 0) {
            log.info("Loan {} has no unpaid installments, skipping", loan.getLoanNumber());
            return;
        }

        // Recalculate monthly payment: A = P * r * (1+r)^n / ((1+r)^n - 1)
        // where P = remainingDebt, r = monthly rate, n = remaining months.
        // Safety floor (>= rateFloor, default 0.50%) above guarantees monthlyRate > 0, so no zero-rate branch.
        BigDecimal monthlyRate = newEffectiveRate.divide(BigDecimal.valueOf(1200), 10, RoundingMode.HALF_UP);
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal onePlusRn = onePlusR.pow(remainingMonths, MathContext.DECIMAL128);
        BigDecimal newMonthlyPayment = loan.getRemainingDebt()
                .multiply(monthlyRate)
                .multiply(onePlusRn)
                .divide(onePlusRn.subtract(BigDecimal.ONE), 4, RoundingMode.HALF_UP);

        // Update loan entity
        loan.setEffectiveRate(newEffectiveRate);
        loan.setMonthlyPayment(newMonthlyPayment);
        loanRepository.save(loan);

        // Recalculate and update unpaid installments with new principal/interest breakdown
        BigDecimal remainingPrincipal = loan.getRemainingDebt();
        for (int i = 0; i < unpaidInstallments.size(); i++) {
            LoanInstallment installment = unpaidInstallments.get(i);
            BigDecimal interestPortion = remainingPrincipal.multiply(monthlyRate).setScale(4, RoundingMode.HALF_UP);
            BigDecimal principalPortion = newMonthlyPayment.subtract(interestPortion);

            // R3-1596 (money-loss): poslednja rata je ranije postavljala principalPortion =
            // CEO remainingPrincipal, dok amount ostaje newMonthlyPayment. InstallmentProcessor
            // tada nula-ira remainingDebt po principalAmount-u, a naplacuje samo amount → posle
            // LATE preskocenih rata jedna mala naplata gasi ceo dug (banka gubi glavnicu).
            // Fix: principalPortion = min(remaining, amount - interest) — nikad vise nego sto
            // se stvarno naplacuje. U normalnoj amortizaciji amount-interest >= remaining na
            // poslednjoj rati pa je ovo no-op; u pathological slucaju kapira na naplaceno.
            if (i == unpaidInstallments.size() - 1) {
                principalPortion = principalPortion.min(remainingPrincipal);
            }
            // Defanzivno: principalPortion ne sme biti negativan (interest > rata).
            if (principalPortion.compareTo(BigDecimal.ZERO) < 0) {
                principalPortion = BigDecimal.ZERO;
            }

            remainingPrincipal = remainingPrincipal.subtract(principalPortion);

            installment.setAmount(newMonthlyPayment);
            installment.setInterestRate(newEffectiveRate);
            installment.setInterestAmount(interestPortion);
            installment.setPrincipalAmount(principalPortion);
            installmentRepository.save(installment);
        }

        log.info("Loan {} adjusted: effectiveRate {}% -> {}%, monthlyPayment {} -> {}, remaining installments: {}",
                loan.getLoanNumber(),
                oldEffectiveRate, newEffectiveRate,
                oldMonthlyPayment, newMonthlyPayment,
                remainingMonths);
    }

    /**
     * R1-657: bank margin per loan type — delegira na {@link LoanType#getMargin()}
     * (jedinstven izvor istine; pre je tabela bila duplirana sa LoanServiceImpl).
     */
    private BigDecimal getMargin(LoanType type) {
        return type.getMargin();
    }
}
