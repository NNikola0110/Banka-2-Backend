package rs.raf.trading.pricealert.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * [B5 - Cenovni alarmi] Izlazni DTO jednog cenovnog alarma.
 * {@code listingTicker} i {@code listingType} su denormalizovani — mapper ih puni
 * iz {@code Listing} entiteta da FE ne mora zaseban poziv.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceAlertDto {

    private Long id;

    private Long ownerId;

    private String ownerType;

    private Long listingId;

    private String listingTicker;

    private String listingType;

    private String condition;

    private BigDecimal threshold;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime triggeredAt;
}
