package rs.raf.trading.recurringorder.dto;

// ============================================================
// TODO [B8 - Trajni nalozi (DCA / RecurringOrder) | Nosilac: Nikola Djurovic]
//
// DTO koji klijent salje pri kreiranju novog trajnog naloga (POST /recurring-orders).
//
// IMPLEMENTIRATI — dodati sva polja sa Jakarta Validation anotacijama:
//   - @NotNull Long listingId
//       -> ID hartije od vrednosti koja ce biti predmet naloga
//   - @NotBlank @Pattern(regexp="BUY|SELL") String direction
//       -> smer naloga; validacija na nivou DTO-a sprjecava pogresne vrednosti
//   - @NotNull rs.raf.trading.recurringorder.model.RecurringMode mode
//       -> nacin definisanja iznosa (BY_QUANTITY ili BY_AMOUNT)
//   - @NotNull @DecimalMin("0.0001") java.math.BigDecimal value
//       -> kolicina (BY_QUANTITY >= 1 akcija) ili iznos u valuti racuna (BY_AMOUNT >= 0.01);
//          dodatna semanticka validacija raditi u service sloju
//   - @NotNull Long accountId
//       -> racun s kojeg se skida iznos; service verifikuje vlasnistvo
//   - @NotNull rs.raf.trading.recurringorder.model.RecurringCadence cadence
//       -> ucestalost izvrsavanja (DAILY / WEEKLY / MONTHLY)
//   - java.time.LocalDateTime firstRun (opciono, moze biti null)
//       -> ako je null, service postavi nextRun = LocalDateTime.now() + 1 cadence
//          (tj. prvi run desava se sutra/sledece nedelje/sledeceg meseca);
//          ako je postavljeno, mora biti u buducnosti
//
// Koristiti Lombok @Data konzistentno sa ostalim DTO klasama u projektu.
// Dodati import jakarta.validation.constraints.* za anotacije.
//
// Konvencija: pratiti paket `savings` kao sablon.
// Spec: Zadaci_Backend.pdf, zadatak B8.
// ============================================================
public class CreateRecurringOrderDto {
}
