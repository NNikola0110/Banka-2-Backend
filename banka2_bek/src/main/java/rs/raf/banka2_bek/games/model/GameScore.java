package rs.raf.banka2_bek.games.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Score zapis za leaderboard u Sobi za cekanje. Submit-uje se posle Game Over-a
 * (klijent samo svoje score-ove; BE validira ownership).
 *
 * Score semantika zavisi od igre — vidi {@link GameType}. Repo ranking metoda
 * koristi `ORDER BY score DESC` (vise je bolje). Za SOLITAIRE gde manje je bolje,
 * service-strana invertuje pre persist-a (score = MAX_VALUE - moves).
 */
@Entity
@Table(name = "game_scores", indexes = {
        @Index(name = "ix_game_scores_type", columnList = "game_type"),
        @Index(name = "ix_game_scores_client", columnList = "client_id"),
        @Index(name = "ix_game_scores_type_score", columnList = "game_type, score DESC")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /** Snapshot imena klijenta — izbegava JOIN pri leaderboard query-ju. */
    @Column(name = "player_name", nullable = false, length = 100)
    private String playerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", nullable = false, length = 20)
    private GameType gameType;

    @Column(nullable = false)
    private Long score;

    @Column(name = "created_at", nullable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
