package rs.raf.banka2_bek.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import rs.raf.banka2_bek.account.model.AccountSubtype;
import rs.raf.banka2_bek.account.model.AccountType;

import java.math.BigDecimal;

@Data
public class CreateAccountDto {

    @NotNull(message = "Tip racuna je obavezan")
    private AccountType accountType;

    private AccountSubtype accountSubtype;

    // FE salje "currency" (code string), BE takodje prihvata "currencyCode"
    private String currencyCode;
    private String currency;

    @PositiveOrZero(message = "Pocetno stanje mora biti 0 ili vece")
    private BigDecimal initialBalance;

    // [P2-input-validation-1 / R1 310] initialDeposit je alternativni izvor za
    // pocetno stanje (getResolvedInitialBalance) — bez validacije je negativan
    // depozit prolazio i kreirao racun sa negativnim stanjem.
    @PositiveOrZero(message = "Pocetni depozit mora biti 0 ili vece")
    private Double initialDeposit;

    private Long clientId;
    private String ownerEmail;

    private CreateAccountCompanyDto company;

    // FE salje poslovne podatke kao flat polja
    private String companyName;
    private String registrationNumber;
    private String taxId;

    // [P2-input-validation-1 / R1 312] sifra delatnosti je u formatu xx.xx
    // (Celina 2 §63, npr. "62.01" / "10.1"). Regex dozvoljava prazan string
    // (opciono za fizicka lica) ali odbacuje nevalidan format. @Pattern preskace null.
    @Pattern(regexp = "^(\\d{2}\\.\\d{1,2})?$",
            message = "Sifra delatnosti mora biti u formatu xx.xx (npr. 62.01)")
    private String activityCode;
    private String firmAddress;

    private Boolean createCard = false;

    private BigDecimal dailyLimit;
    private BigDecimal monthlyLimit;

    // Helper da se dobije valuta bez obzira koje polje je poslato
    public String getResolvedCurrencyCode() {
        if (currencyCode != null && !currencyCode.isBlank()) return currencyCode;
        if (currency != null && !currency.isBlank()) return currency;
        return null;
    }

    // Helper za pocetno stanje
    public BigDecimal getResolvedInitialBalance() {
        if (initialBalance != null) return initialBalance;
        if (initialDeposit != null) return BigDecimal.valueOf(initialDeposit);
        return BigDecimal.ZERO;
    }
}
