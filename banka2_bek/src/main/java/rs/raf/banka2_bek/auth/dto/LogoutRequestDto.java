package rs.raf.banka2_bek.auth.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * N3 — Logout payload. Opcioni body koji nosi refresh token koji treba povuci
 * (blacklist) zajedno sa access token-om iz Authorization header-a. Polje je
 * namerno bez {@code @NotBlank} validacije — legacy klijenti mogu pozvati
 * {@code POST /auth/logout} bez body-ja (tada se blacklist-uje samo access token).
 */
@Getter
@Setter
public class LogoutRequestDto {
    private String refreshToken;
}
