package rs.raf.banka2_bek.games.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rs.raf.banka2_bek.games.model.GameScore;
import rs.raf.banka2_bek.games.model.GameType;

import java.util.List;
import java.util.Optional;

public interface GameScoreRepository extends JpaRepository<GameScore, Long> {

    /** Top N najboljih score-ova po datom gameType. ORDER BY score DESC. */
    List<GameScore> findByGameTypeOrderByScoreDescCreatedAtAsc(GameType gameType, Pageable pageable);

    /** Personal best — najveci score klijenta za datu igru. */
    @Query("SELECT g FROM GameScore g WHERE g.clientId = :clientId AND g.gameType = :gameType "
            + "ORDER BY g.score DESC")
    List<GameScore> findPersonalBest(@Param("clientId") Long clientId,
                                     @Param("gameType") GameType gameType,
                                     Pageable pageable);

    default Optional<GameScore> findBestForClient(Long clientId, GameType gameType) {
        return findPersonalBest(clientId, gameType, org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst();
    }
}
