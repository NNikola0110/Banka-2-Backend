package rs.raf.banka2_bek.account.dto;

import lombok.Data;

/**
 * R1-625: nested company put u {@code CreateAccountDto}. ACCEPTED dual-put —
 * {@code AccountServiceImplementation.createAccount} prihvata I flat polja
 * ({@code companyName}/{@code registrationNumber}/...) I ovaj nested objekat
 * (vidi {@code request.getCompany() != null ? ... : flat} granjanje). FE trenutno
 * salje flat polja (CreateAccountPage.tsx), ali nested put se zadrzava radi
 * Mobile/API klijenata i backwards-kompatibilnosti. Konsolidacija na jedan put
 * je breaking contract change kroz FE+Mobile — namerno ostaje dual.
 */
@Data
public class CreateAccountCompanyDto {
    private String name;
    private String registrationNumber;
    private String taxNumber;
    private String activityCode;
    private String address;
}
