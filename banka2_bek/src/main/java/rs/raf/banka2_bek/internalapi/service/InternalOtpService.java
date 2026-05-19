package rs.raf.banka2_bek.internalapi.service;

import org.springframework.stereotype.Service;
import rs.raf.banka2.contracts.internal.InternalOtpVerifyResponse;
import rs.raf.banka2_bek.otp.service.OtpService;

import java.util.Map;

/**
 * Verifikacija OTP koda preko internog API-ja (za trading-service OTC / fond / order flow).
 * Delegira na monolitov {@link OtpService} i adaptira njegov {@code Map<String,Object>}
 * rezultat u strukturisan {@link InternalOtpVerifyResponse}.
 */
@Service
public class InternalOtpService {

    private final OtpService otpService;

    public InternalOtpService(OtpService otpService) {
        this.otpService = otpService;
    }

    /**
     * Verifikuje OTP kod za dati email.
     * {@code verified} = kod je tacan; {@code blocked} = previse neuspesnih pokusaja.
     */
    public InternalOtpVerifyResponse verify(String email, String code) {
        Map<String, Object> result = otpService.verify(email, code);
        boolean verified = Boolean.TRUE.equals(result.get("verified"));
        boolean blocked = Boolean.TRUE.equals(result.get("blocked"));
        return new InternalOtpVerifyResponse(verified, blocked);
    }
}
