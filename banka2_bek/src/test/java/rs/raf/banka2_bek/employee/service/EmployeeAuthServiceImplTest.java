package rs.raf.banka2_bek.employee.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import rs.raf.banka2_bek.employee.dto.ActivationTokenStatusDto;
import rs.raf.banka2_bek.employee.model.ActivationToken;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.ActivationTokenRepository;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;
import rs.raf.banka2_bek.employee.service.implementation.EmployeeAuthServiceImpl;

import java.time.LocalDateTime;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeAuthServiceImplTest {

    @Mock private ActivationTokenRepository activationTokenRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private NotificationPublisher notificationPublisher;

    @InjectMocks private EmployeeAuthServiceImpl service;

    private Employee buildEmployee(boolean active) {
        return Employee.builder().id(1L).email("emp@b.rs").firstName("Marko").password("old")
                .saltPassword("salt123").active(active).build();
    }

    private ActivationToken buildToken(Employee emp, boolean used, boolean invalidated, LocalDateTime exp) {
        return ActivationToken.builder().id(1L).token("tok").employee(emp).used(used)
                .invalidated(invalidated).expiresAt(exp).build();
    }

    @Test void activateAccount_valid_activatesEmployee() {
        Employee emp = buildEmployee(false);
        ActivationToken tok = buildToken(emp, false, false, LocalDateTime.now().plusHours(1));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        service.activateAccount("tok", "NewPass12");
        assertThat(emp.getActive()).isTrue();
        assertThat(tok.isUsed()).isTrue();
        verify(employeeRepository).save(emp);
        verify(activationTokenRepository).save(tok);
        verify(notificationPublisher).sendActivationConfirmationMail(anyString(), anyString());
    }

    @Test void activateAccount_invalidToken_throws() {
        when(activationTokenRepository.findByToken("bad")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activateAccount("bad", "P12")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void activateAccount_usedToken_throws() {
        Employee emp = buildEmployee(false);
        ActivationToken tok = buildToken(emp, true, false, LocalDateTime.now().plusHours(1));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));
        assertThatThrownBy(() -> service.activateAccount("tok", "P12")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void activateAccount_invalidatedToken_throws() {
        Employee emp = buildEmployee(false);
        ActivationToken tok = buildToken(emp, false, true, LocalDateTime.now().plusHours(1));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));
        assertThatThrownBy(() -> service.activateAccount("tok", "P12")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void activateAccount_expiredToken_throws() {
        Employee emp = buildEmployee(false);
        ActivationToken tok = buildToken(emp, false, false, LocalDateTime.now().minusHours(1));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));
        assertThatThrownBy(() -> service.activateAccount("tok", "P12")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void activateAccount_alreadyActive_throwsIllegalState() {
        Employee emp = buildEmployee(true);
        ActivationToken tok = buildToken(emp, false, false, LocalDateTime.now().plusHours(1));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));
        assertThatThrownBy(() -> service.activateAccount("tok", "P12")).isInstanceOf(IllegalStateException.class);
    }

    @Test void activateAccount_encodesWithSalt() {
        Employee emp = buildEmployee(false);
        ActivationToken tok = buildToken(emp, false, false, LocalDateTime.now().plusHours(1));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));
        when(passwordEncoder.encode("NewPass12salt123")).thenReturn("encoded-salt");
        service.activateAccount("tok", "NewPass12");
        assertThat(emp.getPassword()).isEqualTo("encoded-salt");
    }

    // ---- Spec Celina 1 Sc 9: getTokenStatus (activation-token status endpoint, audit Celina1 #25) ----

    @Test void getTokenStatus_nullToken_returnsInvalid() {
        assertThat(service.getTokenStatus(null).getStatus()).isEqualTo("INVALID");
        verifyNoInteractions(activationTokenRepository);
    }

    @Test void getTokenStatus_blankToken_returnsInvalid() {
        assertThat(service.getTokenStatus("   ").getStatus()).isEqualTo("INVALID");
        verifyNoInteractions(activationTokenRepository);
    }

    @Test void getTokenStatus_unknownToken_returnsInvalid() {
        when(activationTokenRepository.findByToken("ghost")).thenReturn(Optional.empty());
        assertThat(service.getTokenStatus("ghost").getStatus()).isEqualTo("INVALID");
    }

    @Test void getTokenStatus_usedToken_returnsUsedWithEmailAndExpiry() {
        Employee emp = buildEmployee(false);
        LocalDateTime exp = LocalDateTime.now().plusHours(1);
        ActivationToken tok = buildToken(emp, true, false, exp);
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));

        ActivationTokenStatusDto dto = service.getTokenStatus("tok");

        assertThat(dto.getStatus()).isEqualTo("USED");
        assertThat(dto.getEmail()).isEqualTo("emp@b.rs");
        assertThat(dto.getExpiresAt()).isEqualTo(exp);
    }

    @Test void getTokenStatus_invalidatedToken_returnsUsed() {
        Employee emp = buildEmployee(false);
        ActivationToken tok = buildToken(emp, false, true, LocalDateTime.now().plusHours(1));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));

        assertThat(service.getTokenStatus("tok").getStatus()).isEqualTo("USED");
    }

    @Test void getTokenStatus_expiredToken_returnsExpired() {
        Employee emp = buildEmployee(false);
        LocalDateTime exp = LocalDateTime.now().minusHours(1);
        ActivationToken tok = buildToken(emp, false, false, exp);
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));

        ActivationTokenStatusDto dto = service.getTokenStatus("tok");

        assertThat(dto.getStatus()).isEqualTo("EXPIRED");
        assertThat(dto.getExpiresAt()).isEqualTo(exp);
    }

    @Test void getTokenStatus_validTokenButEmployeeAlreadyActive_returnsAlreadyActive() {
        Employee emp = buildEmployee(true);
        ActivationToken tok = buildToken(emp, false, false, LocalDateTime.now().plusHours(1));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));

        ActivationTokenStatusDto dto = service.getTokenStatus("tok");

        assertThat(dto.getStatus()).isEqualTo("ALREADY_ACTIVE");
        assertThat(dto.getEmail()).isEqualTo("emp@b.rs");
    }

    @Test void getTokenStatus_freshTokenInactiveEmployee_returnsValid() {
        Employee emp = buildEmployee(false);
        ActivationToken tok = buildToken(emp, false, false, LocalDateTime.now().plusHours(5));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));

        ActivationTokenStatusDto dto = service.getTokenStatus("tok");

        assertThat(dto.getStatus()).isEqualTo("VALID");
        assertThat(dto.getEmail()).isEqualTo("emp@b.rs");
        assertThat(dto.getExpiresAt()).isNotNull();
    }

    // ---- Spec Celina 1 Sc 9: resend activation link ----

    @Test void resendActivation_inactiveEmployee_invalidatesOldTokenAndSendsNewMail() {
        Employee emp = buildEmployee(false);
        // Stari token je istekao (tipican EXPIRED scenario sa FE-a).
        ActivationToken oldTok = buildToken(emp, false, false, LocalDateTime.now().minusHours(1));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(oldTok));

        service.resendActivation("tok");

        // 1) Stari aktivni tokeni za zaposlenog su invalidovani.
        verify(activationTokenRepository).invalidateAllActiveTokensForEmployee(emp);
        // 2) Nov token je perzistovan sa svezim TTL (24h) i vezan za istog zaposlenog.
        ArgumentCaptor<ActivationToken> captor = ArgumentCaptor.forClass(ActivationToken.class);
        verify(activationTokenRepository).save(captor.capture());
        ActivationToken saved = captor.getValue();
        assertThat(saved.getEmployee()).isSameAs(emp);
        assertThat(saved.isUsed()).isFalse();
        assertThat(saved.isInvalidated()).isFalse();
        assertThat(saved.getToken()).isNotBlank().isNotEqualTo("tok");
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        // 3) Nov aktivacioni email je poslat sa novim tokenom.
        verify(notificationPublisher)
                .sendActivationMail(eq("emp@b.rs"), eq("Marko"), eq(saved.getToken()));
    }

    @Test void resendActivation_alreadyActiveEmployee_noTokenChangeNoMail() {
        Employee emp = buildEmployee(true);
        ActivationToken tok = buildToken(emp, false, false, LocalDateTime.now().plusHours(1));
        when(activationTokenRepository.findByToken("tok")).thenReturn(Optional.of(tok));

        // Anti-enumeration: vraca tiho, bez izuzetka.
        assertThatCode(() -> service.resendActivation("tok")).doesNotThrowAnyException();

        verify(activationTokenRepository, never()).invalidateAllActiveTokensForEmployee(any());
        verify(activationTokenRepository, never()).save(any());
        verify(notificationPublisher, never()).sendActivationMail(anyString(), anyString(), anyString());
    }

    @Test void resendActivation_unknownToken_genericNoLeakNoMail() {
        when(activationTokenRepository.findByToken("ghost")).thenReturn(Optional.empty());

        assertThatCode(() -> service.resendActivation("ghost")).doesNotThrowAnyException();

        verify(activationTokenRepository, never()).invalidateAllActiveTokensForEmployee(any());
        verify(activationTokenRepository, never()).save(any());
        verifyNoInteractions(notificationPublisher);
    }

    @Test void resendActivation_blankToken_genericNoLeakNoMail() {
        assertThatCode(() -> service.resendActivation("  ")).doesNotThrowAnyException();

        verifyNoInteractions(employeeRepository);
        verify(activationTokenRepository, never()).save(any());
        verifyNoInteractions(notificationPublisher);
    }
}
