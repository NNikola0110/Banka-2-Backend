package rs.raf.banka2_bek.transfers.controller;

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
import java.util.Collections;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.raf.banka2_bek.account.repository.AccountRepository;
import rs.raf.banka2_bek.auth.config.GlobalExceptionHandler;
import rs.raf.banka2_bek.client.repository.ClientRepository;
import rs.raf.banka2_bek.otp.service.OtpService;
import rs.raf.banka2_bek.transfers.service.TransferService;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@DisplayName("TransferController coverage tests")
class TransferControllerCoverageTest {

    private MockMvc mockMvc;

    @Mock private TransferService transferService;
    @Mock private ClientRepository clientRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private OtpService otpService;

    @InjectMocks private TransferController transferController;

    private static final String VALID_PAYLOAD = """
            {
              "fromAccountNumber": "111111111111111111",
              "toAccountNumber": "222222222222222222",
              "amount": 500.00,
              "otpCode": "123456"
            }
            """;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(transferController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- POST /transfers/internal ----------

    @Test
    @DisplayName("POST /transfers/internal - 401 Unauthorized kad nema principal")
    void internalTransfer_noPrincipal_returns401() throws Exception {
        // No .principal(auth) -> controller dobija null Authentication -> 401
        mockMvc.perform(post("/transfers/internal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(transferService, never()).internalTransfer(any());
        verify(otpService, never()).verify(anyString(), anyString());
    }

    @Test
    @DisplayName("POST /transfers/internal - 401 Unauthorized kad Authentication nije authenticated")
    void internalTransfer_notAuthenticated_returns401() throws Exception {
        Authentication unauth = mock(Authentication.class);
        when(unauth.isAuthenticated()).thenReturn(false);
        when(unauth.getPrincipal()).thenReturn("someone@example.com");

        mockMvc.perform(post("/transfers/internal")
                        .principal(unauth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(transferService, never()).internalTransfer(any());
    }

    @Test
    @DisplayName("POST /transfers/internal - 403 Forbidden kad OTP nije validan")
    void internalTransfer_otpNotVerified_returns403() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "marko@banka.rs", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_CLIENT")));
        when(otpService.verify(anyString(), anyString()))
                .thenReturn(Map.of("verified", false));

        mockMvc.perform(post("/transfers/internal")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isForbidden());

        verify(transferService, never()).internalTransfer(any());
    }

    // ---------- POST /transfers/fx ----------

    @Test
    @DisplayName("POST /transfers/fx - 401 Unauthorized kad nema principal")
    void fxTransfer_noPrincipal_returns401() throws Exception {
        mockMvc.perform(post("/transfers/fx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(transferService, never()).fxTransfer(any());
    }

    @Test
    @DisplayName("POST /transfers/fx - 403 Forbidden kad OTP nije validan")
    void fxTransfer_otpNotVerified_returns403() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "marko@banka.rs", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_CLIENT")));
        when(otpService.verify(anyString(), anyString()))
                .thenReturn(Map.of("verified", false));

        mockMvc.perform(post("/transfers/fx")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isForbidden());

        verify(transferService, never()).fxTransfer(any());
    }

    @Test
    @DisplayName("POST /transfers/fx - 403 Forbidden kad OTP map ne sadrzi 'verified' kljuc")
    void fxTransfer_otpMapMissingVerified_returns403() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "marko@banka.rs", null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_CLIENT")));
        when(otpService.verify(anyString(), anyString()))
                .thenReturn(Map.of("message", "neuspesno"));

        mockMvc.perform(post("/transfers/fx")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isForbidden());
    }

    // ---------- GET /transfers ----------

    // R1-652 (P3-bc-transfer-exchange-loan-savings-1): nepostojeci/anoniman klijent
    // -> 401, NE prazan 200 (vidi TransferController.getAllTransfers).
    @Test
    @DisplayName("GET /transfers - 401 kad SecurityContext ima Authentication ali nije authenticated")
    void getAllTransfers_authNotAuthenticated_returns401() throws Exception {
        Authentication unauth = mock(Authentication.class);
        when(unauth.isAuthenticated()).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(unauth);

        mockMvc.perform(get("/transfers"))
                .andExpect(status().isUnauthorized());

        verify(transferService, never()).getAllTransfers(any(), any(), any(), any());
    }

    @Test
    @DisplayName("GET /transfers - 401 kad clientRepository vrati prazan Optional")
    void getAllTransfers_clientMissing_returns401() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "ghost@banka.rs", null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_CLIENT"))));
        when(clientRepository.findByEmail("ghost@banka.rs")).thenReturn(Optional.empty());

        mockMvc.perform(get("/transfers"))
                .andExpect(status().isUnauthorized());

        verify(transferService, never()).getAllTransfers(any(), any(), any(), any());
    }
}
