package rs.raf.trading.pricealert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.trading.pricealert.repository.PriceAlertRepository;
import rs.raf.trading.pricealert.scheduler.PriceAlertScheduler;
import rs.raf.trading.pricealert.service.PriceAlertService;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * [B5 - Cenovni alarmi] Unit testovi za {@link PriceAlertScheduler}.
 */
@ExtendWith(MockitoExtension.class)
class PriceAlertSchedulerTest {

    @InjectMocks
    private PriceAlertScheduler scheduler;

    @Mock private PriceAlertRepository alertRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private PriceAlertService priceAlertService;

    @Test
    @DisplayName("scanActiveAlerts_noActiveListings_doesNothing")
    void scanActiveAlerts_noActiveListings_doesNothing() {
        when(alertRepository.findDistinctListingIdsByActiveTrue())
                .thenReturn(Collections.emptyList());

        scheduler.scanActiveAlerts();

        verifyNoInteractions(listingRepository);
        verifyNoInteractions(priceAlertService);
    }

    @Test
    @DisplayName("scanActiveAlerts_fetchesListingsAndDelegatesToService")
    void scanActiveAlerts_fetchesListingsAndDelegatesToService() {
        List<Long> ids = List.of(10L, 20L);
        when(alertRepository.findDistinctListingIdsByActiveTrue()).thenReturn(ids);

        Listing l1 = new Listing();
        l1.setId(10L);
        l1.setTicker("AAPL");
        l1.setListingType(ListingType.STOCK);
        l1.setPrice(new BigDecimal("160"));
        Listing l2 = new Listing();
        l2.setId(20L);
        l2.setTicker("MSFT");
        l2.setListingType(ListingType.STOCK);
        l2.setPrice(new BigDecimal("250"));
        when(listingRepository.findAllById(ids)).thenReturn(List.of(l1, l2));
        when(priceAlertService.checkAlerts(List.of(l1, l2))).thenReturn(2);

        scheduler.scanActiveAlerts();

        ArgumentCaptor<List<Listing>> captor = ArgumentCaptor.forClass(List.class);
        verify(priceAlertService, times(1)).checkAlerts(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("scanActiveAlerts_repositoryThrows_doesNotPropagate")
    void scanActiveAlerts_repositoryThrows_doesNotPropagate() {
        when(alertRepository.findDistinctListingIdsByActiveTrue())
                .thenThrow(new RuntimeException("DB down"));

        // Ne sme da baci — scheduler mora da preživi
        scheduler.scanActiveAlerts();

        verifyNoInteractions(listingRepository);
        verifyNoInteractions(priceAlertService);
    }

    @Test
    @DisplayName("scanActiveAlerts_serviceThrows_doesNotPropagate")
    void scanActiveAlerts_serviceThrows_doesNotPropagate() {
        when(alertRepository.findDistinctListingIdsByActiveTrue()).thenReturn(List.of(10L));
        Listing l1 = new Listing();
        l1.setId(10L);
        when(listingRepository.findAllById(List.of(10L))).thenReturn(List.of(l1));
        when(priceAlertService.checkAlerts(List.of(l1))).thenThrow(new RuntimeException("oops"));

        scheduler.scanActiveAlerts();

        verify(priceAlertService).checkAlerts(List.of(l1));
    }
}
