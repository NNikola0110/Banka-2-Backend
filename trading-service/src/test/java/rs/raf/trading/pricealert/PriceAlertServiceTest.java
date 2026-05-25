package rs.raf.trading.pricealert;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import rs.raf.trading.common.UserContext;
import rs.raf.trading.common.UserRole;
import rs.raf.trading.notification.model.NotificationType;
import rs.raf.trading.notification.service.NotificationService;
import rs.raf.trading.pricealert.dto.CreatePriceAlertDto;
import rs.raf.trading.pricealert.dto.PriceAlertDto;
import rs.raf.trading.pricealert.model.PriceAlert;
import rs.raf.trading.pricealert.model.PriceAlertCondition;
import rs.raf.trading.pricealert.repository.PriceAlertRepository;
import rs.raf.trading.pricealert.service.PriceAlertService;
import rs.raf.trading.security.TradingUserResolver;
import rs.raf.trading.stock.model.Listing;
import rs.raf.trading.stock.model.ListingType;
import rs.raf.trading.stock.repository.ListingRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * [B5 - Cenovni alarmi] Unit testovi za {@link PriceAlertService}.
 * Mockito strict stubs + JUnit 5; bez SpringBoot konteksta.
 */
@ExtendWith(MockitoExtension.class)
class PriceAlertServiceTest {

    @InjectMocks
    private PriceAlertService priceAlertService;

    @Mock private PriceAlertRepository alertRepository;
    @Mock private ListingRepository listingRepository;
    @Mock private TradingUserResolver userResolver;
    @Mock private NotificationService notificationService;

    private final UserContext clientCtx = new UserContext(42L, UserRole.CLIENT);
    private final UserContext employeeCtx = new UserContext(7L, UserRole.EMPLOYEE);

    private Listing aapl;

    @BeforeEach
    void setUp() {
        aapl = new Listing();
        aapl.setId(10L);
        aapl.setTicker("AAPL");
        aapl.setName("Apple Inc.");
        aapl.setListingType(ListingType.STOCK);
        aapl.setPrice(new BigDecimal("150.00"));
    }

    // ---------- createAlert ----------

    @Test
    @DisplayName("createAlert_validRequest_persistsAndReturnsDto")
    void createAlert_validRequest_persistsAndReturnsDto() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        when(listingRepository.findById(10L)).thenReturn(Optional.of(aapl));
        when(alertRepository.findByOwnerIdAndOwnerTypeAndListingIdAndConditionAndActiveTrue(
                42L, UserRole.CLIENT, 10L, PriceAlertCondition.ABOVE))
                .thenReturn(Optional.empty());

