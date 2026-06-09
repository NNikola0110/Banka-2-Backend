package rs.raf.banka2_bek.payment.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdatePaymentRecipientRequestDto {

    @Size(max = 100)
    private String name;

    // Cross-bank: dozvoli 18-34 cifre (B2=18, Banka 1=19, IBAN max=34). Vidi
    // CreatePaymentRecipientRequestDto.
    @Size(min = 18, max = 34, message = "Account number must be 18-34 digits")
    @Pattern(regexp = "\\d{18,34}", message = "Account number must contain only digits")
    private String accountNumber;
}
