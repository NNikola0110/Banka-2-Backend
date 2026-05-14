package rs.raf.banka2_bek.games.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.games.dto.GameScoreDto;
import rs.raf.banka2_bek.games.dto.SubmitScoreDto;
import rs.raf.banka2_bek.games.model.GameScore;
import rs.raf.banka2_bek.games.model.GameType;
import rs.raf.banka2_bek.games.repository.GameScoreRepository;
import rs.raf.banka2_bek.games.service.implementation.GameScoreServiceImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameScoreServiceImplTest {

    @Mock
    private GameScoreRepository gameScoreRepository;

    @Mock
    private ClientRepository clientRepository;

    @InjectMocks
    private GameScoreServiceImpl service;

    private Client client;

    @BeforeEach
    void setUp() {
        client = Client.builder()
                .id(42L)
                .firstName("Ana")
                .lastName("Anic")
                .email("ana@example.com")
                .build();
        var auth = new UsernamePasswordAuthenticationToken("ana@example.com", "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitScore_persistsWithSnapshotPlayerNameAndAuthenticatedClientId() {
        when(clientRepository.findByEmail("ana@example.com")).thenReturn(Optional.of(client));
        when(gameScoreRepository.save(any(GameScore.class))).thenAnswer(inv -> {
            GameScore g = inv.getArgument(0);
            g.setId(7L);
            g.setCreatedAt(LocalDateTime.now());
            return g;
        });

        SubmitScoreDto req = new SubmitScoreDto();
        req.setGameType(GameType.DINO);
        req.setScore(5000L);
        GameScoreDto dto = service.submitScore(req);

        ArgumentCaptor<GameScore> captor = ArgumentCaptor.forClass(GameScore.class);
        org.mockito.Mockito.verify(gameScoreRepository).save(captor.capture());
        GameScore saved = captor.getValue();
        assertThat(saved.getClientId()).isEqualTo(42L);
        assertThat(saved.getPlayerName()).isEqualTo("Ana Anic");
        assertThat(saved.getGameType()).isEqualTo(GameType.DINO);
        assertThat(saved.getScore()).isEqualTo(5000L);

        assertThat(dto.getId()).isEqualTo(7L);
        assertThat(dto.getRank()).isNull();
    }

    @Test
    void submitScore_throwsWhenClientNotFound() {
        when(clientRepository.findByEmail("ana@example.com")).thenReturn(Optional.empty());

        SubmitScoreDto req = new SubmitScoreDto();
        req.setGameType(GameType.SOLITAIRE);
        req.setScore(100L);
        assertThatThrownBy(() -> service.submitScore(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Klijent");
    }

    @Test
    void getLeaderboard_returnsTopNWithSequentialRanksStartingAt1() {
        GameScore a = scoreOf(1L, "Pera Peric", GameType.DINO, 9000L);
        GameScore b = scoreOf(2L, "Mika Mikic", GameType.DINO, 7500L);
        GameScore c = scoreOf(3L, "Zika Zikic", GameType.DINO, 5000L);
        when(gameScoreRepository.findByGameTypeOrderByScoreDescCreatedAtAsc(
                eq(GameType.DINO), eq(PageRequest.of(0, 5))))
                .thenReturn(List.of(a, b, c));

        List<GameScoreDto> board = service.getLeaderboard(GameType.DINO, 5);

        assertThat(board).hasSize(3);
        assertThat(board.get(0).getRank()).isEqualTo(1);
        assertThat(board.get(0).getScore()).isEqualTo(9000L);
        assertThat(board.get(1).getRank()).isEqualTo(2);
        assertThat(board.get(2).getRank()).isEqualTo(3);
    }

    @Test
    void getLeaderboard_normalizesInvalidLimitTo10() {
        when(gameScoreRepository.findByGameTypeOrderByScoreDescCreatedAtAsc(
                eq(GameType.BANKA2_RUSH), eq(PageRequest.of(0, 10))))
                .thenReturn(List.of());

        List<GameScoreDto> board = service.getLeaderboard(GameType.BANKA2_RUSH, 0);
        assertThat(board).isEmpty();

        service.getLeaderboard(GameType.BANKA2_RUSH, -5);
        service.getLeaderboard(GameType.BANKA2_RUSH, 500);
        org.mockito.Mockito.verify(gameScoreRepository, org.mockito.Mockito.times(3))
                .findByGameTypeOrderByScoreDescCreatedAtAsc(eq(GameType.BANKA2_RUSH), eq(PageRequest.of(0, 10)));
    }

    @Test
    void getMyBest_returnsPersonalBestWhenExists() {
        when(clientRepository.findByEmail("ana@example.com")).thenReturn(Optional.of(client));
        GameScore best = scoreOf(11L, "Ana Anic", GameType.CHESS, 1500L);
        when(gameScoreRepository.findBestForClient(eq(42L), eq(GameType.CHESS)))
                .thenReturn(Optional.of(best));

        GameScoreDto dto = service.getMyBest(GameType.CHESS);

        assertThat(dto.getScore()).isEqualTo(1500L);
        assertThat(dto.getId()).isEqualTo(11L);
        assertThat(dto.getPlayerName()).isEqualTo("Ana Anic");
    }

    @Test
    void getMyBest_returnsEmptyDtoWithZeroScoreWhenNoRecord() {
        when(clientRepository.findByEmail("ana@example.com")).thenReturn(Optional.of(client));
        when(gameScoreRepository.findBestForClient(eq(42L), eq(GameType.SOLITAIRE)))
                .thenReturn(Optional.empty());

        GameScoreDto dto = service.getMyBest(GameType.SOLITAIRE);

        assertThat(dto.getId()).isNull();
        assertThat(dto.getScore()).isEqualTo(0L);
        assertThat(dto.getClientId()).isEqualTo(42L);
        assertThat(dto.getPlayerName()).isEqualTo("Ana Anic");
        assertThat(dto.getGameType()).isEqualTo(GameType.SOLITAIRE);
    }

    private GameScore scoreOf(Long id, String name, GameType type, Long s) {
        return GameScore.builder()
                .id(id).clientId(99L).playerName(name).gameType(type).score(s)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
