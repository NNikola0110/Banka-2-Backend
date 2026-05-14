package rs.raf.banka2_bek.games.dto;

import lombok.Builder;
import lombok.Data;
import rs.raf.banka2_bek.games.model.GameType;

import java.time.LocalDateTime;

@Data
@Builder
public class GameScoreDto {
    private Long id;
    private Long clientId;
    private String playerName;
    private GameType gameType;
    private Long score;
    private LocalDateTime createdAt;
    /** Rang u leaderboard-u (popunjen pri leaderboard query-ju; inace null). */
    private Integer rank;
}
