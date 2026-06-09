package rs.raf.banka2_bek.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreatePaymentRecipientRequestDto {

    @NotBlank(message = "Recipient name is required")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "Account number is required")
    // Cross-bank: B2 racuni su 18 cifara, ali partner-banke (npr. Banka 1) imaju
    // 19-cifrene racune. Sablon primaoca mora da prihvati i strane racune, pa
    // dozvoljavamo 18-34 cifre (IBAN max je 34). Provera valute/postojanja racuna
    // se radi na placanju, ne ovde.
    @Size(min = 18, max = 34, message = "Account number must be 18-34 digits")
    @Pattern(regexp = "\\d{18,34}", message = "Account number must contain only digits")
    private String accountNumber;
}
