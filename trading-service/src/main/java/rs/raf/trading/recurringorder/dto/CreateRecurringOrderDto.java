package rs.raf.trading.recurringorder.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import rs.raf.trading.recurringorder.model.RecurringCadence;
import rs.raf.trading.recurringorder.model.RecurringMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// ============================================================
// [B8 - Trajni nalozi (DCA / RecurringOrder) | Nosilac: Nikola Djurovic] - DONE
//
// DTO koji klijent salje pri kreiranju novog trajnog naloga (POST /recurring-orders).
//
// Spec: Zadaci_Backend.pdf, zadatak B8.
// ============================================================
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateRecurringOrderDto {

    @NotNull(message = "Listing ID je obavezan")
    private Long listingId;

    @NotBlank(message = "Smer je obavezan")
    @Pattern(regexp = "BUY|SELL", message = "Smer mora biti BUY ili SELL")
    private String direction;

    @NotNull(message = "Nacin definisanja iznosa je obavezan")
    private RecurringMode mode;

    @NotNull(message = "Vrednost je obavezna")
    @DecimalMin(value = "0.0001", message = "Vrednost mora biti veca od 0")
    private BigDecimal value;

    @NotNull(message = "Racun je obavezan")
    private Long accountId;

    @NotNull(message = "Ucestalost izvrsavanja je obavezna")
    private RecurringCadence cadence;

    // [P2-input-validation-1 / R1 528] firstRun (ako je zadat) mora biti u
    // buducnosti — proslost je ranije tiho zamenjivana sledecim cadence run-om,
    // sto je krilo korisnicku gresku. @Future validira samo kad je polje != null.
    @Future(message = "firstRun mora biti u buducnosti")
    private LocalDateTime firstRun;
}
