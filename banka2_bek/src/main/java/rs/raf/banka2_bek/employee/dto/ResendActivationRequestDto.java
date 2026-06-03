package rs.raf.banka2_bek.employee.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Zahtev za slanje novog aktivacionog linka (Spec Celina 1 Sc 9).
 *
 * <p>Identifikator je stari/istekli aktivacioni token — on jednoznacno vezuje
 * zahtev za konkretnog zaposlenog (preko {@code ActivationToken.employee}),
 * pa ne moramo da radimo email lookup niti da izlazemo email enumeration.
 * To je takodje tacno ono sto FE EXPIRED ekran ima u URL-u.
 */
@Data
public class ResendActivationRequestDto {

    @NotBlank
    private String token;
}
