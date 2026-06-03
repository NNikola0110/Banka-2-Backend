package rs.raf.trading.margin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO za prikaz margin racuna.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarginAccountDto {

    private Long id;
    private Long accountId;
    private String accountNumber;
    private Long userId;
    /**
     * BE-STK-06: ID kompanije za COMPANY marzni racun (Marzni_Racuni.txt §65).
     * Null za USER marzni racun (tada je {@code userId} popunjen).
     */
    private Long companyId;
    private BigDecimal initialMargin;
    private BigDecimal loanValue;
    private BigDecimal maintenanceMargin;
    private BigDecimal bankParticipation;
    private String status;
    private LocalDateTime createdAt;
}
