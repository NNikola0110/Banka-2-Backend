package rs.raf.banka2_bek.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.notification.NotificationPublisher;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccountLockoutService — DB lockout")
class AccountLockoutServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private NotificationPublisher notificationPublisher;

    private AccountLockoutService service;

    private User user;

    @BeforeEach
    void setUp() {
        service = new AccountLockoutService(userRepository, employeeRepository, notificationPublisher);
        ReflectionTestUtils.setField(service, "maxFailedAttempts", 5);
        ReflectionTestUtils.setField(service, "lockDurationMinutes", 10);

        user = new User();
        user.setId(1L);
        user.setEmail("alice@example.com");
        user.setFailedLoginAttempts(0);
        user.setAccountLockedUntil(null);

        when(employeeRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(employeeRepository.findByEmailIgnoreCaseForUpdate(any())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));
        // R1-618: mutirajuce putanje (recordFailure/recordSuccess/assertNotLocked) sad
        // koriste locking finder (PESSIMISTIC_WRITE) — mockuje se na isti entitet.
        when(userRepository.findByEmailIgnoreCaseForUpdate("alice@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Novi nalog: failure count = 0, isLocked = false")
    void freshEmail_zeroFailures() {
        assertThat(service.getFailureCount("alice@example.com")).isZero();
        assertThat(service.isLocked("alice@example.com")).isFalse();
    }

    @Test
    @DisplayName("recordFailure inkrementira brojac u bazi")
    void recordFailure_incrementsCount() {
        service.recordFailure("alice@example.com");
        service.recordFailure("alice@example.com");

        assertThat(user.getFailedLoginAttempts()).isEqualTo(2);
        verify(userRepository, org.mockito.Mockito.times(2)).save(user);
    }

    @Test
    @DisplayName("Posle 5 neuspeha — racun je lock-ovan i salje se email")
    void fiveFailures_locksAccountAndSendsEmail() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("alice@example.com");
        }
        assertThat(service.isLocked("alice@example.com")).isFalse();

        assertThatThrownBy(() -> service.recordFailure("alice@example.com"))
                .isInstanceOf(AccountLockoutService.AccountLockedException.class)
                .hasMessageContaining("Nalog je privremeno zakljucan");

        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(user.getAccountLockedUntil()).isAfter(LocalDateTime.now());
        assertThat(service.isLocked("alice@example.com")).isTrue();
        verify(notificationPublisher).sendAccountLockedMail("alice@example.com", 10);
    }

    @Test
    @DisplayName("Lock se ne aktivira pre 5 neuspeha")
    void fourFailures_doesNotLock() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure("alice@example.com");
        }

        assertThat(service.isLocked("alice@example.com")).isFalse();
        assertThat(user.getAccountLockedUntil()).isNull();
        verify(notificationPublisher, never()).sendAccountLockedMail(any(), eq(10));
    }

    @Test
    @DisplayName("recordSuccess resetuje brojac i otkljucava nalog")
    void recordSuccess_resetsCountAndUnlocks() {
        user.setFailedLoginAttempts(5);
        user.setAccountLockedUntil(LocalDateTime.now().plusMinutes(10));

        service.recordSuccess("alice@example.com");

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getAccountLockedUntil()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("assertNotLocked baca izuzetak kad je lock aktivan")
    void assertNotLocked_throwsWhenLocked() {
        user.setFailedLoginAttempts(5);
        user.setAccountLockedUntil(LocalDateTime.now().plusMinutes(10));

        assertThatThrownBy(() -> service.assertNotLocked("alice@example.com"))
                .isInstanceOf(AccountLockoutService.AccountLockedException.class)
                .hasMessageContaining("Nalog je privremeno zakljucan");
    }

    @Test
    @DisplayName("Istekao lock — assertNotLocked prolazi i cisti accountLockedUntil")
    void assertNotLocked_allowsAfterLockExpired() {
        user.setFailedLoginAttempts(5);
        user.setAccountLockedUntil(LocalDateTime.now().minusMinutes(1));

        service.assertNotLocked("alice@example.com");

        assertThat(user.getAccountLockedUntil()).isNull();
        assertThat(service.isLocked("alice@example.com")).isFalse();
    }

    @Test
    @DisplayName("Ne postojeci email — no-op")
    void unknownEmail_noOp() {
        when(userRepository.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());

        service.recordFailure("unknown@example.com");
        service.recordSuccess("unknown@example.com");
        service.assertNotLocked("unknown@example.com");

        assertThat(service.getFailureCount("unknown@example.com")).isZero();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("R1-618: mutirajuce putanje koriste locking (PESSIMISTIC_WRITE) read")
    void mutatingPaths_usePessimisticLockingFinder() {
        service.recordFailure("alice@example.com");
        service.recordSuccess("alice@example.com");
        service.assertNotLocked("alice@example.com");

        // Sve 3 mutirajuce putanje moraju da idu kroz locking finder, ne plain read.
        verify(userRepository, org.mockito.Mockito.times(3))
                .findByEmailIgnoreCaseForUpdate("alice@example.com");
    }

    @Test
    @DisplayName("R1-618: read-only helperi NE koriste locking finder")
    void readOnlyHelpers_useNonLockingFinder() {
        service.getFailureCount("alice@example.com");
        service.isLocked("alice@example.com");

        verify(userRepository, org.mockito.Mockito.times(2))
                .findByEmailIgnoreCase("alice@example.com");
        verify(userRepository, never()).findByEmailIgnoreCaseForUpdate("alice@example.com");
    }

    @Test
    @DisplayName("Zaposleni — lockout koristi Employee entitet")
    void employeeAccount_lockout() {
        Employee employee = Employee.builder()
                .id(2L)
                .email("emp@banka.rs")
                .firstName("E")
                .lastName("E")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M")
                .phone("0611111111")
                .address("A")
                .username("emp")
                .password("hash")
                .saltPassword("salt")
                .position("Dev")
                .department("IT")
                .active(true)
                .failedLoginAttempts(0)
                .permissions(Set.of("ADMIN"))
                .build();

        when(employeeRepository.findByEmailIgnoreCase("emp@banka.rs")).thenReturn(Optional.of(employee));
        when(employeeRepository.findByEmailIgnoreCaseForUpdate("emp@banka.rs")).thenReturn(Optional.of(employee));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> inv.getArgument(0));

        for (int i = 0; i < 5; i++) {
            try {
                service.recordFailure("emp@banka.rs");
            } catch (AccountLockoutService.AccountLockedException ignored) {
                // poslednji pokusaj
            }
        }

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(employee.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(employee.getAccountLockedUntil()).isNotNull();
        verify(notificationPublisher).sendAccountLockedMail("emp@banka.rs", 10);
    }
}
