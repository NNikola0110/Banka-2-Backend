package rs.raf.banka2_bek.account.dto;

import rs.raf.banka2_bek.company.dto.CompanyDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountResponseDto {
    private Long id;

    private String accountNumber;
    private String accountType;
    private String accountSubType;
    private String name;
    private String status;

    // vlasnik
    private String ownerName;

    // stanja
    private BigDecimal availableBalance;
    private BigDecimal balance;
    private BigDecimal reservedFunds;

    // R1-628: ACCEPTED dupli izlaz — `currency` je NAMERAN alias za `currencyCode`.
    // FE (50+ komponenti) cita `currency`, a `currencyCode` je zadrzan radi
    // backwards-kompatibilnosti starijih klijenata. Oba uvek nose istu vrednost
    // (vidi toResponse: .currencyCode(code).currency(code)). Uklanjanje bilo kog
    // je breaking contract change kroz ceo FE — namerno se ne konsoliduje ovde.
    private String currencyCode;
    private String currency; // alias za currencyCode — FE koristi ovo polje

    // limiti
    private BigDecimal dailyLimit;
    private BigDecimal monthlyLimit;

    // potrosnja
    private BigDecimal dailySpending;
    private BigDecimal monthlySpending;

    // naknada
    private BigDecimal maintenanceFee;

    private LocalDate expirationDate;
    private LocalDateTime createdAt;

    // zaposleni koji je kreirao
    private String createdByEmployee;

    // podaci o firmi (samo za poslovne racune, inace null)
    private CompanyDto company;

}
