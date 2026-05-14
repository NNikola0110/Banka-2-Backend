package rs.raf.banka2_bek.games.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import rs.raf.banka2_bek.games.model.GameType;

@Data
public class SubmitScoreDto {
    @NotNull
    private GameType gameType;

    @NotNull
    @Min(0)
    private Long score;
}