        PriceAlert saved = PriceAlert.builder()
                .id(100L)
                .ownerId(42L)
                .ownerType(UserRole.CLIENT)
                .listingId(10L)
                .condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("160.00"))
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
        when(alertRepository.save(any(PriceAlert.class))).thenReturn(saved);

        CreatePriceAlertDto dto = CreatePriceAlertDto.builder()
                .listingId(10L)
                .condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("160.00"))
                .build();

        PriceAlertDto res = priceAlertService.createAlert(dto);

        assertThat(res.getId()).isEqualTo(100L);
        assertThat(res.getActive()).isTrue();
        assertThat(res.getCondition()).isEqualTo("ABOVE");
        assertThat(res.getListingTicker()).isEqualTo("AAPL");
        assertThat(res.getListingType()).isEqualTo("STOCK");
        verify(alertRepository, times(1)).save(any(PriceAlert.class));
    }

    @Test
    @DisplayName("createAlert_listingNotFound_throwsEntityNotFound")
    void createAlert_listingNotFound_throwsEntityNotFound() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        when(listingRepository.findById(99L)).thenReturn(Optional.empty());

        CreatePriceAlertDto dto = CreatePriceAlertDto.builder()
                .listingId(99L)
                .condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("100.00"))
                .build();

        assertThatThrownBy(() -> priceAlertService.createAlert(dto))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("99");
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAlert_thresholdZero_throwsIllegalArgument")
    void createAlert_thresholdZero_throwsIllegalArgument() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);

        CreatePriceAlertDto dto = CreatePriceAlertDto.builder()
                .listingId(10L)
                .condition(PriceAlertCondition.ABOVE)
                .threshold(BigDecimal.ZERO)
                .build();

        assertThatThrownBy(() -> priceAlertService.createAlert(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAlert_thresholdNegative_throwsIllegalArgument")
    void createAlert_thresholdNegative_throwsIllegalArgument() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);

        CreatePriceAlertDto dto = CreatePriceAlertDto.builder()
                .listingId(10L)
                .condition(PriceAlertCondition.BELOW)
                .threshold(new BigDecimal("-1.00"))
                .build();

        assertThatThrownBy(() -> priceAlertService.createAlert(dto))
                .isInstanceOf(IllegalArgumentException.class);
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAlert_duplicateActiveSameCondition_throwsIllegalArgument")
    void createAlert_duplicateActiveSameCondition_throwsIllegalArgument() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        when(listingRepository.findById(10L)).thenReturn(Optional.of(aapl));
        PriceAlert existing = PriceAlert.builder().id(55L).build();
        when(alertRepository.findByOwnerIdAndOwnerTypeAndListingIdAndConditionAndActiveTrue(
                42L, UserRole.CLIENT, 10L, PriceAlertCondition.ABOVE))
                .thenReturn(Optional.of(existing));

        CreatePriceAlertDto dto = CreatePriceAlertDto.builder()
                .listingId(10L)
                .condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("160.00"))
                .build();

        assertThatThrownBy(() -> priceAlertService.createAlert(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vec postoji");
        verify(alertRepository, never()).save(any());
    }

    // ---------- listMyAlerts ----------

    @Test
    @DisplayName("listMyAlerts_noFilter_returnsAllSortedDesc")
    void listMyAlerts_noFilter_returnsAllSortedDesc() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);

        PriceAlert a1 = PriceAlert.builder().id(1L).ownerId(42L).ownerType(UserRole.CLIENT)
                .listingId(10L).condition(PriceAlertCondition.ABOVE).threshold(new BigDecimal("160"))
                .active(true).createdAt(LocalDateTime.now()).build();
        PriceAlert a2 = PriceAlert.builder().id(2L).ownerId(42L).ownerType(UserRole.CLIENT)
                .listingId(10L).condition(PriceAlertCondition.BELOW).threshold(new BigDecimal("140"))
                .active(false).createdAt(LocalDateTime.now().minusHours(1)).build();

        when(alertRepository.findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(42L, UserRole.CLIENT))
                .thenReturn(Arrays.asList(a1, a2));
        when(listingRepository.findAllById(any())).thenReturn(List.of(aapl));

        List<PriceAlertDto> res = priceAlertService.listMyAlerts(null);

        assertThat(res).hasSize(2);
        assertThat(res.get(0).getId()).isEqualTo(1L);
        assertThat(res.get(1).getId()).isEqualTo(2L);
        // Ticker popunjen iz batch lookup-a
        assertThat(res.get(0).getListingTicker()).isEqualTo("AAPL");
    }

    @Test
    @DisplayName("listMyAlerts_activeFilterTrue_returnsOnlyActive")
    void listMyAlerts_activeFilterTrue_returnsOnlyActive() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);

        PriceAlert a1 = PriceAlert.builder().id(1L).ownerId(42L).ownerType(UserRole.CLIENT)
                .listingId(10L).condition(PriceAlertCondition.ABOVE).threshold(new BigDecimal("160"))
                .active(true).createdAt(LocalDateTime.now()).build();

        when(alertRepository.findByOwnerIdAndOwnerTypeAndActiveOrderByCreatedAtDesc(
                42L, UserRole.CLIENT, true))
                .thenReturn(List.of(a1));
        when(listingRepository.findAllById(any())).thenReturn(List.of(aapl));

        List<PriceAlertDto> res = priceAlertService.listMyAlerts(true);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).getActive()).isTrue();
        verify(alertRepository).findByOwnerIdAndOwnerTypeAndActiveOrderByCreatedAtDesc(
                42L, UserRole.CLIENT, true);
    }

    @Test
    @DisplayName("listMyAlerts_emptyResult_returnsEmptyList")
    void listMyAlerts_emptyResult_returnsEmptyList() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        when(alertRepository.findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(42L, UserRole.CLIENT))
                .thenReturn(Collections.emptyList());

        List<PriceAlertDto> res = priceAlertService.listMyAlerts(null);

        assertThat(res).isEmpty();
        verify(listingRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("listMyAlerts_employeeContext_usesEmployeeOwnerType")
    void listMyAlerts_employeeContext_usesEmployeeOwnerType() {
        when(userResolver.resolveCurrent()).thenReturn(employeeCtx);
        when(alertRepository.findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(7L, UserRole.EMPLOYEE))
                .thenReturn(Collections.emptyList());

        priceAlertService.listMyAlerts(null);

        verify(alertRepository).findByOwnerIdAndOwnerTypeOrderByCreatedAtDesc(7L, UserRole.EMPLOYEE);
    }

    // ---------- deleteAlert ----------

    @Test
    @DisplayName("deleteAlert_ownerDeletes_callsDelete")
    void deleteAlert_ownerDeletes_callsDelete() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        PriceAlert alert = PriceAlert.builder()
                .id(50L).ownerId(42L).ownerType(UserRole.CLIENT)
                .listingId(10L).condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("160")).active(true).build();
        when(alertRepository.findById(50L)).thenReturn(Optional.of(alert));

        priceAlertService.deleteAlert(50L);

        verify(alertRepository, times(1)).delete(alert);
    }

    @Test
    @DisplayName("deleteAlert_ownershipMismatchOwnerId_throwsAccessDenied")
    void deleteAlert_ownershipMismatchOwnerId_throwsAccessDenied() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        PriceAlert alert = PriceAlert.builder()
                .id(50L).ownerId(99L).ownerType(UserRole.CLIENT) // drugi vlasnik
                .build();
        when(alertRepository.findById(50L)).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> priceAlertService.deleteAlert(50L))
                .isInstanceOf(AccessDeniedException.class);
        verify(alertRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteAlert_ownershipMismatchOwnerType_throwsAccessDenied")
    void deleteAlert_ownershipMismatchOwnerType_throwsAccessDenied() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx); // CLIENT
        PriceAlert alert = PriceAlert.builder()
                .id(50L).ownerId(42L).ownerType(UserRole.EMPLOYEE) // ali EMPLOYEE
                .build();
        when(alertRepository.findById(50L)).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> priceAlertService.deleteAlert(50L))
                .isInstanceOf(AccessDeniedException.class);
        verify(alertRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteAlert_notFound_throwsEntityNotFound")
    void deleteAlert_notFound_throwsEntityNotFound() {
        when(userResolver.resolveCurrent()).thenReturn(clientCtx);
        when(alertRepository.findById(123L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> priceAlertService.deleteAlert(123L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---------- checkAlerts ----------

    @Test
    @DisplayName("checkAlerts_aboveTriggersWhenPriceAboveThreshold")
    void checkAlerts_aboveTriggersWhenPriceAboveThreshold() {
        aapl.setPrice(new BigDecimal("160.00"));
        PriceAlert alert = PriceAlert.builder()
                .id(1L).ownerId(42L).ownerType(UserRole.CLIENT)
                .listingId(10L).condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("150.00")).active(true).build();
        when(alertRepository.findByActiveTrueAndListingIdIn(List.of(10L)))
                .thenReturn(List.of(alert));

        int triggered = priceAlertService.checkAlerts(List.of(aapl));

        assertThat(triggered).isEqualTo(1);
        ArgumentCaptor<PriceAlert> savedCaptor = ArgumentCaptor.forClass(PriceAlert.class);
        verify(alertRepository).save(savedCaptor.capture());
        PriceAlert saved = savedCaptor.getValue();
        assertThat(saved.getActive()).isFalse();
        assertThat(saved.getTriggeredAt()).isNotNull();
        verify(notificationService, times(1)).notify(
                eq(42L), eq(UserRole.CLIENT),
                eq(NotificationType.PRICE_ALERT_TRIGGERED),
                anyString(), anyString(), eq("PRICE_ALERT"), eq(1L));
    }

    @Test
    @DisplayName("checkAlerts_belowTriggersWhenPriceBelowThreshold")
    void checkAlerts_belowTriggersWhenPriceBelowThreshold() {
        aapl.setPrice(new BigDecimal("90.00"));
        PriceAlert alert = PriceAlert.builder()
                .id(2L).ownerId(42L).ownerType(UserRole.CLIENT)
                .listingId(10L).condition(PriceAlertCondition.BELOW)
                .threshold(new BigDecimal("100.00")).active(true).build();
        when(alertRepository.findByActiveTrueAndListingIdIn(List.of(10L)))
                .thenReturn(List.of(alert));

        int triggered = priceAlertService.checkAlerts(List.of(aapl));

        assertThat(triggered).isEqualTo(1);
        verify(notificationService).notify(eq(42L), eq(UserRole.CLIENT),
                eq(NotificationType.PRICE_ALERT_TRIGGERED),
                anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("checkAlerts_priceEqualToThresholdAbove_triggers")
    void checkAlerts_priceEqualToThresholdAbove_triggers() {
        aapl.setPrice(new BigDecimal("150.00"));
        PriceAlert alert = PriceAlert.builder()
                .id(3L).ownerId(42L).ownerType(UserRole.CLIENT)
                .listingId(10L).condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("150.00")).active(true).build();
        when(alertRepository.findByActiveTrueAndListingIdIn(List.of(10L)))
                .thenReturn(List.of(alert));

        int triggered = priceAlertService.checkAlerts(List.of(aapl));

        // >= za ABOVE okida i pri jednakim vrednostima
        assertThat(triggered).isEqualTo(1);
    }

    @Test
    @DisplayName("checkAlerts_aboveDoesNotTriggerWhenPriceBelowThreshold")
    void checkAlerts_aboveDoesNotTriggerWhenPriceBelowThreshold() {
        aapl.setPrice(new BigDecimal("140.00"));
        PriceAlert alert = PriceAlert.builder()
                .id(4L).ownerId(42L).ownerType(UserRole.CLIENT)
                .listingId(10L).condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("150.00")).active(true).build();
        when(alertRepository.findByActiveTrueAndListingIdIn(List.of(10L)))
                .thenReturn(List.of(alert));

        int triggered = priceAlertService.checkAlerts(List.of(aapl));

        assertThat(triggered).isEqualTo(0);
        verify(alertRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("checkAlerts_emptyInput_returnsZero")
    void checkAlerts_emptyInput_returnsZero() {
        int triggered = priceAlertService.checkAlerts(Collections.emptyList());

        assertThat(triggered).isEqualTo(0);
        verifyNoInteractions(alertRepository);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("checkAlerts_nullInput_returnsZero")
    void checkAlerts_nullInput_returnsZero() {
        int triggered = priceAlertService.checkAlerts(null);

        assertThat(triggered).isEqualTo(0);
        verifyNoInteractions(alertRepository);
    }

    @Test
    @DisplayName("checkAlerts_skipsAlertsForListingsNotInInput")
    void checkAlerts_skipsAlertsForListingsNotInInput() {
        // Repo vraca alarm za listing 20 (van input liste — neocekivano, ali resilient handling)
        aapl.setPrice(new BigDecimal("160.00"));
        PriceAlert alertOnOther = PriceAlert.builder()
                .id(5L).ownerId(42L).ownerType(UserRole.CLIENT)
                .listingId(20L).condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("50.00")).active(true).build();
        when(alertRepository.findByActiveTrueAndListingIdIn(List.of(10L)))
                .thenReturn(List.of(alertOnOther));

        int triggered = priceAlertService.checkAlerts(List.of(aapl));

        assertThat(triggered).isEqualTo(0);
        verify(alertRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("checkAlerts_priceNull_doesNotTrigger")
    void checkAlerts_priceNull_doesNotTrigger() {
        aapl.setPrice(null);
        PriceAlert alert = PriceAlert.builder()
                .id(6L).ownerId(42L).ownerType(UserRole.CLIENT)
                .listingId(10L).condition(PriceAlertCondition.ABOVE)
                .threshold(new BigDecimal("150.00")).active(true).build();
        when(alertRepository.findByActiveTrueAndListingIdIn(List.of(10L)))
                .thenReturn(List.of(alert));

        int triggered = priceAlertService.checkAlerts(List.of(aapl));

        assertThat(triggered).isEqualTo(0);
        verify(alertRepository, never()).save(any());
    }
}
