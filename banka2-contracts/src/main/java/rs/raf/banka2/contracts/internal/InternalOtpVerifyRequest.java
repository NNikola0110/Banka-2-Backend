package rs.raf.banka2.contracts.internal;

/** Zahtev za verifikaciju OTP koda preko internog API-ja. */
public record InternalOtpVerifyRequest(String email, String code) {
}
