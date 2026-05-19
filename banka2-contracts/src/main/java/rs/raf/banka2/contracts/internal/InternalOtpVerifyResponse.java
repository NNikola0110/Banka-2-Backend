package rs.raf.banka2.contracts.internal;

/**
 * Rezultat OTP verifikacije.
 * {@code verified} — kod je tacan; {@code blocked} — previse neuspesnih pokusaja.
 */
public record InternalOtpVerifyResponse(boolean verified, boolean blocked) {
}
