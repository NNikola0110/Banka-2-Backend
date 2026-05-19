package rs.raf.banka2_bek.internalapi.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.raf.banka2.contracts.internal.InternalOtpVerifyRequest;
import rs.raf.banka2.contracts.internal.InternalOtpVerifyResponse;
import rs.raf.banka2_bek.internalapi.service.InternalOtpService;

/**
 * Interni REST API za OTP verifikaciju (trading-service OTC / fond / order flow).
 * Zasticen X-Internal-Key (InternalAuthFilter + ROLE_INTERNAL).
 */
@RestController
@RequestMapping("/internal")
public class InternalOtpController {

    private final InternalOtpService otpService;

    public InternalOtpController(InternalOtpService otpService) {
        this.otpService = otpService;
    }

    /**
     * Verifikuje OTP kod za dati email.
     */
    @PostMapping("/otp/verify")
    public ResponseEntity<InternalOtpVerifyResponse> verify(
            @RequestBody InternalOtpVerifyRequest body) {
        return ResponseEntity.ok(otpService.verify(body.email(), body.code()));
    }
}
