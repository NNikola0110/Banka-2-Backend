package rs.raf.banka2_bek.transfers.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import rs.raf.banka2_bek.client.model.Client;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.otp.service.OtpService;
import rs.raf.banka2_bek.transfers.dto.*;
import rs.raf.banka2_bek.transfers.service.TransferService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;
    private final ClientRepository clientRepository;
    private final OtpService otpService;

    /**
     * Internal transfer (same or different currency - auto-detected in service).
     * OTP kod MORA biti validan - verifikuje se pre izvrsavanja transfera.
     */
    @PostMapping("/internal")
    public ResponseEntity<?> internalTransfer(
            @Valid @RequestBody TransferInternalRequestDto request,
            Authentication auth) {

        // 1. OTP verifikacija
        String email = getEmail(auth);
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Map<String, Object> otpResult = otpService.verify(email, request.getOtpCode());
        if (!Boolean.TRUE.equals(otpResult.get("verified"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(otpResult);
        }

        // 2. Servis radi auto-detect same vs FX i izvrsava sa pessimistic lock
        return ResponseEntity.ok(transferService.internalTransfer(request));
    }

    /**
     * FX transfer (explicit - different currencies).
     * OTP kod MORA biti validan.
     */
    @PostMapping("/fx")
    public ResponseEntity<?> fxTransfer(
            @Valid @RequestBody TransferFxRequestDto request,
            Authentication auth) {

        String email = getEmail(auth);
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Map<String, Object> otpResult = otpService.verify(email, request.getOtpCode());
        if (!Boolean.TRUE.equals(otpResult.get("verified"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(otpResult);
        }

        return ResponseEntity.ok(transferService.fxTransfer(request));
    }

    @GetMapping
    public ResponseEntity<List<TransferResponseDto>> getAllTransfers(
            @RequestParam(required = false) String accountNumber,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate
    ) {
        // R1-652 (error-contract): kad nema autentifikovanog klijenta (anon ili
        // ne-CLIENT principal) vrati 401, NE prazan 200 — prazan-200 je krio "nisi
        // prijavljen" kao "nemas transfere". /transfers je client-only resurs.
        Client client = getOptionalClient();
        if (client == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(transferService.getAllTransfers(client, accountNumber, fromDate, toDate));
    }

    @GetMapping("/{transferId}")
    public ResponseEntity<TransferResponseDto> getTransferById(@PathVariable Long transferId) {
        return ResponseEntity.ok(transferService.getTransferById(transferId));
    }

    private String getEmail(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        Object p = auth.getPrincipal();
        if (p instanceof UserDetails ud) return ud.getUsername();
        return p.toString();
    }

    private Client getOptionalClient() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        String email = getEmail(auth);
        if (email == null) return null;
        return clientRepository.findByEmail(email).orElse(null);
    }
}
