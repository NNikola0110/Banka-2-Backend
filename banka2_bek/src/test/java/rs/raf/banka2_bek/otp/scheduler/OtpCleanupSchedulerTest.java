package rs.raf.banka2_bek.otp.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rs.raf.banka2_bek.otp.repository.OtpConsumedCodeRepository;
import rs.raf.banka2_bek.otp.repository.OtpVerificationRepository;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpCleanupSchedulerTest {

    @Mock
    private OtpVerificationRepository otpVerificationRepository;

    @Mock
    private OtpConsumedCodeRepository otpConsumedCodeRepository;

    @InjectMocks
    private OtpCleanupScheduler otpCleanupScheduler;

    @Nested
    @DisplayName("cleanupExpiredOtps")
    class CleanupExpiredOtps {

        @Test
        @DisplayName("calls deleteAllOlderThan with cutoff of ~24h")
        void callsDeleteAllOlderThanWith24hCutoff() {
            when(otpVerificationRepository.deleteAllOlderThan(any())).thenReturn(3);
            when(otpVerificationRepository.deleteUsedOlderThan(any())).thenReturn(0);

            LocalDateTime before = LocalDateTime.now().minusHours(24);
            otpCleanupScheduler.cleanupExpiredOtps();
            LocalDateTime after = LocalDateTime.now().minusHours(24);

            ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(otpVerificationRepository).deleteAllOlderThan(captor.capture());

            assertThat(captor.getValue()).isBetween(before.minusSeconds(2), after.plusSeconds(2));
        }

        @Test
        @DisplayName("calls deleteUsedOlderThan with cutoff of ~1h")
        void callsDeleteUsedOlderThanWith1hCutoff() {
            when(otpVerificationRepository.deleteAllOlderThan(any())).thenReturn(0);
            when(otpVerificationRepository.deleteUsedOlderThan(any())).thenReturn(2);

            LocalDateTime before = LocalDateTime.now().minusHours(1);
            otpCleanupScheduler.cleanupExpiredOtps();
            LocalDateTime after = LocalDateTime.now().minusHours(1);

            ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(otpVerificationRepository).deleteUsedOlderThan(captor.capture());

            assertThat(captor.getValue()).isBetween(before.minusSeconds(2), after.plusSeconds(2));
        }

        @Test
        @DisplayName("both delete methods are called exactly once")
        void bothDeleteMethodsCalledExactlyOnce() {
            when(otpVerificationRepository.deleteAllOlderThan(any())).thenReturn(0);
            when(otpVerificationRepository.deleteUsedOlderThan(any())).thenReturn(0);

            otpCleanupScheduler.cleanupExpiredOtps();

            verify(otpVerificationRepository, times(1)).deleteAllOlderThan(any());
            verify(otpVerificationRepository, times(1)).deleteUsedOlderThan(any());
        }

        @Test
        @DisplayName("N2: cisti i single-use consumed-store sa ~1h cutoff-om")
        void cleansConsumedStoreWith1hCutoff() {
            when(otpVerificationRepository.deleteAllOlderThan(any())).thenReturn(0);
            when(otpVerificationRepository.deleteUsedOlderThan(any())).thenReturn(0);
            when(otpConsumedCodeRepository.deleteConsumedOlderThan(any())).thenReturn(5);

            LocalDateTime before = LocalDateTime.now().minusHours(1);
            otpCleanupScheduler.cleanupExpiredOtps();
            LocalDateTime after = LocalDateTime.now().minusHours(1);

            ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(otpConsumedCodeRepository).deleteConsumedOlderThan(captor.capture());

            assertThat(captor.getValue()).isBetween(before.minusSeconds(2), after.plusSeconds(2));
        }

        @Test
        @DisplayName("expired cutoff is older than used cutoff")
        void expiredCutoffIsOlderThanUsedCutoff() {
            when(otpVerificationRepository.deleteAllOlderThan(any())).thenReturn(0);
            when(otpVerificationRepository.deleteUsedOlderThan(any())).thenReturn(0);

            otpCleanupScheduler.cleanupExpiredOtps();

            ArgumentCaptor<LocalDateTime> expiredCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> usedCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(otpVerificationRepository).deleteAllOlderThan(expiredCaptor.capture());
            verify(otpVerificationRepository).deleteUsedOlderThan(usedCaptor.capture());

            assertThat(expiredCaptor.getValue()).isBefore(usedCaptor.getValue());
        }

        @Test
        @DisplayName("no exception when repository returns 0 for both calls")
        void noExceptionWhenNothingDeleted() {
            when(otpVerificationRepository.deleteAllOlderThan(any())).thenReturn(0);
            when(otpVerificationRepository.deleteUsedOlderThan(any())).thenReturn(0);

            otpCleanupScheduler.cleanupExpiredOtps();

            verifyNoMoreInteractions(otpVerificationRepository);
        }
    }
}
