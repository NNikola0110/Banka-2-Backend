package rs.raf.banka2_bek.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2_bek.auth.dto.*;
import rs.raf.banka2_bek.auth.service.AuthService;
import rs.raf.banka2_bek.auth.service.JwtBlacklistService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AuthService authService;

    @Mock
    private JwtBlacklistService blacklistService;

    @InjectMocks
    private AuthController authController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    @Test
    void register_returnsOk() throws Exception {
        when(authService.register(any(RegisterRequestDto.class))).thenReturn("Registration successful");

        RegisterRequestDto request = new RegisterRequestDto();
        request.setFirstName("Marko");
        request.setLastName("Petrovic");
        request.setEmail("marko@banka.rs");
        request.setPassword("Aa12345678");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Registration successful"));
    }

    @Test
    void login_returnsAuthResponse() throws Exception {
        AuthResponseDto authResponse = new AuthResponseDto("access-token", "refresh-token");
        when(authService.login(any(LoginRequestDto.class))).thenReturn(authResponse);

        LoginRequestDto request = new LoginRequestDto("marko@banka.rs", "Aa12345678");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void requestPasswordReset_returnsOk() throws Exception {
        when(authService.requestPasswordReset(any(PasswordResetRequestDto.class)))
                .thenReturn("Reset email sent");

        PasswordResetRequestDto request = new PasswordResetRequestDto("marko@banka.rs");

        mockMvc.perform(post("/auth/password_reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Reset email sent"));
    }

    @Test
    void confirmPasswordReset_returnsOk() throws Exception {
        when(authService.resetPassword(any(PasswordResetDto.class)))
                .thenReturn("Password reset successful");

        PasswordResetDto request = new PasswordResetDto("token-123", "NewPass12");

        mockMvc.perform(post("/auth/password_reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset successful"));
    }

    @Test
    void logout_blacklistsAccessAndRefreshTokens() throws Exception {
        // N3: logout MORA blacklist-ovati i access token (iz Authorization header-a)
        // i refresh token (iz body-ja). Bez refresh blacklist-a, ukradeni refresh
        // token ostaje upotrebljiv 7 dana posle logout-a.
        LogoutRequestDto body = new LogoutRequestDto();
        body.setRefreshToken("refresh.to.revoke");

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer access.to.revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(blacklistService).blacklist("access.to.revoke");
        org.mockito.Mockito.verify(blacklistService).blacklist("refresh.to.revoke");
    }

    @Test
    void logout_withoutBody_blacklistsOnlyAccessToken() throws Exception {
        // N3: logout bez body-ja (legacy klijent) i dalje blacklist-uje access token.
        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer access.only"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(blacklistService).blacklist("access.only");
    }

    @Test
    void refresh_returnsNewTokens() throws Exception {
        RefreshTokenResponseDto responseDto = new RefreshTokenResponseDto("new-access", "new-refresh");
        when(authService.refreshToken(any(RefreshTokenRequestDto.class))).thenReturn(responseDto);

        RefreshTokenRequestDto request = new RefreshTokenRequestDto();
        request.setRefreshToken("old-refresh");

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"));
    }
}
