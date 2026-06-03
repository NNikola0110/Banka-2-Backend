package rs.raf.trading.berza.seed;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import rs.raf.trading.berza.model.Exchange;
import rs.raf.trading.berza.repository.ExchangeRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeSeedDataTest {

    @Test
    void run_seedsWhenEmpty() {
        ExchangeRepository repo = mock(ExchangeRepository.class);
        // R1-451: per-acronym idempotentan seed — nijedan acronym jos ne postoji.
        when(repo.findByAcronym(anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Exchange.class))).thenAnswer(inv -> inv.getArgument(0));

        new ExchangeSeedData(repo).run(null);

        // Svih 7 definisanih berzi se ubacuje pojedinacno.
        verify(repo, times(7)).save(any(Exchange.class));
    }

    @Test
    void run_seedsForexExchange() {
        // R1-191: FOREX listinzi referenciraju berzu "FOREX" — mora postojati u seed-u.
        ExchangeRepository repo = mock(ExchangeRepository.class);
        when(repo.findByAcronym(anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Exchange.class))).thenAnswer(inv -> inv.getArgument(0));

        new ExchangeSeedData(repo).run(null);

        ArgumentCaptor<Exchange> captor = ArgumentCaptor.forClass(Exchange.class);
        verify(repo, times(7)).save(captor.capture());
        List<Exchange> seeded = captor.getAllValues();

        Exchange forex = seeded.stream()
                .filter(e -> "FOREX".equals(e.getAcronym()))
                .findFirst()
                .orElse(null);
        assertThat(forex).as("FOREX berza mora biti seedovana").isNotNull();
        // 24/5 trziste — efektivno otvoreno radnim danima
        assertThat(forex.getOpenTime()).isEqualTo(java.time.LocalTime.of(0, 0));
        assertThat(forex.getCloseTime()).isEqualTo(java.time.LocalTime.of(23, 59, 59));
    }

    @Test
    void run_skipsAlreadySeededExchanges() {
        // R1-451: per-acronym idempotencija — sve berze vec postoje → nista se ne upisuje.
        ExchangeRepository repo = mock(ExchangeRepository.class);
        when(repo.findByAcronym(anyString()))
                .thenReturn(Optional.of(Exchange.builder().acronym("X").build()));

        new ExchangeSeedData(repo).run(null);

        verify(repo, never()).save(any(Exchange.class));
    }

    @Test
    void run_seedsOnlyMissingAcronyms() {
        // R1-451: ako neke berze vec postoje (npr. NYSE), ubacuju se SAMO one koje fale.
        ExchangeRepository repo = mock(ExchangeRepository.class);
        when(repo.findByAcronym(anyString())).thenReturn(Optional.empty());
        when(repo.findByAcronym("NYSE"))
                .thenReturn(Optional.of(Exchange.builder().acronym("NYSE").build()));
        when(repo.save(any(Exchange.class))).thenAnswer(inv -> inv.getArgument(0));

        new ExchangeSeedData(repo).run(null);

        // 7 definisanih - 1 postojeci (NYSE) = 6 novih.
        ArgumentCaptor<Exchange> captor = ArgumentCaptor.forClass(Exchange.class);
        verify(repo, times(6)).save(captor.capture());
        assertThat(captor.getAllValues())
                .noneMatch(e -> "NYSE".equals(e.getAcronym()));
    }

    @Test
    void run_concurrentSeedDuplicate_swallowsDataIntegrityViolation() {
        // R1-451: multi-replica race — druga replika ubaci isti acronym izmedju
        // find-a i save-a → UNIQUE(acronym) baci DataIntegrityViolation koji svesno
        // gutamo (red vec postoji), seed ne puca.
        ExchangeRepository repo = mock(ExchangeRepository.class);
        when(repo.findByAcronym(anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Exchange.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key acronym"));

        // Ne sme baciti — graceful swallow.
        new ExchangeSeedData(repo).run(null);

        verify(repo, times(7)).save(any(Exchange.class));
    }
}
