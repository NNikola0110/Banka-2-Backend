package rs.raf.trading.pricealert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import rs.raf.trading.pricealert.repository.PriceAlertRepository;
import rs.raf.trading.pricealert.scheduler.PriceAlertScheduler;
import rs.raf.trading.pricealert.service.PriceAlertService;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * [B5 - Cenovni alarmi] Unit testovi za {@link PriceAlertScheduler}.
 *
 * <p>R2-1384: scheduler vise NE cita Listing entitete sam — prosledjuje samo
 * {@code listingId}-eve servisu (sveza cena se cita u servis-tx).
 */
@ExtendWith(MockitoExtension.class)
class PriceAlertSchedulerTest {

    @InjectMocks
    private PriceAlertScheduler scheduler;

    @Mock private PriceAlertRepository alertRepository;
    @Mock private PriceAlertService priceAlertService;

    @Test
    @DisplayName("scanActiveAlerts_noActiveListings_doesNothing")
    void scanActiveAlerts_noActiveListings_doesNothing() {
        when(alertRepository.findDistinctListingIdsByActiveTrue())
                .thenReturn(Collections.emptyList());

        scheduler.scanActiveAlerts();

        verifyNoInteractions(priceAlertService);
    }

    @Test
    @DisplayName("scanActiveAlerts_delegatesListingIdsToService")
    void scanActiveAlerts_delegatesListingIdsToService() {
        List<Long> ids = List.of(10L, 20L);
        when(alertRepository.findDistinctListingIdsByActiveTrue()).thenReturn(ids);
        when(priceAlertService.checkAlertsForListings(ids)).thenReturn(2);

        scheduler.scanActiveAlerts();

        // R2-1384: scheduler prosledjuje ID-eve; servis cita svezu cenu u svojoj tx.
        verify(priceAlertService, times(1)).checkAlertsForListings(ids);
    }

    @Test
    @DisplayName("scanActiveAlerts_repositoryThrows_doesNotPropagate")
    void scanActiveAlerts_repositoryThrows_doesNotPropagate() {
        when(alertRepository.findDistinctListingIdsByActiveTrue())
                .thenThrow(new RuntimeException("DB down"));

        // Ne sme da baci — scheduler mora da preživi
        scheduler.scanActiveAlerts();

        verifyNoInteractions(priceAlertService);
    }

    @Test
    @DisplayName("scanActiveAlerts_serviceThrows_doesNotPropagate")
    void scanActiveAlerts_serviceThrows_doesNotPropagate() {
        when(alertRepository.findDistinctListingIdsByActiveTrue()).thenReturn(List.of(10L));
        when(priceAlertService.checkAlertsForListings(List.of(10L)))
                .thenThrow(new RuntimeException("oops"));

        scheduler.scanActiveAlerts();

        verify(priceAlertService).checkAlertsForListings(List.of(10L));
    }

    @Test
    @DisplayName("R1 512: scanActiveAlerts koristi fixedDelay (ne fixedRate) — bez preklapajucih instanci")
    void scanActiveAlerts_usesFixedDelayNotFixedRate() throws Exception {
        Method m = PriceAlertScheduler.class.getMethod("scanActiveAlerts");
        Scheduled scheduled = m.getAnnotation(Scheduled.class);

        assertThat(scheduled).as("scanActiveAlerts mora imati @Scheduled").isNotNull();
        // fixedDelayString postavljen → fixedDelay() literal je default -1 (koristi string).
        assertThat(scheduled.fixedDelayString())
                .as("mora koristiti fixedDelay (fixedDelayString), ne fixedRate")
                .isNotBlank();
        assertThat(scheduled.fixedRate())
                .as("NE sme koristiti fixedRate (preklapajuci ciklusi)").isEqualTo(-1L);
        assertThat(scheduled.fixedRateString())
                .as("NE sme koristiti fixedRateString").isEmpty();
    }
}
