package rs.raf.trading.watchlist.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateWatchlistDto {
    @NotBlank(message = "Watchlist name cannot be blank.")
    private String name;
}
