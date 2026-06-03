package rs.raf.banka2_bek.transfers.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.otp.service.OtpService;
import rs.raf.banka2_bek.transfers.service.TransferAuthException;
import rs.raf.banka2_bek.transfers.service.TransferService;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1 340 — TIPIZOVANO error-contract mapiranje {@code TransferExceptionHandler}-a.
 *
 * Wire-uje {@link TransferController} sa PRAVIM {@link TransferExceptionHandler}
 * (ne GlobalExceptionHandler) i potvrdjuje da konkretni izuzeci iz {@code TransferService}
 * daju ispravan HTTP status (zamena za raniji fragilni {@code msg.contains(...)}):
 * auth → 401, not-found → 404, validacija/insufficient → 400, access → 403,
 * a telo svuda nosi {@code message}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferExceptionHandlerStatusTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private TransferService transferService;
    @Mock private ClientRepository clientRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private OtpService otpService;

    @InjectMocks private TransferController transferController;

    private static final String PAYLOAD = """
            {
              "fromAccountNumber": "111111111111111111",
              "toAccountNumber": "222222222222222222",
              "amount": 500.00,
              "otpCode": "123456"
            }
            """;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders
                .standaloneSetup(transferController)
                .setControllerAdvice(new TransferExceptionHandler())
                .build();
        when(otpService.verify(anyString(), anyString())).thenReturn(Map.of("verified", true));

        Authentication auth = org.mockito.Mockito.mock(Authentication.class);
        when(auth.getName()).thenReturn("marko@banka.rs");
        when(auth.getPrincipal()).thenReturn("marko@banka.rs");
        when(auth.isAuthenticated()).thenReturn(true);
        SecurityContext secCtx = org.mockito.Mockito.mock(SecurityContext.class);
        when(secCtx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(secCtx);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private org.springframework.test.web.servlet.ResultActions perform() throws Exception {
        return mockMvc.perform(post("/transfers/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PAYLOAD)
                .principal(SecurityContextHolder.getContext().getAuthentication()));
    }

    @Test
    @DisplayName("R1 340 - TransferAuthException → 401 + message")
    void authException_returns401() throws Exception {
        when(transferService.internalTransfer(any()))
                .thenThrow(new TransferAuthException("User is not authenticated"));
        perform()
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value("User is not authenticated"));
    }

    @Test
    @DisplayName("R1 340 - EntityNotFoundException → 404 + message")
    void notFound_returns404() throws Exception {
        when(transferService.internalTransfer(any()))
                .thenThrow(new jakarta.persistence.EntityNotFoundException("From account not found"));
        perform()
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value("From account not found"));
    }

    @Test
    @DisplayName("R1 340 - IllegalArgumentException (insufficient) → 400 + message")
    void validation_returns400() throws Exception {
        when(transferService.internalTransfer(any()))
                .thenThrow(new IllegalArgumentException("Insufficient funds"));
        perform()
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value("Insufficient funds"));
    }

    @Test
    @DisplayName("R1 340 - AccessDeniedException → 403 + message")
    void accessDenied_returns403() throws Exception {
        when(transferService.internalTransfer(any()))
                .thenThrow(new AccessDeniedException("You do not have access to the specified account"));
        perform()
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value("You do not have access to the specified account"));
    }
}
