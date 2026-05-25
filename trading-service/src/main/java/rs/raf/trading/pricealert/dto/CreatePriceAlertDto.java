package rs.raf.trading.pricealert.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import rs.raf.trading.pricealert.model.PriceAlertCondition;

import java.math.BigDecimal;

/**
 * [B5 - Cenovni alarmi] Request body za {@code POST /price-alerts}.
 * Sva polja su obavezna; threshold mora biti striktno > 0.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePriceAlertDto {

    @NotNull(message = "listingId je obavezan")
    private Long listingId;

    @NotNull(message = "condition je obavezan (ABOVE ili BELOW)")
    private PriceAlertCondition condition;

    @NotNull(message = "threshold je obavezan")
    @DecimalMin(value = "0.0000000001", message = "threshold mora biti veci od 0")
    private BigDecimal threshold;
}
