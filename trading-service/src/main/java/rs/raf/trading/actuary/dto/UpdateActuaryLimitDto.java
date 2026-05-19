package rs.raf.trading.actuary.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateActuaryLimitDto {
    @DecimalMin(value="0", message="Daily limit must be non-negative.")
    private BigDecimal dailyLimit;
    private Boolean needApproval;
}
