package rs.raf.banka2_bek.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TODO_final Mobile bonus #7 — request body za POST /payments/{id}/approve.
 *
 * <p>Mobile QuickApproveScreen consume-uje endpoint preko ovog DTO-a posle
 * korisnikove potvrde u OTP modal-u (TOTP iz Google Authenticator-a, 6 cifara).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovePaymentRequest {

    @NotBlank(message = "Verifikacioni kod je obavezan")
    @Pattern(regexp = "\\d{6}", message = "Verifikacioni kod mora biti 6 cifara")
    private String otpCode;
}
