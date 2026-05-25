package rs.raf.trading.watchlist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchlistItemDto {

    private Long id;
    private Long watchlistId;
    private Long listingId;

    private String ticker;
    private String listingName;
    private String securityType;
    private String exchangeName;

    private BigDecimal currentPrice;
    private BigDecimal dailyChange;
    private Long volume;

    private LocalDateTime addedAt;
}