package rs.raf.banka2_bek.loan.model;

import java.math.BigDecimal;

public enum LoanType {
    CASH(new BigDecimal("1.75")),
    MORTGAGE(new BigDecimal("1.50")),
    AUTO(new BigDecimal("1.25")),
    REFINANCING(new BigDecimal("1.00")),
    STUDENT(new BigDecimal("0.75"));

    private final BigDecimal margin;

    LoanType(BigDecimal margin) {
        this.margin = margin;
    }

    /**
     * R1-657: JEDINSTVEN izvor istine za maržu banke po tipu kredita
     * (effectiveRate = baseRate + offset + margin). Pre je ista switch-tabela bila
     * duplirana verbatim u {@code LoanServiceImpl.getMargin} i
     * {@code VariableRateProcessor.getMargin}; sad oba delegiraju ovde.
     */
    public BigDecimal getMargin() {
        return margin;
    }
}
