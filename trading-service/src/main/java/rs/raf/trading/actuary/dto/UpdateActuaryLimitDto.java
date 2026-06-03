package rs.raf.trading.actuary.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateActuaryLimitDto {
    // P2-9 (Celina 3 S4): limit od 0 ili negativan se odbija. Pre fix-a je
    // inclusive=true (default) prihvatao 0; sad inclusive=false → 0 i negativan = 400.
    @DecimalMin(value="0", inclusive=false, message="Daily limit must be positive.")
    private BigDecimal dailyLimit;
    private Boolean needApproval;
}
