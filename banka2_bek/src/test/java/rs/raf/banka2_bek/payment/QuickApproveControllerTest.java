package rs.raf.banka2_bek.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2_bek.auth.config.GlobalExceptionHandler;
import rs.raf.banka2_bek.otp.service.OtpService;
import rs.raf.banka2_bek.payment.controller.PaymentController;
import rs.raf.banka2_bek.payment.dto.PaymentDirection;
import rs.raf.banka2_bek.payment.dto.PaymentResponseDto;
import rs.raf.banka2_bek.payment.exception.OtpLockedException;
import rs.raf.banka2_bek.payment.exception.OtpInvalidException;
import rs.raf.banka2_bek.payment.exception.PaymentAlreadyFinalizedException;
import rs.raf.banka2_bek.payment.exception.PaymentNotFoundException;
import rs.raf.banka2_bek.payment.exception.PaymentNotOwnedException;
import rs.raf.banka2_bek.payment.exception.PaymentTimeoutException;
import rs.raf.banka2_bek.payment.model.PaymentStatus;
import rs.raf.banka2_bek.payment.service.PaymentService;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TODO_final Mobile bonus #7 — Quick Approve BE endpoint tests (Faza A: failing tests first).
 *
 * Endpoint: POST /payments/{id}/approve
 * Request body: { "otpCode": "123456" }
 *
 * HTTP status matrica:
 * - 200 OK: validan OTP + payment dispatch uspeo (ili idempotent retry na vec COMPLETED)
 * - 401 Unauthorized: nema authentication ILI pogresan OTP kod (verified=false, blocked=false)
 * - 403 Forbidden: payment.fromAccount ne pripada current user-u
 * - 404 Not Found: payment id ne postoji
 * - 409 Conflict: payment status REJECTED/ABORTED/CANCELLED (vec finaled u failure state)
 * - 410 Gone: payment.createdAt + 5min < now (TTL istekao)
 * - 423 Locked: OTP brute-force gate aktivan (3 fail-a u rolling window)
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("QuickApprove POST /payments/{id}/approve tests")
class QuickApproveControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Authentication auth = new UsernamePasswordAuthenticationToken("client@example.com", null);

    @Mock
    private PaymentService paymentService;

    @Mock
    private OtpService otpService;

    @InjectMocks
    private PaymentController paymentController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(paymentController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private PaymentResponseDto completedResponse() {
        return PaymentResponseDto.builder()
                .id(42L)
                .orderNumber("ORD-42")
                .fromAccount("1234567890")
                .toAccount("0987654321")
                .amount(new BigDecimal("100.00"))
                .fee(BigDecimal.ZERO)
                .currency("RSD")
                .paymentCode("289")
                .referenceNumber("REF-1")
                .description("Quick approve test")
                .recipientName("Recipient")
                .direction(PaymentDirection.OUTGOING)
                .status(PaymentStatus.COMPLETED)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String body(String otp) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("otpCode", otp));
    }

    @Test
    @DisplayName("POST /payments/{id}/approve — 200 OK kad je OTP validan i dispatch uspeo")
    void approve_validOtp_returns200() throws Exception {
        when(paymentService.quickApprove(eq(42L), eq("client@example.com"), eq("123456")))
                .thenReturn(completedResponse());

        mockMvc.perform(post("/payments/42/approve")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(paymentService).quickApprove(42L, "client@example.com", "123456");
    }

    @Test
    @DisplayName("POST /payments/{id}/approve — 410 Gone kad je TTL istekao (>5min)")
    void approve_pendingTimeout_returns410() throws Exception {
        when(paymentService.quickApprove(eq(42L), anyString(), anyString()))
                .thenThrow(new PaymentTimeoutException("Vreme za Quick Approve je isteklo."));

        mockMvc.perform(post("/payments/42/approve")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("123456")))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("POST /payments/{id}/approve — 401 Unauthorized kad je OTP pogresan")
    void approve_wrongOtp_returns401() throws Exception {
        when(paymentService.quickApprove(eq(42L), anyString(), anyString()))
                .thenThrow(new OtpInvalidException("Pogresan verifikacioni kod."));

        mockMvc.perform(post("/payments/42/approve")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("000000")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /payments/{id}/approve — 423 Locked posle 3 OTP fail-a (brute-force)")
    void approve_otpBruteForce_returns423() throws Exception {
        when(paymentService.quickApprove(eq(42L), anyString(), anyString()))
                .thenThrow(new OtpLockedException("Prekoracen broj pokusaja. Transakcija je otkazana."));

        mockMvc.perform(post("/payments/42/approve")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("000000")))
                .andExpect(status().isLocked());
    }

    @Test
    @DisplayName("POST /payments/{id}/approve — 200 OK idempotent kad je payment vec COMPLETED")
    void approve_alreadyCompleted_returns200Idempotent() throws Exception {
        // Service vraca vec COMPLETED payment bez novog dispatch-a (idempotent retry posle network 502).
        when(paymentService.quickApprove(eq(42L), anyString(), anyString()))
                .thenReturn(completedResponse());

        mockMvc.perform(post("/payments/42/approve")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST /payments/{id}/approve — 403 Forbidden kad payment ne pripada user-u")
    void approve_notOwner_returns403() throws Exception {
        when(paymentService.quickApprove(eq(42L), anyString(), anyString()))
                .thenThrow(new PaymentNotOwnedException("Placanje ne pripada korisniku."));

        mockMvc.perform(post("/payments/42/approve")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("123456")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /payments/{id}/approve — 404 Not Found kad payment ne postoji")
    void approve_paymentNotFound_returns404() throws Exception {
        when(paymentService.quickApprove(eq(99L), anyString(), anyString()))
                .thenThrow(new PaymentNotFoundException("Placanje nije pronadjeno."));

        mockMvc.perform(post("/payments/99/approve")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("123456")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /payments/{id}/approve — 409 Conflict kad je payment vec ABORTED/REJECTED")
    void approve_alreadyFinalized_returns409() throws Exception {
        when(paymentService.quickApprove(eq(42L), anyString(), anyString()))
                .thenThrow(new PaymentAlreadyFinalizedException("Placanje je vec u ABORTED stanju."));

        mockMvc.perform(post("/payments/42/approve")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("123456")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /payments/{id}/approve — 401 Unauthorized kad nema authentication")
    void approve_noAuth_returns401() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/payments/42/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("123456")))
                .andExpect(status().isUnauthorized());

        verify(paymentService, never()).quickApprove(any(Long.class), anyString(), anyString());
    }

    @Test
    @DisplayName("POST /payments/{id}/approve — 400 Bad Request kad je OTP blank")
    void approve_blankOtp_returns400() throws Exception {
        mockMvc.perform(post("/payments/42/approve")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("")))
                .andExpect(status().isBadRequest());

        verify(paymentService, never()).quickApprove(any(Long.class), anyString(), anyString());
    }
}
