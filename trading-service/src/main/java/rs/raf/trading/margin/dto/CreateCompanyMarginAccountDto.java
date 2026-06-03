package rs.raf.trading.margin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO za kreiranje marznog racuna kompanije (COMPANY vlasnistvo).
 *
 * <p>BE-STK-06: Marzni_Racuni.txt §43-55 ("U CompanyAccountController otvoriti
 * rutu @PostMapping(/createMarginAccount)") definise DTO sa poljima
 * {@code employeeId}, {@code companyId}, {@code InitialMargin},
 * {@code MaitenanceMargin}, {@code BankParticipation}.
 *
 * <p><b>Identitet:</b> {@code employeeId} se NE prihvata iz tela zahteva —
 * identitet zaposlenog razresava se iz JWT-a ({@code TradingUserResolver}).
 * Polje je namerno izostavljeno (server ne sme da veruje klijentu za identitet).
 *
 * <p>{@code accountId} (RSD bazni racun kompanije sa kog se skida pocetni margin
 * depozit) NIJE u spec DTO-u, ali je neophodan da bi se inicirao bank-debit i
 * popunio NOT NULL {@code account_id} u {@code margin_accounts} (mirror
 * user-margin putanje). Validacija IM/MM/BP je identicna eksplicitnoj putanji
 * {@code MarginAccountService.createForUser}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateCompanyMarginAccountDto {

    /**
     * ID RSD baznog racuna kompanije sa kog se skida pocetni margin depozit
     * (Marzni_Racuni.txt §17 — Currency uvek RSD). Mirror user-margin putanje;
     * neophodan za bank-debit + NOT NULL {@code account_id}.
     */
    @NotNull(message = "ID racuna je obavezan")
    private Long accountId;

    /** ID kompanije kojoj marzni racun pripada (Marzni_Racuni.txt §49). */
    @NotNull(message = "ID kompanije je obavezan")
    private Long companyId;

    /** Pocetna margina (stanje na racunu) — zadaje zaposleni. Mora biti > 0. */
    @NotNull(message = "InitialMargin je obavezan")
    @DecimalMin(value = "0.00", message = "InitialMargin ne sme biti negativan")
    private BigDecimal initialMargin;

    /** Maintenance margina — zadaje zaposleni. Validacija: MM <= IM (u servisu). */
    @NotNull(message = "MaintenanceMargin je obavezan")
    @DecimalMin(value = "0.00", message = "MaintenanceMargin ne sme biti negativan")
    private BigDecimal maintenanceMargin;

    /** BankParticipation — zadaje zaposleni. Validni opseg: 0 < BP < 1 (u servisu). */
    @NotNull(message = "BankParticipation je obavezan")
    @DecimalMin(value = "0.0000", message = "BankParticipation ne sme biti negativan")
    private BigDecimal bankParticipation;
}
