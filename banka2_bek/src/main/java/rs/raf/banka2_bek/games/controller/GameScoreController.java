package rs.raf.banka2_bek.games.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rs.raf.banka2_bek.games.dto.GameScoreDto;
import rs.raf.banka2_bek.games.dto.SubmitScoreDto;
import rs.raf.banka2_bek.games.model.GameType;
import rs.raf.banka2_bek.games.service.GameScoreService;

import java.util.List;

@Tag(name = "Game Scores", description = "Leaderboard za Sobu za cekanje (Dino/Solitaire/Sah/Banka2Rush)")
@RestController
@RequestMapping("/games")
@RequiredArgsConstructor
public class GameScoreController {

    private final GameScoreService gameScoreService;

    @Operation(summary = "Submit score posle Game Over-a")
    @PostMapping("/scores")
    public ResponseEntity<GameScoreDto> submitScore(@Valid @RequestBody SubmitScoreDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(gameScoreService.submitScore(dto));
    }

    @Operation(summary = "Leaderboard za datu igru (top 10 default)")
    @GetMapping("/leaderboard")
    public ResponseEntity<List<GameScoreDto>> getLeaderboard(
            @RequestParam GameType type,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return ResponseEntity.ok(gameScoreService.getLeaderboard(type, limit));
    }

    @Operation(summary = "Personal best za autentifikovanog klijenta")
    @GetMapping("/my-best")
    public ResponseEntity<GameScoreDto> getMyBest(@RequestParam GameType type) {
        return ResponseEntity.ok(gameScoreService.getMyBest(type));
    }
}
