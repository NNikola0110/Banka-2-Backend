package rs.raf.banka2_bek.card.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.card.service.CardService;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R1 317 — unit testovi za {@link CardExpiryScheduler} (delegacija + prezivljavanje greske).
 */
@ExtendWith(MockitoExtension.class)
class CardExpirySchedulerTest {

    @Mock private CardService cardService;

    @InjectMocks private CardExpiryScheduler scheduler;

    @Test
    @DisplayName("deactivateExpiredCards delegira na CardService.expireDueCards sa danasnjim datumom")
    void delegatesToServiceWithToday() {
        when(cardService.expireDueCards(any(LocalDate.class))).thenReturn(3);

        scheduler.deactivateExpiredCards();

        verify(cardService, times(1)).expireDueCards(any(LocalDate.class));
    }

    @Test
    @DisplayName("greska u servisu se proguta (scheduler prezivljava)")
    void serviceThrows_doesNotPropagate() {
        when(cardService.expireDueCards(any(LocalDate.class)))
                .thenThrow(new RuntimeException("DB down"));

        // Ne sme da baci.
        scheduler.deactivateExpiredCards();

        verify(cardService).expireDueCards(any(LocalDate.class));
    }
}
