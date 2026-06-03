package rs.raf.banka2_bek.interbank.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.interbank.service.OtcNegotiationService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-B6 (Nalaz 1) — scheduler delegira na
 * {@link OtcNegotiationService#expireSettledContracts()} i ne propagira greske
 * (jedan sweep-fail ne ruzi naredne run-ove).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterbankOtcContractExpiryScheduler (P0-B6 Nalaz 1)")
class InterbankOtcContractExpirySchedulerTest {

    @Mock private OtcNegotiationService otcNegotiationService;

    private InterbankOtcContractExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new InterbankOtcContractExpiryScheduler(otcNegotiationService);
    }

    @Test
    @DisplayName("expireContracts delegira na OtcNegotiationService.expireSettledContracts()")
    void expireContracts_delegates() {
        when(otcNegotiationService.expireSettledContracts()).thenReturn(3);

        scheduler.expireContracts();

        verify(otcNegotiationService).expireSettledContracts();
    }

    @Test
    @DisplayName("expireContracts ne propagira RuntimeException iz sweep-a")
    void expireContracts_swallowsException() {
        when(otcNegotiationService.expireSettledContracts())
                .thenThrow(new RuntimeException("DB down"));

        assertThatCode(() -> scheduler.expireContracts()).doesNotThrowAnyException();
        verify(otcNegotiationService).expireSettledContracts();
    }
}
