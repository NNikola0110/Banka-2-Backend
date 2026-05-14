package rs.raf.banka2_bek.games.service;

import rs.raf.banka2_bek.games.dto.GameScoreDto;
import rs.raf.banka2_bek.games.dto.SubmitScoreDto;
import rs.raf.banka2_bek.games.model.GameType;

import java.util.List;

public interface GameScoreService {

    /**
     * Submit-uje score za autentifikovanog klijenta. Player name se snapshotuje
     * iz Client entitea radi brzine leaderboard query-ja (bez JOIN-a).
     */
    GameScoreDto submitScore(SubmitScoreDto dto);

    /**
     * Top 10 score-ova za datu igru (highest score wins; tie-break po
     * createdAt ASC — raniji submit dobija visi rank).
     */
    List<GameScoreDto> getLeaderboard(GameType gameType, int limit);

    /**
     * Personal best score autentifikovanog klijenta za datu igru.
     * Vraca null DTO sa score=0 ako klijent nije igrao igru.
     */
    GameScoreDto getMyBest(GameType gameType);
}
