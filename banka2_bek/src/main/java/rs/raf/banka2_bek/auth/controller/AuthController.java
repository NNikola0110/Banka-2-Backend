package rs.raf.banka2_bek.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rs.raf.banka2_bek.auth.dto.*;
import rs.raf.banka2_bek.auth.service.AuthService;
import rs.raf.banka2_bek.auth.service.JwtBlacklistService;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtBlacklistService blacklistService;

    public AuthController(AuthService authService, JwtBlacklistService blacklistService) {
        this.authService = authService;
        this.blacklistService = blacklistService;
    }

    @PostMapping("/register")
    public ResponseEntity<MessageResponseDto> register(@Valid @RequestBody RegisterRequestDto request) {
        return ResponseEntity.ok(new MessageResponseDto(authService.register(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/password_reset/request")
    public ResponseEntity<MessageResponseDto> requestPasswordReset(@Valid @RequestBody PasswordResetRequestDto request) {
        return ResponseEntity.ok(new MessageResponseDto(authService.requestPasswordReset(request)));
    }

    @PostMapping("/password_reset/confirm")
    public ResponseEntity<MessageResponseDto> confirmPasswordReset(@Valid @RequestBody PasswordResetDto reset) {
        return ResponseEntity.ok(new MessageResponseDto(authService.resetPassword(reset)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshTokenResponseDto> refresh(@Valid @RequestBody RefreshTokenRequestDto request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    /**
     * Opciono.1 / N3 — Logout. Povlaci access token iz Authorization headera i,
     * ako je prosledjen, refresh token iz body-ja, pa ih stavlja na blacklist do
     * isteka TTL-a. Frontend treba dodatno da obrise sessionStorage.
     *
     * <p>N3 fix: pre ove izmene logout je blacklist-ovao SAMO access token. Posto
     * refresh token ima 7-dnevni TTL, ukraden refresh token je ostajao upotrebljiv
     * ceo taj period i posle logout-a (napadac bi /auth/refresh-ovao nove access
     * tokene). Sada se blacklist-uju i access i refresh. Body je opcioni — legacy
     * klijenti bez refresh tokena i dalje rade (blacklist-uje se samo access).</p>
     */
    @PostMapping("/logout")
    public ResponseEntity<MessageResponseDto> logout(
            HttpServletRequest request,
            @RequestBody(required = false) LogoutRequestDto body) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            blacklistService.blacklist(authHeader.substring(7));
        }
        if (body != null && body.getRefreshToken() != null && !body.getRefreshToken().isBlank()) {
            blacklistService.blacklist(body.getRefreshToken());
        }
        return ResponseEntity.ok(new MessageResponseDto("Uspesno odjavljen."));
    }
}