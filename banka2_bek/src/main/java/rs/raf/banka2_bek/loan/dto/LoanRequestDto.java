package rs.raf.banka2_bek.loan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class LoanRequestDto {

    @NotNull
    private String loanType;

    @NotNull
    private String interestType;

    @NotNull
    @Positive
    private BigDecimal amount;

    @NotBlank
    private String currency;

    private String loanPurpose;

    @NotNull
    @Positive
    private Integer repaymentPeriod;

    @NotBlank
    private String accountNumber;

    private String phoneNumber;
    private String employmentStatus;
    private BigDecimal monthlyIncome;
    private Boolean permanentEmployment;
    private Integer employmentPeriod;

    /**
     * ACCEPTED-DEVIATION (user-directed 03.06): zahtev za kredit vise NE zahteva OTP.
     * Polje je zadrzano radi back-compat-a (FE/Mobile/postojeci pozivi sme da ga
     * salju), ali se IGNORISE — OTP vazi iskljucivo za placanja i transfere.
     * @deprecated OTP uklonjen iz loan flow-a; polje se ne cita.
     */
    @Deprecated
    private String otpCode;
}
