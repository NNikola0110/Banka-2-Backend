package rs.raf.banka2_bek.games.service.implementation;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.games.dto.GameScoreDto;
import rs.raf.banka2_bek.games.dto.SubmitScoreDto;
import rs.raf.banka2_bek.games.model.GameScore;
import rs.raf.banka2_bek.games.model.GameType;
import rs.raf.banka2_bek.games.repository.GameScoreRepository;
import rs.raf.banka2_bek.games.service.GameScoreService;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class GameScoreServiceImpl implements GameScoreService {

    private final GameScoreRepository gameScoreRepository;
    private final ClientRepository clientRepository;

    @Override
    @Transactional
    public GameScoreDto submitScore(SubmitScoreDto dto) {
        Client client = getAuthenticatedClient();
        String playerName = client.getFirstName() + " " + client.getLastName();
        GameScore score = GameScore.builder()
                .clientId(client.getId())
                .playerName(playerName)
                .gameType(dto.getGameType())
                .score(dto.getScore())
                .build();
        return toDto(gameScoreRepository.save(score), null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GameScoreDto> getLeaderboard(GameType gameType, int limit) {
        int normalizedLimit = limit <= 0 || limit > 100 ? 10 : limit;
        List<GameScore> top = gameScoreRepository.findByGameTypeOrderByScoreDescCreatedAtAsc(
                gameType, PageRequest.of(0, normalizedLimit));
        AtomicInteger rank = new AtomicInteger(1);
        return top.stream()
                .map(s -> toDto(s, rank.getAndIncrement()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GameScoreDto getMyBest(GameType gameType) {
        Client client = getAuthenticatedClient();
        return gameScoreRepository.findBestForClient(client.getId(), gameType)
                .map(s -> toDto(s, null))
                .orElseGet(() -> GameScoreDto.builder()
                        .gameType(gameType)
                        .score(0L)
                        .playerName(client.getFirstName() + " " + client.getLastName())
                        .clientId(client.getId())
                        .build());
    }

    private GameScoreDto toDto(GameScore s, Integer rank) {
        return GameScoreDto.builder()
                .id(s.getId())
                .clientId(s.getClientId())
                .playerName(s.getPlayerName())
                .gameType(s.getGameType())
                .score(s.getScore())
                .createdAt(s.getCreatedAt())
                .rank(rank)
                .build();
    }

    private Client getAuthenticatedClient() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("Korisnik nije autentifikovan.");
        }
        String email;
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails ud) {
            email = ud.getUsername();
        } else {
            email = principal.toString();
        }
        return clientRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Klijent nije pronadjen za score submission."));
    }
}
