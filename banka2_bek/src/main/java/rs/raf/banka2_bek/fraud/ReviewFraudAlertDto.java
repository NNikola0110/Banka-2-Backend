package rs.raf.banka2_bek.fraud;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * [W3-T2] Ulazni DTO za {@code POST /admin/fraud-alerts/{id}/review}.
 *
 * <p>{@code status} mora biti jedna od tri dozvoljene vrednosti — ne dajemo
 * supervizoru moc da unese arbitrary tekst (server bi onda upisao bilo sta
 * u {@code review_status} koju koriste i SQL upiti i FE filteri).
 * {@code note} je opciono za revizora — pamti se kao prefix u {@code reviewedBy}
 * polju (format: "{email} | {note}") da ne menjamo schema (DB-init je
 * upravo postavljen u W2-T3, izbegavamo dodatnu migraciju).
 */
public record ReviewFraudAlertDto(
        @NotBlank
        @Pattern(regexp = "confirmed|false_positive|closed",
                message = "status mora biti: confirmed | false_positive | closed")
        String status,

        @Size(max = 256, message = "note moze biti max 256 karaktera")
        String note
) {
}
