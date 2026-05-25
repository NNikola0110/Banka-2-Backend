package rs.raf.trading.watchlist.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatchlistDto {

    private Long id;
    private Long ownerId;
    private String ownerType;
    private String name;
    private int itemCount;
    private LocalDateTime createdAt;
}