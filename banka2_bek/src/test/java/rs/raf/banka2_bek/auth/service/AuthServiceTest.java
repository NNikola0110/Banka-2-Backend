package rs.raf.banka2_bek.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import rs.raf.banka2_bek.account.model.Account;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.auth.dto.AuthResponseDto;
import rs.raf.banka2_bek.auth.dto.LoginRequestDto;
import rs.raf.banka2_bek.auth.dto.PasswordResetDto;
import rs.raf.banka2_bek.auth.dto.PasswordResetRequestDto;
import rs.raf.banka2_bek.auth.dto.RefreshTokenRequestDto;
import rs.raf.banka2_bek.auth.dto.RefreshTokenResponseDto;
import rs.raf.banka2_bek.auth.dto.RegisterRequestDto;
import rs.raf.banka2_bek.auth.exception.AuthenticationFailedException;
import rs.raf.banka2_bek.auth.model.PasswordResetToken;
import rs.raf.banka2_bek.auth.model.User;
import rs.raf.banka2_bek.notification.NotificationPublisher;
import rs.raf.banka2_bek.auth.repository.PasswordResetTokenRepository;
import rs.raf.banka2_bek.auth.repository.UserRepository;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.currency.model.Currency;
import rs.raf.banka2_bek.currency.repository.CurrencyRepository;
import rs.raf.banka2_bek.employee.model.Employee;
import rs.raf.banka2_bek.employee.repository.EmployeeRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CurrencyRepository currencyRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private NotificationPublisher notificationPublisher;

    @Mock
    private AccountLockoutService accountLockoutService;

    @Mock
    private JwtBlacklistService jwtBlacklistService;

    @Mock
    private rs.raf.banka2_bek.monitoring.BusinessMetrics businessMetrics;

    @InjectMocks
    private AuthService authService;

    private User user;
    private Employee employee;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");
        user.setPassword("hashed-user");
        user.setActive(true);
        user.setRole("CLIENT");

        employee = Employee.builder()
                .id(2L)
                .firstName("Ana")
                .lastName("Test")
                .email("employee@test.com")
                .password("hashed-emp")
                .saltPassword("salt")
                .active(true)
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("F")
                .phone("+38160111222")
                .address("Test")
                .username("ana")
                .position("QA")
                .department("IT")
                .permissions(Set.of("ADMIN"))
                .build();
    }

    @Test
    void loginUsesEmployeeWhenExists() {
        when(employeeRepository.findByEmailIgnoreCase(employee.getEmail())).thenReturn(Optional.of(employee));
        when(passwordEncoder.matches(eq("password" + employee.getSaltPassword()), eq(employee.getPassword())))
                .thenReturn(true);
        when(jwtService.generateAccessToken(employee)).thenReturn("access");
        when(jwtService.generateRefreshToken(employee)).thenReturn("refresh");

        AuthResponseDto response = authService.login(new LoginRequestDto(employee.getEmail(), "password"));

        assertThat(response.getAccessToken()).isEqualTo("access");
        assertThat(response.getRefreshToken()).isEqualTo("refresh");
    }

    @Test
    void loginFallsBackToUser() {
        when(employeeRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(eq("password"), eq(user.getPassword()))).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("access");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh");

        AuthResponseDto response = authService.login(new LoginRequestDto(user.getEmail(), "password"));

        assertThat(response.getAccessToken()).isEqualTo("access");
        assertThat(response.getRefreshToken()).isEqualTo("refresh");
        // R7 observability: uspesan login inkrementira banka2_login_success_total.
        verify(businessMetrics).recordLoginSuccess();
        verify(businessMetrics, never()).recordLoginFailure();
    }

    @Test
    void loginRejectsWhenAccountLocked() {
        doThrow(new AccountLockoutService.AccountLockedException(
                "Nalog je privremeno zakljucan zbog previse neuspesnih pokusaja. Pokusajte ponovo za 10 min.",
                600))
                .when(accountLockoutService).assertNotLocked("user@test.com");

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("user@test.com", "password")))
                .isInstanceOf(AccountLockoutService.AccountLockedException.class)
                .hasMessageContaining("Nalog je privremeno zakljucan");

        verify(accountLockoutService).assertNotLocked("user@test.com");
    }

    @Test
    void requestPasswordResetForEmployeePublishesEvent() {
        when(userRepository.findByEmail(employee.getEmail())).thenReturn(Optional.empty());
        when(employeeRepository.findByEmail(employee.getEmail())).thenReturn(Optional.of(employee));
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.requestPasswordReset(new PasswordResetRequestDto(employee.getEmail()));

        verify(notificationPublisher).sendPasswordResetMail(eq(employee.getEmail()), anyString());
    }

    @Test
    void resetPasswordForEmployeeUsesSaltedHash() {
        PasswordResetToken token = new PasswordResetToken();
        token.setToken("token");
        token.setEmployee(employee);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        token.setUsed(false);

        when(passwordResetTokenRepository.findByToken("token")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NewPass12" + employee.getSaltPassword())).thenReturn("new-hash");

        authService.resetPassword(new PasswordResetDto("token", "NewPass12"));

        verify(employeeRepository).save(employee);
        verify(passwordResetTokenRepository).save(token);
        assertThat(token.getUsed()).isTrue();
    }

    @Test
    void refreshTokenWorksForEmployee() {
        when(jwtService.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtService.extractEmail("refresh-token")).thenReturn(employee.getEmail());
        when(userRepository.findByEmail(employee.getEmail())).thenReturn(Optional.empty());
        when(employeeRepository.findByEmail(employee.getEmail())).thenReturn(Optional.of(employee));
        when(jwtService.generateAccessToken(employee)).thenReturn("new-access");
        // P1-auth-2 (1740): refresh se ROTIRA — vraca se NOV refresh, stari se blacklist-uje.
        when(jwtService.generateRefreshToken(employee)).thenReturn("rotated-refresh");

        RefreshTokenRequestDto request = new RefreshTokenRequestDto();
        request.setRefreshToken("refresh-token");
        RefreshTokenResponseDto response = authService.refreshToken(request);

        assertThat(response.getAccessToken()).isEqualTo("new-access");
        assertThat(response.getRefreshToken()).isEqualTo("rotated-refresh");
        verify(jwtBlacklistService).blacklist("refresh-token");
    }

    @Test
    void loginRejectsInvalidCredentials() {
        when(employeeRepository.findByEmailIgnoreCase("missing@test.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("missing@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("missing@test.com", "bad")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Neispravan email ili lozinka");
        // R7 observability: neuspesan login inkrementira banka2_login_failure_total
        // (input za BruteForceLogin Prometheus alert).
        verify(businessMetrics).recordLoginFailure();
        verify(businessMetrics, never()).recordLoginSuccess();
    }

    // ===== Employee login with salt-based password hashing =====

    @Test
    void loginEmployeeSaltIsAppendedBeforeMatching() {
        // Verifies that the password + salt concatenation is passed to the encoder
        when(employeeRepository.findByEmailIgnoreCase(employee.getEmail())).thenReturn(Optional.of(employee));
        // The salt is "salt", so encoder should receive "myPass123salt"
        when(passwordEncoder.matches(eq("myPass123" + "salt"), eq(employee.getPassword())))
                .thenReturn(true);
        when(jwtService.generateAccessToken(employee)).thenReturn("acc");
        when(jwtService.generateRefreshToken(employee)).thenReturn("ref");

        AuthResponseDto response = authService.login(new LoginRequestDto(employee.getEmail(), "myPass123"));

        assertThat(response.getAccessToken()).isEqualTo("acc");
        verify(passwordEncoder).matches("myPass123salt", employee.getPassword());
    }

    @Test
    void loginEmployeeWrongPasswordFallsToUserLookup() {
        // Employee exists but password doesn't match -> falls through to user lookup
        when(employeeRepository.findByEmailIgnoreCase(employee.getEmail())).thenReturn(Optional.of(employee));
        when(passwordEncoder.matches(eq("wrongPass" + employee.getSaltPassword()), eq(employee.getPassword())))
                .thenReturn(false);
        when(userRepository.findByEmailIgnoreCase(employee.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequestDto(employee.getEmail(), "wrongPass")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Neispravan email ili lozinka");
    }

    // ===== Login with inactive account =====

    @Test
    void loginRejectsInactiveEmployee() {
        employee = Employee.builder()
                .id(2L)
                .firstName("Ana")
                .lastName("Test")
                .email("inactive-emp@test.com")
                .password("hashed-emp")
                .saltPassword("salt")
                .active(false)  // inactive
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("F")
                .phone("+38160111222")
                .address("Test")
                .username("ana")
                .position("QA")
                .department("IT")
                .permissions(Set.of("ADMIN"))
                .build();

        when(employeeRepository.findByEmailIgnoreCase("inactive-emp@test.com")).thenReturn(Optional.of(employee));
        when(passwordEncoder.matches(eq("password" + "salt"), eq("hashed-emp"))).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("inactive-emp@test.com", "password")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Nalog je deaktiviran");
    }

    @Test
    void loginRejectsInactiveUser() {
        User inactiveUser = new User();
        inactiveUser.setId(10L);
        inactiveUser.setEmail("inactive@test.com");
        inactiveUser.setPassword("hashed");
        inactiveUser.setActive(false);
        inactiveUser.setRole("CLIENT");

        when(employeeRepository.findByEmailIgnoreCase("inactive@test.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("inactive@test.com")).thenReturn(Optional.of(inactiveUser));
        when(passwordEncoder.matches(eq("password"), eq("hashed"))).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("inactive@test.com", "password")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Nalog je deaktiviran");
    }

    // ===== Password reset token expiry validation =====

    @Test
    void resetPasswordRejectsExpiredToken() {
        PasswordResetToken token = new PasswordResetToken();
        token.setToken("expired-token");
        token.setUser(user);
        token.setExpiresAt(LocalDateTime.now().minusMinutes(5)); // expired
        token.setUsed(false);

        when(passwordResetTokenRepository.findByToken("expired-token")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetDto("expired-token", "NewPass12")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Reset token has expired");
    }

    @Test
    void resetPasswordRejectsAlreadyUsedToken() {
        PasswordResetToken token = new PasswordResetToken();
        token.setToken("used-token");
        token.setUser(user);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        token.setUsed(true); // already used

        when(passwordResetTokenRepository.findByToken("used-token")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetDto("used-token", "NewPass12")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Reset token has been already used");
    }

    @Test
    void resetPasswordRejectsTokenNotLinkedToAnyUser() {
        PasswordResetToken token = new PasswordResetToken();
        token.setToken("orphan-token");
        token.setUser(null);
        token.setEmployee(null);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        token.setUsed(false);

        when(passwordResetTokenRepository.findByToken("orphan-token")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetDto("orphan-token", "NewPass12")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Reset token is not linked to a user");
    }

    @Test
    void resetPasswordRejectsNonExistentToken() {
        when(passwordResetTokenRepository.findByToken("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetDto("nonexistent", "NewPass12")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Reset token does not exist");
    }

    // ===== TEST-auth-5: reset password on a DEACTIVATED account =====

    @Test
    void resetPasswordOnDeactivatedUser_succeedsButAccountStaysInactive_TEST_auth_5() {
        // TEST-auth-5: postoje testovi za token-expiry/used/orphan, ali ne i za
        // reset na DEAKTIVIRANOM nalogu. Karakterizacija trenutnog ponasanja:
        // resetPassword NE proverava user.active — reset na deaktiviranom nalogu
        // USPEVA (postavi novi hash, oznaci token iskoriscenim). To NIJE
        // security rupa: login (vidi loginRejectsInactiveUser) i refresh
        // (refreshTokenRejectsInactiveUser) i dalje gate-uju na active==false, pa
        // resetovana lozinka ne daje pristup dok admin ne reaktivira nalog. Test
        // pinuje da reset prolazi A da active flag ostaje netaknut (false).
        User deactivated = new User();
        deactivated.setId(50L);
        deactivated.setEmail("deactivated@test.com");
        deactivated.setPassword("old-hash");
        deactivated.setActive(false);
        deactivated.setRole("CLIENT");

        PasswordResetToken token = new PasswordResetToken();
        token.setToken("deact-token");
        token.setUser(deactivated);
        token.setEmployee(null);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        token.setUsed(false);

        when(passwordResetTokenRepository.findByToken("deact-token")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NewPass12")).thenReturn("new-hash");

        authService.resetPassword(new PasswordResetDto("deact-token", "NewPass12"));

        assertThat(deactivated.getPassword()).isEqualTo("new-hash");
        assertThat(token.getUsed()).isTrue();
        // Reset NE reaktivira nalog — i dalje deaktiviran (login ostaje blokiran).
        assertThat(deactivated.isActive()).isFalse();
        verify(userRepository).save(deactivated);
    }

    // ===== Reset password for regular user (not employee) =====

    @Test
    void resetPasswordForUserUpdatesPasswordWithoutSalt() {
        PasswordResetToken token = new PasswordResetToken();
        token.setToken("user-token");
        token.setUser(user);
        token.setEmployee(null);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        token.setUsed(false);

        when(passwordResetTokenRepository.findByToken("user-token")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NewPass12")).thenReturn("new-hash");

        authService.resetPassword(new PasswordResetDto("user-token", "NewPass12"));

        assertThat(user.getPassword()).isEqualTo("new-hash");
        verify(userRepository).save(user);
        verify(passwordResetTokenRepository).save(token);
        assertThat(token.getUsed()).isTrue();
        verify(accountLockoutService).recordSuccess("user@test.com");
    }

    // ===== Register with duplicate email error =====

    @Test
    void registerThrowsOnDuplicateEmail() {
        RegisterRequestDto request = new RegisterRequestDto();
        request.setEmail("user@test.com");
        request.setFirstName("John");
        request.setLastName("Doe");
        request.setPassword("StrongPass12");

        when(userRepository.existsByEmail("user@test.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User with this email already exists");
    }

    @Test
    void registerSucceedsForNewEmail() {
        RegisterRequestDto request = new RegisterRequestDto();
        request.setEmail("new@test.com");
        request.setFirstName("Jane");
        request.setLastName("Doe");
        request.setPassword("StrongPass12");
        request.setUsername("jane");
        request.setPhone("+381601234567");
        request.setAddress("Belgrade");
        request.setDateOfBirth(946684800L);
        request.setGender("F");

        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(passwordEncoder.encode("StrongPass12")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        // Bootstrap: novi Client + default RSD CHECKING/PERSONAL racun.
        when(clientRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
        when(clientRepository.save(any(Client.class))).thenAnswer(inv -> {
            Client c = inv.getArgument(0);
            c.setId(101L);
            return c;
        });
        when(accountRepository.findByClientId(101L)).thenReturn(List.of());
        Currency rsd = new Currency();
        rsd.setCode("RSD");
        when(currencyRepository.findByCode("RSD")).thenReturn(Optional.of(rsd));
        when(employeeRepository.findAll()).thenReturn(List.of(employee));
        when(accountRepository.existsByAccountNumber(any(String.class))).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        String result = authService.register(request);

        assertThat(result).isEqualTo("User registered successfully");
        verify(userRepository).save(any(User.class));
        verify(clientRepository).save(any(Client.class));
        verify(accountRepository).save(any(Account.class));
    }

    // ===== Refresh token rotation (new access from refresh) =====

    @Test
    void refreshTokenRejectsBlacklistedRefreshToken() {
        // N3: posle logout-a refresh token je blacklist-ovan. refreshToken() ne sme
        // da izda nov access token za revoke-ovan refresh — inace je logout no-op.
        when(jwtService.isRefreshToken("revoked-refresh")).thenReturn(true);
        when(jwtBlacklistService.isBlacklisted("revoked-refresh")).thenReturn(true);

        RefreshTokenRequestDto request = new RefreshTokenRequestDto();
        request.setRefreshToken("revoked-refresh");

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid refresh token");
        verify(jwtService, never()).generateAccessToken(any(User.class));
        verify(jwtService, never()).generateAccessToken(any(Employee.class));
    }

    @Test
    void refreshTokenRejectsInactiveUser() {
        // N1: refresh deaktiviranog korisnickog naloga ne sme da izda nov access token.
        User inactive = new User();
        inactive.setId(20L);
        inactive.setEmail("inactive-refresh@test.com");
        inactive.setActive(false);
        inactive.setRole("CLIENT");

        when(jwtService.isRefreshToken("user-refresh")).thenReturn(true);
        when(jwtService.extractEmail("user-refresh")).thenReturn("inactive-refresh@test.com");
        when(userRepository.findByEmail("inactive-refresh@test.com")).thenReturn(Optional.of(inactive));

        RefreshTokenRequestDto request = new RefreshTokenRequestDto();
        request.setRefreshToken("user-refresh");

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("Nalog je deaktiviran");
        verify(jwtService, never()).generateAccessToken(any(User.class));
    }

    @Test
    void refreshTokenRejectsInactiveEmployee() {
        // N1: refresh deaktiviranog employee naloga ne sme da izda nov access token.
        Employee inactiveEmp = Employee.builder()
                .id(21L)
                .firstName("In")
                .lastName("Active")
                .email("inactive-emp-refresh@test.com")
                .password("hashed")
                .saltPassword("s")
                .active(false)
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .gender("M")
                .phone("+381")
                .address("Addr")
                .username("inactemp")
                .position("Dev")
                .department("IT")
                .permissions(Set.of())
                .build();

        when(jwtService.isRefreshToken("emp-refresh")).thenReturn(true);
        when(jwtService.extractEmail("emp-refresh")).thenReturn("inactive-emp-refresh@test.com");
        when(userRepository.findByEmail("inactive-emp-refresh@test.com")).thenReturn(Optional.empty());
        when(employeeRepository.findByEmail("inactive-emp-refresh@test.com"))
                .thenReturn(Optional.of(inactiveEmp));

        RefreshTokenRequestDto request = new RefreshTokenRequestDto();
        request.setRefreshToken("emp-refresh");

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("Nalog je deaktiviran");
        verify(jwtService, never()).generateAccessToken(any(Employee.class));
    }

    @Test
    void refreshTokenReturnsNewAccessTokenForUser() {
        when(jwtService.isRefreshToken("valid-refresh")).thenReturn(true);
        when(jwtService.extractEmail("valid-refresh")).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("new-access-token");
        // P1-auth-2 (1740): rotacija refresh tokena.
        when(jwtService.generateRefreshToken(user)).thenReturn("rotated-refresh");

        RefreshTokenRequestDto request = new RefreshTokenRequestDto();
        request.setRefreshToken("valid-refresh");
        RefreshTokenResponseDto response = authService.refreshToken(request);

        assertThat(response.getAccessToken()).isEqualTo("new-access-token");
        assertThat(response.getRefreshToken()).isEqualTo("rotated-refresh");
    }

    // ===== P1-auth-2 (R4 1740): refresh token rotation + reuse blacklist =====

    @Test
    void refreshTokenRotatesAndBlacklistsOldRefreshForUser() {
        // REPRODUKCIJA: pre fix-a /auth/refresh vracao ISTI refresh (response==input)
        // i NIKAD ga ne blacklist-uje -> ukraden refresh radi punih 7 dana.
        when(jwtService.isRefreshToken("old-refresh")).thenReturn(true);
        when(jwtService.extractEmail("old-refresh")).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("fresh-access");
        when(jwtService.generateRefreshToken(user)).thenReturn("new-refresh");

        RefreshTokenRequestDto request = new RefreshTokenRequestDto();
        request.setRefreshToken("old-refresh");
        RefreshTokenResponseDto response = authService.refreshToken(request);

        // Nov refresh != stari + stari je blacklist-ovan (reuse stare kopije -> Invalid).
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh");
        verify(jwtBlacklistService).blacklist("old-refresh");
    }

    // ===== Invalid refresh token handling =====

    @Test
    void refreshTokenRejectsNonRefreshToken() {
        when(jwtService.isRefreshToken("access-token-not-refresh")).thenReturn(false);

        RefreshTokenRequestDto request = new RefreshTokenRequestDto();
        request.setRefreshToken("access-token-not-refresh");

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid refresh token");
    }

    @Test
    void refreshTokenRejectsUnknownEmail() {
        when(jwtService.isRefreshToken("orphan-refresh")).thenReturn(true);
        when(jwtService.extractEmail("orphan-refresh")).thenReturn("nobody@test.com");
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());
        when(employeeRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        RefreshTokenRequestDto request = new RefreshTokenRequestDto();
        request.setRefreshToken("orphan-refresh");

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    // ===== Request password reset edge cases =====

    @Test
    void requestPasswordResetForUserPublishesEvent() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> inv.getArgument(0));

        authService.requestPasswordReset(new PasswordResetRequestDto(user.getEmail()));

        verify(notificationPublisher).sendPasswordResetMail(eq(user.getEmail()), anyString());
    }

    // C1 Sc4 (§spec): reset link mora da vazi 15 minuta (ranije 30). Asertujemo TTL
    // na sacuvanom PasswordResetToken-u (expiresAt ~ now + 15min, sa tolerancijom).
    @Test
    void requestPasswordResetSetsFifteenMinuteTtl_Sc4() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        org.mockito.ArgumentCaptor<PasswordResetToken> captor =
                org.mockito.ArgumentCaptor.forClass(PasswordResetToken.class);
        when(passwordResetTokenRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        authService.requestPasswordReset(new PasswordResetRequestDto(user.getEmail()));
        LocalDateTime after = LocalDateTime.now();

        LocalDateTime expiresAt = captor.getValue().getExpiresAt();
        // expiresAt mora biti izmedju (before+15min) i (after+15min) — tj. tacno 15min TTL.
        assertThat(expiresAt).isAfterOrEqualTo(before.plusMinutes(15).minusSeconds(2));
        assertThat(expiresAt).isBeforeOrEqualTo(after.plusMinutes(15).plusSeconds(2));
        // I eksplicitno: NIJE 30min (regresija-guard za staru vrednost).
        assertThat(expiresAt).isBefore(before.plusMinutes(16));
    }

    @Test
    void requestPasswordResetReturnsGenericResponseForUnknownEmail() {
        // SEC-07 (Email enumeration mitigation): endpoint UVEK vraca generic
        // success poruku, bez obzira da li email postoji ili ne. Pre fix-a je
        // bacao RuntimeException ("User with this email does not exist") koji
        // se mapirao u 400 i otkrivao da li nalog postoji. Sad: log internal
        // i vrati generic message — UI ne razlikuje "valid" od "unknown".
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());
        when(employeeRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());

        String response = authService.requestPasswordReset(new PasswordResetRequestDto("unknown@test.com"));

        assertThat(response).contains("If an account");
        // Token NE sme da bude generisan za nepostojeci email.
        verify(passwordResetTokenRepository, never()).save(any(PasswordResetToken.class));
        verify(notificationPublisher, never()).sendPasswordResetMail(anyString(), anyString());
    }

    // ===== Employee login with null active field =====

    @Test
    void loginRejectsEmployeeWithNullActive() {
        Employee empNullActive = Employee.builder()
                .id(5L)
                .firstName("Null")
                .lastName("Active")
                .email("null-active@test.com")
                .password("hashed")
                .saltPassword("s")
                .active(null)  // null active
                .dateOfBirth(LocalDate.of(1995, 5, 5))
                .gender("M")
                .phone("+381")
                .address("Addr")
                .username("nullact")
                .position("Dev")
                .department("IT")
                .permissions(Set.of())
                .build();

        when(employeeRepository.findByEmailIgnoreCase("null-active@test.com")).thenReturn(Optional.of(empNullActive));
        when(passwordEncoder.matches("pass" + "s", "hashed")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("null-active@test.com", "pass")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Nalog je deaktiviran");
    }

    // ===== P1-auth-2 (R2 1365): login case-insensitivity =====

    @Test
    void loginIsCaseInsensitiveForEmail() {
        // REPRODUKCIJA: pre fix-a login je radio exact findByEmail("USER@TEST.COM")
        // -> miss (jer je sacuvan "user@test.com") -> tretiran kao pogresan kredencijal,
        // dok lockout brojac normalizuje na lowercase -> razilazenje kljuceva.
        // Sada login koristi findByEmailIgnoreCase i razresava nalog bez obzira na case.
        when(employeeRepository.findByEmailIgnoreCase("USER@TEST.COM")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("USER@TEST.COM")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(eq("password"), eq(user.getPassword()))).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("access");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh");

        AuthResponseDto response = authService.login(new LoginRequestDto("USER@TEST.COM", "password"));

        assertThat(response.getAccessToken()).isEqualTo("access");
    }
}
